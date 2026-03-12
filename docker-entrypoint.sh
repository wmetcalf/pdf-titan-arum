#!/bin/sh
set -e

: "${DB_URL:?DB_URL is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

exec java -jar /app/app.jar server \
  --host 0.0.0.0 \
  --port "${PORT:-7272}" \
  --db "$DB_URL" \
  --db-user "$DB_USER" \
  --db-password "$DB_PASSWORD" \
  --artifact-root /data/artifacts \
  ${WORKERS:+--workers "$WORKERS"}
