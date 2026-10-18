# Development

## Prerequisites

- JDK 21 (the Gradle build uses a Java 21 toolchain)
- Node.js 22 and npm (web clients)
- PostgreSQL and Redis, for example `docker compose up postgres redis`
- Docker with Compose (optional, whole stack)

## Build, run, check

```bash
# engine
./gradlew build installDist      # compile, run the test task, build build/install/ton-game
./gradlew run                    # start on :8080 (needs PostgreSQL + Redis)

# example operator (separate Gradle project with its own wrapper)
./gradlew -p example/operator build installDist
./gradlew -p example/operator run

# web clients
cd catalog/<game>
npm ci
npm run dev                      # Vite dev server, proxies /ws and /api to :8080
npm run type-check               # vue-tsc --noEmit
npm run build                    # dist/
```

There is no automated test suite yet (`./gradlew build` runs an empty test task). New
tests are welcome, especially for the pure game math in `src/game/*/service/`.

CI (`.github/workflows/ci.yml`) runs the engine and example-operator builds, type-checks
and builds every client, and validates `docker-compose.yml`.

## A local end-to-end loop

1. `docker compose up postgres redis`
2. `./gradlew run` (engine) and `./gradlew -p example/operator run`
3. Register the demo brand once (see [Getting Started](Getting-Started#option-2---run-on-the-host)).
4. `curl -X POST http://localhost:9090/session/42` and open a client's dev server with the
   returned token, e.g. `http://localhost:5173/?sessionToken=<token>&operator=demo-operator`.

## Code map

| Path | Contents |
|---|---|
| `src/Application.kt` | server bootstrap, database connection and schema, routes, loop startup |
| `src/AppModule.kt` | Koin bindings |
| `src/api/` | REST endpoints and DTOs |
| `src/adapter/` | `BrandAdapter` (webhook client), `CacheAdapter` (Redis), `TonAdapter` |
| `src/service/` | `PlayerService`, `RoundService`, `ProvablyFair`, `BrandService` |
| `src/model/` | shared Exposed tables/entities |
| `src/game/<game>/` | `command/`, `model/`, `network/`, `service/` per game |
| `src/util/` | `Env`, value objects, `runTransactionCatching`, hashing helpers |
| `resources/logback.xml` | logging |

## Conventions

The full list is in `AGENTS.md`. In short:

- Concrete classes wired by Koin; no interfaces for services or adapters.
- Exposed DAO: table object and entity class in one file, `Long` ids, relations by
  reference.
- Money as `Amount` (Long minor units), time as `kotlinx.datetime.Instant`.
- Database work inside `runTransactionCatching { }`; operator debits and credits only via
  `RoundService`, never inside a game's own DB transaction.
- Game math is pure and deterministic; if you change it, update the client's
  `fairness.ts`, [Games](Games) and [Provably Fair](Provably-Fair).
- New settings go into `src/util/Env.kt` and [Configuration](Configuration).

## Documentation rule

Every change that affects behaviour, configuration, API, build or deployment must update
`README.md` and the relevant pages in `docs/wiki/` in the same commit / pull request. A
change is not done until the docs match the code. See `CONTRIBUTING.md`.
