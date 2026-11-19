package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
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

/** Opt-in loopback server. All game access happens in tick(), on the render thread. */
public final class PlayerApi implements AutoCloseable {

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

	private void handle(HttpExchange exchange) throws IOException {
		try {
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),
					("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
				reply(exchange, 401, error("Bearer token required"));
				return;
			}
			// A local API is not intended to be callable by arbitrary web pages.
			if (exchange.getRequestHeaders().containsKey("Origin")) {
				reply(exchange, 403, error("Browser origins are not supported"));
				return;
			}
			String path = exchange.getRequestURI().getPath();
			boolean action = path.equals("/action") && exchange.getRequestMethod().equals("POST");
			if (!action && !(path.equals("/state") && exchange.getRequestMethod().equals("GET"))) {
				reply(exchange, 404, error("Use GET /state or POST /action"));
				return;
			}
			JsonValue command = null;
			if (action) {
				byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
				if (body.length > MAX_BODY) {
					reply(exchange, 413, error("Request too large"));
					return;
				}
				try {
					command = new JsonReader().parse(new String(body, StandardCharsets.UTF_8));
					if (!command.isObject()) throw new IllegalArgumentException();
				} catch (Exception e) {
					reply(exchange, 400, error("Expected a JSON object"));
					return;
				}
			}
			Request request = new Request(command);
			requests.add(request);
			try {
				Response response = request.result.get(30, TimeUnit.SECONDS);
				reply(exchange, response.status, response.body);
			} catch (TimeoutException e) {
				request.result.cancel(false);
				reply(exchange, 504, error("Timed out waiting for a decision point; an action may already have executed. Read /state before continuing; do not blindly retry."));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				reply(exchange, 503, error("Server interrupted"));
			} catch (ExecutionException e) {
				reply(exchange, 500, error("API request failed"));
			}
		} finally {
			exchange.close();
		}
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
			if (request.command.getLong("observation_id", -1) != revision || revision == 0) {
				request.result.complete(new Response(409, error("Stale observation_id. Read /state first.")));
				return;
			}
			movement.reset();
			if (request.command.getString("type", "").equals("cell") && Dungeon.hero != null) {
				movement.begin(Dungeon.depth, Dungeon.branch, Dungeon.hero.pos);
			}
			dispatch(request.command);
			// Invalidate old handles immediately, even if the action opens a menu.
			revision++;
			pending = request;
			dispatchedFrame = frame;
		} catch (IllegalArgumentException e) {
			movement.reset();
			request.result.complete(new Response(400, error(e.getMessage())));
		} catch (Exception e) {
			movement.reset();
			Game.reportException(e);
			request.result.complete(new Response(500, error("Action failed; read /state before continuing")));
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
			JsonValue state = observation.capture(++revision);
			if (request.command != null && Dungeon.hero != null) {
				JsonValue trace = movement.finish(Dungeon.depth, Dungeon.branch, Dungeon.hero.pos);
				if (trace != null) state.addChild("movement_trace", trace);
			}
			request.result.complete(new Response(200, state));
		} catch (Exception e) {
			movement.reset();
			Game.reportException(e);
			request.result.complete(new Response(500, error("Observation failed")));
		}
	}

	private void dispatch(JsonValue command) {
		String type = command.getString("type", "");
		Gizmo root = inputRoot();
		if (type.equals("text")) {
			if (!(root instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput)) {
				throw new IllegalArgumentException("No visible text-entry dialog");
			}
			String text = command.getString("text", "");
			if (text.length() > 200) throw new IllegalArgumentException("Text entry is too long");
			((com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput)root).apiSetText(text);
			return;
		}
		if (type.equals("button")) {
			Button button = observation.buttons.get(command.getString("id", ""));
			if (button == null || !ApiUI.buttons(root).contains(button)) {
				throw new IllegalArgumentException("Button is no longer available; read /state");
			}
			ApiUI.click(button);
			return;
		}
		if (type.equals("back")) {
			if (root instanceof Window) ((Window) root).onBackPressed();
			else if (root instanceof InventoryPane) ((InventoryPane) root).apiCancelSelection();
			else if (Game.scene() instanceof GameScene) GameScene.cancel();
			else throw new IllegalArgumentException("No window to dismiss");
			return;
		}
		if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null || !Dungeon.hero.isAlive()) {
			throw new IllegalArgumentException("Not in an active game; use visible UI buttons");
		}
		if (root instanceof Window || GameScene.interfaceBlockingHero()) {
			throw new IllegalArgumentException("A menu requires input; use its buttons or back");
		}
		if (GameScene.apiTargetPrompt() != null && !type.equals("cell")) {
			throw new IllegalArgumentException("Choose a target with cell, or cancel with back");
		}
		switch (type) {
			case "talent": {
				String name = command.getString("name", "");
				Talent selected = null;
				for (int tier = 0; tier < Dungeon.hero.talents.size(); tier++) {
					for (Talent talent : Dungeon.hero.talents.get(tier).keySet()) {
						if (talent.title().equals(name) && Dungeon.hero.talentPointsAvailable(tier+1) > 0
								&& Dungeon.hero.pointsInTalent(talent) < talent.maxPoints()) selected = talent;
					}
				}
				if (selected == null) throw new IllegalArgumentException("Talent is not available");
				Dungeon.hero.upgradeTalent(selected);
				break;
			}
			case "cell": {
				int cell = command.getInt("cell", -1);
				if (cell < 0 || cell >= Dungeon.level.length()) throw new IllegalArgumentException("Invalid cell");
				GameScene.handleCell(cell);
				break;
			}
			case "wait": Dungeon.hero.rest(false); break;
			case "search": Dungeon.hero.search(true); break;
			case "inspect": {
				int cell = command.getInt("cell", -1);
				if (cell < 0 || cell >= Dungeon.level.length() || !Dungeon.level.heroFOV[cell]) {
					throw new IllegalArgumentException("Inspect requires a currently visible cell");
				}
				GameScene.examineCell(cell);
				break;
			}
			case "item": {
				Item item = observation.items.get(command.getString("id", ""));
				boolean held = false;
				for (Item candidate : Dungeon.hero.belongings) if (candidate == item) held = true;
				String action = command.getString("action", "");
				if (!held || item == null || !item.actions(Dungeon.hero).contains(action)) {
					throw new IllegalArgumentException("Item/action unavailable; read /state");
				}
				item.execute(Dungeon.hero, action);
				break;
			}
			default: throw new IllegalArgumentException("Unknown action type: " + type);
		}
	}

	private static JsonValue error(String message) {
		JsonValue result = object();
		put(result, "error", message);
		return result;
	}

	private static void reply(HttpExchange exchange, int status, JsonValue body) throws IOException {
		byte[] bytes = body.toJson(JsonWriter.OutputType.json).getBytes(StandardCharsets.UTF_8);
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
		final JsonValue command;
		final CompletableFuture<Response> result = new CompletableFuture<>();
		Request(JsonValue command) { this.command = command; }
	}
	private static final class Response {
		final int status;
		final JsonValue body;
		Response(int status, JsonValue body) { this.status = status; this.body = body; }
	}
}
