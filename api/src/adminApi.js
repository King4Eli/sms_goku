// Admin-only operations: worker (sender identity) management. Workers are
// never self-service - a customer can never create, list, or revoke one,
// only select an already-provisioned number as 'from' (see userApi.js).
// Gated by a single static secret in .env/admin.env (ADMIN_TOKEN), not a
// per-user credential and not stored in the database.
const path = require("node:path");
const crypto = require("node:crypto");
const express = require("express");

require("dotenv").config({
  path: path.join(__dirname, "..", "..", ".env", "admin.env"),
});

const { pool } = require("./db");
const { parsePhone } = require("./validate");

const router = express.Router();
const wrap = (fn) => (req, res, next) => fn(req, res, next).catch(next);

function timingSafeEqualStr(a, b) {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

function adminAuth(req, res, next) {
  if (!process.env.ADMIN_TOKEN) {
    return res
      .status(503)
      .json({ error: "Admin API not configured - missing ADMIN_TOKEN" });
  }
  const token = req.header("X-Admin-Token");
  if (!token || !timingSafeEqualStr(token, process.env.ADMIN_TOKEN)) {
    return res
      .status(401)
      .json({ error: "Missing or invalid X-Admin-Token header" });
  }
  next();
}

router.use("/admin", adminAuth);

// The only way to register a worker (sender identity) - no self-service.
// Workers are never assigned to a customer: 'public' is the only
// visibility control (see userApi.js) - a private worker simply isn't
// selectable via GET /numbers / POST /sms by anyone.
router.post(
  "/admin/workers",
  wrap(async (req, res) => {
    const { name, phone, public: isPublicInput, subId: subIdInput } = req.body || {};
    if (typeof name !== "string" || name.trim() === "") {
      return res.status(400).json({ error: "'name' is required" });
    }
    const parsedPhone = parsePhone(phone);
    if (!parsedPhone) {
      return res.status(400).json({
        error:
          "'phone' must be a valid phone number in international format, e.g. +15551234567",
      });
    }
    const isPublic = Boolean(isPublicInput);

    // SIM subscription id the registering device sends this worker from.
    // Optional: absent / null / negative all mean "device default SIM".
    let subId = null;
    if (subIdInput !== undefined && subIdInput !== null) {
      if (!Number.isInteger(subIdInput) || subIdInput < 0) {
        return res
          .status(400)
          .json({ error: "'subId' must be a non-negative integer if present" });
      }
      subId = subIdInput;
    }

    try {
      const [result] = await pool.query(
        `INSERT INTO worker_tokens (name, phone_number, is_public, sub_id) VALUES (?, ?, ?, ?)`,
        [name.trim(), parsedPhone.e164, isPublic ? 1 : 0, subId],
      );
      res.status(201).json({
        id: result.insertId,
        name: name.trim(),
        phone: parsedPhone.e164,
        isPublic,
        subId,
      });
    } catch (err) {
      if (err.errno === 1062) {
        return res.status(409).json({
          error: "That phone number is already registered to a worker",
        });
      }
      throw err;
    }
  }),
);

// Every worker.
router.get(
  "/admin/workers",
  wrap(async (req, res) => {
    const [rows] = await pool.query(
      `SELECT id, name, phone_number, is_public, sub_id, created_at, revoked_at
     FROM worker_tokens ORDER BY created_at DESC`,
    );
    res.status(200).json(
      rows.map((r) => ({
        id: r.id,
        name: r.name,
        phone: r.phone_number,
        isPublic: Boolean(r.is_public),
        subId: r.sub_id === null ? null : Number(r.sub_id),
        createdAt: r.created_at,
        revokedAt: r.revoked_at,
      })),
    );
  }),
);

// Revokes any worker, owned or global - admin isn't bound by the
// ownership check customer-facing routes would need.
router.patch(
  "/admin/workers/:id/revoke",
  wrap(async (req, res) => {
    const id = Number(req.params.id);
    if (!Number.isInteger(id) || id <= 0) {
      return res.status(400).json({ error: "Invalid worker id" });
    }

    const [result] = await pool.query(
      `UPDATE worker_tokens SET revoked_at = NOW() WHERE id = ? AND revoked_at IS NULL`,
      [id],
    );
    if (result.affectedRows === 0) {
      const [existing] = await pool.query(
        `SELECT id FROM worker_tokens WHERE id = ?`,
        [id],
      );
      if (existing.length === 0) {
        return res.status(404).json({ error: "Worker not found" });
      }
      return res.status(409).json({ error: "Worker is already revoked" });
    }

    res.status(200).json({ id, revoked: true });
  }),
);

// Claims up to 'limit' queued messages for a worker (status 0 -> 2,
// pulled_at set) and returns them for sending. Claiming rather than just
// SELECTing is what lets a device poll repeatedly without risking a
// message going out twice - FOR UPDATE inside a transaction blocks a
// second concurrent claim of the same rows until this one commits.
router.get(
  "/admin/sms/pending",
  wrap(async (req, res) => {
    const workerId = Number(req.query.workerId);
    if (!Number.isInteger(workerId) || workerId <= 0) {
      return res
        .status(400)
        .json({ error: "'workerId' query param (integer) is required" });
    }
    const limit = Math.min(Math.max(Number(req.query.limit) || 20, 1), 50);

    const conn = await pool.getConnection();
    try {
      await conn.beginTransaction();
      const [claimable] = await conn.query(
        `SELECT id FROM sms_queue
       WHERE worker_token_id = ? AND status = 0
       ORDER BY created_at ASC LIMIT ? FOR UPDATE`,
        [workerId, limit],
      );
      if (claimable.length === 0) {
        await conn.commit();
        return res.status(200).json([]);
      }
      const ids = claimable.map((r) => r.id);
      await conn.query(
        `UPDATE sms_queue SET status = 2, pulled_at = NOW() WHERE id IN (?)`,
        [ids],
      );
      const [rows] = await conn.query(
        `SELECT id, to_number, message FROM sms_queue
       WHERE id IN (?) ORDER BY created_at ASC`,
        [ids],
      );
      await conn.commit();
      res
        .status(200)
        .json(
          rows.map((r) => ({ id: r.id, to: r.to_number, message: r.message })),
        );
    } catch (err) {
      await conn.rollback();
      throw err;
    } finally {
      conn.release();
    }
  }),
);

// Closes the loop on one previously-pulled message (status 2 -> 1). Only
// rows currently in the 'pulled' state can be reported on, so a message
// can only be reported once - a retry of the same report (e.g. after a
// flaky response the device didn't see) 409s harmlessly instead of
// double-counting attempts.
router.patch(
  "/admin/sms/:id/report",
  wrap(async (req, res) => {
    const id = Number(req.params.id);
    if (!Number.isInteger(id) || id <= 0) {
      return res.status(400).json({ error: "Invalid sms id" });
    }
    const { error } = req.body || {};
    if (error !== undefined && error !== null && typeof error !== "string") {
      return res
        .status(400)
        .json({ error: "'error' must be a string if present" });
    }

    const [result] = await pool.query(
      `UPDATE sms_queue
     SET status = 1, processed_at = NOW(), attempts = attempts + 1,
         error_message = ?
     WHERE id = ? AND status = 2`,
      [error || null, id],
    );
    if (result.affectedRows === 0) {
      const [existing] = await pool.query(
        `SELECT id FROM sms_queue WHERE id = ?`,
        [id],
      );
      if (existing.length === 0) {
        return res.status(404).json({ error: "Sms not found" });
      }
      return res.status(409).json({ error: "Sms was not in a pulled state" });
    }
    res.status(200).json({ id, processed: true, success: !error });
  }),
);

module.exports = router;
