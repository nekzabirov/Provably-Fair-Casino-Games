# Web Clients

Each game has its own single-page client in `catalog/<directory>`. They are independent
npm projects (Vue 3, TypeScript, Vite) with their own `package.json` and lockfile.

| Directory | Game | Rendering | Engine endpoint | Dev port | Compose port |
|---|---|---|---|---|---|
| `catalog/mines` | Mines | Pixi.js | `/ws/mines` | 5173 | 8082 |
| `catalog/lucky` | Lucky Cups | Three.js | `/ws/shell` | 5174 | 8083 |
| `catalog/telefly` | TeleFly | Pixi.js | `/ws/telefly` | 5175 | 8084 |
| `catalog/plinko` | Plinko | Pixi.js | `/ws/plinko` | 5176 | 8085 |
| `catalog/pvp` | PvP wheel | Pixi.js | `/ws/pvp` (+ `/api/v1/avatar`) | 5177 | 8086 |
| `catalog/lottery` | Lottery 6/49 | DOM/CSS | `/api/v1/lottery/*` (REST) | 5174 | 8087 |

`lucky` and `lottery` both default to dev port 5174; run them one at a time or pass
`--port`.

## Typical layout

```
catalog/<game>/
  index.html           splash screen painted before the bundle loads
  vite.config.ts       dev server port and proxy of /ws/<game> and /api/v1 to localhost:8080
  public/              static assets (fonts, images, sounds)
  src/
    main.ts, App.vue   entry; App checks the currency and shows the game or an error
    config.ts          launch parameters, engine URL, deposit request to the host page
    net/               WebSocket (or REST) client and protocol types
    composables/       game state (useMines, useShell, ...)
    components/        Vue UI: HUD, controls, overlays, provably fair modal, help
    game/              rendering scene (Pixi.js / Three.js) where the game has one
    fairness.ts        in-browser verifier (Web Crypto) mirroring the engine math
    sound.ts           Web Audio effects
    styles/tokens.css  design tokens
```

## Launch parameters

The operator opens a client with query parameters:

| Parameter | Required | Meaning |
|---|---|---|
| `sessionToken` | yes | token registered with `POST /api/v1/session` |
| `operator` | yes | brand identity |
| `currency` | no | defaults to `TON`; the game clients other than the lottery show an "unsupported currency" screen for any other value |
| `depositUrl` | no | passed back to the parent window in the deposit request |

When a bet cannot be covered, the client calls
`window.parent.postMessage({type: "openDeposit", currency, url: depositUrl}, "*")` so a
host page embedding it in an iframe can open its own deposit flow. Opened standalone, this
does nothing.

## Engine URL

Clients talk to the engine on their own origin: `ws(s)://<page host>/ws/<game>` and
`/api/v1/...`. Something must route those paths to the engine:

- `npm run dev` - the Vite dev server proxies them to `localhost:8080`.
- Docker (`catalog/<game>/Dockerfile`) - nginx serves the bundle and proxies `/ws/` and
  `/api/` to `ENGINE_URL` (default `http://engine:8080`).
- Your own reverse proxy in production.

Build-time overrides: `VITE_WS_URL` (socket games), `VITE_API_BASE` (lottery),
`VITE_AVATAR_PROXY_URL` (pvp). See [Configuration](Configuration#web-clients-build-time).

## Build

```bash
cd catalog/mines
npm ci
npm run dev          # http://localhost:5173/?sessionToken=...&operator=...
npm run type-check   # vue-tsc
npm run build        # static bundle in dist/
```

## Music

Mines and TeleFly can loop a background track. The track is configured in
`src/music.ts` (`AMBIENT_URL`); this repository ships none, so it is `null` and the games
play only their synthesized effects. To add music, place a file you are licensed to use in
`public/music/` and set `AMBIENT_URL` to its path.

## Adding a game

Engine (`src/`):

1. Create `src/game/<name>/` with `model/` (an Exposed table + entity for per-round state,
   plus the state/frames sent to clients), `service/` (pure outcome math from the
   provably fair hash; a round engine if the game is shared), `command/` (one handler per
   WebSocket command, using `PlayerService`, `RoundService` and `ProvablyFair`), and
   `network/` (socket route, connection registry, command bus - copy an existing game).
2. Give the game an `Identity` constant (its `game` value on webhooks) and add it to
   `GameCatalog`.
3. Register the handlers and registry in `AppModule.kt`; add the socket route and the
   table to `SchemaUtils.create` in `Application.kt` (start the loop there for a shared
   game).
4. Settle money only through `RoundService` (`open`, `debit`, `credit`, `close`,
   `compensateDebit`) so the ledger and webhook guarantees hold.

Client (`catalog/<name>/`):

5. Copy the closest existing client, set the socket path in `src/config.ts` and
   `vite.config.ts`, pick a free dev port, implement `fairness.ts` to mirror the new math.
6. Add a `Dockerfile` and `nginx.conf.template` like the other clients, a service in
   `docker-compose.yml`, and the directory to the matrix in `.github/workflows/ci.yml`.

Docs: add the game to `README.md`, [Games](Games), [Provably Fair](Provably-Fair),
[API](API) and this page.
