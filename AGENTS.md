# AGENTS.md

Instructions for AI coding agents working in this repository.

> **Documentation rule.** Every change that affects behaviour, configuration, API, build or deployment MUST
> update `README.md` and the relevant pages in `docs/wiki/` in the same commit / pull request. A change is not
> done until the docs match the code.

## Project overview

A self-hosted, provably fair casino game provider. A Kotlin/Ktor server (the "engine")
runs six games (Mines, Lucky Cups, TeleFly, Plinko, PvP jackpot wheel, Lottery 6/49) for
any number of operators ("brands"). Operators register player sessions over REST and
receive one webhook for player info and money movement; players' browsers talk to the
engine over WebSockets (and, for the lottery client, REST). The engine never holds
balances. Every outcome is derived from a committed server seed, a public TON block hash
and, for single-player games, the player's own seed.

- `src/` - engine sources (Gradle source root, package-less entry point `ApplicationKt`)
  - `api/` operator REST API, lottery REST API, avatar proxy, game catalog
  - `adapter/` operator webhook client, Redis cache, TON block-hash fetcher
  - `service/` shared services: players/sessions, rounds and money, provably fair
  - `model/` shared Exposed entities (brand, player, session, round, transaction)
  - `game/<name>/` one package per game: `command/`, `model/`, `network/`, `service/`
  - `util/` value objects (`Amount`, `Currency`, `Identity`), `Env`, helpers
- `resources/logback.xml` - JSON logging config
- `catalog/<game>/` - one Vue 3 + Vite web client per game (Pixi.js or Three.js)
- `example/operator/` - standalone reference operator (separate Gradle project)
- `docs/` - integration guides; `docs/wiki/` is the source of the GitHub Wiki

## Build and test

```bash
./gradlew build installDist                      # engine (JDK 21); there is no test source set yet
./gradlew -p example/operator build installDist  # example operator
(cd catalog/<game> && npm ci && npm run build)   # each web client (Node 22)
(cd catalog/<game> && npx vue-tsc --noEmit)      # type-check a client
docker compose up --build                        # whole stack
```

The engine needs PostgreSQL and Redis; configuration is read from environment variables
in `src/util/Env.kt` (documented in README.md).

## Code conventions

- No interfaces for services and adapters: concrete classes, wired in `src/AppModule.kt`
  with Koin.
- Persistence is Exposed DAO: one `Table` object and its `Entity` class in the same file,
  `LongIdTable`/`LongEntity`, relations through `reference`/`referencedOn`, value-object
  columns through `.transform(...)`. Schema is created by `SchemaUtils.create` at startup;
  idempotent `ALTER ... IF NOT EXISTS` statements in `Application.kt` migrate existing
  databases.
- Money is `Amount` (Long minor units, TON in nanoton); never `Double` or `BigDecimal`
  in transport or storage. Time is `kotlinx.datetime.Instant` (UTC).
- DB work runs inside `runTransactionCatching { }` and returns `Result<T>`.
- Debits and credits use the PENDING-transaction pattern in `RoundService`: the
  transaction row commits before the operator webhook, so retries replay the same id.
  Never move a debit/credit call back inside a game's DB transaction.
- Game math (`game/<name>/service/*` pure objects such as `MinesMultiplier`,
  `CrashPoint`) has no IO, clock or DB access; it maps a provably fair hash to an outcome.
- Domain errors extend the sealed `GameException`; WebSocket handlers turn them into
  `{ "type": "error", "error": "..." }` frames.
- Internal ids never leave the engine, except `roundId`/`transactionId` on webhooks.
- Configuration is environment variables with defaults in `Env.kt`; add new settings
  there and document them in README.md and `docs/wiki/Configuration.md`.
- Keep changes small and focused; do not add speculative abstractions.
