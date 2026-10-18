# Mines — `game/mines/`

Classic casino Mines, provably-fair over the TON block hash, delivered over a
single WebSocket connection. This module owns **only** Mines: its entity, its
math, its game logic, and its CQRS command layer. It never knows about the
operator, other games, or how money is held — it reaches shared concerns
exclusively through the shared services it is allowed to call.

---

## 1. Game design

- **Board:** 25 cells. Each cell is either a **diamond** or a **mine**.
- **Setup:** player picks a **bet** and a **mine count** (3–24). Diamonds on the
  board = `25 − mineCount`.
- **Play:** reveal cells one at a time.
  - Diamond → multiplier rises, player may continue or cash out.
  - Mine → round lost, stake gone.
- **Cash out** any time while the round is active → pays `bet × multiplier`.

### Multiplier (industry-standard combinatorics)

```
multiplier(opened) = RTP × C(25, opened) / C(25 − mineCount, opened)
RTP = 0.97
```

Properties this gives for free:
- More mines → fewer safe cells → higher multiplier (risk/reward).
- Every reveal is individually fair at RTP.
- 24 mines, 1 diamond revealed → ≈ ×24.25 (max-risk case).

`opened = 0` → multiplier `1.0` (just the stake), so a round before any reveal
settles at exactly the bet.

---

## 2. Provably fair (TON)

The mine layout is **never stored**. It is always derived from the round's
seeds, which is what makes the game verifiable — we cannot change positions
after the fact.

```
serverSeed = ProvablyFair.generateServerSeed()    // 32 random bytes, ours
clientSeed = <player's, sent on auth>             // the player controls this
tonSeed    = ProvablyFair.getTonSeed()            // latest TON block hash (public)
nonce      = roundId
hash       = ProvablyFair.computeHash(serverSeed, clientSeed, tonSeed, nonce)  // HMAC-SHA256
```

`MinesRng.minePositions(hash, mineCount)` runs a deterministic Fisher–Yates
shuffle of the 25 cells driven by the hash and takes the first `mineCount` as
mines. Same hash + same mineCount ⇒ same layout, every time.

The player sends their **own client seed** on `auth` (changeable any time — the
next round picks it up), and the engine commits to `sha256(serverSeed)` up front
via the state's `fairness` block. When the round closes, the full `serverSeed` is
revealed so anyone — including the in-app verifier — can recompute and confirm.
TON here is a neutral public **randomness beacon**, mixed in so neither side alone
controls the result; it is not a payment rail. Money moves through the operator.

---

## 3. Transport — WebSocket

Mines runs over WebSocket (not REST) because a round has **state that lives
between steps** and must survive a client reload. The transport lives inside this
module (`network/`) and is owned solely by Mines. The connection is the unit of
session; the active round is restored on (re)connect.

### Connection rules

- **One player = one connection.** A re-`auth` for the same player replaces the
  previous connection (old socket closed), then re-registers.
- **Currency lives on the connection.** It is sent **once, on `auth`**, and
  remembered. Subsequent commands do not carry currency — it is read from the
  connection (needed to display balance) and, for round operations, from the
  active round itself.
- A connection may outlive many rounds (start → cashout → start again).

### Incoming commands (client → server)

```json
{ "type": "auth",    "token": "BASE32...", "currency": "TON" }
{ "type": "start",   "amount": 100, "mineCount": 5 }
{ "type": "open",    "index": 12 }
{ "type": "cashout" }
```

- `auth` — resolve player from token, store player + currency on the connection,
  register it, return the current state (restores an in-progress round).
- `start` — open round + debit; currency comes from the connection.
- `open` — reveal a cell; no currency needed.
- `cashout` — settle and close; no currency needed.

All commands except `auth` require an authenticated connection.

---

## 4. Single response shape

Every command replies with the same structure, so the client always renders from
one payload and a reload fully restores the round:

```json
{
  "player":      { "username": "…", "balance": 15000, "profilePic": "…" },
  "state":       "ACTIVE",            // ACTIVE | WAIT | FAIL
  "placeAmount": 100,
  "settleAmount": 270,                // current payout = bet × multiplier
  "mineCount":   5,
  "cells": [
    { "index": 5,  "type": "DIAMOND", "multiplier": 1.10 },
    { "index": 12, "type": "DIAMOND", "multiplier": 1.26 }
  ]
}
```

### States
| State    | Meaning                                                   |
|----------|-----------------------------------------------------------|
| `ACTIVE` | Round open; player may `open` more cells or `cashout`.    |
| `WAIT`   | No active round; waiting for `start`.                     |
| `FAIL`   | Round ended on a mine; stake lost.                        |

### `cells`
- Lists the diamonds the player has revealed, in order, each with the
  **cumulative multiplier at the moment it was revealed** (history of the climb).
- On `FAIL`, `cells` contains **only what the player opened** — we do not reveal
  the rest of the board.

### `settleAmount`
- `ACTIVE`: `bet × multiplier(opened)`.
- `WAIT` / `FAIL`: `0`.

---

## 5. Balance handling

Balance is owned by the operator; we never store a running balance. To avoid
hammering the operator on every reveal:

- The operator balance is fetched **only on `auth`, `start`, and `cashout`** —
  the moments it can change.
- On `start`, the post-stake balance is **captured into `MinesGame.balance`**.
- On `open`, the response reuses that captured value (no operator call). This is
  correct because the balance does not change between `start` and `cashout`.

---

## 6. Round lifecycle and money

Order matters and is delegated to `RoundService` (operator-first for money):

- **start:** `RoundService.open` (registers round with operator) →
  `RoundService.debit` (operator takes the stake; rejection rolls everything
  back) → persist `MinesGame`.
- **open (diamond):** record the index; recompute multiplier. No money movement.
- **open (mine):** `RoundService.close`. Stake already taken; no credit.
- **cashout:** `RoundService.credit` (operator pays winnings) →
  `RoundService.close`.

One active round per `(player, game, currency)` is enforced by a partial unique
index on `rounds` (`WHERE closed_at IS NULL`). `start` is idempotent: opening
while an active round exists returns the existing one.

---

## 7. Files

The module is four packages — `model`, `service`, `command`, `network`. There is
**no `MinesService`**: each command handler holds its own game logic and reaches
the shared services (`RoundService`, `ProvablyFair`, `PlayerService`) directly.

```
game/mines/
├── model/
│   ├── MinesGame.kt        Exposed entity: round(ref), mineCount, placeAmount,
│   │                       balance (captured at start), opened (CSV of revealed
│   │                       diamond indices), serverSeed, clientSeed. Mine layout
│   │                       is NOT stored — derived from seeds. Also holds the
│   │                       GAME id, forRound(...), settleAmount(), revealedCells().
│   └── MinesState.kt       @Serializable single response (player, state,
│                           placeAmount, settleAmount, mineCount, cells) with
│                           wait()/active()/fail() factories.
├── service/                Pure game math (no IO/clock/DB).
│   ├── MinesRng.kt         minePositions(hash, mineCount), isMine(...).
│   │                       Deterministic Fisher–Yates from the hash.
│   └── MinesMultiplier.kt  calculate(mineCount, opened). RTP 0.97.
├── command/                CQRS — one @Serializable command + its handler per
│   │                       file. The handler IS the game logic (wrapped in
│   │                       runTransactionCatching).
│   ├── MinesAuth.kt        { token, currency } → resolve + register + current state
│   ├── MinesStart.kt       { amount, mineCount } → open round + debit + persist
│   ├── MinesOpen.kt        { index }            → reveal cell (mine → FAIL+close)
│   └── MinesCashout.kt     { }                  → credit + close
└── network/                WebSocket transport, owned solely by Mines.
    ├── Connection.kt       Socket + mutable player/currency + send(payload).
    ├── ConnectionRegistry.kt  One connection per player, replace-on-reauth.
    ├── CommandBus.kt       type → handler dispatch; turns errors into an error frame.
    ├── Envelope.kt         Incoming-frame parsing + error-response shape.
    ├── WsException.kt      Transport errors (malformed / unknown / not-authenticated).
    └── MinesSocket.kt      Ktor /ws/mines route: per-connection loop that parses
                            the frame's `type` and dispatches via the command bus;
                            unregisters on disconnect.
```

---

## 8. Interaction with shared services

This module calls — and must **not** modify — the shared services. The only
shared-service change Mines required was agreed explicitly:

- `RoundService.findActiveRound(player, game, currency)` is **public** so the game
  can resolve the single active round on reconnect, scoped to the currency held on
  the connection (the currency is part of the round's identity).

Everything else Mines needs (the stake and the in-round balance) is stored on
`MinesGame` rather than reaching into shared tables. Mines does not touch
`TransactionTable` or `RoundTable` directly — only `RoundService`,
`PlayerService`, and `ProvablyFair`.

---

## 9. Decisions locked for this module

- Classic two-type board (diamond / mine); the earlier three-type idea
  (multiplier / diamond / mine cells with random per-cell values) was dropped in
  favour of standard casino math.
- Mine layout derived from seeds, never persisted.
- WebSocket transport; currency sent once on `auth` and held on the connection.
- One response shape for all commands; reload restores via `auth`.
- Operator balance fetched only on `auth` / `start` / `cashout`; captured on
  `MinesGame` for use during `open`.
- The CQRS bus uses no interfaces (a `type → lambda` registry), consistent with
  the repo-wide "no interfaces" rule. Commands are `@Serializable` data classes;
  handlers are Koin-wired classes; command + handler share one file.
