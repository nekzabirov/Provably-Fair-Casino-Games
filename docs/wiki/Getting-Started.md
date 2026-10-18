# Getting Started

Two ways to run the project locally: everything in Docker, or the engine and clients on
your machine for development.

## Option 1 - Docker Compose

Requirements: Docker with Compose v2. The engine container needs outbound internet
access to `https://toncenter.com` (the public TON seed); see [Configuration](Configuration)
to point it elsewhere.

```bash
git clone https://github.com/nekzabirov/Provably-Fair-Casino-Games.git
cd Provably-Fair-Casino-Games
docker compose up --build
```

Services started by `docker-compose.yml`:

| Service | Port | What it is |
|---|---|---|
| `postgres` | 5432 | PostgreSQL 16, database `gameengine`, user/password `postgres` |
| `redis` | 6379 | Redis 7 (session cache) |
| `engine` | 8080 | the game engine |
| `brand-seed` | - | one-shot job: inserts the demo brand once the engine created its tables |
| `operator` | 9090 | example operator (`example/operator`) with an in-memory wallet |
| `mines-web` | 8082 | Mines client |
| `lucky-web` | 8083 | Lucky Cups client |
| `telefly-web` | 8084 | TeleFly client |
| `plinko-web` | 8085 | Plinko client |
| `pvp-web` | 8086 | PvP wheel client |
| `lottery-web` | 8087 | Lottery client |

Each client container is nginx serving the built bundle and proxying `/ws/` and `/api/`
to the engine, so the page and its socket share one origin.

### Play a round

1. Ask the example operator for a session. It mints a token, registers it with the engine
   (`POST /api/v1/session`) and returns the engine's answer:

   ```bash
   curl -X POST http://localhost:9090/session/42
   # {"token":"...","expireAt":"2026-...Z"}
   ```

2. Open a client with the token:

   ```
   http://localhost:8082/?sessionToken=<token>&currency=TON&operator=demo-operator
   ```

   Use ports 8083-8087 for the other games. The demo player `42` starts with 1000 TON
   (`OPERATOR_START_BALANCE`, in nanoton).

3. Play. Follow the engine's JSON logs with `docker compose logs -f engine`.

To use another player, call `/session/<any id>` again. Sessions last 24 hours by default.

### Register your own operator

The demo brand is inserted by `brand-seed`. To add another brand, insert a row into the
`brands` table (the engine creates the table at startup):

```bash
docker compose exec postgres psql -U postgres -d gameengine -c \
  "INSERT INTO brands (identity, secret, webhook_url) VALUES ('my-casino', 's3cret', 'https://casino.example.com/webhook')"
```

See [Operator Integration](Operator-Integration) for what the webhook must implement.

## Option 2 - run on the host

Requirements: JDK 21, Node.js 22, PostgreSQL and Redis (you can start just those two with
`docker compose up postgres redis`).

```bash
# 1. engine on :8080 (defaults match the compose datastores)
./gradlew run

# 2. example operator on :9090
./gradlew -p example/operator run

# 3. register the demo brand (after the engine has started once)
psql -h localhost -U postgres -d gameengine -c \
  "INSERT INTO brands (identity, secret, webhook_url) VALUES ('demo-operator', 'demo-secret', 'http://localhost:9090/webhook') ON CONFLICT (identity) DO NOTHING"

# 4. a client with hot reload; Vite proxies /ws and /api to localhost:8080
cd catalog/mines && npm ci && npm run dev
```

Then mint a session (`curl -X POST http://localhost:9090/session/42`) and open
`http://localhost:5173/?sessionToken=<token>&currency=TON&operator=demo-operator`.
Development ports of the other clients are listed in [Web Clients](Web-Clients).

## Troubleshooting

- **"Session not found"** in a client - the token was not registered, or `operator` in the
  URL does not match the brand that registered it.
- **Rounds fail to start** - the engine could not reach the TON API (`TON_API_URL`), or the
  operator webhook rejected the call (look for `Operator rejected ...` in the engine logs).
- **`POST /session/...` returns an error** - the brand row is missing or its `secret` does
  not match `OPERATOR_SECRET`.
