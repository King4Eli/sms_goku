# Build context is the repo root (see docker-compose.yml), not this
# directory - the API reads _docs/schema.sql at startup, so the image
# needs that file at the same relative path it has on the host.
FROM node:20-alpine

WORKDIR /app

COPY api/package.json api/package-lock.json* ./api/
RUN npm install --prefix api --omit=dev --no-audit --no-fund

COPY api/src ./api/src
# COPY api/scripts ./api/scripts
# COPY _docs/schema.sql ./_docs/schema.sql

WORKDIR /app/api
EXPOSE 8080

CMD ["node", "src/server.js"]
