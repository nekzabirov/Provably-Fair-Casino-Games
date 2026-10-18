# Games

Six games, all played in TON (amounts in nanoton). Every outcome comes from an
HMAC-SHA256 hash defined in [Provably Fair](Provably-Fair); this page describes the
rules and the math that turns a hash into a result. RTP figures are the theoretical
return to player derived from that math.

| Game | Identity | Socket | Kind | RTP |
|---|---|---|---|---|
| [Mines](#mines) | `mines` | `/ws/mines` | single player | 97% |
| [Lucky Cups](#lucky-cups) | `luckycup` | `/ws/shell` | single player | 66.7% |
| [TeleFly](#telefly-crash) | `telefly` | `/ws/telefly` | shared, always on | 96% |
| [Plinko](#plinko) | `plinko` | `/ws/plinko` | single player, instant | 96.5% - 97.2% |
| [PvP wheel](#pvp-jackpot-wheel) | `pvp` | `/ws/pvp` | shared, always on | 100% (no house edge) |
| [Lottery 6/49](#lottery-649) | `lottery` | `/ws/lottery`, REST | shared draw, all operators | pool-based, 10% commission on won pools |

Payouts are `floor(stake * multiplier)` in minor units.

## Mines

**Rules.** A 5x5 grid hides `mineCount` mines (3..24) among 25 cells. The player stakes,
then reveals cells one by one. Each diamond raises the multiplier; a mine ends the round
and the stake is lost. The player may cash out at any time after the start.

**Math** (`MinesMultiplier`, `MinesRng`):

```
multiplier(mines, opened) = 0.97 * C(25, opened) / C(25 - mines, opened)     (1.0 when opened = 0)
```

This is the fair multiplier for surviving `opened` reveals, times 0.97, so the RTP is
97% whenever the player stops. Examples: 5 mines, 1 diamond = x1.2125; 24 mines,
1 diamond = x24.25; 3 mines, all 22 diamonds = x2231.

Mine positions: a Fisher-Yates shuffle of the cells `0..24` driven by the hash (see
[Provably Fair](Provably-Fair#mines)); the first `mineCount` cells of the shuffled list
are mines. Positions are never stored, only recomputed from the seeds.

## Lucky Cups

**Rules.** Three cups, one ball. The player stakes (`start`), watches a shuffle, and picks
a cup (`pick`, 0..2). Picking the cup that hides the ball pays x2; otherwise the stake is
lost.

**Math** (`ShellResult`):

```
winningCup = (first 8 hex chars of hash, as an unsigned 32-bit integer) mod 3
```

Win probability is 1/3 and the payout is x2, so the theoretical RTP is 2/3 (66.7%).
The ball shown during the opening shuffle is cosmetic; the outcome is fixed by the hash
when the round starts.

## TeleFly (crash)

**Rules.** One shared round for all players, forever:

1. `BETTING` (10 s, `TELEFLY_BETTING_MS`) - each player may place one bet.
2. `FLYING` - the multiplier grows as `exp(0.07 * seconds)` (`TELEFLY_GROWTH_RATE`) and
   is broadcast every 100 ms. A player may `cashout` at the current multiplier.
3. `CRASHED` - at the crash point every bet not cashed out loses; the server seed is
   revealed; after 4 s (`TELEFLY_CRASH_PAUSE_MS`) betting opens again.

**Math** (`CrashPoint`):

```
x     = (first 8 hex chars of hash) / 2^32          in [0, 1)
crash = max(1.00, 0.96 / (1 - x))
```

`P(crash >= t) = 0.96 / t` for every target `t >= 1`, so cashing out at any fixed target
returns 96%. About 4% of rounds crash at x1.00 (when `x < 0.04`); that clamp is the house
edge, and there is no separate instant-bust rule.

## Plinko

**Rules.** The player picks a stake, the number of peg rows (8..16) and a risk level
(`LOW`, `MEDIUM`, `HIGH`), then drops a ball. Each drop is settled immediately; drops can
be sent back to back.

**Math** (`PlinkoPath`, `PlinkoMultiplier`): for row `i` (0-based) the ball goes right
when hex digit `i` of the hash is odd, left when it is even. The landing slot is the
number of right bounces (`0..rows`), which happens with probability
`C(rows, slot) / 2^rows`. The slot's multiplier comes from a symmetric paytable per
`(risk, rows)`. RTP and top multiplier of every table:

| Rows | LOW | MEDIUM | HIGH |
|---|---|---|---|
| 8 | 97.05% (max x5.5) | 96.68% (max x12.7) | 96.56% (max x28) |
| 9 | 96.74% (max x5.5) | 96.92% (max x17.6) | 96.94% (max x42) |
| 10 | 96.97% (max x8.7) | 97.00% (max x22) | 96.52% (max x74) |
| 11 | 97.08% (max x8.2) | 96.96% (max x24) | 96.75% (max x117) |
| 12 | 97.01% (max x9.8) | 96.98% (max x32) | 97.02% (max x166) |
| 13 | 97.10% (max x7.9) | 96.73% (max x42) | 96.80% (max x255) |
| 14 | 97.01% (max x7) | 97.03% (max x57) | 97.09% (max x412) |
| 15 | 97.19% (max x14.7) | 96.86% (max x86) | 96.55% (max x607) |
| 16 | 97.11% (max x15.7) | 96.70% (max x108) | 96.97% (max x980) |

The full tables are in `src/game/plinko/service/PlinkoMultiplier.kt` and mirrored in the
client for display.

## PvP jackpot wheel

**Rules.** One shared round at a time:

1. `WAITING` - players `bet` into a pot; each stake becomes an arc of the wheel
   proportional to its share. A player may top up or `cancel` (full refund).
2. `COUNTDOWN` - starts when at least 2 players (`PVP_MIN_PLAYERS`) have a stake; lasts
   10 s, bets, top-ups and cancels stay open. If players drop below the minimum it
   returns to `WAITING`.
3. `SPINNING` - bets are locked, the winner is already decided, the wheel spins (6 s).
4. `PAYOUT` - the winner receives the whole pot; after 4 s a new round opens.

**Math** (`WheelResult`):

```
x       = (first 8 hex chars of hash) / 2^32          in [0, 1)
number  = x * 100                                      the number shown on the wheel
ticket  = min(floor(x * pot), pot - 1)
winner  = the player whose cumulative stake interval [start, end) contains ticket,
          players taken in join order (the roster order)
```

A player's chance to win equals `stake / pot` and the winner gets the entire pot, so
there is no house edge.

## Lottery 6/49

**Rules.** One draw every 2 hours (`LOTTERY_ROUND_MS`, closing on UTC boundaries), shared
by every operator on the engine.

- During `SELLING` a player may buy one system ticket per draw
  (`LOTTERY_MAX_TICKETS_PER_PLAYER`) with 6 to 36 distinct numbers from 1..49.
- A ticket with `n` numbers costs `10 TON * (n - 5)^2` (6 numbers = 10 TON, 12 numbers =
  490 TON, 36 numbers = 9610 TON) and its price goes into the pool.
- At the boundary sales close (`DRAWING`); six numbers are drawn and revealed one every
  10 s. A ticket wins when all six drawn numbers are among its picks.
- `SETTLING` pays the winners; `SETTLED` shows the result for 10 s, then the next draw
  opens.

**Pool.** `gross = seed + ticket sales`. With at least one winner, the net pool
`gross - gross * 10%` is split equally among all winning tickets, and the next draw starts
again from the guaranteed 1000 TON seed. With no winner nothing is paid and no commission
is taken; the whole gross pool becomes the next draw's seed. Odds for a 6-number ticket
are 1 in C(49, 6) = 13 983 816; an `n`-number ticket covers C(n, 6) combinations.

Winners are paid through their own operator's `/credit`, so a winning player's operator
may pay out more than its players contributed; settling that between operators is outside
the engine. Details: `docs/lottery-integration.md` in the repository.

**Math** (`LotteryDraw`): a partial Fisher-Yates shuffle of `1..49` driven by the hash
draws six distinct numbers (see [Provably Fair](Provably-Fair#lottery)).
