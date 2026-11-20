package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.shatteredpixel.shatteredpixeldungeon.desktop.api.ApiModels.*;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ApiUI;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventoryPane;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.shatteredpixel.shatteredpixeldungeon.desktop.api.PlayerObservation.*;
import static com.shatteredpixel.shatteredpixeldungeon.desktop.api.ApiModels.ErrorCode.*;

/** Opt-in loopback server. All game access happens in tick(), on the render thread. */
@Path("/")
@Produces("application/json")
@OpenAPIDefinition(info = @Info(title = "Shattered Pixel Dungeon player API", version = "1",
		description = "Experimental, opt-in loopback API for the normal graphical game. "
				+ "All game access runs at settled decision points. Only player-visible information is exported. "
				+ "Optional null fields are omitted. Browser origins are rejected; this reference is read-only."))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
		@ApiResponse(responseCode = "200", description = "A fresh observation at a decision point.",
				content = @Content(schema = @Schema(implementation = Observation.class))),
		@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: bearer token missing or incorrect.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "BROWSER_ORIGIN: Origin header is not supported.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "NOT_FOUND: unsupported path or HTTP method.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "500", description = "INTERNAL_ERROR: read /state before continuing.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "503", description = "INTERRUPTED: server interrupted while waiting.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "504", description = "DECISION_TIMEOUT: no decision point within 30 seconds. "
				+ "An action may already have executed. Read /state; never blindly retry.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public final class PlayerApi implements AutoCloseable {

	public static final String STATE_PATH = "/state";
	public static final String ACTION_PATH = "/action";
	private static final int MAX_BODY = 8192;
	private final HttpServer server;
	private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "Player API HTTP");
		thread.setDaemon(true);
		return thread;
	});
	private final String token;
	private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
	private final PlayerObservation observation = new PlayerObservation();
	private final MovementTrace movement = new MovementTrace();
	private Request pending;
	private long revision;
	private long frame;
	private long dispatchedFrame;

	public PlayerApi(int port, String configuredToken) throws IOException {
		token = configuredToken == null || configuredToken.isEmpty()
				? UUID.randomUUID().toString() : configuredToken;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
		server.setExecutor(workers);
		server.createContext("/", this::handle);
		ApiUI.mappedCellObserver = observation::rememberMappedCell;
		ApiUI.heroMoveObserver = movement::moved;
		server.start();
		System.out.println("Player API: http://127.0.0.1:" + server.getAddress().getPort());
		System.out.println("Player API bearer token: " + token);
	}

	int port() {
		return server.getAddress().getPort();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try {
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),
					("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
				reply(exchange, 401, error(UNAUTHORIZED, "Bearer token required"));
				return;
			}
			// A local API is not intended to be callable by arbitrary web pages.
			if (exchange.getRequestHeaders().containsKey("Origin")) {
				reply(exchange, 403, error(BROWSER_ORIGIN, "Browser origins are not supported"));
				return;
			}
			String path = exchange.getRequestURI().getPath();
			boolean action = path.equals(ACTION_PATH) && exchange.getRequestMethod().equals("POST");
			if (!action && !(path.equals(STATE_PATH) && exchange.getRequestMethod().equals("GET"))) {
				reply(exchange, 404, error(NOT_FOUND, "Use GET /state or POST /action"));
				return;
			}
			Action command = null;
			if (action) {
				byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
				if (body.length > MAX_BODY) {
					reply(exchange, 413, error(REQUEST_TOO_LARGE, "Request too large"));
					return;
				}
				try {
					command = ApiJson.readAction(body);
				} catch (IOException | IllegalArgumentException e) {
					reply(exchange, 400, error(INVALID_REQUEST, "Invalid action JSON: check type, observation_id, and required fields"));
					return;
				}
			}
			CompletableFuture<Response> result = action ? action(command) : state();
			try {
				Response response = result.get(30, TimeUnit.SECONDS);
				reply(exchange, response.status, response.body);
			} catch (TimeoutException e) {
				result.cancel(false);
				reply(exchange, 504, new ErrorResponse(DECISION_TIMEOUT,
						"Timed out waiting for a decision point; read /state before continuing; do not blindly retry.", action));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				reply(exchange, 503, new ErrorResponse(INTERRUPTED, "Server interrupted", action));
			} catch (ExecutionException e) {
				reply(exchange, 500, new ErrorResponse(INTERNAL_ERROR, "API request failed", action));
			}
		} finally {
			exchange.close();
		}
	}

	// JAX-RS annotations are build-time metadata only; HttpServer calls these methods directly.
	@GET
	@Path(STATE_PATH)
	@Operation(operationId = "state", summary = "Observe the current player decision point",
			description = "Waits up to 30 seconds. Every capture invalidates previous observation IDs and handles. "
					+ "Human input is not locked out; obtain a fresh observation after manual play.")
	public CompletableFuture<Response> state() {
		return enqueue(null);
	}

	@POST
	@Path(ACTION_PATH)
	@Consumes("application/json")
	@Operation(operationId = "step", summary = "Perform one input and observe the result",
			description = "Uses the latest observation_id. Serial controllers only. A cell click may start multi-step "
					+ "pathfinding. Modal UI must be handled using its buttons, text, or back. "
					+ "On timeout or an uncertain failure, read /state instead of repeating the action.")
	@ApiResponses({
			@ApiResponse(responseCode = "400", description = "INVALID_REQUEST or INVALID_ACTION: malformed or unavailable input.",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "STALE_OBSERVATION: read /state for new handles.",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "413", description = "REQUEST_TOO_LARGE: body exceeds 8192 bytes.",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public CompletableFuture<Response> action(
			@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
					content = @Content(schema = @Schema(implementation = Action.class))) Action command) {
		if (command == null) throw new IllegalArgumentException("Action required");
		return enqueue(command);
	}

	private CompletableFuture<Response> enqueue(Action command) {
		Request request = new Request(command);
		requests.add(request);
		return request.result;
	}

	/** Call after each normal game frame; never from an HTTP thread. */
	public void tick() {
		frame++;
		if (!settled()) return;
		if (pending != null) {
			// Let normal updates/actor scheduling run before observing an action's result.
			if (frame - dispatchedFrame < 3) return;
			Request done = pending;
			pending = null;
			capture(done);
			return;
		}
		Request request = requests.poll();
		if (request == null || request.result.isDone()) return;
		if (request.command == null) {
			capture(request);
			return;
		}
		try {
			if (request.command.observation_id != revision || revision == 0) {
				request.result.complete(new Response(409, error(STALE_OBSERVATION, "Stale observation_id. Read /state first.")));
				return;
			}
			movement.reset();
			if (request.command instanceof CellAction && Dungeon.hero != null) {
				movement.begin(Dungeon.depth, Dungeon.branch, Dungeon.hero.pos);
			}
			dispatch(request.command);
			// Invalidate old handles immediately, even if the action opens a menu.
			revision++;
			pending = request;
			dispatchedFrame = frame;
		} catch (InvalidActionException e) {
			movement.reset();
			request.result.complete(new Response(400, error(INVALID_ACTION, e.getMessage())));
		} catch (Exception e) {
			movement.reset();
			Game.reportException(e);
			request.result.complete(new Response(500, new ErrorResponse(INTERNAL_ERROR,
					"Action failed; read /state before continuing", true)));
		}
	}

	private boolean settled() {
		if (Game.scene() == null || Game.switchingScene() || Game.scene() instanceof InterlevelScene) return false;
		if (!(Game.scene() instanceof GameScene)) return true;
		return Dungeon.hero != null && (GameScene.apiActorIdle() || !Dungeon.hero.isAlive())
				&& (inputRoot() instanceof Window || Dungeon.hero.ready || !Dungeon.hero.isAlive());
	}

	private void capture(Request request) {
		try {
			Observation state = observation.capture(++revision);
			if (request.command != null && Dungeon.hero != null) {
				state.movement_trace = movement.finish(Dungeon.depth, Dungeon.branch, Dungeon.hero.pos);
			}
			request.result.complete(new Response(200, state));
		} catch (Exception e) {
			movement.reset();
			Game.reportException(e);
			request.result.complete(new Response(500, new ErrorResponse(INTERNAL_ERROR,
					"Observation failed; read /state before continuing", request.command != null)));
		}
	}

	private void dispatch(Action command) {
		Gizmo root = inputRoot();
		if (command instanceof TextAction) {
			if (!(root instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput)) {
				throw new InvalidActionException("No visible text-entry dialog");
			}
			String text = ((TextAction) command).text;
			((com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput)root).apiSetText(text);
			return;
		}
		if (command instanceof ButtonAction) {
			Button button = observation.buttons.get(((ButtonAction) command).id);
			if (button == null || !ApiUI.buttons(root).contains(button)) {
				throw new InvalidActionException("Button is no longer available; read /state");
			}
			ApiUI.click(button);
			return;
		}
		if (command instanceof BackAction) {
			if (root instanceof Window) ((Window) root).onBackPressed();
			else if (root instanceof InventoryPane) ((InventoryPane) root).apiCancelSelection();
			else if (Game.scene() instanceof GameScene) GameScene.cancel();
			else throw new InvalidActionException("No window to dismiss");
			return;
		}
		if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null || !Dungeon.hero.isAlive()) {
			throw new InvalidActionException("Not in an active game; use visible UI buttons");
		}
		if (root instanceof Window || GameScene.interfaceBlockingHero()) {
			throw new InvalidActionException("A menu requires input; use its buttons or back");
		}
		if (GameScene.apiTargetPrompt() != null && !(command instanceof CellAction)) {
			throw new InvalidActionException("Choose a target with cell, or cancel with back");
		}
		if (command instanceof TalentAction) {
			String name = ((TalentAction) command).name;
			Talent selected = null;
			for (int tier = 0; tier < Dungeon.hero.talents.size(); tier++) {
				for (Talent talent : Dungeon.hero.talents.get(tier).keySet()) {
					if (talent.title().equals(name) && Dungeon.hero.talentPointsAvailable(tier+1) > 0
							&& Dungeon.hero.pointsInTalent(talent) < talent.maxPoints()) selected = talent;
				}
			}
			if (selected == null) throw new InvalidActionException("Talent is not available");
			Dungeon.hero.upgradeTalent(selected);
		} else if (command instanceof CellAction) {
			int cell = ((CellAction) command).cell;
			if (cell < 0 || cell >= Dungeon.level.length()) throw new InvalidActionException("Invalid cell");
			GameScene.handleCell(cell);
		} else if (command instanceof WaitAction) {
			Dungeon.hero.rest(false);
		} else if (command instanceof SearchAction) {
			Dungeon.hero.search(true);
		} else if (command instanceof InspectAction) {
			int cell = ((InspectAction) command).cell;
			if (cell < 0 || cell >= Dungeon.level.length() || !Dungeon.level.heroFOV[cell]) {
				throw new InvalidActionException("Inspect requires a currently visible cell");
			}
			GameScene.examineCell(cell);
		} else if (command instanceof ItemAction) {
			Item item = observation.items.get(((ItemAction) command).id);
			boolean held = false;
			for (Item candidate : Dungeon.hero.belongings) if (candidate == item) held = true;
			String action = ((ItemAction) command).action;
			if (!held || item == null || !item.actions(Dungeon.hero).contains(action)) {
				throw new InvalidActionException("Item/action unavailable; read /state");
			}
			item.execute(Dungeon.hero, action);
		} else {
			throw new InvalidActionException("Unknown action type");
		}
	}

	private static ErrorResponse error(ErrorCode code, String message) {
		return new ErrorResponse(code, message, false);
	}

	/** Only our pre-dispatch checks can certify that an action was rejected before execution. */
	private static final class InvalidActionException extends IllegalArgumentException {
		InvalidActionException(String message) { super(message); }
	}

	private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = ApiJson.MAPPER.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	@Override public void close() {
		ApiUI.mappedCellObserver = null;
		ApiUI.heroMoveObserver = null;
		server.stop(0);
		workers.shutdownNow();
	}

	private static final class Request {
		final Action command;
		final CompletableFuture<Response> result = new CompletableFuture<>();
		Request(Action command) { this.command = command; }
	}
	public static final class Response {
		final int status;
		final Object body;
		Response(int status, Object body) { this.status = status; this.body = body; }
	}
}
