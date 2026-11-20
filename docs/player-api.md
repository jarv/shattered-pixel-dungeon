# Desktop player API (experimental)

The desktop game can expose a token-protected JSON API on **127.0.0.1 only**.
It runs the normal graphical game, with normal rules, animations, and saves.
There is no headless simulator or deterministic replay requirement.

## Generated API reference

The wire contract is defined by the runtime Java DTOs in
`desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/api/ApiModels.java`
and the endpoint annotations in `PlayerApi.java`. There is no hand-maintained
OpenAPI YAML file.

With a Gradle-compatible JDK (Java 21 works):

```sh
./gradlew :desktop:apiDocs --console=plain
# Open desktop/build/player-api/html/index.html in a browser.
# Or publish that directory using any static web host.
```

This generates:

- `desktop/build/player-api/openapi.yaml` and `openapi.json`: machine-readable contract.
- `desktop/build/player-api/html/index.html`: static endpoint and model reference.

Use `:desktop:generateOpenApi` to build only the specification. Neither task
starts the game, needs a display, or starts an HTTP server. Generation tooling is
build-time only and is not shipped in the game. The HTML reference is for reading;
browser calls to the live API remain deliberately unsupported.

### Version 1 contract

All observations and error responses contain `api_version: 1`. Update the Python
client and game together when changing API versions. Optional null fields are
**omitted**, so clients should use optional lookups rather than expecting JSON
null (for example `state.get("boss_health_text")`).

Action requests require a positive integer `observation_id`, a recognized `type`,
and all fields required for that variant. Unknown fields, duplicate JSON keys,
trailing JSON, and scalar coercion (for example `"cell": "123"`) are rejected.
Requests are limited to 8192 bytes.

Errors have a stable code plus a human-readable message:

```json
{
  "api_version": 1,
  "error": {
    "code": "DECISION_TIMEOUT",
    "message": "Timed out waiting for a decision point; read /state before continuing; do not blindly retry.",
    "action_may_have_executed": true
  }
}
```

The Python client's `PlayerAPIError` exposes `status`, `code`, `message`, and
`action_may_have_executed`. It never automatically retries requests and clears
cached observation handles after errors. Obtain a fresh state before deciding
what to do next.

## Launch

Use a Gradle-compatible JDK (Java 21 works; Java 27 currently does not):

```sh
export SPD_API_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export SPD_API_PORT=8765
# Recommended: absolute path to a separate save/settings directory.
export SPD_DATA_DIR=/tmp/opencode/spd-api-profile
./gradlew :desktop:debug --console=plain
```

Without `SPD_API_PORT`, the game is unchanged and no server starts. Without
`SPD_API_TOKEN`, a random token is generated and printed to the launch terminal.
Without `SPD_DATA_DIR`, the game uses your **normal profile and saves**.
Packaged desktop builds support the same environment variables.

The Python client and bot live in the separate
[shattered-bot repository](https://github.com/jarv/shattered-bot).
In a second terminal, from that checkout with the same token exported:

```sh
python3 tools/player_api.py
python3 tools/player_api.py '{"type":"wait"}'
```

Start/continue a run using the normal UI, or use the API's exposed menu buttons.
No endpoint silently starts, overwrites, or unlocks a run.

## Observe → decide → act

For a concrete controller with a paced watch mode, see
[Shattered Bot](https://github.com/jarv/shattered-bot#run-locally).

```python
from tools.player_api import PlayerAPI

api = PlayerAPI()
state = api.state()
while state.get("status") != "dead":
    action = choose_action(state)  # your program/model
    state = api.step(action)
```

- `GET /state` waits for a decision point and returns an observation.
- `POST /action` executes one input, waits for the next decision point, and returns
  the next observation. A cell click can start multi-step pathfinding, just as a
  human click does; use neighboring cells for one-step movement.
- Observations advertise `capabilities: ["movement_trace"]`. Cell-action responses
  include `movement_trace` with the starting `depth`/`branch`, `same_floor`,
  `truncated`, and `cells`: the actual hero positions recorded during that input.
  A destination click interrupted before arrival reports only the crossed prefix,
  never the requested remainder. These are own-position receipts, not a hidden
  path or intermediate creature/map observations. A fresh `/state` does not replay
  a previous action's movement receipt.
- Both require `Authorization: Bearer <token>`. Browser-origin requests are rejected.
- Every action must include the last `observation_id`. Handles (`item-N`,
  `button-N`) are valid only for that observation. Multiple controllers should not
  issue commands concurrently. A stale observation receives HTTP 409.
- HTTP 400 means an invalid/unavailable action. HTTP 504 means no decision point
  was reached within 30 seconds. The action **may already have executed**: inspect
  `/state`, never automatically repeat a timed-out action.
- Requests run on the render thread only after actor processing settles. Animation
  and enemy turns continue normally. There are no fixed client-side sleep delays.

Example direct request:

```sh
curl -H "Authorization: Bearer $SPD_API_TOKEN" http://127.0.0.1:8765/state
curl -H "Authorization: Bearer $SPD_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"observation_id":1,"type":"wait"}' http://127.0.0.1:8765/action
```

## Actions

| JSON | Meaning |
| --- | --- |
| `{"type":"cell","cell":123}` | Normal map click: walk, attack, pick up, interact, stairs, or choose a pending target |
| `{"type":"wait"}` | Wait once, not continuous resting |
| `{"type":"search"}` | Deliberate search, with its normal time cost |
| `{"type":"inspect","cell":123}` | Open the normal inspection UI for a currently visible cell |
| `{"type":"item","id":"item-2","action":"ZAP"}` | Execute an action advertised by that inventory entry |
| `{"type":"button","id":"button-3"}` | Click an advertised visible/enabled UI button |
| `{"type":"text","text":"ABC-DEF-GHI"}` | Replace text in the currently visible text-entry dialog, respecting its length limit; does not submit it |
| `{"type":"back"}` | Normal window back/cancel, or cancel targeting/pathfinding |
| `{"type":"talent","name":"backup barrier"}` | Spend an available talent point using an advertised name |

Add `observation_id` when using raw HTTP; the Python client supplies it.
Use `ui.buttons` for dialogue choices, inventory selectors, hero selection, talents,
etc. When an item opens targeting, `status` becomes `targeting`, and `target_prompt`
contains the game's prompt. Send a `cell` action next. Modal windows block map and
item actions until handled. Buttons invoke their ordinary click handlers, not cheats.
Text-entry dialogs expose their rendered editable value as `ui.text_input`.
Submit with the advertised button. This supports ordinary custom-seed entry;
there is no API action that directly sets a dungeon seed or reads seed contents.

## Observation and information boundary

This is a **conservative structured accessibility view**, not a pixel-perfect or
complete description of every graphical effect. It provides:

- Scene/status, rendered UI text and enabled buttons. The topmost window is the
  input surface while a modal is open.
- Hero HP, level, strength, gold, displayed buff descriptions, and currently exposed talent choices.
- `evaluation_stats` contains only own-run accounting: exact hero-panel
  `experience`/`experience_to_level` and cumulative `elapsed_turns` (normalized
  run duration plus currently elapsed time). No individual actor schedules or
  hidden enemy information are exported. These counters do not alter game rules;
  the adaptive evaluation records them separately from tactical scoring.
- `keys` contains player-visible journal key names, floor numbers, and quantities;
  keys are not backpack items. `branch` prevents applying main-floor keys to subfloors.
- Inventory names, descriptions, status strings, quantities, equipped status, and
  actions using the same knowledge-filtered methods as the game's inventory UI.
  `identified` reports ordinary identification state; `curse_known` says whether
  curse state has been learned, independently of level identification.
  `known_cursed` is true only
  when the curse is known to the player. False means **not known cursed**, not
  guaranteed uncursed. No raw Java item types, unknown curses, or unidentified upgrade levels.
  `upgradable` is the same eligibility used by the normal Upgrade item selector;
  artifacts are not eligible for Upgrade scrolls in this version.
- Visible `objects`: names and positions of characters and ground heaps, not exact
  HP, combat stats, AI state, or loot tables. They share one list so a disguised
  mimic isn't identified by its underlying object type. Only the top item of an
  ordinary pile or shop heap is described. Closed container contents are never exported.
  Visible actors also expose `status_icons`: names of buffs with icons shown in
  their ordinary inspection window. Hidden tracker buffs, private alignment and
  resistance values are not exported. Corruption is therefore observable without
  treating every species-shaped actor as hostile.
  Object `description` is ordinary inspection text (`mob.info()` / `heap.info()`),
  including mimic disguises. For crystal chests it reveals only the normal visible
  reward category (wand/ring/artifact), not the concealed item's identity or stats.
  Shop heaps add `for_sale:true` and `price`, using the same gold price as the
  normal trade window. Buying uses ordinary `cell` navigation/clicks followed by
  the window's enabled `Buy for …g` button and current observation ID/handle.
  There is no bypass-buy endpoint.
  Visible health bars provide `[filled_pixels, total_pixels]`, and visible sleep
  icons add `sleeping:true`; these do not expose raw monster HP or AI state.
  Visibility follows the game's `heroFOV`, including Mind Vision and awareness:
  sensed creatures' current locations are included while revealed, and disappear
  from `objects` when the effect expires unless still visible normally.
- `boss_health_text` is the text in the displayed boss bar (omitted when absent).
  `danger_cells` contains visible tiles marked by Goo's pump-up particle warning,
  not a prediction from hidden AI state.
- Visible terrain, plants and discovered traps. Secret doors look like walls and
  secret traps like floor. `terrain` uses the public IDs in `levels/Terrain.java`.
  Visible gas/cloud effects include their ordinary inspection descriptions.
  Tile names include player-visible custom landmarks such as `Distant well`.
- Previously **API-observed** tiles as remembered snapshots (`visible:false`),
  never continuously refreshed off-screen terrain. Magic Mapping and Clairvoyance
  also export revealed terrain and discovered traps as snapshots, without unseen
  creatures, items, plants, or gas effects. Repeated use refreshes only cells revealed
  by that item. Tiles have `mapped` (game mapping flag) and `observed` (seen by this
  API at least once) flags; map-only tiles remain `visible:false, observed:false`.
  The bot routes through mapped geometry but still explores map-only rooms.
  Unrevealed cells are absent. Convert a cell to
  coordinates with `x = cell % width`, `y = cell // width`.

Known limitations:

- Map memory starts when the API first observes a level and is not persisted.
  Magic-mapped tiles from loaded saves are reconstructed once; ordinary explored
  tiles from a loaded save are not reconstructed.
  Changing/reloading levels may reset that conservative memory.
- Custom graphical decorations and non-button widgets such as sliders are not
  fully represented. Visible text-entry dialogs are supported.
- Some icon-only controls have empty labels. Not every menu has been validated.
  Manual play can handle unsupported UI; then read a fresh state.
- The interface exposes player-accessible inventory/inspection text without
  requiring a separate click for each description. It does not reproduce camera
  clipping or require opening the inventory before reading it.
- Human input is not locked out. Avoid simultaneous manual/API actions; obtain a
  fresh observation after taking over manually.

The API deliberately under-reports information rather than serializing internal
game state. It is not yet a complete bot environment for every class and menu.

## Validation

```sh
./gradlew :desktop:test
# From the separate shattered-bot checkout:
# With a disposable run already active (this plays several turns):
python3 tools/smoke_player_api.py
```

The smoke check covers observations, waiting, searching, inspection/back, inventory
targeting/cancel, movement, stale observation rejection, and token authentication.

### Maintaining the contract

Add or change fields on the actual runtime DTOs, with `@Schema` descriptions and
requiredness. Update the capture/dispatch code to use those fields. New action
variants must be registered with Jackson's `@JsonSubTypes` and the matching
OpenAPI discriminator annotations. Do not add game-object references to DTOs.
Jackson serializes only the explicitly populated API models, never the game's
object graph.

Endpoint JAX-RS annotations are metadata for the build-time Swagger reader; they
do not introduce a JAX-RS server. `GenerateOpenApi` flattens Swagger's inherited
action schemas to avoid circular composition and restricts variant `type` values
using the generated discriminator mapping.

Run `:desktop:test` after changes. Tests exercise all action variants, strict
request decoding, real loopback HTTP rejections, serialized response/schema
conformance, movement receipts, and conservative information boundaries.
Behavioral prose (including decision points and what counts as visible information)
still needs deliberate maintenance; annotations cannot infer those guarantees.
