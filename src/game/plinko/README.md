# Plinko — `game/plinko/`

Classic casino Plinko, provably-fair over a player seed + the TON block hash,
delivered over a single WebSocket connection. This module owns **only** Plinko:
its entity, its math, its game logic, and its CQRS command layer. It never knows
about the operator, other games, or how money is held — it reaches shared
concerns exclusively through the shared services it is allowed to call.

---

## 1. Game design

- **Board:** a triangular peg field of **8–16 rows**; under it `rows + 1` slots.
- **Setup:** player picks a **bet**, the **row count**, and a **risk** level
  (LOW / MEDIUM / HIGH).
- **Play:** one command — `drop`. The ball bounces left/right once per row and
  lands in a slot; the slot's multiplier settles the bet instantly.
- **Rapid fire:** the first drop opens the player's round and every further
  drop joins it as another place/settle pair while the round stays open (the
  round closes when the connection goes away). The client may send the next
  drop while the previous ball is still falling — the drop button never locks,
  and holding it auto-drops until released or the balance runs out. Commands on
  one socket are handled sequentially server-side.

### Multiplier (binomial paytable)

The ball lands in slot `k` with probability `C(rows, k) / 2^rows`. Each
(risk, rows) table is symmetric (only the edge→center half is stored) and tuned
so that

```
RTP = Σ C(rows,k)/2^rows × multiplier[k]  ∈  [0.9650, 0.9720]
```

— house edge ~3%, matching Mines. LOW keeps the center near ×1 with modest
edges; HIGH sinks the center below ×0.2 and pays up to **×980** (16 rows) at the
edges. Tables live in `PlinkoMultiplier` (Kotlin) and are mirrored in the web
client's `tables.ts` for display/verification; the engine's copy is
authoritative.

---

## 2. Provably fair (player seed + TON)

The path is **never stored**. It is always derived from the drop's seeds, which
is what makes the game verifiable — we cannot steer a ball after the fact.

```
serverSeed = ProvablyFair.generateServerSeed()    // 32 random bytes, ours
clientSeed = <player's, sent on auth>             // the player controls this
tonSeed    = ProvablyFair.getTonSeed()            // latest TON block hash (public)
nonce      = debit transaction id (unique per drop)
hash       = ProvablyFair.computeHash(serverSeed, clientSeed, tonSeed, nonce)  // HMAC-SHA256
```

`PlinkoPath` reads one hex digit per row: its lowest bit is the bounce
(0 = left, 1 = right); the landing slot is the number of right-bounces. The
64-hex-char hash covers the maximum 16 rows with room to spare.

Plinko resolves instantly, so the commitment (`sha256(serverSeed)`) and the
reveal (the seed itself) arrive in the same RESULT frame: the hash proves the
seed was fixed before the outcome was computed, and the in-app verifier (shield
icon → Provably Fair) reproduces the path, slot and multiplier on the spot.

---

## 3. Transport — WebSocket

### Incoming commands (client → server)

```json
{ "type": "auth", "token": "BASE32...", "currency": "TON", "operator": "acme", "clientSeed": "..." }
{ "type": "drop", "amount": 100, "rows": 12, "risk": "MEDIUM" }
```

- `auth` — resolve player from token, store player + currency + clientSeed on
  the connection, register it, return WAIT (there is never an in-progress round
  to restore — Plinko is instant).
- `drop` — one ball: open-or-adopt round + debit → derive path → credit payout.
  The round stays open for the next ball. Returns RESULT.

### Response — single shape

```json
{
  "player":      { "username": "…", "balance": 15000, "profilePic": "…" },
  "state":       "RESULT",            // WAIT | RESULT
  "rows":        12,
  "risk":        "MEDIUM",
  "placeAmount": 100,
  "settleAmount": 196,
  "slot":        4,
  "multiplier":  1.96,
  "path":        [0,1,1,0,1,0,0,1,0,0,0,1],
  "fairness":    { "serverSeedHash": "…", "serverSeed": "…", "clientSeed": "…", "tonSeed": "…", "nonce": 41 }
}
```

The client animates the ball along `path`; the outcome is the engine's, the
animation is pure presentation.

---

## 4. Round lifecycle and money

Order matters and is delegated to `RoundService` (operator-first for money):

- **drop:** `RoundService.open` (idempotent — the first drop creates the round,
  later drops adopt it) → `RoundService.debit` (rejection rolls back) → derive
  outcome → `RoundService.credit` (payout > 0 only) → persist `PlinkoGame` (the
  audit row, one per drop).
- **disconnect:** the socket's `finally` closes the active round (only if the
  connection is still the registered one — a reauth-replacement keeps playing
  the same round).

One open `Round` therefore spans many drops; each drop is its own debit/credit
pair, carried to the operator with a unique `transactionId` (the fairness nonce
is that DEBIT tx id, NOT the round id). A crash-stranded open round is simply
adopted by the next drop.

---

## 5. Files

```
game/plinko/
├── model/
│   ├── PlinkoGame.kt    Exposed entity: round(ref), rows, risk, placeAmount,
│   │                    balance, slot, multiplier, serverSeed, clientSeed,
│   │                    tonSeed. Path NOT stored — derived from seeds.
│   └── PlinkoState.kt   @Serializable single response (player, state, rows,
│                        risk, amounts, slot, multiplier, path, fairness).
├── service/             Pure game math (no IO/clock/DB).
│   ├── PlinkoMultiplier.kt  Risk enum + RTP-tuned half-tables + lookups.
│   └── PlinkoPath.kt        path(hash, rows), slot(hash, rows).
├── command/             CQRS — one @Serializable command + its handler per file.
│   ├── PlinkoAuth.kt    { token, currency, operator, clientSeed } → WAIT
│   └── PlinkoDrop.kt    { amount, rows, risk } → debit + resolve + credit + close
└── network/             WebSocket transport, owned solely by Plinko.
    ├── Connection.kt    Socket + player/currency/token/clientSeed + send().
    ├── ConnectionRegistry.kt  One connection per player, replace-on-reauth.
    ├── CommandBus.kt    type → handler dispatch; errors become an error frame.
    ├── Envelope.kt      Incoming-frame parsing + error-response shape.
    ├── WsException.kt   Transport errors.
    └── PlinkoSocket.kt  Ktor /ws/plinko route.
```

Web client: `catalog/plinko/` — Vue 3 + Pixi.js board (pegs, slot chips, gold
balls hopping along the server path), navy theme (#0E304A, accent #3AB0FF), bet/rows/risk
controls, big-win overlay, in-app provably-fair verifier.

---

## 6. Interaction with shared services

This module calls — and must **not** modify — the shared services
(`RoundService`, `PlayerService`, `ProvablyFair`). It does not touch
`TransactionTable` or `RoundTable` directly. (The shared `RoundService` itself
now creates the Transaction row first and sends its id as `transactionId` on
debit/credit webhooks — a shared-service change all games inherit.)
