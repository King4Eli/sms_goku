package com.smsjustu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Worker(
    val id: Long,
    val name: String,
    val phone: String,
    val isPublic: Boolean,
    val createdAt: String?,
    val revokedAt: String?
)

data class CreatedWorker(
    val id: Long,
    val name: String,
    val phone: String,
    val isPublic: Boolean
)

data class PendingSms(
    val id: Long,
    val to: String,
    val message: String
)

class AdminApiException(message: String) : Exception(message)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

class AdminApiClient(private val baseUrl: String, private val adminToken: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject? {
        return withContext(Dispatchers.IO) {
            val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("X-Admin-Token", adminToken)
                .method(method, requestBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val message = try {
                            JSONObject(text).optString("error", "HTTP ${response.code}")
                        } catch (e: Exception) {
                            "HTTP ${response.code}"
                        }
                        throw AdminApiException(message)
                    }
                    if (text.isBlank()) null else JSONObject(text)
                }
            } catch (e: IOException) {
                throw AdminApiException("Network error: ${e.message}")
            }
        }
    }

    suspend fun listWorkers(): List<Worker> {
        val response = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/v1/admin/workers")
                .header("X-Admin-Token", adminToken)
                .get()
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val message = try {
                            JSONObject(body).optString("error", "HTTP ${resp.code}")
                        } catch (e: Exception) {
                            "HTTP ${resp.code}"
                        }
                        throw AdminApiException(message)
                    }
                    body
                }
            } catch (e: IOException) {
                throw AdminApiException("Network error: ${e.message}")
            }
        }
        val array = JSONArray(response)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Worker(
                id = o.getLong("id"),
                name = o.getString("name"),
                phone = o.getString("phone"),
                isPublic = o.getBoolean("isPublic"),
                createdAt = if (o.isNull("createdAt")) null else o.optString("createdAt"),
                revokedAt = if (o.isNull("revokedAt")) null else o.optString("revokedAt")
            )
        }
    }

    suspend fun createWorker(name: String, phone: String, isPublic: Boolean): CreatedWorker {
        val payload = JSONObject().apply {
            put("name", name)
            put("phone", phone)
            put("public", isPublic)
        }
        val o = request("POST", "/api/v1/admin/workers", payload)
            ?: throw AdminApiException("Empty response from server")
        return CreatedWorker(
            id = o.getLong("id"),
            name = o.getString("name"),
            phone = o.getString("phone"),
            isPublic = o.getBoolean("isPublic")
        )
    }

    suspend fun revokeWorker(id: Long) {
        request("PATCH", "/api/v1/admin/workers/$id/revoke", JSONObject())
    }

    /** Claims (server-side status 0 -> 2) and returns up to [limit] queued
     *  messages for [workerId], oldest first. Each returned message must be
     *  followed by [reportSmsResult] once it's been attempted - a claimed
     *  message that's never reported stays claimed forever. */
    suspend fun pullPendingSms(workerId: Long, limit: Int = 20): List<PendingSms> {
        val response = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/v1/admin/sms/pending?workerId=$workerId&limit=$limit")
                .header("X-Admin-Token", adminToken)
                .get()
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val message = try {
                            JSONObject(body).optString("error", "HTTP ${resp.code}")
                        } catch (e: Exception) {
                            "HTTP ${resp.code}"
                        }
                        throw AdminApiException(message)
                    }
                    body
                }
            } catch (e: IOException) {
                throw AdminApiException("Network error: ${e.message}")
            }
        }
        val array = JSONArray(response)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PendingSms(
                id = o.getLong("id"),
                to = o.getString("to"),
                message = o.getString("message")
            )
        }
    }

    /** Reports the outcome of a message previously claimed via [pullPendingSms].
     *  [error] null means success; a non-null description means it failed. */
    suspend fun reportSmsResult(id: Long, error: String?) {
        val payload = JSONObject().apply {
            if (error != null) put("error", error)
        }
        request("PATCH", "/api/v1/admin/sms/$id/report", payload)
    }
}
