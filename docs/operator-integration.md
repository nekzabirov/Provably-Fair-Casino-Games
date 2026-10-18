# Operator Integration Guide

How a casino operator ("brand") integrates the game engine. You integrate it the
same way you integrate any third-party game provider: you give us one webhook URL,
and we drive game rounds and money movement against it.

There are **three** surfaces:

1. **REST API** (you → engine) — mint a session for one of your players.
2. **Webhook** (engine → you) — we ask you for player info and tell you to move money.
3. **Game WebSocket** (player's browser → engine) — gameplay; you don't implement this,
   your game client connects to it with the session token.

```
            POST /api/v1/session                 ws://engine/ws/mines
  ┌──────────┐ ───────────────► ┌────────────┐ ◄──────────────────── ┌─────────────┐
  │ Operator │                  │   Engine   │                       │ Game client │
  │ backend  │ ◄─────────────── │            │                       │  (browser)  │
  └──────────┘   POST {webhook} └────────────┘                       └─────────────┘
                 /player /balance
                 /round/open /round/close
                 /debit /credit
```

All money is in **minor units** (integers), never decimals. See [Money](#money).

---

## 1. Onboarding

Before anything works, the engine needs a **brand** record for you with three values:

| Field         | Meaning                                                              |
|---------------|---------------------------------------------------------------------|
| `identity`    | Your operator slug. Matches `^[a-z0-9-_]+$`. Sent as `X-Operator`.   |
| `secret`      | Shared secret. Sent as `X-Secret-Key` in **both** directions.       |
| `webhookUrl`  | The single base URL we call for everything. Paths are appended to it. |

Provisioning is done on the engine side. For a local/self-hosted setup:

```sql
INSERT INTO brands (identity, secret, webhook_url)
VALUES ('your-operator', 'your-shared-secret', 'https://ops.example.com/webhook');
```

The `secret` authenticates traffic in both directions:

- You send it to us as `X-Secret-Key` on every REST call (plus `X-Operator`).
- We send it back to you as `X-Secret-Key` on every webhook call. Reject any
  webhook request whose `X-Secret-Key` does not match.

---

## 2. REST API (operator → engine)

Base path: `/api/v1`. JSON over HTTP.

**Authentication — every request:**

| Header         | Value                          |
|----------------|--------------------------------|
| `X-Operator`   | your brand `identity`          |
| `X-Secret-Key` | your brand `secret`            |

On an auth failure the engine responds `401` with:

```json
{ "error": "Missing or malformed operator headers" }
```
```json
{ "error": "Invalid operator credentials" }
```

### GET /api/v1/games

Lists the games available to your players.

**Response `200`:**

```json
[
  { "identity": "mines" },
  { "identity": "luckycup" },
  { "identity": "telefly" },
  { "identity": "plinko" },
  { "identity": "pvp" },
  { "identity": "lottery" }
]
```

The `identity` value is the game slug (it is also the `game` field on every
webhook). The WebSocket path is `/ws/<identity>` for every game except Lucky Cups
(`luckycup`), whose socket is `/ws/shell`.

> **`lottery`** is a shared, TON-only draw every 2 hours with a different shape from
> the per-player games (a single full-snapshot frame, one pot for all brands). It has
> its own brand guide: [Lottery Integration](./lottery-integration.md).

### POST /api/v1/session

Registers (or extends) a session. **You mint the session token and pass it to us**
— we store it as-is and never generate our own, and we learn which player it
belongs to by calling your `/player` webhook with it (see below). This is the only
thing your backend must call to start a play session.

**Request body:**

```json
{ "token": "JBSWY3DPEHPK3PXP" }
```

| Field   | Type   | Notes                                                              |
|---------|--------|--------------------------------------------------------------------|
| `token` | string | **Your** session credential. We store it and the game client carries it on the WS; we echo it back as `sessionToken` in every webhook. Must be unique per live session. |

**Response `200`:**

```json
{
  "token": "JBSWY3DPEHPK3PXP",
  "expireAt": "2026-05-30T14:30:00Z"
}
```

| Field      | Type            | Notes                                                  |
|------------|-----------------|--------------------------------------------------------|
| `token`    | string          | The same token you sent, echoed back.                  |
| `expireAt` | string ISO-8601 | UTC instant the session expires.                       |

Behaviour:

- Before binding the session we call your webhook `/player` with this `token` to
  learn the player's `id`/`username`/`profilePic`, then remember it.
- Calls are **idempotent per token**: re-sending the same `token` extends its
  expiry; a new `token` opens a new session (a player may hold several over time).
- Default session TTL is 1 day (engine-configurable). There is no forced
  revoke — sessions lapse naturally.

Example:

```bash
curl -X POST https://engine.example.com/api/v1/session \
  -H "X-Operator: your-operator" \
  -H "X-Secret-Key: your-shared-secret" \
  -H "Content-Type: application/json" \
  -d '{"token":"JBSWY3DPEHPK3PXP"}'
```

---

## 3. Webhook (engine → operator)

You implement these. Every request is:

- **`POST`**, with `Content-Type: application/json`.
- Path = your `webhookUrl` + the suffix below (e.g. `https://ops.example.com/webhook/debit`).
- Authenticated by header **`X-Secret-Key: <your secret>`** — verify it and return
  `401` if it does not match.
- Subject to a request timeout (5000 ms by default).

**Every request carries `sessionToken` = the token you minted** and passed to
`/api/v1/session`, never our internal id. Resolve your session (and from it, the
player) by that token. Round/money requests also carry `roundId` — our internal
round id, the one piece of internal state that crosses the wire so you have a
handle to reference a round.

> **Rollback semantics.** The engine treats any non-2xx response as a rejection.
> For `/round/open`, `/round/close`, `/debit`, a rejection rolls back the engine's
> surrounding database transaction (the round/stake is not recorded). This is how
> you decline a stake: return non-2xx from `/debit`. A timeout counts as a
> rejection too.

### Summary

```
POST {webhookUrl}/player        {sessionToken}                                  -> {id, username, profilePic?}
POST {webhookUrl}/balance       {sessionToken, currency}                        -> {balance}
POST {webhookUrl}/round/open    {sessionToken, roundId, game, currency}         -> 200
POST {webhookUrl}/round/close   {sessionToken, roundId, game, currency}         -> 200
POST {webhookUrl}/debit         {sessionToken, roundId, game, currency, amount, transactionId} -> 200 | non-2xx to decline
POST {webhookUrl}/credit        {sessionToken, roundId, game, currency, amount, transactionId} -> 200
```

### POST {webhookUrl}/player

We ask who a session token belongs to. Called once when we first register a
session (from `POST /api/v1/session`), before any play. Resolve the session by
`sessionToken` and return the player it belongs to.

**Request:**

```json
{ "sessionToken": "JBSWY3DPEHPK3PXP" }
```

**Response `200`:**

```json
{ "id": "42", "username": "alice", "profilePic": "https://cdn.example.com/a.png" }
```

| Field        | Type            | Notes              |
|--------------|-----------------|--------------------|
| `id`         | string          | **Your** id for the player. We store it as the player's external id; we never expose our internal ids. |
| `username`   | string          | Required.          |
| `profilePic` | string \| null  | Optional avatar URL. Omit or `null` if none. |

### POST {webhookUrl}/balance

We ask for the player's current balance in a given currency. Called at session
start and whenever the engine needs a fresh figure.

**Request:**

```json
{ "sessionToken": "JBSWY3DPEHPK3PXP", "currency": "TON" }
```

**Response `200`:**

```json
{ "balance": 5000000000 }
```

| Field     | Type | Notes                                  |
|-----------|------|----------------------------------------|
| `balance` | int  | Minor units. e.g. `5000000000` = 5 TON. |

### POST {webhookUrl}/round/open

A round is starting. Record it against `roundId` if you wish. Return `200` to allow.

**Request:**

```json
{ "sessionToken": "JBSWY3DPEHPK3PXP", "roundId": 1001, "game": "mines", "currency": "TON" }
```

| Field      | Type   | Notes                                  |
|------------|--------|----------------------------------------|
| `roundId`  | int    | Engine's round id; your handle to it.  |
| `game`     | string | Game slug, e.g. `mines`.               |
| `currency` | string | Currency code for the round.           |

**Response:** `200` (empty body is fine). Non-2xx aborts the round.

### POST {webhookUrl}/round/close

The round ended (the player hit a mine, cashed out, or it was otherwise settled).
Same body as `/round/open`. Return `200`.

```json
{ "sessionToken": "JBSWY3DPEHPK3PXP", "roundId": 1001, "game": "mines", "currency": "TON" }
```

### POST {webhookUrl}/debit

Take the stake from the player's wallet. **This is where you decline a bet.**

**Request:**

```json
{ "sessionToken": "JBSWY3DPEHPK3PXP", "roundId": 1001, "game": "mines", "currency": "TON", "amount": 100000000, "transactionId": 5001 }
```

| Field    | Type | Notes                          |
|----------|------|--------------------------------|
| `amount` | int  | Minor units to debit.          |
| `transactionId` | int | Engine's id for this money movement. Unique per debit/credit; a retried call reuses the same id, so deduplicate on it. |

**Response:**

- `200` — funds taken. The round proceeds.
- **Any non-2xx** (e.g. `402`) — insufficient funds / declined. The engine rolls
  back: no round, no stake. Return a body like `{"error":"insufficient funds"}`
  for your own logs; the engine only inspects the status code.

### POST {webhookUrl}/credit

Pay winnings into the player's wallet. Called on cash-out. Same body shape as
`/debit`.

```json
{ "sessionToken": "JBSWY3DPEHPK3PXP", "roundId": 1001, "game": "mines", "currency": "TON", "amount": 247000000, "transactionId": 5002 }
```

**Response:** `200`. You own the money; the engine does not retry-reconcile a
failed credit, so make this reliable on your side.

---

## 4. Money

- **Always integer minor units.** Never decimals, never floating point on the wire.
- The `amount`/`balance` scale is per currency, by that currency's smallest unit:
  - **TON** — nanoton. `1 TON = 1_000_000_000`. So `100000000` = 0.1 TON.
  - Fiat (e.g. `USD`) — cents. `1 USD = 100`. So `550` = $5.50.
- **Every game is played in TON only.** `currency` is always `TON` (nanoton
  minor units). Stars (`XTR`) and other currencies were removed; the engine
  ignores any other `currency` value a client sends and books the round in TON.
- The engine **tracks and reports** money movement; it does **not enforce limits**.
  Balance checks, bet limits, and wallet rules are yours to enforce — that is the
  purpose of the `/debit` decline path.

---

## 5. Game WebSocket (player's client → engine)

Your game client (browser) connects here with the session token. You do not
implement this endpoint — it's documented so you understand the full flow and so
the gameplay frames the engine drives against your webhook make sense.

**Endpoint:** `GET /ws/mines` (WebSocket upgrade).

**Framing.** Every client→engine frame is a JSON object whose `type` selects the
command. The engine replies to every command with a single **`MinesState`** object
(see below), or an **error frame** on failure:

```json
{ "type": "error", "error": "Session expired" }
```

### 5.1 Launch URL

You open the game client with three query params, which the client reads and
replays on `auth`:

```
https://<game>.<host>/?sessionToken=<token>&currency=TON&operator=<your-identity>
```

- `sessionToken` — the token you minted and registered via `POST /api/v1/session`.
- `currency` — optional and always `TON`; may be omitted. The engine is TON-only
  and ignores any other value.
- `operator` — your brand `identity`.

### Commands

**`auth`** — authenticate the connection; restores any in-progress round.

```json
{ "type": "auth", "token": "JBSWY3DPEHPK3PXP", "currency": "TON", "operator": "your-operator", "clientSeed": "player-seed" }
```

`clientSeed` is the player's provably-fair seed (optional; the engine mints a random
one if omitted). It is generated/persisted by the game client, not the operator.

`token` is the session token; `operator` is your brand `identity` (both come from
the launch URL — see [§5.1](#51-launch-url)). The engine resolves the session by
`(token, operator)`. The `currency` chosen here is held on the connection and used
by every later command. The engine calls your `/balance` and replies with the
current `MinesState`.

**`start`** — open a round and place the stake.

```json
{ "type": "start", "amount": 100000000, "mineCount": 5 }
```

| Field       | Type | Notes                          |
|-------------|------|--------------------------------|
| `amount`    | int  | Stake, minor units. Must be > 0. |
| `mineCount` | int  | Number of mines, `3..24`.      |

Drives your webhook: `/round/open` then `/debit`. Re-sending `start` while a round
is live is **idempotent** — it returns the in-progress round without debiting again.

**`open`** — reveal a cell on the 5×5 (25-cell) grid.

```json
{ "type": "open", "index": 7 }
```

`index` is `0..24`. A diamond → round stays `ACTIVE` and `settleAmount` grows. A
mine → round ends `FAIL` and the engine calls your `/round/close`. (No money moves
on a diamond; balance was captured at `start`.)

**`cashout`** — settle the round and take winnings.

```json
{ "type": "cashout" }
```

Drives your webhook: `/credit` (current winnings) then `/round/close`. Replies with
state `WAIT`.

### MinesState (engine → client)

```json
{
  "player": { "username": "alice", "balance": 4900000000, "profilePic": null },
  "state": "ACTIVE",
  "placeAmount": 100000000,
  "settleAmount": 121250000,
  "mineCount": 5,
  "cells": [
    { "index": 3,  "type": "DIAMOND", "multiplier": 1.2125 },
    { "index": 11, "type": "DIAMOND", "multiplier": 1.4774 }
  ]
}
```

| Field            | Type                        | Notes                                                 |
|------------------|-----------------------------|-------------------------------------------------------|
| `player.username`| string                      |                                                       |
| `player.balance` | int                         | Minor units, from your `/balance`.                    |
| `player.profilePic` | string \| null           |                                                       |
| `state`          | `ACTIVE` \| `WAIT` \| `FAIL`| Round open / no round / lost on a mine.               |
| `placeAmount`    | int                         | Stake placed (minor units). `0` when `WAIT`.          |
| `settleAmount`   | int                         | Current/final winnings (minor units). `0` on `FAIL`/`WAIT`. |
| `mineCount`      | int                         | Mines in the grid.                                    |
| `cells`          | array                       | Revealed cells, in reveal order.                      |
| `cells[].index`  | int                         | Cell position `0..24`.                                |
| `cells[].type`   | `DIAMOND` \| `MINE`         | `MINE` only appears in the final `FAIL` state.        |
| `cells[].multiplier` | number                  | Cumulative multiplier at the moment that cell was revealed. |

**Payout.** `settleAmount = floor(placeAmount × multiplier(mineCount, diamonds))`,
where the multiplier is classic Mines combinatorics at **RTP 0.97**:
`multiplier = 0.97 × C(25, opened) / C(25 − mineCount, opened)`.

---

## 6. Provably fair

Each round's outcome is verifiable; the engine cannot pick mine positions after a
bet. For every round:

- `serverSeed` — 32 random bytes (hex), generated and stored at round start. Its
  SHA-256 hash is shown to the player up front (the commitment) and the full seed
  revealed once the round closes.
- `clientSeed` — the **player's own seed**, sent on the `auth` frame and changeable
  at any time (the next round uses the new one).
- `tonSeed` — the latest TON block hash: a public value neither party controls.
- `roundId` — the engine's round id, used as the nonce.

The round randomness is `HMAC-SHA256(serverSeed, "clientSeed:tonSeed:roundId")`. The
mine layout is derived from that hash via a deterministic Fisher–Yates shuffle and
is **never stored** — anyone can recompute it from
`(serverSeed, clientSeed, tonSeed, roundId)` after the round and confirm the board.
The game clients ship a built-in verifier that does exactly this. (TeleFly's crash
is a shared round, so its public seed is the TON hash alone:
`HMAC-SHA256(serverSeed, tonSeed + roundId)`.)

---

## 7. End-to-end example

```
1. Player opens your game. Your backend mints a token and registers the session:

   POST /api/v1/session   (X-Operator, X-Secret-Key)   { "token": "JBSWY3DPEHPK3PXP" }

   Engine -> you:  POST /webhook/player   { "sessionToken": "JBSWY3DPEHPK3PXP" }
                   -> { "id": "42", "username": "alice" }
   -> { "token": "JBSWY3DPEHPK3PXP", "expireAt": "2026-05-30T14:30:00Z" }

   You then open the game client at:
   https://mines.<host>/?sessionToken=JBSWY3DPEHPK3PXP&currency=TON&operator=your-operator

2. Your client connects ws://engine/ws/mines and sends:
   { "type": "auth", "token": "JBSWY3DPEHPK3PXP", "currency": "TON", "operator": "your-operator", "clientSeed": "player-seed" }

   Engine -> you:  POST /webhook/balance  { "sessionToken": "JBSWY3DPEHPK3PXP", "currency": "TON" }
   Engine -> client: MinesState { state: "WAIT", ... }

3. Client: { "type": "start", "amount": 100000000, "mineCount": 5 }

   Engine -> you:  POST /webhook/round/open  { ..., "roundId": 1001 }
                   POST /webhook/debit       { ..., "amount": 100000000 }   -> 200
   Engine -> client: MinesState { state: "ACTIVE", settleAmount: 0, cells: [] }

4. Client: { "type": "open", "index": 7 }   (diamond)
   Engine -> client: MinesState { state: "ACTIVE", settleAmount: 116400000, cells:[...] }

5. Client: { "type": "cashout" }

   Engine -> you:  POST /webhook/credit      { ..., "amount": 116400000 }   -> 200
                   POST /webhook/round/close { ..., "roundId": 1001 }        -> 200
   Engine -> client: MinesState { state: "WAIT", ... }
```

A runnable reference operator (session minting + all six webhook endpoints, with an
in-memory wallet) lives in [`example/operator`](../example/operator).

---

## 8. Error reference

**REST (`401`):** `Missing or malformed operator headers`, `Invalid operator credentials`.

**WebSocket** (sent as `{ "type": "error", "error": "..." }`, the connection stays open):

| Message                          | Cause                                              |
|----------------------------------|----------------------------------------------------|
| `Session not found`              | Token unknown.                                     |
| `Session expired`                | Token past its expiry.                              |
| `Not authenticated`              | A command sent before `auth`.                      |
| `Malformed frame`                | Frame is not a JSON object with a `type`.           |
| `Unknown command: <type>`        | Unrecognised `type`.                                |
| `Invalid payload`                | Command fields missing/wrong type.                  |
| `amount must be positive`        | `start` with `amount <= 0`.                         |
| `mineCount must be 3..24`        | `start` with mine count out of range.               |
| `index out of range`             | `open` with `index` outside `0..24`.                |
| `cell already opened`            | `open` on a previously revealed cell.               |
| `Active round not found`         | `open`/`cashout` with no live round.                |
| `Operator rejected <path> (<status>)` | Your webhook returned non-2xx / timed out.     |
| `Internal error`                 | Unexpected engine failure.                          |

---

## 9. Configuration reference (engine side)

| Variable                  | Default  | Meaning                          |
|---------------------------|----------|----------------------------------|
| `SESSION_EXPIRE_MINUTES`  | `1440`   | Session TTL (1 day).             |
| `BRAND_TIMEOUT_MS`        | `5000`   | Webhook request timeout.         |
| `SERVER_PORT`             | `8080`   | Engine HTTP/WS port.             |
