# Operator Integration

An operator (a casino, "brand") connects to the engine the way it would connect to any
third-party game provider:

1. **REST** (operator to engine) - register a player session.
2. **Webhook** (engine to operator) - player info, balance, round lifecycle, debit and
   credit. The operator holds the money.
3. **Game client** (player's browser to engine) - the operator opens a game client with the
   session token; the client talks to the engine directly.

A complete, runnable reference is `example/operator` (Kotlin/Ktor, in-memory wallet).
The long-form guides with every field are `docs/operator-integration.md` and
`docs/lottery-integration.md` in the repository.

## 1. Onboarding

Each operator is a row in the `brands` table:

| Column | Meaning |
|---|---|
| `identity` | slug matching `^[a-z0-9]+([-_][a-z0-9]+)*$`; sent as `X-Operator` |
| `secret` | shared secret; sent as `X-Secret-Key` in both directions |
| `webhook_url` | base URL; webhook paths are appended to it |

```sql
INSERT INTO brands (identity, secret, webhook_url)
VALUES ('my-casino', 's3cret', 'https://casino.example.com/webhook');
```

The table is created by the engine at startup. There is no API for managing brands.

## 2. Register a session

The operator mints an opaque token for a player and registers it:

```bash
curl -X POST http://localhost:8080/api/v1/session \
  -H "X-Operator: my-casino" \
  -H "X-Secret-Key: s3cret" \
  -H "Content-Type: application/json" \
  -d '{"token":"9F2C0D7A4B1E4E52A3F0C6D1B2A39E77"}'
# {"token":"9F2C0D7A4B1E4E52A3F0C6D1B2A39E77","expireAt":"2026-01-02T10:00:00Z"}
```

Before answering, the engine calls the operator's `/player` webhook with the token to
learn who the player is. Re-sending the same token extends its expiry
(`SESSION_EXPIRE_MINUTES`, default 24 h); there is no revoke. `GET /api/v1/games` lists
the game identities.

## 3. Open a game

Open the game client (an iframe or a new page) with:

```
https://<client host>/?sessionToken=<token>&currency=TON&operator=<identity>
```

`currency` may be omitted (every game is TON-only). Optionally `depositUrl=<url>`: when a
bet cannot be covered the client posts
`{type: "openDeposit", currency, url}` to its parent window. See [Web Clients](Web-Clients).

## 4. Implement the webhook

All calls are `POST {webhook_url}<path>` with `Content-Type: application/json` and
`X-Secret-Key: <secret>`; reject anything with a wrong secret. Each call times out after
`BRAND_TIMEOUT_MS` (5 s); any non-2xx response or timeout is treated as a rejection.

| Path | Request body | Response |
|---|---|---|
| `/player` | `{"sessionToken"}` | `{"id": "<your player id>", "username": "...", "profilePic": "<url or null>"}` |
| `/balance` | `{"sessionToken", "currency"}` | `{"balance": <minor units>}` |
| `/round/open` | `{"sessionToken", "roundId", "game", "currency"}` | 2xx to allow |
| `/round/close` | same as `/round/open` | 2xx |
| `/debit` | `{"sessionToken", "roundId", "game", "currency", "amount", "transactionId"}` | 2xx to take the stake, non-2xx (e.g. `402`) to decline |
| `/credit` | same as `/debit` | 2xx |

Notes:

- `sessionToken` is your own token; resolve the player from it.
- `game` is the game identity: `mines`, `luckycup`, `telefly`, `plinko`, `pvp`,
  `lottery`. `currency` is always `TON`; amounts are nanoton.
- `roundId` and `transactionId` are the engine's ids. **Deduplicate on `transactionId`**:
  every debit and credit is stored before the call, and a retry after a lost response
  sends the same `transactionId` again.
- A rejected `/debit` declines the bet: the engine records nothing for it. If the engine
  fails after a successful debit, it refunds with a `/credit` (new `transactionId`) and
  closes the round.
- The engine does not enforce limits; balance checks, bet limits and responsible-gaming
  rules belong to the operator and are applied by declining `/debit`.

## 5. Money flow per game

| Game | Calls |
|---|---|
| Mines | `start`: `/round/open`, `/debit`. Cash-out: `/credit`, `/round/close`. Mine hit: `/round/close`. |
| Lucky Cups | `start`: `/round/open`, `/debit`. Win: `/credit`, `/round/close`. Loss: `/round/close`. |
| Plinko | first drop: `/round/open`; every drop: `/debit`, then `/credit` when the payout is positive. The round is closed when the connection ends. |
| TeleFly | `bet`: `/round/open`, `/debit`. Cash-out: `/credit`, `/round/close`. Crash: `/round/close`. |
| PvP | `bet`/top-up: `/round/open` (first bet), `/debit`. Cancel: `/credit` refund, `/round/close`. Settlement: `/credit` of the whole pot to the winner, `/round/close` for everyone. |
| Lottery | `buy`: `/round/open`, `/debit`. Settlement: `/credit` for winning tickets, `/round/close` for all. |

`/balance` is called on `auth` and after money moves so the client can show a fresh
balance.

## 6. The shared lottery pot

The lottery is one draw for all operators on an engine. Each ticket is debited from the
buyer's operator, while winners are credited through their own operator, so a winner's
operator can pay out more than its players paid in. Agree on settlement between operators
before enabling it; see `docs/lottery-integration.md`. The PvP pot is shared across
operators in the same way.

## 7. Example operator

```bash
docker compose up --build          # or: ./gradlew -p example/operator run
curl -X POST http://localhost:9090/session/42
```

`example/operator` mints tokens (`POST /session/{playerId}`), serves all six webhook paths
under `/webhook`, verifies `X-Secret-Key`, and keeps balances in memory (declining debits
with `402` when funds are short). Configuration: [Configuration](Configuration#example-operator-exampleoperator).
