# Contributing

Thanks for your interest in improving provably-fair-games.

> **Documentation rule.** Every change that affects behaviour, configuration, API, build or deployment MUST
> update `README.md` and the relevant pages in `docs/wiki/` in the same commit / pull request. A change is not
> done until the docs match the code.

## Prerequisites

- JDK 21
- Node.js 22 (for the web clients)
- Docker with Compose (optional, to run the whole stack)

## Build and test

```bash
# engine
./gradlew build installDist

# example operator
./gradlew -p example/operator build installDist

# each web client
for g in lottery lucky mines plinko pvp telefly; do
  (cd catalog/$g && npm ci && npm run build)
done
```

The engine has no automated test suite yet; tests for new code are welcome. To try a
change end to end, run `docker compose up --build` and follow the Quick start in
README.md.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change and follow the conventions in
   [AGENTS.md](AGENTS.md) (they apply to humans too).
3. Make sure every build above passes; CI runs the same commands.
4. Update `README.md` and the relevant `docs/wiki/` pages (see the documentation rule).
5. Describe what changed and why, and fill in the pull request checklist.

Changes to game math (`src/game/*/service`) change payouts and the provably fair
verification: explain the effect on RTP and keep the in-app verifiers in
`catalog/<game>` consistent with the engine.

By contributing you agree that your contributions are licensed under the Apache
License 2.0.
