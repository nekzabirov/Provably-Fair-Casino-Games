# Configuration

All configuration is environment variables with defaults suitable for local
development. Engine settings are read once at startup in `src/util/Env.kt`; logging
settings are read by `resources/logback.xml`.

## Engine

### Server, storage, integrations

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP and WebSocket port (binds `0.0.0.0`). |
| `DB_URL` | `jdbc:postgresql://localhost:5432/gameengine` | PostgreSQL JDBC URL. Tables are created and migrated at startup. |
| `DB_USER` | `postgres` | Database user. |
| `DB_PASSWORD` | `postgres` | Database password. |
| `DB_POOL_SIZE` | `10` | HikariCP maximum pool size. |
| `REDIS_URL` | `redis://localhost:6379` | Redis URL. Used as a cache for session lookups. |
| `TON_API_URL` | `https://toncenter.com/api/v2` | TON HTTP API base URL. The engine calls `GET {TON_API_URL}/getMasterchainInfo` and uses `result.last.root_hash` as the public seed. |
| `TON_BLOCK_CACHE_SECONDS` | `5` | How long a fetched block hash is reused before the next fetch. |
| `SESSION_EXPIRE_MINUTES` | `1440` | Lifetime of a session registered with `POST /api/v1/session`. |
| `BRAND_TIMEOUT_MS` | `5000` | Timeout for each operator webhook call; a timeout counts as a rejection. |

### TeleFly (crash)

| Variable | Default | Description |
|---|---|---|
| `TELEFLY_TICK_MS` | `100` | Interval of the round loop and of `tick` broadcasts. |
| `TELEFLY_BETTING_MS` | `10000` | Length of the betting phase. |
| `TELEFLY_CRASH_PAUSE_MS` | `4000` | Pause after the crash before the next betting phase. |
| `TELEFLY_SEND_TIMEOUT_MS` | `1000` | Per-connection send timeout for broadcasts; a socket that does not accept a frame in time misses that frame. |
| `TELEFLY_GROWTH_RATE` | `0.07` | The multiplier follows `exp(rate * seconds)` until the crash point. |

The crash-point distribution (RTP 96%) is a constant in `CrashPoint.kt`, not a setting.

### PvP jackpot wheel

| Variable | Default | Description |
|---|---|---|
| `PVP_TICK_MS` | `100` | Interval of the round loop. |
| `PVP_COUNTDOWN_MS` | `10000` | Countdown that starts once `PVP_MIN_PLAYERS` have bet. |
| `PVP_SPIN_MS` | `6000` | Spin animation phase. |
| `PVP_PAYOUT_PAUSE_MS` | `4000` | Pause showing the winner. |
| `PVP_SEND_TIMEOUT_MS` | `1000` | Per-connection send timeout for broadcasts. |
| `PVP_MIN_PLAYERS` | `2` | Players with a stake needed to start the countdown. |

### Lottery

Amounts are nanoton (1 TON = 10^9).

| Variable | Default | Description |
|---|---|---|
| `LOTTERY_CURRENCY` | `TON` | Lottery currency. |
| `LOTTERY_TICKET_PRICE_NANO` | `10000000000` | Base price (10 TON) of a 6-number ticket; an n-number ticket costs `base * (n - 5)^2`. |
| `LOTTERY_BASE_POOL_NANO` | `1000000000000` | Guaranteed pool (1000 TON) of the first draw and of every draw after a jackpot. |
| `LOTTERY_COMMISSION_PERCENT` | `10` | Commission kept from a pool that has a winner. |
| `LOTTERY_NUMBERS` | `6` | Numbers drawn, and the minimum picks per ticket. |
| `LOTTERY_MAX_PICKS` | `36` | Maximum picks on one system ticket. |
| `LOTTERY_RANGE` | `49` | Numbers are drawn from `1..LOTTERY_RANGE`. |
| `LOTTERY_MAX_TICKETS_PER_PLAYER` | `1` | Tickets one player may buy per draw. |
| `LOTTERY_TICK_MS` | `1000` | Interval of the draw loop. |
| `LOTTERY_DRAW_INTERVAL_MS` | `10000` | Delay between revealing drawn numbers. |
| `LOTTERY_SETTLE_PAUSE_MS` | `10000` | Pause on the result before the next draw opens. |
| `LOTTERY_SEND_TIMEOUT_MS` | `1000` | Per-connection send timeout for pushes. |
| `LOTTERY_ROUND_MS` | `7200000` | Draw length (2 hours); draws close on UTC boundaries. |

### Logging

Logs are one JSON object per line on stdout with the fields `ts`, `level`, `logger`,
`msg`, `stack`, `service` (always `tongame`), `env`, plus any MDC values.

| Variable | Default | Description |
|---|---|---|
| `LOG_LEVEL` | `INFO` | Root log level. |
| `OPERATOR` | `aggregator` | Value written to the `env` field of every line. |

## Example operator (`example/operator`)

| Variable | Default | Description |
|---|---|---|
| `OPERATOR_PORT` | `9090` | Listen port. |
| `OPERATOR_IDENTITY` | `demo-operator` | Brand identity, sent as `X-Operator`. |
| `OPERATOR_SECRET` | `demo-secret` | Shared secret, sent as `X-Secret-Key` and required on webhooks. |
| `ENGINE_URL` | `http://localhost:8080` | Engine base URL. |
| `OPERATOR_START_BALANCE` | `1000000` | Starting balance of a new player, minor units. `docker-compose.yml` sets `1000000000000` (1000 TON). |

## Web clients (build time)

Vite inlines these when the bundle is built (`npm run build`), so they are set in the
build environment, not at runtime.

| Variable | Clients | Default | Description |
|---|---|---|---|
| `VITE_WS_URL` | mines, lucky, telefly, plinko, pvp | same origin `/ws/<socket>` (`ws://` or `wss://` matching the page) | Engine WebSocket URL. |
| `VITE_API_BASE` | lottery | `''` (same origin) | Prefix for `/api/v1/lottery/*` calls. |
| `VITE_AVATAR_PROXY_URL` | pvp | `https://api.<page domain>/api/v1/avatar` (first host label replaced by `api`) | Avatar proxy endpoint. The Docker image sets `/api/v1/avatar`. |

Client containers (nginx) read `ENGINE_URL` at start (default `http://engine:8080`) and
proxy `/ws/` and `/api/` there.

Runtime parameters of a client (session token, operator, currency) come from the page URL;
see [Web Clients](Web-Clients).

## Brands

Operators are rows in the `brands` table (`identity`, `secret`, `webhook_url`); there is
no API to create them. See [Operator Integration](Operator-Integration).
