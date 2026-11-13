package dev.laprace;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The race our server runs (one at a time), as it last told us: who is in, its state. Created, joined and started from
 * {@link RaceScreen}; when it counts down and we are in it, the race session here counts down with it and times our
 * laps as race laps (the server takes them once checked).
 */
public final class ServerRace {
	public record Driver(String uuid, String name, int pos, int laps, long time, long best, boolean finished, int pits) {
	}

	public static final String ROLLING = "rolling";

	public long id;
	public String kind, track, start, host, hostName, status;
	/** laps: for a qualifying its minutes. */
	public int laps;
	public boolean quali;
	/** race / quali / weekend (a qualifying, a break, its race) / time (a time trial); a weekend's session. */
	public String format = "race";
	public boolean chained;
	/** The host's settings, as the server has them. */
	public JsonObject opts = new JsonObject();
	/** A weekend's break: when it is over (our clock, ms), 0 = none. A race by time: when its time is up. */
	public long breakEnd, timeEnd;
	/** Start lights: ms from when the message came to the first light (-1 = a countdown); when it came. */
	public long lightsIn = -1, receivedAt;
	/** A qualifying: when its time is up (our clock, ms), 0 = not running. */
	public long endAt;
	public List<Driver> drivers = List.of();

	/** The current one, or null. */
	private static volatile ServerRace current;
	/** The last error the server answered a request of ours with (a translation key's tail), and when (ms). */
	private static volatile String error;
	private static volatile long errorAt;
	/** The race whose countdown our session runs (id), -1 = none. */
	private static long runningHere = -1;

	private ServerRace() {
	}

	public static ServerRace current() {
		return current;
	}

	public static String error() {
		return System.currentTimeMillis() - errorAt < 6000 ? error : null;
	}

	public int opt(String k, int d) {
		try {
			return opts.has(k) ? opts.get(k).getAsInt() : d;
		} catch (Exception e) {
			return d;
		}
	}

	public boolean optOn(String k) {
		try {
			return opts.has(k) && opts.get(k).getAsBoolean();
		} catch (Exception e) {
			return false;
		}
	}

	/** The server's event we are in burns fuel (its rule, whatever our own setting). */
	public static boolean fuelRule() {
		ServerRace r = current;
		return r != null && r.optOn("fuel") && !"open".equals(r.status) && r.mine();
	}

	/** The server's race we are in forbids the racing line (its creator's choice). */
	public static boolean lineForbidden() {
		ServerRace r = current;
		try {
			return r != null && r.mine() && !"open".equals(r.status) && r.opts.has("line") && !r.opts.get("line").getAsBoolean();
		} catch (Exception e) {
			return false;
		}
	}

	/** The event's fuel rate while we race in it (0: none set, ours then). */
	public static double fuelRateRule() {
		return fuelRule() ? current.opt("fuelRate10", 10) / 10.0 : 0;
	}

	/** A race by time (minutes, then a lap). */
	public boolean byTime() {
		return !quali && optOn("byTime");
	}

	public boolean tour() {
		return "tour".equals(kind);
	}

	public boolean in(String name) {
		return name != null && drivers.stream().anyMatch(d -> d.name.equalsIgnoreCase(name));
	}

	public boolean mine() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && in(mc.player.getGameProfile().name());
	}

	public boolean hosting() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && hostName != null && hostName.equalsIgnoreCase(mc.player.getGameProfile().name());
	}

	/** {"t":"race","race":{...}|null} from the server (network thread): taken over on the game's thread. */
	public static void onMessage(JsonObject m) {
		ServerRace r = null;
		long goIn = 0;
		if (m.has("race") && !m.get("race").isJsonNull()) {
			JsonObject o = m.getAsJsonObject("race");
			r = new ServerRace();
			r.id = o.get("id").getAsLong();
			r.kind = o.get("kind").getAsString();
			r.track = o.get("track").getAsString();
			r.laps = o.get("laps").getAsInt();
			r.start = o.get("start").getAsString();
			r.host = o.get("host").getAsString();
			r.hostName = o.get("hostName").getAsString();
			r.status = o.get("status").getAsString();
			r.quali = o.has("quali") && o.get("quali").getAsBoolean();
			r.format = o.has("format") ? o.get("format").getAsString() : r.quali ? "quali" : "race";
			r.chained = o.has("chained") && o.get("chained").getAsBoolean();
			if (o.has("opts") && o.get("opts").isJsonObject()) {
				r.opts = o.getAsJsonObject("opts");
			}
			long nowMs = System.currentTimeMillis();
			r.breakEnd = o.has("breakIn") ? nowMs + o.get("breakIn").getAsLong() : 0;
			r.timeEnd = o.has("timeIn") ? nowMs + o.get("timeIn").getAsLong() : 0;
			r.lightsIn = o.has("lightsIn") ? o.get("lightsIn").getAsLong() : -1;
			r.receivedAt = nowMs;
			r.endAt = o.has("endIn") ? System.currentTimeMillis() + o.get("endIn").getAsLong() : 0;
			goIn = o.has("goIn") ? o.get("goIn").getAsLong() : 0;
			List<Driver> ds = new ArrayList<>();
			for (var e : o.getAsJsonArray("drivers")) {
				JsonObject d = e.getAsJsonObject();
				ds.add(new Driver(d.get("uuid").getAsString(), d.get("name").getAsString(), d.get("pos").getAsInt(), d.get("laps").getAsInt(),
						d.get("time").getAsLong(), d.get("best").getAsLong(), d.get("finished").getAsBoolean(), d.has("pits") ? d.get("pits").getAsInt() : 0));
			}
			r.drivers = List.copyOf(ds);
		}
		ServerRace nr = r;
		long gi = goIn;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> taken(mc, nr, gi));
	}

	public static void onError(String e) {
		error = e;
		errorAt = System.currentTimeMillis();
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> Notify.show(mc, Component.translatable("laprace.race.err." + e)));
	}

	private static void taken(Minecraft mc, ServerRace r, long goIn) {
		ServerRace was = current;
		current = "done".equals(r == null ? null : r.status) || "cancelled".equals(r == null ? null : r.status) ? null : r;
		RaceSession s = RaceSession.INSTANCE;
		if (r == null) {
			dev.laprace.cruise.RaceStart.release(mc);
			if (runningHere >= 0 && s.serverRace) {
				s.endServerRace();
			}
			runningHere = -1;
			return;
		}
		boolean mine = r.mine();
		if (r.quali) {
			qualiTaken(mc, r, mine);
		}
		boolean live = !r.quali && ("grid".equals(r.status) || "formation".equals(r.status) || "countdown".equals(r.status) || "running".equals(r.status));
		if (live && mine) {
			List<String> names = r.drivers.stream().map(d -> d.name.toLowerCase(Locale.ROOT)).toList();
			java.util.Set<String> set = java.util.Set.copyOf(names);
			boolean fresh = runningHere != r.id;
			s.raceEndWall = r.timeEnd;
			if (fresh) {
				runningHere = r.id;
				if (r.optOn("fuel")) {
					Engine.fuel = Engine.MAX_FUEL * r.opt("fuelStart", 100) / 100.0; // the tank at the start, as the host set it
				}
				if (!s.prepareServerRace(mc, r.track, r.laps, names)) {
					return;
				}
				if ("grid".equals(r.status)) {
					String me = mc.player.getGameProfile().name();
					int index = 0;
					for (int i = 0; i < r.drivers.size(); i++) {
						if (r.drivers.get(i).name.equalsIgnoreCase(me)) {
							index = i;
						}
					}
					dev.laprace.cruise.RaceStart.grid(mc, index, set);
				} else if ("formation".equals(r.status)) {
					dev.laprace.cruise.RaceStart.rolling(mc, set, r.opt("rollKmh", 80));
				}
			}
			if ("countdown".equals(r.status) && r.lightsIn >= 0) {
				long late = System.currentTimeMillis() - r.receivedAt;
				s.serverLights(r.lightsIn - late, goIn - late);
			} else if ("countdown".equals(r.status)) {
				s.serverCountdown(goIn);
			} else if ("running".equals(r.status) && ROLLING.equals(r.start)) {
				s.serverGo(mc); // the first car over the line started it
			} else if ("running".equals(r.status) && fresh) {
				s.serverCountdown(goIn); // joined late in our session: the lights went out goIn ago (or now)
			}
		}
		if ("cancelled".equals(r.status)) {
			dev.laprace.cruise.RaceStart.release(mc);
			if (runningHere == r.id) {
				s.endServerRace();
				runningHere = -1;
			}
			if (mine || r.hosting()) {
				Notify.show(mc, Component.translatable("laprace.race.cancelled"));
			}
		}
		if ("break".equals(r.status) && mine && breakSaid != r.id) {
			breakSaid = r.id;
			Notify.show(mc, Component.translatable("laprace.race.break", clockOf(Math.max(0, r.breakEnd - System.currentTimeMillis()) / 1000)));
		}
		if ("done".equals(r.status)) {
			if (runningHere == r.id && s.serverRace && (s.self == null || !s.self.finished)) {
				s.endServerRace(); // (a race by time: its end is the server's)
			}
			runningHere = -1;
			s.serverRace = false;
			if (mine && r.quali) {
				StringBuilder sb = new StringBuilder();
				for (Driver d : r.drivers) {
					sb.append('\n').append(d.best > 0 ? "P" + d.pos : "—").append("  ").append(d.name).append("  ")
							.append(d.best > 0 ? Fmt.time(d.best / 1000.0) : "");
				}
				Notify.chat(mc, Component.translatable("laprace.quali.results").append(sb.toString()));
			} else if (mine) {
				StringBuilder sb = new StringBuilder();
				for (Driver d : r.drivers) {
					sb.append('\n').append(d.finished ? "P" + d.pos : "DNF").append("  ").append(d.name).append("  ")
							.append(d.finished ? Fmt.time(d.time / 1000.0) : d.laps + " " + Component.translatable("laprace.race.laps_short").getString())
							.append(d.best > 0 ? "  (best " + Fmt.time(d.best / 1000.0) + ")" : "");
				}
				Notify.chat(mc, Component.translatable("laprace.race.results").append(sb.toString()));
			}
		}
		if (was == null && r != null && "open".equals(r.status) && !r.hosting()) {
			// a new race to join: said once, with the key to open the race screen
			Notify.chat(mc, Component.translatable(r.quali ? (r.tour() ? "laprace.quali.new_tour" : "laprace.quali.new") : r.tour() ? "laprace.race.new_tour" : "laprace.race.new",
					r.hostName, Track.displayName(r.track), r.laps));
		}
	}

	private static long breakSaid = -1;

	public static String clockOf(long sec) {
		return sec / 60 + ":" + String.format(Locale.ROOT, "%02d", sec % 60);
	}

	// ------------------------------------------------------------------ qualifying

	/** The qualifying whose start / last minute / flag we have said (id). */
	private static long qualiSaid = -1, qualiMinuteSaid = -1, qualiFlagSaid = -1;

	private static void qualiTaken(Minecraft mc, ServerRace r, boolean mine) {
		if ("running".equals(r.status) && mine && qualiSaid != r.id) {
			qualiSaid = r.id;
			Notify.show(mc, Component.translatable("laprace.quali.started", r.laps, Track.displayName(r.track)));
		}
	}

	/** Our laps on the qualifying's track while it runs (and the laps begun before the flag): qualifying laps. */
	public static boolean qualiOn(String track) {
		ServerRace r = current;
		return r != null && r.quali && "running".equals(r.status) && r.track.equals(track) && r.mine();
	}

	/** Every tick: the qualifying's last minute and its flag, said once. */
	public static void tick(Minecraft mc) {
		ServerRace r = current;
		if (r == null || !r.quali || !"running".equals(r.status) || r.endAt == 0 || !r.mine()) {
			return;
		}
		long left = r.endAt - System.currentTimeMillis();
		if (left <= 60_000 && left > 0 && qualiMinuteSaid != r.id) {
			qualiMinuteSaid = r.id;
			Notify.show(mc, Component.translatable("laprace.quali.minute"));
		}
		if (left <= 0 && qualiFlagSaid != r.id) {
			qualiFlagSaid = r.id;
			Notify.show(mc, Component.translatable("laprace.quali.flag"));
		}
	}

	// ------------------------------------------------------------------ requests

	/** An event with all the host's settings (see the server's Lobby.Opts). */
	public static void create(String kind, String track, JsonObject opts) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "race-create");
		m.addProperty("kind", kind);
		m.addProperty("track", track);
		m.add("opts", opts);
		dev.laprace.net.Backend.sendRace(m);
	}

	public static void createQuali(String kind, String track, int minutes) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "race-create");
		m.addProperty("format", "quali");
		m.addProperty("kind", kind);
		m.addProperty("track", track);
		m.addProperty("minutes", minutes);
		dev.laprace.net.Backend.sendRace(m);
	}

	public static void create(String kind, String track, int laps, String start) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "race-create");
		m.addProperty("kind", kind);
		m.addProperty("track", track);
		m.addProperty("laps", laps);
		m.addProperty("start", start);
		dev.laprace.net.Backend.sendRace(m);
	}

	public static void simple(String what) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "race-" + what);
		dev.laprace.net.Backend.sendRace(m);
	}
}
