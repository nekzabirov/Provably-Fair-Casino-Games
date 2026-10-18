# PvP — `game/pvp/`

A real-time, always-on **jackpot wheel** ("Roulette"), delivered over a single
WebSocket connection and played in **TON only**. Players bet into
a shared pot; each occupies a contiguous arc of a 100% wheel sized to their share;
a provably-fair number lands the pointer on one arc and that player **wins the
entire pot** (0% rake — zero house edge). This module owns only PvP and reaches
shared concerns (`RoundService`, `PlayerService`, `ProvablyFair`) through the
shared services it is allowed to call; it does not modify them.

It is the engine's second always-on, shared game and mirrors **TeleFly**
(`game/telefly/`): the same in-memory mutex-guarded round loop, per-player
`Round`/`Transaction` money flow, broadcast frames, and startup reconciliation.

---

## 1. Game design

- **Phases** (one shared round, forever):
  - `WAITING` — fewer than `Env.pvpMinPlayers` (2) players staked; bets open.
  - `COUNTDOWN` — 2+ players → a 10s countdown; bets **stay open** (join / top-up /
    cancel; the pot grows live). If active players drop below 2 it reverts to `WAITING`.
  - `SPINNING` — countdown hit 0 → bets **locked**; the winner is already decided;
    the wheel animates (~6s) to the winning number.
  - `PAYOUT` — ~4s pause showing the winner; then a fresh round (new seeds) opens.
- **Bet model:** `bet {amount}` places or **tops up** a player's cumulative stake;
  `cancel` refunds the whole stake and leaves. Both only in `WAITING`/`COUNTDOWN`.
- **Winner takes the whole pot.** Probability of winning = stake / pot, so paying
  the full pot is exactly fair (house edge 0).

---

## 2. Provably fair (shared-game form, TON public seed)

One outcome serves every player, so there is no per-player client seed — the public
seed is a TON block hash, exactly like TeleFly's crash point.

```
serverSeed = ProvablyFair.generateServerSeed()        // ours; sha256 committed up-front
clientSeed = ProvablyFair.getTonSeed()                // public TON block hash
nonce      = roundNonce
hash       = ProvablyFair.computeHash(serverSeed, clientSeed, nonce)   // 3-arg, HMAC-SHA256
```

`WheelResult` (pure) maps the hash to the outcome:

```
fraction = first 32 bits of hash / 2^32   ∈ [0,1)
number   = fraction × 100                  → the 0..100 wheel number (the center value)
ticket   = floor(fraction × pot)           → an index into the pooled stake
winner   = the player whose cumulative [start,end) stake interval contains ticket,
           players in canonical join order (the order the roster is broadcast in)
```

Because each player's arc is drawn proportional to their stake in that same order,
the pointer at angle `fraction × 360°` lands in exactly the winning arc — the visual
and the math agree by construction. `serverSeedHash` is broadcast while bets are
open; `serverSeed` + the final ordered roster are revealed in the spin frame, so the
in-app verifier (shield icon) reproduces the number **and** the winning player.

---

## 3. Transport — WebSocket

### Incoming commands (client → server)

```json
{ "type": "auth",   "token": "BASE32...", "currency": "TON", "operator": "acme" }
{ "type": "bet",    "amount": 500 }
{ "type": "cancel" }
```

### Server frames

- Personal `state` (reply to auth/bet/cancel): balance, phase, pot, players, the
  ordered roster, this player's stake, the commitment hash, history.
- Broadcast `phase`: phase/roster/pot snapshot on every membership/stake change and
  phase transition — drives everyone's wheel arcs + countdown.
- Broadcast `spin`: winning number, winner, pot, **revealed seeds**, final roster.

---

## 4. Round lifecycle and money (operator-first, via RoundService)

- **bet (new):** `open` → `debit(amount)` → persist `PvpGame`.
- **bet (top-up):** `debit(amount)` on the same active round; bump `PvpGame.placeAmount`.
- **cancel:** `credit(stake)` refund → `close`; the player leaves the round.
- **lock (countdown end):** the winner is computed under the engine mutex from the
  committed seeds + the locked roster; then off-lock, per player: the winner gets
  `credit(pot)` and everyone is `close`d.

Each player has their own shared `Round` (the partial unique index allows one active
round per player+game+currency), all carrying the same `roundNonce`. A crash mid-round
is reconciled on boot by refunding every open `pvp` round (net-zero).

Concurrency mirrors TeleFly exactly: a single `Mutex` guards the in-memory round/bet
state and is **never** held across an operator HTTP call (lock → claim in memory →
unlock → money work → re-lock → finalize/revert).

---

## 5. Files

```
game/pvp/
├── model/
│   ├── PvpGame.kt     Exposed entity: round(ref), roundNonce, sessionToken,
│   │                  placeAmount (cumulative), balance, serverSeed, clientSeed,
│   │                  winningNumber?, won?.
│   ├── PvpState.kt    Phase enum + @Serializable personal state (player, phase,
│   │                  pot, players, roster: Seat[], bet, history).
│   └── PvpFrames.kt   PvpPhaseFrame (roster/pot snapshot) + PvpSpinFrame (reveal).
├── service/
│   ├── WheelResult.kt Pure: fraction(hash), winningNumber(f), winner(f, stakes).
│   └── PvpEngine.kt   The always-on engine (tick loop, phases, mutex money flow,
│                      settlement, reconcile). Mirrors TeleFlyEngine.
├── command/           PvpAuth / PvpBet / PvpCancel (+ handlers). Currency == TON.
└── network/           Connection, ConnectionRegistry (broadcast), CommandBus,
                       Envelope, WsException, PvpSocket (/ws/pvp).
```

Web client: `catalog/pvp/` — Vue 3 + Pixi.js spinning wheel (proportional arcs,
player chips, top pointer, center number), violet "midnight" theme, bet stepper +
presets, Pot/Stake/Chance readouts, cancel, winner overlay, players strip, in-app
provably-fair verifier. TON only.
