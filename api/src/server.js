const express = require("express");
const { ensureSchema } = require("./db");
const userApi = require("./userApi");
const adminApi = require("./adminApi");

const app = express();
app.use(express.json());

app.use("/api/v1", userApi);
app.use("/api/v1", adminApi);

app.use((req, res) => {
  res.status(404).json({ error: "Not found" });
});

app.use((err, req, res, _next) => {
  console.error(err);
  res.status(500).json({ error: "Internal server error | fix you side" });
});


const port = 8080;
// Start the server
app.listen(port, () => {
  console.log(`sms-processing-api listening on :${port}`);
});
 