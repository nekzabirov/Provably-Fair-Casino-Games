# Lottery — `game/lottery/`

An always-on **shared lottery** drawn every **2 hours**, played in **TON only**. A
brand integrates against the WebSocket (one `auth`, one optional `buy`, and a single
full-snapshot frame pushed on every change); our own iframe client
(`catalog/lottery`) consumes the equivalent REST API (`api/LotteryApi.kt`). One pot
for **all brands** (the same cross-brand shared-jackpot model as PvP). This module
owns only the lottery and reaches shared concerns (`RoundService`, `PlayerService`,
`ProvablyFair`) through the services it is allowed to call.

Unlike PvP/TeleFly (in-memory rounds that live seconds), the lottery aggregate is
**persisted**, so the engine **resumes the active draw on boot**.

---

## 1. Game design

- **Cycle** (one shared draw, forever):
  - `SELLING` — a full 2-hour round; buying a ticket is open until the boundary.
  - `DRAWING` — at the **2-hour UTC boundary** (00:00, 02:00, …) sales close; the
    **6** numbers are committed at once and **revealed one every 10s**
    (presentation only — see §2).
  - `SETTLING` — winners decided and paid.
  - `SETTLED` — a brief pause; then the next round opens.
- **Ticket:** a **system ticket** of **6..36 distinct** numbers in **1..49**, bought
  **once per player per draw**. It covers `C(n, 6)` six-number combinations and
  costs `(n − 5)² × 10 TON` (quadratic volume pricing: 6 → 10, 12 → 490,
  36 → 9 610 TON — discounted against full per-combination pricing, but still
  above `winChance × pool` for any realistic pool). The price is added to the pool.
- **Pool:** `prizePool = seedPool + Σ ticket.amount`. Genesis `seedPool` = **1000 TON**.
- **Win:** a ticket that holds **all 6 drawn numbers among its picks**.
  Winners split `netPool = gross × (100 − 10)%` equally (10% commission).
- **Rollover:** with **no winner** the **whole pool rolls** into the next round's
  `seedPool` (the jackpot grows). With a winner, the next round **reseeds the 1000
  TON** guaranteed floor. The 1000 base is a real guarantee paid to the winner
  (cross-brand, like PvP).

A minimum ticket hits ~1 in 14M (`C(49,6)`), so most rounds roll over — by design.

---

## 2. Provably fair (shared-game form, TON public seed)

One outcome serves every player, so there is no per-player client seed — the public
seed is a TON block hash, as in TeleFly/PvP.

```
serverSeed = ProvablyFair.generateServerSeed()        // ours; sha256 committed all round
tonSeed    = ProvablyFair.getTonSeed()                // public TON block hash, captured at draw start
nonce      = lotteryId
hash       = ProvablyFair.computeHash(serverSeed, tonSeed, nonce)   // 3-arg HMAC-SHA256(serverSeed, tonSeed + nonce)
numbers    = LotteryDraw.numbers(hash, 6, 49)         // 6 distinct in 1..49, partial Fisher–Yates, sorted
```

All six numbers are derived **the instant `tonSeed` is captured** — revealing them one
per 10s is purely visual, never a late re-draw. `serverSeedHash` is sent the whole
round; `serverSeed` + `tonSeed` are revealed only once `finished`, so anyone
reproduces the draw.

---

## 3. Transport — WebSocket (`/ws/lottery`) + REST (`/api/v1/lottery`)

### Incoming commands (client → server)

```json
{ "type": "auth", "token": "BASE32...", "operator": "acme" }
{ "type": "buy",  "numbers": [3, 11, 17, 22, 40, 44, 49] }
```

`currency` is fixed to TON (any client-sent value is ignored). The REST API
(`POST /api/v1/lottery/state` | `/buy`) exposes the same two operations for our
iframe client.

### Server frame — one shape, full snapshot

Pushed on `auth`, on the player's own `buy`, and to everyone on every shared change
(a new ticket grows the pool, each number revealed, settle, next round opens).
See `LotteryState.kt` for every field; the shape is the wire contract documented in
[`docs/lottery-integration.md`](../../../docs/lottery-integration.md) — personal
fields are `tickets` (each `{ id, numbers, matched?, payout? }`), `ticketCount` and
`winChance` (= combinations covered / `C(49,6)`); shared fields carry the pool, the
`minPicks`/`maxPicks`/`ticketPrice` pricing inputs, the sales window, the reveal
progress and the provably-fair material.

---

## 4. Money & durability (operator-first, via RoundService)

- **buy:** `open` the player's shared `Round` → `debit((n−5)² × 10 TON)` → persist
  `LotteryTicket` (the charged `amount` is kept per-row, so the pool is exact even
  if the configured price changes between rounds).
  A failed ticket insert refunds via `compensateDebit`.
- **settle:** outcomes (`matched` / `payout`) are persisted on each ticket **before** the
  winner credit; winners get `credit(share)` (reuse path — a replay sends the SAME
  transactionId so the operator dedupes), then every round is `close`d.
- **resume:** on boot the engine reloads the active lottery and its tickets, continues the
  current phase, re-runs an unfinished settle (idempotent), and sweeps orphan rounds
  (pay per persisted payout, close) so a crash never double-pays or loses the pot.

One ticket per player per draw is enforced by the engine's per-player cap
(`LOTTERY_MAX_TICKETS_PER_PLAYER`, default 1) and the round's `uniq_active_round`.
Concurrency mirrors PvP: a single `Mutex` guards the in-memory runtime and is
**never** held across an operator HTTP call or the TON fetch.

---

## 5. Files

```
game/lottery/
├── model/
│   ├── Lottery.kt        Exposed entity: currency, status(SELLING/DRAWING/SETTLING/
│   │                     SETTLED), startsAt, endsAt, seedPool, serverSeed,
│   │                     serverSeedHash, tonSeed?, winningNumbers, drawnCount,
│   │                     netPool?, winnerCount?, settledAt.
│   ├── LotteryTicket.kt  round(ref, 1:1), lottery(ref), player(ref), numbers,
│   │                     amount, matched?, payout?.
│   └── LotteryState.kt   @Serializable full snapshot (§3).
├── service/
│   ├── LotteryDraw.kt    Pure: 6 distinct numbers from the hash (Fisher–Yates).
│   ├── LotterySchedule.kt Pure: next 2-hour UTC boundary (configurable).
│   └── LotteryEngine.kt  The always-on, persisted engine (tick loop, phases,
│                         mutex money flow, settle, boot resume + reconcile).
├── command/             LotteryAuth / LotteryBuy (+ handlers). TON only.
└── network/             Connection, ConnectionRegistry (per-player broadcast),
                         CommandBus, Envelope, WsException, LotterySocket (/ws/lottery).
```

```
LOTTERY_* env knobs (see util/Env.kt): base (6-number) ticket price, max picks, base pool,
commission, numbers, range, tick/draw/settle pacing, and the round length
(LOTTERY_ROUND_MS, 2h).
```
