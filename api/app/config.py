import os
from urllib.parse import quote


def _env(key: str, default: str | None = None) -> str:
    val = os.environ.get(key, default)
    if val is None or val == "":
        raise SystemExit(f"missing required env var: {key}")
    return val


DATABASE_URL = (f"postgresql+psycopg://{quote(_env('POSTGRES_USER'))}:{quote(_env('POSTGRES_PASSWORD'))}"
                f"@{_env('DB_HOST', 'db:5432')}/{_env('POSTGRES_DB')}")
ADMIN_TOKEN = _env("ADMIN_TOKEN")
# X-Runner-Token for /api/runner/*
RUNNER_TOKEN = _env("RUNNER_TOKEN")
# optional, seeded on startup
DEMO_API_KEY = os.environ.get("DEMO_API_KEY", "")

MAX_ATTEMPTS = int(_env("MAX_ATTEMPTS", "3"))
# retry N waits N * this
RETRY_BACKOFF_SECONDS = int(_env("RETRY_BACKOFF_SECONDS", "15"))
# stuck in sending longer -> re-queue
STALE_SENDING_SECONDS = int(_env("STALE_SENDING_SECONDS", "120"))
