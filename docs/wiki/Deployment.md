# Deployment

## What you deploy

| Component | Image / artifact | Needs |
|---|---|---|
| Engine | root `Dockerfile` (builds with Gradle, runs `/app/bin/ton-game` on a JRE 21) | PostgreSQL, Redis, outbound HTTPS to the TON API, the operators' webhooks |
| Web clients | `catalog/<game>/Dockerfile` (builds with Node 22, serves with nginx) | a route from `/ws/` and `/api/` on the client origin to the engine |
| Example operator | `example/operator/Dockerfile` | for testing only; replace it with your own backend |

`./gradlew installDist` produces a plain distribution in `build/install/ton-game`
(`bin/ton-game` plus `lib/`), if you prefer to run without Docker. The engine needs a
Java 21 runtime.

## Run exactly one engine instance

TeleFly, PvP and the lottery are driven by loops inside the engine process, and their live
round state (bets, pot, multiplier) is held in memory. Two engine replicas would run two
independent rounds of each shared game and two competing lottery loops. Run a single
replica; restart it with a rolling "stop then start" strategy, not "start then stop".

On restart, TeleFly and PvP reconcile rounds left open by the previous process and the
lottery resumes its persisted draw.

## Database

- PostgreSQL (16 is used in development). Point `DB_URL`, `DB_USER`, `DB_PASSWORD` at it.
- The engine creates all tables at startup (`SchemaUtils.create`) and applies a few
  idempotent `ALTER ... IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` statements; there is
  no separate migration step. The database user therefore needs DDL rights.
- Back it up like any ledger: `transactions` and `rounds` are the audit trail of every
  webhook money movement.
- Operators are added with SQL (`INSERT INTO brands ...`), see
  [Operator Integration](Operator-Integration).

## Redis

Only used as a cache of session lookups (`session:<token>`, expiring with the session).
It can be restarted or flushed at any time; lookups fall back to PostgreSQL.

## Network and proxies

- The engine listens on `SERVER_PORT` (8080) on all interfaces, plain HTTP. Terminate TLS
  in front of it.
- WebSockets: forward the `Upgrade`/`Connection` headers and keep idle timeouts above
  60 s. The engine pings every 25 s and closes peers that stay silent for 60 s.
- Serve each web client so that `/ws/` and `/api/` on its origin reach the engine (the
  client images do this with `ENGINE_URL`), or build the clients with an absolute
  `VITE_WS_URL` / `VITE_API_BASE`.
- Expose `/api/v1/games` and `/api/v1/session` only to operator backends if you can; they
  are authenticated with the brand secret either way.
- The engine must reach the TON API (`TON_API_URL`, default `https://toncenter.com/api/v2`).
  If it cannot, new rounds cannot be seeded and fail; shared games reuse the last good
  seed for a while. For heavy traffic consider your own TON API endpoint, since public
  endpoints rate-limit anonymous clients.

## Health and logs

There is no dedicated health endpoint; use a TCP check on the engine port (an HTTP request
to `/api/v1/games` without credentials returns `401` once the server is up).

Logs are JSON lines on stdout (`ts`, `level`, `logger`, `msg`, `stack`, `service`, `env`).
Set `LOG_LEVEL` for verbosity and `OPERATOR` for the `env` field. A rejected or failed operator webhook
surfaces as the error `Operator rejected <path> (<status>)`.

## Configuration

Every setting is an environment variable; see [Configuration](Configuration). The defaults
are development values: always set `DB_PASSWORD` and the brand secrets for production.

## Docker Compose

`docker-compose.yml` is a development stack (fixed passwords, published database ports,
demo brand). Use it as a starting point, not as a production deployment.
