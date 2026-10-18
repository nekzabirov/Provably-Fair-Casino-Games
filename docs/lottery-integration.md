# Lottery Integration Guide

How a casino operator ("brand") integrates the **Lottery** — an always-on **shared**
draw every **2 hours**, one jackpot for **all brands**, played in **TON only**.

This guide covers what is **specific to the lottery**. The shared surfaces —
onboarding, session minting, the webhook contract, money units, and the
provably-fair primitives — are the same for every game and are documented once in
[Operator Integration Guide](./operator-integration.md). Read that first.

What makes the lottery different from the other games:

- **Two ways to play it.** A reference browser client ships in `catalog/lottery`
  (Vue 3); it uses the player-facing REST endpoints `POST /api/v1/lottery/state` and
  `POST /api/v1/lottery/buy` (see [REST](#rest--apiv1lottery)). You can also consume
  the lottery **directly over one WebSocket** (`/ws/lottery`) and render it however you
  like. The protocol is deliberately a single full-snapshot frame so a thin client
  is enough.
- **It is one shared draw for everyone.** A single pot per round is built from tickets
  bought by players across every brand. The winners — who may belong to a *different*
  brand — split the whole net pool. See [Cross-brand settlement](#5-cross-brand-settlement).
- **It is persisted.** A draw runs a full 2-hour round and survives engine
  restarts; you don't manage any of that.
- **Tickets are system tickets.** A player buys ONE ticket per draw with **6..36**
  numbers; it covers `C(n, 6)` six-number combinations and costs `ticketPrice × (n − 5)²`.

```
                POST /api/v1/session                 ws://engine/ws/lottery
   ┌──────────┐ ───────────────► ┌────────────┐ ◄──────────────────── ┌─────────────┐
   │ Operator │                  │   Engine   │   auth / buy           │ Your client │
   │ backend  │ ◄─────────────── │  (lottery) │ ────────────────────► │  (any UI)   │
   └──────────┘  POST {webhook}  └────────────┘   snapshot frames      └─────────────┘
                 /player /balance
                 /round/open /round/close
                 /debit /credit          game = "lottery", currency = "TON"
```

---

## 1. Prerequisites (same as every game)

1. **Onboarding** — a brand record (`identity`, `secret`, `webhookUrl`). See
   [§1 Onboarding](./operator-integration.md#1-onboarding).
2. **Session** — your backend mints a token and registers it with
   `POST /api/v1/session`. See [§2 REST API](./operator-integration.md#2-rest-api-operator--engine).
3. **Webhooks** — you implement `/player`, `/balance`, `/round/open`, `/round/close`,
   `/debit`, `/credit`. The lottery uses the **same** webhooks, with `game: "lottery"`
   and `currency: "TON"`. See [§3 Webhook](./operator-integration.md#3-webhook-engine--operator).
   If you already integrated any other game, the lottery needs **no new webhooks**.

The lottery appears in `GET /api/v1/games` as `{ "identity": "lottery" }`.

> **TON only.** Every lottery webhook carries `currency: "TON"`. Amounts are in
> **nanoton** (`1 TON = 1_000_000_000`). A 10 TON ticket is `amount: 10000000000`.

---

## 2. The round cycle

One shared draw runs forever, in fixed **2-hour rounds** aligned to UTC boundaries
(00:00, 02:00, 04:00, …; engine-configurable):

| Phase      | `bettingOpen` | `finished` | What happens |
|------------|:-------------:|:----------:|--------------|
| `SELLING`  | `true`        | `false`    | A full round. Players buy tickets; the pool grows. |
| `DRAWING`  | `false`       | `false`    | At the boundary, sales close. Six winning numbers are committed at once and **revealed one every 10s** (`winningNumbers` grows 0 → 6). |
| `SETTLING` | `false`       | `false`    | All six revealed. Winners are decided and paid. |
| `SETTLED`  | `false`       | `true`     | Brief pause showing the result; then the next round opens. |

Your client never tracks phases by name — it reads `bettingOpen` / `finished` /
`winningNumbers` from the snapshot (§4).

---

## 3. WebSocket — `/ws/lottery`

Your client connects with the session token and exchanges JSON frames. Every frame
your client sends is a JSON object whose `type` selects the command; the engine
replies (and broadcasts) a single **`lottery`** snapshot, or an **error frame**:

```json
{ "type": "error", "error": "Betting is closed" }
```

### Launch parameters

The same shape as other games, minus per-player provably-fair seed and currency
(the draw is shared and TON-only):

```
sessionToken = <the token you minted via POST /api/v1/session>
operator     = <your brand identity>
```

### Command — `auth`

Authenticate the connection and receive the current snapshot.

```json
{ "type": "auth", "token": "JBSWY3DPEHPK3PXP", "operator": "your-operator" }
```

| Field      | Type   | Notes                                                    |
|------------|--------|----------------------------------------------------------|
| `token`    | string | The session token you minted and registered.             |
| `operator` | string | Your brand `identity`.                                   |
| `currency` | string | Optional and ignored — the lottery is always TON.        |

The engine resolves the session by `(token, operator)` and replies with a `lottery`
snapshot. After `auth`, the engine pushes a fresh snapshot to this connection on
every change (a new ticket grows the pool, each number revealed, the draw settles, a
new round opens) — so a passive client only needs to render whatever arrives.

### Command — `buy`

Buy this draw's single **system ticket**: **6..36 distinct** numbers in **1..49**.
The ticket covers `C(n, 6)` six-number combinations but is priced **quadratically**:
`ticketPrice × (n − 5)²` — a steep volume discount versus full system-entry pricing
(`C(n,6) ×` would price a 36-number ticket at 19.5M TON, far beyond the pool), while
still growing fast enough that price always exceeds `winChance × pool` for any
realistic pool:

| Numbers | Combinations covered | Price (base 10 TON) | Jackpot odds |
|--------:|---------------------:|--------------------:|-------------:|
| 6       | 1                    | 10 TON              | 1 in 13 983 816 |
| 7       | 7                    | 40 TON              | 1 in 1 997 688  |
| 8       | 28                   | 90 TON              | 1 in 499 422    |
| 10      | 210                  | 250 TON             | 1 in 66 590     |
| 12      | 924                  | 490 TON             | 1 in 15 134     |
| 20      | 38 760               | 2 250 TON           | 1 in 361        |
| 36      | 1 947 792            | 9 610 TON           | 1 in 7.2 (≈13.9%) |

```json
{ "type": "buy", "numbers": [3, 11, 17, 22, 40, 49] }
```

| Field     | Type      | Notes                                                      |
|-----------|-----------|------------------------------------------------------------|
| `numbers` | int[6..36]| 6 to 36, all distinct, each `1..49`. Order is ignored.     |

- Allowed only while `bettingOpen` is `true`. One ticket per player per draw —
  a second `buy` returns `Ticket limit reached: max 1 tickets per draw`.
- Drives your webhooks: **`/round/open` then `/debit` (the full system-ticket
  price)**. You decline a purchase exactly as for any bet — return non-2xx from
  `/debit`.
- On success the engine replies with the player's snapshot (the ticket now in
  `tickets`) and broadcasts the grown `prizePool` to everyone.

### REST — `/api/v1/lottery`

The bundled client uses two player-facing REST endpoints instead of the socket. The
session token is the bearer (no operator secret is involved), and failures map to
HTTP statuses with a body of `{ "error": "..." }`:

| Endpoint                     | Body                                        | Returns            |
|------------------------------|---------------------------------------------|--------------------|
| `POST /api/v1/lottery/state` | `{ "token": "...", "operator": "..." }`     | the `lottery` snapshot for this player |
| `POST /api/v1/lottery/buy`   | `{ "token": "...", "operator": "...", "numbers": [ ... ] }` | the snapshot with the new ticket |

Status codes: `401` unknown or expired session, `409` ticket limit reached or a
purchase already in progress, `422` betting closed / wrong phase, `402` operator
rejected the debit, `400` invalid numbers, `502` any other failure (the purchase was
compensated, no charge remains).

### Snapshot frame — `lottery` (engine → client)

One object carries everything a brand needs. Personal fields (`tickets`,
`ticketCount`, `winChance`) reflect **this** player; the rest are shared.

```json
{
  "type": "lottery",
  "prizePool": 1010000000000,
  "currency": "TON",
  "ticketPrice": 10000000000,
  "tickets": [ { "id": 7, "numbers": [3, 11, 17, 22, 40, 49], "matched": null, "payout": null } ],
  "ticketCount": 1,
  "maxTickets": 1,
  "minPicks": 6,
  "maxPicks": 36,
  "winChance": 0.0000000715,
  "startsAt": 1750464000000,
  "endsAt": 1750471200000,
  "winningNumbers": [3, 17],
  "bettingOpen": true,
  "finished": false,
  "drawNonce": 42,
  "serverSeedHash": "9f86d0…",
  "serverSeed": null,
  "tonSeed": null
}
```

| Field            | Type            | Notes                                                         |
|------------------|-----------------|---------------------------------------------------------------|
| `prizePool`      | int             | Current pool (seed + all ticket sales), nanoton.              |
| `currency`       | string          | Always `"TON"`.                                               |
| `ticketPrice`    | int             | Price of a minimum (6-number) ticket, nanoton. An n-number ticket costs `(n − 5)²` times this. |
| `tickets`        | object[]        | **This** player's tickets: `{ id, numbers, matched, payout }`. `matched`/`payout` are `null` until the draw is decided. |
| `ticketCount`    | int             | `tickets.length`.                                             |
| `maxTickets`     | int             | Max tickets per player per draw (**1**).                      |
| `minPicks`       | int             | Minimum numbers on a ticket (= how many are drawn, **6**).    |
| `maxPicks`       | int             | Maximum numbers on a system ticket (**36**).                  |
| `winChance`      | float           | Probability the held ticket wins: combinations covered / `C(49, 6)`, in `[0, 1]`. |
| `startsAt`       | int             | When the draw opened (epoch **milliseconds**).                |
| `endsAt`         | int             | When sales close / the draw runs (epoch **milliseconds**).    |
| `winningNumbers` | int[]           | Numbers revealed so far (empty until the draw; grows to 6).   |
| `bettingOpen`    | bool            | Whether `buy` is currently accepted.                          |
| `finished`       | bool            | Whether this draw has settled (winners decided, pool paid).   |
| `drawNonce`      | int             | Nonce mixed into the draw hash (= the draw id). See §6.        |
| `serverSeedHash` | string          | Commitment to the server seed, shown the whole round.         |
| `serverSeed`     | string \| null  | Revealed only once `finished` — recompute the draw with it.   |
| `tonSeed`        | string \| null  | The public TON seed mixed into the draw, revealed once `finished`. |

---

## 4. Money flow (which webhooks fire, and when)

Each ticket is one engine `Round` (`game: "lottery"`, `currency: "TON"`). The same
webhook contract as every game; nothing lottery-specific to implement.

**On `buy` (player buys a ticket):**

```
POST {webhookUrl}/round/open  { sessionToken, roundId, game: "lottery", currency: "TON" }              -> 200
POST {webhookUrl}/debit       { sessionToken, roundId, game: "lottery", currency: "TON", amount: <(n−5)² × 10 TON> }  -> 200 | non-2xx to decline
```

**At settlement (just after the 2-hour boundary + the ~60s draw):**

- **A winning ticket:**

  ```
  POST {webhookUrl}/credit      { sessionToken, roundId, game: "lottery", currency: "TON", amount: <share> }  -> 200
  POST {webhookUrl}/round/close { sessionToken, roundId, game: "lottery", currency: "TON" }                   -> 200
  ```

- **A losing ticket:** only `/round/close` (no `/credit`). The stake stayed in the pool.

Durability and replay are identical to the rest of the engine: the engine commits a
PENDING transaction before each webhook, so a lost response is **replayed with the
same `transactionId`** — make `/debit` and `/credit` idempotent on `transactionId`
and a retry never moves money twice. A failed payout is retried by the engine's
recovery pass (same id), so a confirmed credit is never paid twice.

---

## 5. Cross-brand settlement

**Read this if you offer the lottery.** The pool is one shared jackpot across all
brands: each ticket's price is debited from *that* player's operator, but the
**entire net pool** is credited to the **winners**, through each winner's
operator. A winner may belong to a different brand than most of the contributors,
and the base **1000 TON** guaranteed floor is funded on top of ticket sales.

Consequently, when one of *your* players wins, the engine will call *your* `/credit`
for an amount that can far exceed what your own players paid into that draw. The
engine, as always, **tracks and reports** money movement; it does **not** move funds
between operators. Reconciliation between operators (and funding of the guaranteed
floor) is a **commercial/treasury arrangement** you must have in place before going
live. This mirrors how the shared PvP jackpot already works.

> If your wallet cannot honour a payout larger than the player's contributions, do
> not enable the lottery until the inter-operator settlement is agreed.

---

## 6. Economics

| Rule | Default | Notes |
|------|---------|-------|
| Round | 2 hours | Aligned to UTC boundaries (00:00, 02:00, …). |
| Ticket price | `(n − 5)² × 10 TON` | Quadratic in the numbers picked: 6 → 10 TON, 12 → 490 TON, 36 → 9 610 TON. Added to the pool on purchase. |
| Numbers per ticket | 6..36 distinct, `1..49` | ONE ticket per player per draw. Match is on the **set** — order is irrelevant. |
| Win condition | all 6 drawn numbers among the picks | A 6-number ticket: ≈ **1 in 13,983,816** (`C(49,6)`); an n-number ticket: `C(n,6)` in `C(49,6)`. |
| Commission | 10% | Taken **only** when there is a winner. |
| Payout | `pool × 0.9 / winners` | Split equally among all winning tickets. |
| Genesis seed | 1000 TON | The very first draw starts here. |
| No winner | **full rollover** | The whole pool (seed + sales) rolls into the next round — the jackpot grows. |
| Winner | **reseed 1000 TON** | After a jackpot, the next round restarts at the guaranteed floor. |

Because matching all six with a minimum ticket is ~1-in-14M, most rounds roll over
and the jackpot climbs — by design. Big system tickets are discounted against full
per-combination pricing, but the price still grows faster than the pool a realistic
round can hold, so a big system ticket buys no positive edge.

---

## 7. Provably fair (verify the draw)

The draw is a **shared** outcome, so there is no per-player seed — the public seed is
a TON block hash, as in TeleFly/PvP. For each draw:

- `serverSeed` — ours, 32 random bytes (hex). Its SHA-256 (`serverSeedHash`) is shown
  the whole round as a commitment; the full seed is revealed once `finished`.
- `tonSeed` — the latest TON block hash, captured once when the draw starts. Public,
  controlled by neither side. Revealed once `finished`.
- `drawNonce` — the draw id, carried in every snapshot.

The six numbers are:

```
hash    = HMAC-SHA256(serverSeed, tonSeed + drawNonce)        // hex
numbers = a 6-element distinct draw from 1..49, by a deterministic
          partial Fisher–Yates shuffle seeded by `hash`
```

After a draw, anyone can recompute the numbers from `(serverSeed, tonSeed,
drawNonce)` revealed in the final snapshot and confirm they match `winningNumbers`.
Because `serverSeedHash` was published before the draw and `tonSeed` is a public
beacon, neither the house nor a player can target a result.

---

## 8. End-to-end example

```
1. Player opens your lottery view. Your backend mints a token and registers the session:

   POST /api/v1/session   (X-Operator, X-Secret-Key)   { "token": "JBSWY3DPEHPK3PXP" }
   Engine -> you: POST /webhook/player   { "sessionToken": "JBSWY3DPEHPK3PXP" }  -> { "id": "42", "username": "alice" }
   -> { "token": "JBSWY3DPEHPK3PXP", "expireAt": "2026-07-01T00:00:00Z" }

2. Your client connects ws://engine/ws/lottery and authenticates:

   { "type": "auth", "token": "JBSWY3DPEHPK3PXP", "operator": "your-operator" }
   Engine -> client: lottery { prizePool: 1000000000000, bettingOpen: true, tickets: [], winningNumbers: [], ... }

3. Player picks seven numbers (a system ticket covering 7 combinations, price
   (7−5)² × 10 = 40 TON) and buys:

   { "type": "buy", "numbers": [3, 11, 17, 22, 40, 44, 49] }
   Engine -> you:    POST /webhook/round/open  { ..., roundId: 5001, game: "lottery", currency: "TON" }      -> 200
                     POST /webhook/debit       { ..., roundId: 5001, amount: 40000000000 }                   -> 200
   Engine -> client: lottery { prizePool: 1000040000000, tickets: [{ id: 7, numbers: [3,11,17,22,40,44,49] }], ... }
   Engine -> all:    lottery { prizePool: 1000070000000, ... }   (pool grew for everyone)

4. The 2-hour boundary — sales close, six numbers reveal over ~60s:

   Engine -> all: lottery { bettingOpen: false, winningNumbers: [3], ... }
   ... every 10s ...
   Engine -> all: lottery { winningNumbers: [3,11,17,22,40,49], ... }

5. Settlement (this ticket held all six drawn numbers):

   Engine -> you:    POST /webhook/credit      { ..., roundId: 5001, amount: <share> }   -> 200
                     POST /webhook/round/close { ..., roundId: 5001 }                     -> 200
   Engine -> client: lottery { finished: true, tickets: [{ ..., matched: true, payout: <share> }],
                               serverSeed: "…", tonSeed: "…", drawNonce: 42, ... }

   (A losing ticket gets only POST /webhook/round/close, no credit.)

6. After a brief pause the next round opens; bettingOpen becomes true again with a
   reseeded pool, and players may buy a new ticket.
```

A runnable reference operator (session minting + all six webhook endpoints with an
in-memory wallet) lives in [`example/operator`](../example/operator); it serves every
game, the lottery included.

---

## 9. Error reference (lottery)

Sent as `{ "type": "error", "error": "..." }`; the connection stays open. The shared
errors from [§8](./operator-integration.md#8-error-reference) apply (`Session not
found`, `Session expired`, `Not authenticated`, `Operator rejected /debit (<status>)`,
…). Lottery-specific:

| Message                                   | Cause                                            |
|-------------------------------------------|--------------------------------------------------|
| `Betting is closed`                       | `buy` while not `SELLING` (draw running / closed).|
| `Ticket limit reached: max 1 tickets per draw` | `buy` when the player already holds a ticket. |
| `Ticket already bought for this lottery`  | A second `buy` while one is still in flight.     |
| `ticket must have 6..36 numbers`          | Wrong count of numbers.                          |
| `numbers must be in 1..49`                | A number outside the range.                      |
| `numbers must be distinct`                | Duplicate numbers in the ticket.                 |
| `Lottery is played in TON only`           | A non-TON currency reached a money operation.    |

---

## 10. Configuration reference (engine side)

| Variable                     | Default          | Meaning                                   |
|------------------------------|------------------|-------------------------------------------|
| `LOTTERY_CURRENCY`           | `TON`            | The (single) lottery currency.            |
| `LOTTERY_TICKET_PRICE_NANO`  | `10000000000`    | Base ticket price (6 numbers, 10 TON), nanoton; n numbers cost base × (n − 5)². |
| `LOTTERY_BASE_POOL_NANO`     | `1000000000000`  | Guaranteed base / reseed (1000 TON), nanoton. |
| `LOTTERY_COMMISSION_PERCENT` | `10`             | Commission on a paid pool.                |
| `LOTTERY_NUMBERS`            | `6`              | Numbers drawn / minimum picks per ticket. |
| `LOTTERY_MAX_PICKS`          | `36`             | Maximum picks on a system ticket.         |
| `LOTTERY_RANGE`              | `49`             | Number range (`1 .. RANGE`).              |
| `LOTTERY_MAX_TICKETS_PER_PLAYER` | `1`          | Tickets per player per draw.              |
| `LOTTERY_ROUND_MS`           | `7200000`        | Round length (2h), draws on UTC boundaries. |
| `LOTTERY_DRAW_INTERVAL_MS`   | `10000`          | Delay between revealing each number.      |
| `LOTTERY_SETTLE_PAUSE_MS`    | `10000`          | Pause on the result before the next round. |

See also the game's internal notes in [`src/game/lottery/README.md`](../src/game/lottery/README.md).
