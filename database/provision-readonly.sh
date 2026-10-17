#!/bin/sh
set -eu
: "${ANALYTICS_PASSWORD:?Export ANALYTICS_PASSWORD first}"
# psql reads the secret from its process environment, never a command-line argument.
{ printf '%s\n' '\getenv analytics_password ANALYTICS_PASSWORD'; cat database/provision-readonly.sql; } |
  docker compose exec -T -e ANALYTICS_PASSWORD postgres sh -c '
    exec psql --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"
  '
