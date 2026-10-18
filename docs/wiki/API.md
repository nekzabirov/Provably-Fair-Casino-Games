# API

The engine listens on one port (`SERVER_PORT`, default `8080`) and exposes three
surfaces: a REST API, game WebSockets, and calls to the operator's webhook. There is no
gRPC interface. All amounts are integers in minor units (TON in nanoton, 1 TON =
1 000 000 000) and every game is played in `TON`.

## REST

| Method and path | Auth | Body | Response |
|---|---|---|---|
| `GET /api/v1/games` | `X-Operator`, `X-Secret-Key` | - | `[{"identity":"mines"}, {"identity":"luckycup"}, {"identity":"telefly"}, {"identity":"plinko"}, {"identity":"pvp"}, {"identity":"lottery"}]` |
| `POST /api/v1/session` | `X-Operator`, `X-Secret-Key` | `{"token": "<operator-minted token>"}` | `{"token": "...", "expireAt": "<ISO-8601 UTC>"}` |
| `POST /api/v1/lottery/state` | session token in body | `{"token": "...", "operator": "..."}` | lottery snapshot for this player |
| `POST /api/v1/lottery/buy` | session token in body | `{"token": "...", "operator": "...", "numbers": [..]}` | lottery snapshot including the new ticket |
| `GET /api/v1/avatar?u=<url>` | none | - | the image bytes with `Access-Control-Allow-Origin: *` |

- Operator authentication: `X-Operator` is the brand identity, `X-Secret-Key` its secret.
  Missing or malformed headers and wrong credentials return `401` with
  `{"error": "..."}`.
- `POST /api/v1/session` first calls the operator's `/player` webhook with the token; it is
  idempotent per token (re-sending extends the expiry).
- Lottery REST errors: `401` unknown/expired session, `409` ticket limit reached or a
  purchase in progress, `422` betting closed or wrong phase, `402` operator rejected the
  debit, `400` invalid numbers, `502` other failures (no charge remains).
- The avatar proxy only fetches Telegram hosts (`t.me`, `*.t.me`, `*.telegram.org`,
  `*.telegram-cdn.org`, `*.cdn-telegram.org`) and answers `400` otherwise. It exists so
  the PvP wheel can draw Telegram profile pictures on a WebGL canvas.

There is no dedicated health endpoint.

## WebSockets

| Path | Game | Commands |
|---|---|---|
| `/ws/mines` | Mines | `auth`, `start`, `open`, `cashout` |
| `/ws/shell` | Lucky Cups | `auth`, `start`, `pick` |
| `/ws/plinko` | Plinko | `auth`, `drop` |
| `/ws/telefly` | TeleFly | `auth`, `bet`, `cashout` |
| `/ws/pvp` | PvP wheel | `auth`, `bet`, `cancel` |
| `/ws/lottery` | Lottery | `auth`, `buy` |
| `/ws/jackpot` | Lottery jackpot feed | none (read-only, no session) |

Every frame is a JSON object; `type` selects the command. A failing command produces
`{"type": "error", "error": "<message>"}` and the connection stays open. Commands sent
before `auth` fail with `Not authenticated`. A new `auth` for the same player replaces
the previous connection. The server pings every 25 s and drops a peer silent for 60 s.

### auth

```json
{ "type": "auth", "token": "<session token>", "operator": "<brand identity>", "currency": "TON", "clientSeed": "my-seed" }
```

`currency` is optional and ignored (always TON). `clientSeed` exists on Mines, Lucky
Cups and Plinko only: 1-64 characters of `[A-Za-z0-9-_]`; when empty the engine generates
one. The engine answers with the game's current state, restoring an unfinished round.

### Commands per game

| Game | Command | Fields | Notes |
|---|---|---|---|
| Mines | `start` | `amount`, `mineCount` (3..24) | Opens a round and debits the stake. Re-sending while a round is live returns it without a second debit. |
| Mines | `open` | `index` (0..24) | Reveals a cell. |
| Mines | `cashout` | - | Credits the current win and closes the round. |
| Lucky Cups | `start` | `amount` | Opens a round and debits the stake. |
| Lucky Cups | `pick` | `cup` (0..2) | Resolves the round. |
| Plinko | `drop` | `amount`, `rows` (8..16), `risk` (`LOW`, `MEDIUM`, `HIGH`) | Debit, outcome and credit in one step; drops may be sent back to back. |
| TeleFly | `bet` | `amount` | Betting phase only, one bet per player per round. |
| TeleFly | `cashout` | - | Flying phase only. |
| PvP | `bet` | `amount` | Places or tops up the player's stake (waiting or countdown phase). |
| PvP | `cancel` | - | Refunds the whole stake and leaves the round (waiting or countdown phase). |
| Lottery | `buy` | `numbers` (6..36 distinct, 1..49) | One ticket per player per draw by default. |

### Server frames

- **Mines** - one state object per reply: `player {username, balance, profilePic}`,
  `state` (`WAIT`, `ACTIVE`, `FAIL`), `placeAmount`, `settleAmount`, `mineCount`,
  `cells [{index, type, multiplier}]`, `fairness`.
- **Lucky Cups** - `player`, `state` (`WAIT`, `ACTIVE`, `WIN`, `LOSE`), `placeAmount`,
  `settleAmount`, `winningCup`, `fairness`.
- **Plinko** - `player`, `state` (`WAIT`, `RESULT`), `rows`, `risk`, `placeAmount`,
  `settleAmount`, `slot`, `multiplier`, `path` (0 = left, 1 = right per row), `fairness`.
- **TeleFly** - `state` (personal snapshot after `auth`/`bet`/`cashout`), and broadcasts
  `phase` (`BETTING`, `FLYING`, `CRASHED` with `roundNonce`, `serverSeedHash`,
  `phaseEndsAt`), `tick` (`multiplier`), `crash` (`crashMultiplier`, `serverSeed`,
  `clientSeed`, `roundNonce`), `betPlaced`, `betCashout`.
- **PvP** - `state` (personal snapshot), and broadcasts `phase` (`WAITING`, `COUNTDOWN`,
  `SPINNING`, `PAYOUT` with `roundNonce`, `serverSeedHash`, `pot`, `roster`) and `spin`
  (`winningNumber`, `winnerUsername`, `pot`, `payout`, `serverSeed`, `clientSeed`,
  `roundNonce`, `roster`).
- **Lottery** - one `lottery` snapshot type, pushed on every change.
- **Jackpot feed** - `{"type": "jackpot", "identity": "lottery", "currency", "startAt",
  "endAt", "prizePool", "status", ...}` for showing a live jackpot without a session.

`fairness` is `{serverSeedHash, clientSeed, tonSeed, nonce, serverSeed}`; `serverSeed` is
present only once the round is over. See [Provably Fair](Provably-Fair).

Field-level references with examples: `docs/operator-integration.md` (Mines) and
`docs/lottery-integration.md` (Lottery) in the repository, plus each game's
`src/game/<game>/README.md`.

## Webhook (engine to operator)

All calls are `POST {webhookUrl}<path>` with JSON and the header `X-Secret-Key`.

| Path | Request | Expected response |
|---|---|---|
| `/player` | `{sessionToken}` | `{id, username, profilePic?}` |
| `/balance` | `{sessionToken, currency}` | `{balance}` |
| `/round/open` | `{sessionToken, roundId, game, currency}` | any 2xx |
| `/round/close` | `{sessionToken, roundId, game, currency}` | any 2xx |
| `/debit` | `{sessionToken, roundId, game, currency, amount, transactionId}` | 2xx, or non-2xx to decline |
| `/credit` | `{sessionToken, roundId, game, currency, amount, transactionId}` | 2xx |

Details and semantics: [Operator Integration](Operator-Integration).
