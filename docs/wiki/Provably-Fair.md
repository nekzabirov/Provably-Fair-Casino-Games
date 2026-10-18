# Provably Fair

Every result in every game is a deterministic function of values that are either
committed in advance or public. After a round the engine reveals its secret, and anyone
can recompute the outcome with the code on this page or the verifier built into each web
client (shield icon, "Provably Fair").

## Inputs

| Input | Who controls it | How it is produced |
|---|---|---|
| `serverSeed` | engine | 32 bytes from `SecureRandom`, hex encoded (64 characters). |
| `serverSeedHash` | - | `SHA-256(serverSeed)` (hex, over the UTF-8 bytes of the hex string). Published as a commitment before the outcome is used; the seed itself is revealed afterwards. |
| `clientSeed` | player | Sent in the `auth` frame (Mines, Lucky Cups, Plinko), 1-64 characters of `[A-Za-z0-9-_]`; the clients generate one and keep it in `localStorage`, and the player can change it. When empty the engine generates 16 random bytes. |
| `tonSeed` | nobody | The root hash of the latest TON masterchain block, from `GET {TON_API_URL}/getMasterchainInfo` (`result.last.root_hash`, used verbatim as returned). Cached for `TON_BLOCK_CACHE_SECONDS` (5 s). |
| `nonce` | engine | A per-round number, written in decimal: the round id, the debit transaction id (Plinko), a round counter (TeleFly, PvP) or the draw id (Lottery). |

## Outcome hash

All hashes are HMAC-SHA256, lowercase hex. The key is the UTF-8 bytes of the
`serverSeed` **string** (it is not hex-decoded) and the message is a UTF-8 string.

| Games | Formula |
|---|---|
| Mines, Lucky Cups, Plinko (single player) | `HMAC-SHA256(key = serverSeed, message = clientSeed + ":" + tonSeed + ":" + nonce)` |
| TeleFly, PvP, Lottery (shared) | `HMAC-SHA256(key = serverSeed, message = tonSeed + nonce)` (plain concatenation) |

Shared games cannot use a player's seed because one outcome serves every player; the
TON block hash takes that role.

## From hash to result

Two primitives are used:

- **`u32(hash)`** - the first 8 hex characters as an unsigned 32-bit integer;
  `x = u32(hash) / 2^32` is in `[0, 1)`.
- **Integer stream** - successive 8-hex-character slices of the hash, each parsed as an
  unsigned 32-bit integer and reduced `mod bound`. When fewer than 8 characters remain,
  `sha256(pool)` (hex of the SHA-256 of the whole current hex string) is appended to the
  pool and reading continues.

### Mines

Fisher-Yates over the cells `[0, 1, ..., 24]`: for `i` from 24 down to 0, take
`j = next_int(i + 1)` and swap cells `i` and `j`. The first `mineCount` cells of the
shuffled list are the mines. Nonce = round id.

### Lucky Cups

`winningCup = u32(hash) mod 3`. Nonce = round id.

### Plinko

For row `i` (0-based), hex digit `i` of the hash is read; odd = right, even = left. The
slot is the number of right bounces; the multiplier comes from the `(risk, rows)` table.
Nonce = the id of the drop's debit transaction.

### TeleFly

`crash = max(1.0, 0.96 / (1 - x))`. Nonce = the round counter (`roundNonce`).

### PvP wheel

`number = x * 100`; `ticket = min(floor(x * pot), pot - 1)`; the winner is the player whose
cumulative stake interval `[start, end)` contains `ticket`, with players in join order
(the order of the revealed roster). Nonce = `roundNonce`.

### Lottery

Partial Fisher-Yates over `[1, 2, ..., 49]`: for `k` from 0 to 5, `i = 48 - k`,
`j = next_int(i + 1)`, swap positions `i` and `j`. The last six positions, sorted, are the
winning numbers. Nonce = the draw id (`drawNonce`).

## What each game reveals, and when

| Game | Before the outcome | After the round |
|---|---|---|
| Mines, Lucky Cups | `fairness.serverSeedHash`, `clientSeed`, `tonSeed`, `nonce` in the state when the round starts | `fairness.serverSeed` in the final state |
| Plinko | - | the result frame carries `serverSeedHash`, `serverSeed`, `clientSeed`, `tonSeed`, `nonce` together |
| TeleFly | `serverSeedHash` and `roundNonce` in the `phase` frames from the start of betting | `crash` frame: `crashMultiplier`, `serverSeed`, `clientSeed` (the TON seed), `roundNonce` |
| PvP | `serverSeedHash` and `roundNonce` while bets are open | `spin` frame: `serverSeed`, `clientSeed` (the TON seed), `roundNonce`, final `roster` |
| Lottery | `serverSeedHash` for the whole draw | `serverSeed`, `tonSeed`, `drawNonce` once the draw is finished |

## How a player verifies a round

1. Note `serverSeedHash` when it is shown, and keep your own `clientSeed` (single-player
   games).
2. After the round, take the revealed `serverSeed` and check
   `SHA-256(serverSeed) == serverSeedHash`.
3. Optionally check that `tonSeed` is the root hash of a real TON masterchain block from
   around the round's start time (any TON explorer or `getMasterchainInfo`/block lookup).
4. Recompute the outcome hash with the formula for the game and the revealed `nonce`.
5. Recompute the result (mine layout, cup, path, crash point, winner, numbers) and compare
   it with what the game showed.

The web clients do steps 2, 4 and 5 in the browser with Web Crypto
(`catalog/<game>/src/fairness.ts`); the lottery client shows the revealed values.

## What the scheme guarantees

- **Mines, Lucky Cups** - the outcome is fixed and committed when the round starts and
  cannot be changed in reaction to the player's moves or cash-out.
- **TeleFly, PvP** - the crash point / winning fraction is fixed and committed before any
  bet of that round is placed, so it cannot be steered towards or away from particular
  bets.
- **Lottery** - the server seed is committed for the whole sales period and the TON block
  hash is taken only when the draw starts, so the numbers are unknown to everyone,
  including the engine, until the draw.
- **Plinko** - the server seed is generated and revealed in the same frame, without an
  earlier commitment; verification shows that the path follows from the revealed inputs,
  which include the player's own seed and the public TON hash.

In the single-player games the player's seed is known when the server seed is generated,
and the TON hash is public; the server seed itself comes from `SecureRandom`. Changing
your `clientSeed` between rounds is supported and recommended.

## Reference implementation (Python)

This code reproduces the engine exactly (it was checked against the Kotlin
implementation with the test vectors below).

```python
import hashlib, hmac

def outcome_hash(server_seed: str, message: str) -> str:
    # Key and message are the UTF-8 bytes of the strings (the seed is NOT hex-decoded).
    return hmac.new(server_seed.encode(), message.encode(), hashlib.sha256).hexdigest()

def single_player_hash(server_seed, client_seed, ton_seed, nonce):
    return outcome_hash(server_seed, f"{client_seed}:{ton_seed}:{nonce}")

def shared_hash(server_seed, ton_seed, nonce):
    return outcome_hash(server_seed, f"{ton_seed}{nonce}")

class HashInts:
    """8 hex chars per draw; the pool grows by sha256(pool) when exhausted."""
    def __init__(self, h):
        self.pool, self.cursor = h, 0
    def next_int(self, bound):
        if self.cursor + 8 > len(self.pool):
            self.pool += hashlib.sha256(self.pool.encode()).hexdigest()
        value = int(self.pool[self.cursor:self.cursor + 8], 16)
        self.cursor += 8
        return value % bound

def mine_positions(h, mine_count):
    cells, ints = list(range(25)), HashInts(h)
    for i in range(24, -1, -1):
        j = ints.next_int(i + 1)
        cells[i], cells[j] = cells[j], cells[i]
    return sorted(cells[:mine_count])

def winning_cup(h):
    return int(h[:8], 16) % 3

def plinko_path(h, rows):
    return [int(h[i], 16) & 1 for i in range(rows)]

def crash_point(h):
    x = int(h[:8], 16) / 2**32
    return max(1.0, 0.96 / (1 - x))

def wheel(h, stakes_in_join_order):
    x = int(h[:8], 16) / 2**32
    pot = sum(stakes_in_join_order)
    ticket = min(int(x * pot), pot - 1)
    cumulative = 0
    for index, stake in enumerate(stakes_in_join_order):
        cumulative += stake
        if ticket < cumulative:
            return x * 100, index

def lottery_numbers(h, count=6, rng=49):
    pool, ints = list(range(1, rng + 1)), HashInts(h)
    for k in range(count):
        i = rng - 1 - k
        j = ints.next_int(i + 1)
        pool[i], pool[j] = pool[j], pool[i]
    return sorted(pool[-count:])

def commitment(server_seed):
    return hashlib.sha256(server_seed.encode()).hexdigest()
```

Test vectors:

```python
h = single_player_hash("a1b2c3", "player-seed", "TONHASH=", 1001)
# befe043dea2e6a70571a160f0bd3197b310ce673414c5b4cb843591ed98ccef1
mine_positions(h, 5)        # [4, 6, 13, 15, 21]
winning_cup(h)              # 2
sum(plinko_path(h, 16))     # slot 5

h = shared_hash("a1b2c3", "TONHASH=", 77)
# 1338f22e9e2f48aabce46fe1f3345e2b86351fb326a604311ae0ffbd12bd3c1b
crash_point(h)              # 1.037936219853087
wheel(h, [100, 250, 650])   # (7.508767722174525, 0)
lottery_numbers(h)          # [8, 18, 30, 33, 34, 43]
```

Source of truth in the repository: `src/service/ProvablyFair.kt`, `src/util/HexExt.kt`
and the game math in `src/game/<game>/service/`.
