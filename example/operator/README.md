# Example operator

A minimal casino operator that integrates the game engine. It does two things:

1. **Creates sessions** — calls the engine's `POST /api/v1/session` as the
   operator, authenticated with `X-Operator` (brand identity) + `X-Secret-Key`.
2. **Serves the webhook** the engine drives for player info and money movement,
   verifying `X-Secret-Key` on every call. Balances are kept in memory here — the
   operator owns the money, the engine never does.

This is a standalone Gradle project; it does not affect the engine build.

## Configuration (env, with defaults)

| Var                     | Default                  | Meaning                                   |
|-------------------------|--------------------------|-------------------------------------------|
| `OPERATOR_PORT`         | `9090`                   | Port this operator listens on             |
| `OPERATOR_IDENTITY`     | `demo-operator`          | Brand identity (sent as `X-Operator`)     |
| `OPERATOR_SECRET`       | `demo-secret`            | Shared secret (`X-Secret-Key`, both ways) |
| `ENGINE_URL`            | `http://localhost:8080`  | Engine base URL                           |
| `OPERATOR_START_BALANCE`| `1000000`                | Starting wallet balance (minor units)     |

## Wire it up in the engine

The engine must have a brand row matching the config above, with its webhook URL
pointing back at this operator:

```sql
INSERT INTO brands (identity, secret, webhook_url)
VALUES ('demo-operator', 'demo-secret', 'http://localhost:9090/webhook');
```

## Run

```bash
# 1. start the engine (from the repo root) — needs Postgres + Redis
./gradlew run

# 2. start this operator
./gradlew -p example/operator run

# 3. mint a session for player 42 (operator -> engine -> back)
curl -X POST http://localhost:9090/session/42
# -> { "token": "BASE32...", "expireAt": "2026-05-29T..." }
```

The game client then connects to the engine's `/ws/mines` with that token; the
engine calls this operator's `/webhook/*` endpoints for balance and money moves.

## Webhook endpoints (engine -> operator)

```
POST /webhook/player        {sessionToken}                              -> {id, username, profilePic?}
POST /webhook/balance       {sessionToken, currency}                    -> {balance}
POST /webhook/round/open    {sessionToken, roundId, game, currency}     -> 200
POST /webhook/round/close   {sessionToken, roundId, game, currency}     -> 200
POST /webhook/debit         {sessionToken, roundId, game, currency, amount, transactionId} -> 200 | 402 (insufficient)
POST /webhook/credit        {sessionToken, roundId, game, currency, amount, transactionId} -> 200
```
