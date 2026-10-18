# provably-fair-games

Self-hosted provably fair casino games - Mines, Crash, Plinko, Lottery and more -
verifiable on the TON blockchain.

The project is a complete game provider: a Kotlin/Ktor server (the **engine**) that runs
six games for any number of casino operators, plus one browser client per game. An
operator integrates it like any third-party provider - it registers player sessions over
REST and implements one webhook that the engine calls for player info and money
movement. The engine never holds balances.

Every outcome is computed from a server seed committed before play, a public TON
masterchain block hash and, for single-player games, a seed chosen by the player. The
server seed is revealed afterwards so anyone can recompute the result.

## Games

| Game | Identity | Socket | Type |
|---|---|---|---|
| Mines | `mines` | `/ws/mines` | single player, 5x5 grid, cash out any time |
| Lucky Cups | `luckycup` | `/ws/shell` | single player, pick one of three cups |
| TeleFly | `telefly` | `/ws/telefly` | shared crash round, always on |
| Plinko | `plinko` | `/ws/plinko` | single player, instant drop |
| PvP wheel | `pvp` | `/ws/pvp` | shared jackpot wheel, winner takes the pot |
| Lottery 6/49 | `lottery` | `/ws/lottery`, REST | shared draw every 2 hours across all operators |

Details: [Games](Games).

## Where to go next

- [Getting Started](Getting-Started) - run everything locally in a few minutes.
- [Operator Integration](Operator-Integration) - connect a casino backend.
- [Provably Fair](Provably-Fair) - how a player verifies a round.
- [Configuration](Configuration) - every environment variable.
- [Architecture](Architecture), [API](API), [Web Clients](Web-Clients),
  [Deployment](Deployment), [Development](Development).

## Documentation rule

Every change that affects behaviour, configuration, API, build or deployment must update
`README.md` and the relevant pages in `docs/wiki/` in the same commit / pull request.
This wiki is generated from `docs/wiki/` in the repository.

Used in production at [1638.cloud](https://1638.cloud).
