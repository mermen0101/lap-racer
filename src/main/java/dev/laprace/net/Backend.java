package dev.laprace.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.laprace.LapConfig;
import dev.laprace.LapRaceClient;
import dev.laprace.RaceSession;
import dev.laprace.common.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The link to the Lap Race backend (docs/server-plan.md): signs in with the Minecraft account (Mojang, as joining an
 * online-mode server), keeps a WebSocket open and streams the boat's position 10 times a second. Everything off the
 * game thread; if the backend is down, retries quietly (5 s, doubling, at most a minute).
 */
public final class Backend {
	private static final Gson GSON = new Gson();
	/** Made anew on each connection: through the proxy of the settings, if any. */
	private static volatile HttpClient HTTP = Proxies.client(null);

	public enum State {
		OFF, CONNECTING, ONLINE, FAILED
	}

	private static volatile State state = State.OFF;
	private static volatile WebSocket socket;
	private static volatile String name;
	/** The sign-in token and the server's address (for its HTTP calls: the livery upload). */
	private static volatile String token, base;
	private static volatile boolean admin;
	private static volatile boolean killACar;
	/** Everyone else's position from our server (asked for when Ice Racer is installed), as last received. */
	public record Other(String uuid, String name, double x, double y, double z, double yaw, double v, String track, boolean boat, long ts, long arrived) {
	}

	private static volatile java.util.List<Other> others = java.util.List.of();
	private static volatile boolean othersWanted;
	// kill-a-car: whom we jam and how hard (the server's word)
	private static volatile String jamTarget, jamName;
	private static volatile double jamLevel;
	private static long retryAt;
	private static long backoff = 5_000;
	private static int tick;
	private static String connectedUrl;
	private static boolean toldFailure;

	private Backend() {
	}

	public static State state() {
		return state;
	}

	public static boolean admin() {
		return admin && state == State.ONLINE;
	}

	/** The kill-a-cars' names, lower case (the server's word, when connected). */
	private static volatile java.util.Set<String> killACars = java.util.Set.of();

	/** This player name drives a kill-a-car (its own car: the red safety car). */
	public static boolean killACar(String player) {
		return player != null && state == State.ONLINE && killACars.contains(player.toLowerCase(java.util.Locale.ROOT));
	}

	/** This player is a kill-a-car (the server says so). */
	public static boolean killACar() {
		return killACar && state == State.ONLINE;
	}

	/** Everyone else on our server, as last received (empty if nobody asked or nobody is online). */
	public static java.util.List<Other> others() {
		return state == State.ONLINE ? others : java.util.List.of();
	}

	/** Someone (Ice Racer) wants everyone's position: asked for on the next connection if not already. */
	public static void wantOthers() {
		if (!othersWanted) {
			othersWanted = true;
			WebSocket ws = socket;
			if (ws != null && state == State.ONLINE) {
				send(ws, GSON.toJson(Map.of("t", "hello", "v", Protocol.VERSION, "mod", LapRaceClient.version(), "others", true)));
			}
		}
	}

	/** A request about the server's race (create, join, leave, start, cancel). */
	public static void sendRace(JsonObject m) {
		WebSocket ws = socket;
		if (ws != null && state == State.ONLINE) {
			send(ws, GSON.toJson(m));
		} else {
			dev.laprace.ServerRace.onError("offline");
		}
	}

	/** Kill-a-car: jam this player's radio (uuid), or stop (null). */
	public static void jam(String uuid) {
		WebSocket ws = socket;
		jamTarget = uuid;
		jamName = null;
		jamLevel = 0;
		if (ws != null && state == State.ONLINE) {
			JsonObject m = new JsonObject();
			m.addProperty("t", "jam");
			m.addProperty("target", uuid);
			send(ws, GSON.toJson(m));
		}
	}

	/** Our "OK" to our pit crew; false if we are not online. */
	public static boolean sendOk() {
		WebSocket ws = socket;
		if (ws == null || state != State.ONLINE) {
			return false;
		}
		send(ws, GSON.toJson(Map.of("t", "ok")));
		return true;
	}

	/** Our livery picture to the server, with its name: its hash there and its status ("pending"). */
	public static String[] uploadLiveryFull(byte[] png, String title) throws Exception {
		String t = token, b = base;
		if (t == null || b == null || state != State.ONLINE) {
			throw new IllegalStateException(net.minecraft.client.resources.language.I18n.get("laprace.livery.offline"));
		}
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(b + "/api/livery/upload"
				+ (title == null || title.isBlank() ? "" : "?title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)))).timeout(Duration.ofSeconds(30))
				.header("X-Token", t).header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png)).build(),
				HttpResponse.BodyHandlers.ofString());
		JsonObject o = GSON.fromJson(r.body(), JsonObject.class);
		if (r.statusCode() != 200) {
			throw new IllegalStateException(o != null && o.has("error") ? o.get("error").getAsString() : "HTTP " + r.statusCode());
		}
		return new String[] {o.get("hash").getAsString(), o.get("status").getAsString()};
	}

	/** A signed-in GET (our token with it). */
	public static byte[] fetchSigned(String path) throws Exception {
		String t = token, b = base;
		if (t == null || b == null) {
			throw new IllegalStateException("offline");
		}
		HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(b + path)).timeout(Duration.ofSeconds(20)).header("X-Token", t).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		if (r.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + r.statusCode());
		}
		return r.body();
	}

	/** Our livery chosen anew: the server tells everyone. */
	public static void sendLivery() {
		WebSocket ws = socket;
		if (ws != null && state == State.ONLINE) {
			send(ws, GSON.toJson(Map.of("t", "livery", "id", dev.laprace.car.Liveries.choiceId())));
		}
	}

	/** Our own livery picture to the server (signed in); its status there: "pending" or "ok". */
	public static String uploadLivery(byte[] png, String title) throws Exception {
		String t = token, b = base;
		if (t == null || b == null || state != State.ONLINE) {
			throw new IllegalStateException(net.minecraft.client.resources.language.I18n.get("laprace.livery.offline"));
		}
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(b + "/api/livery/upload" + (title == null || title.isBlank() ? "" : "?title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)))).timeout(Duration.ofSeconds(30))
				.header("X-Token", t).header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png)).build(),
				HttpResponse.BodyHandlers.ofString());
		JsonObject o = GSON.fromJson(r.body(), JsonObject.class);
		if (r.statusCode() != 200) {
			throw new IllegalStateException(o != null && o.has("error") ? o.get("error").getAsString() : "HTTP " + r.statusCode());
		}
		return o.get("status").getAsString();
	}

	/** Our own livery's name changed (the server keeps it with the picture). */
	public static void renameLivery(String title) {
		String t = token, b = base;
		if (t == null || b == null || state != State.ONLINE) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				HTTP.send(HttpRequest.newBuilder(URI.create(b + "/api/livery/rename")).timeout(Duration.ofSeconds(15)).header("X-Token", t)
						.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(Map.of("title", title)))).build(),
						HttpResponse.BodyHandlers.discarding());
			} catch (Exception e) {
				LapRaceClient.LOG.debug("livery rename: {}", e.toString());
			}
		});
	}

	/** A one-off code to sign in on the site with (10 minutes). */
	public static String siteCode() throws Exception {
		String t = token, b = base;
		if (t == null || b == null || state != State.ONLINE) {
			throw new IllegalStateException(net.minecraft.client.resources.language.I18n.get("laprace.livery.offline"));
		}
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(b + "/api/site/code")).timeout(Duration.ofSeconds(15))
				.header("X-Token", t).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		JsonObject o = GSON.fromJson(r.body(), JsonObject.class);
		if (r.statusCode() != 200) {
			throw new IllegalStateException(o != null && o.has("error") ? o.get("error").getAsString() : "HTTP " + r.statusCode());
		}
		return o.get("code").getAsString();
	}

	/** A file from the server (a livery picture). */
	public static byte[] fetch(String path) throws Exception {
		String b = base;
		if (b == null) {
			throw new IllegalStateException("offline");
		}
		HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(b + path)).timeout(Duration.ofSeconds(20)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		if (r.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + r.statusCode());
		}
		return r.body();
	}

	/** Our name as the server knows it (signed in). */
	public static String name() {
		return name;
	}

	public static String jamTarget() {
		return jamTarget;
	}

	public static String jamName() {
		return jamName;
	}

	public static double jamLevel() {
		return jamTarget == null ? 0 : jamLevel;
	}

	/** A message from our server. */
	private static void onMessage(String text) {
		try {
			JsonObject m = GSON.fromJson(text, JsonObject.class);
			String t = m.has("t") ? m.get("t").getAsString() : "";
			switch (t) {
				case "others" -> {
					long now = System.currentTimeMillis();
					java.util.List<Other> l = new java.util.ArrayList<>();
					for (var e : m.getAsJsonArray("players")) {
						JsonObject p = e.getAsJsonObject();
						l.add(new Other(p.get("uuid").getAsString(), p.get("name").getAsString(), p.get("x").getAsDouble(),
								p.get("y").getAsDouble(), p.get("z").getAsDouble(), p.get("yaw").getAsDouble(), p.get("v").getAsDouble(),
								p.has("track") && !p.get("track").isJsonNull() ? p.get("track").getAsString() : null,
								p.has("boat") && p.get("boat").getAsBoolean(), p.has("ts") ? p.get("ts").getAsLong() : now, now));
					}
					others = java.util.List.copyOf(l);
				}
				case "cars" -> {
					java.util.Set<String> s = new java.util.HashSet<>();
					for (var e : m.getAsJsonArray("killACar")) {
						s.add(e.getAsString().toLowerCase(java.util.Locale.ROOT));
					}
					killACars = java.util.Set.copyOf(s);
				}
				case "liveries" -> dev.laprace.car.Liveries.all(m.getAsJsonObject("map"));
				case "crew-ok" -> CrewOk.received(m.get("from").getAsString());
				case "ok-result" -> CrewOk.result(m.get("ok").getAsBoolean(), m.has("crew") ? m.get("crew").getAsInt() : 0,
						m.has("why") && !m.get("why").isJsonNull() ? m.get("why").getAsString() : null);
				case "livery" -> dev.laprace.car.Liveries.one(m.get("name").getAsString(), m.get("id").getAsString());
				case "race" -> dev.laprace.ServerRace.onMessage(m);
				case "race-error" -> dev.laprace.ServerRace.onError(m.get("error").getAsString());
				case "jam" -> Jam.set(m.get("level").getAsDouble()); // our radio, jammed by a kill-a-car
				case "jamming" -> {
					String u = m.has("uuid") && !m.get("uuid").isJsonNull() ? m.get("uuid").getAsString() : null;
					if (u != null && u.equals(jamTarget)) {
						jamName = m.has("name") && !m.get("name").isJsonNull() ? m.get("name").getAsString() : null;
						jamLevel = m.get("level").getAsDouble();
					}
				}
				default -> {
				}
			}
		} catch (RuntimeException e) {
			LapRaceClient.LOG.debug("Lap Race server: bad message {}", e.toString());
		}
	}

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		LapConfig c = LapConfig.INSTANCE;
		boolean want = c.online && mc.player != null && mc.level != null;
		if (!want || (connectedUrl != null && !connectedUrl.equals(linkKey(c)))) {
			if (state != State.OFF) {
				close();
			}
			if (!want) {
				return;
			}
		}
		if (state == State.OFF || state == State.FAILED) {
			if (System.currentTimeMillis() >= retryAt) {
				connect(mc, c.serverUrl);
			}
			return;
		}
		if (state == State.ONLINE && ++tick % (20 / Protocol.RATE_HZ) == 0) {
			sendPos(mc);
		}
	}

	private static void sendPos(Minecraft mc) {
		WebSocket ws = socket;
		if (ws == null || mc.player == null) {
			return;
		}
		Entity boat = RaceSession.boatOf(mc.player);
		Entity e = boat != null ? boat : mc.player;
		if (!RaceSession.INSTANCE.inZone(e.getX(), e.getZ())) {
			return; // away from the tracks: where the player is stays here
		}
		var v = e.getDeltaMovement();
		JsonObject m = new JsonObject();
		m.addProperty("t", "pos");
		m.addProperty("ts", System.currentTimeMillis());
		m.addProperty("x", round(e.getX()));
		m.addProperty("y", round(e.getY()));
		m.addProperty("z", round(e.getZ()));
		m.addProperty("yaw", round(e.getYRot()));
		m.addProperty("v", round(Math.hypot(v.x, v.z) * 20));
		m.addProperty("track", RaceSession.INSTANCE.track == null ? null : RaceSession.INSTANCE.track.name);
		m.addProperty("boat", boat != null);
		if (boat != null) {
			m.addProperty("thr", round(dev.laprace.Engine.throttle));
			m.addProperty("brk", round(dev.laprace.Engine.brake));
			m.addProperty("gear", dev.laprace.Engine.gear);
		}
		send(ws, GSON.toJson(m));
	}

	/** A lap just completed by this player (the backend checks it against the positions it was sent). */
	public static void lap(String track, double seconds, double[] sectors, boolean pit, boolean race, long startMs) {
		WebSocket ws = socket;
		if (ws == null || state != State.ONLINE) {
			return;
		}
		JsonObject m = new JsonObject();
		m.addProperty("t", "lap");
		m.addProperty("ts", System.currentTimeMillis());
		m.addProperty("track", track);
		m.addProperty("time", Math.round(seconds * 1000));
		com.google.gson.JsonArray s = new com.google.gson.JsonArray();
		for (double x : sectors) {
			s.add(Math.round(x * 1000));
		}
		m.add("sectors", s);
		m.addProperty("pit", pit);
		m.addProperty("race", race);
		if (startMs > 0) {
			m.addProperty("start", startMs); // when our clock for it started: the server tells a lap from the lights from a short one
		}
		m.addProperty("quali", dev.laprace.ServerRace.qualiOn(track));
		send(ws, GSON.toJson(m));
	}

	private static double round(double d) {
		return Math.round(d * 100) / 100.0;
	}

	/** One message at a time on the socket (the JDK WebSocket refuses overlapping sends). */
	private static CompletableFuture<WebSocket> last = CompletableFuture.completedFuture(null);

	private static synchronized void send(WebSocket ws, String text) {
		last = last.handle((x, err) -> null).thenCompose(x -> ws.sendText(text, true));
	}

	/** The server and the way to it: a change in either reconnects. */
	private static String linkKey(LapConfig c) {
		return c.serverUrl + "|" + c.proxy + (c.proxy == LapConfig.ProxyMode.MANUAL ? "|" + c.proxyAddress : "");
	}

	private static void connect(Minecraft mc, String url) {
		state = State.CONNECTING;
		connectedUrl = linkKey(LapConfig.INSTANCE);
		User user = mc.getUser();
		String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		CompletableFuture.runAsync(() -> {
			try {
				HTTP = Proxies.client(Proxies.forServer(LapConfig.INSTANCE, base));
				// 1. a one-off server id to join
				JsonObject ch = get(base + "/api/auth/challenge?name=" + URLEncoder.encode(user.getName(), StandardCharsets.UTF_8));
				String serverId = ch.get("serverId").getAsString();
				// 2. join it with the Mojang session (a dev backend does not check)
				if (!ch.get("dev").getAsBoolean()) {
					mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
				}
				// 3. the backend checks with Mojang, hands out a token
				JsonObject ok = post(base + "/api/auth/verify", GSON.toJson(Map.of("name", user.getName(), "serverId", serverId)));
				String token = ok.get("token").getAsString();
				Backend.token = token;
				Backend.base = base;
				name = ok.get("name").getAsString();
				admin = ok.get("admin").getAsBoolean();
				killACar = ok.has("killACar") && ok.get("killACar").getAsBoolean();
				// 4. the live link
				String wsUrl = base.replaceFirst("^http", "ws") + "/ws/client?token=" + token;
				socket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
						.buildAsync(URI.create(wsUrl), new Listener()).join();
				// Ice Racer's chase wants everyone's position (the whole track, not only the loaded chunks)
				othersWanted |= net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iceracer");
				send(socket, GSON.toJson(Map.of("t", "hello", "v", Protocol.VERSION, "mod", LapRaceClient.version(), "others", othersWanted)));
				if (jamTarget != null) {
					jam(jamTarget); // still jamming after a reconnect
				}
				send(socket, GSON.toJson(Map.of("t", "livery", "id", dev.laprace.car.Liveries.choiceId())));
				state = State.ONLINE;
				backoff = 5_000;
				toldFailure = false;
				LapRaceClient.LOG.info("Lap Race server: signed in as {}{}", name, admin ? " (admin)" : "");
				mc.execute(() -> dev.laprace.Notify.show(mc, Component.translatable(admin ? "laprace.msg.online_admin" : "laprace.msg.online", name)));
			} catch (Exception e) {
				fail(mc, e);
			}
		});
	}

	private static void fail(Minecraft mc, Throwable e) {
		Throwable cause = e.getCause() != null ? e.getCause() : e;
		LapRaceClient.LOG.warn("Lap Race server unreachable: {}", cause.toString());
		state = State.FAILED;
		socket = null;
		retryAt = System.currentTimeMillis() + backoff;
		backoff = Math.min(60_000, backoff * 2);
		if (!toldFailure) {
			toldFailure = true;
			mc.execute(() -> dev.laprace.Notify.show(mc, Component.translatable("laprace.msg.online_failed")));
		}
	}

	private static void close() {
		WebSocket ws = socket;
		socket = null;
		state = State.OFF;
		Jam.set(0); // no server, no jam
		others = java.util.List.of();
		connectedUrl = null;
		retryAt = 0;
		backoff = 5_000;
		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
		}
	}

	private static JsonObject get(String url) throws Exception {
		return parse(HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
				HttpResponse.BodyHandlers.ofString()));
	}

	private static JsonObject post(String url, String body) throws Exception {
		return parse(HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString()));
	}

	private static JsonObject parse(HttpResponse<String> r) {
		if (r.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + r.statusCode() + " " + r.body());
		}
		return GSON.fromJson(r.body(), JsonObject.class);
	}

	private static final class Listener implements WebSocket.Listener {
		private final StringBuilder part = new StringBuilder();

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			part.append(data);
			if (last) {
				String text = part.toString();
				part.setLength(0);
				onMessage(text);
			}
			ws.request(1);
			return null;
		}
		@Override
		public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			drop(ws, "closed " + code + " " + reason);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			drop(ws, error.toString());
		}

		private void drop(WebSocket ws, String why) {
			if (socket == ws) {
				LapRaceClient.LOG.warn("Lap Race server link lost: {}", why);
				socket = null;
				state = State.FAILED;
				retryAt = System.currentTimeMillis() + backoff;
				Jam.set(0);
				others = java.util.List.of();
			}
		}
	}
}
