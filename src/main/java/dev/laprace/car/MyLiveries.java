package dev.laprace.car;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.laprace.LapConfig;
import dev.laprace.LapRaceClient;
import dev.laprace.net.Backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One's own liveries, as many as one makes: each a PNG and a name, in config/laprace/my-liveries (index.json and
 * ID.png). One of them on our car: copied in as livery.png (what Liveries draws for "custom"), or, once the judges have
 * approved it, by its hash (so everyone sees it). Sending one to the judges keeps the hash the server gave it; the
 * server's word on each (pending, approved, rejected) is asked for when the garage opens.
 */
public final class MyLiveries {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One of ours: its id (file name), name, the server's hash once sent, when made. */
	public static final class Livery {
		public String id;
		public String name;
		public String hash;
		public long at;
		/** The server's word on it (not kept): null unknown / local, pending, ok, no, replaced. */
		public transient String status;
	}

	private static List<Livery> list;
	/** What went wrong with the last upload (shown on the card), by id. */
	public static final java.util.Map<String, String> ERRORS = new java.util.concurrent.ConcurrentHashMap<>();
	public static final Set<String> SENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private MyLiveries() {
	}

	public static Path dir() {
		return Liveries.dir().resolve("my-liveries");
	}

	public static Path file(Livery l) {
		return dir().resolve(l.id + ".png");
	}

	public static synchronized List<Livery> all() {
		if (list == null) {
			load();
		}
		return List.copyOf(list);
	}

	private static void load() {
		list = new ArrayList<>();
		try {
			Path idx = dir().resolve("index.json");
			if (Files.exists(idx)) {
				List<Livery> l = GSON.fromJson(Files.readString(idx), new TypeToken<List<Livery>>() {
				}.getType());
				if (l != null) {
					for (Livery x : l) {
						if (x != null && x.id != null && Files.exists(file(x))) {
							list.add(x);
						}
					}
				}
			} else if (Files.exists(Liveries.ownFile())) {
				// (from before: the one livery.png becomes the first of ours)
				Livery x = new Livery();
				x.id = UUID.randomUUID().toString().substring(0, 8);
				String t = LapConfig.INSTANCE.ownLiveryTitle;
				x.name = t == null || t.isBlank() ? "Моя ливрея" : t;
				x.at = System.currentTimeMillis();
				Files.createDirectories(dir());
				Files.copy(Liveries.ownFile(), file(x));
				list.add(x);
				save();
			}
		} catch (Exception e) {
			LapRaceClient.LOG.warn("my liveries: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(dir());
			Files.writeString(dir().resolve("index.json"), GSON.toJson(list));
		} catch (Exception e) {
			LapRaceClient.LOG.warn("my liveries: {}", e.toString());
		}
	}

	/** A picture checked: null if it will do, else what is wrong (a key's tail: big, not_png, size). */
	public static String check(byte[] png) {
		if (png.length > 600_000) {
			return "big";
		}
		int[][] tex = CarPicture.texture(png);
		if (tex == null) {
			return "not_png";
		}
		int w = tex[1][0], h = tex[1][1];
		return w != h || (w != 128 && w != 256 && w != 512) ? "size" : null;
	}

	/** A new one of ours, from this picture and name; it goes on our car. */
	public static synchronized Livery create(byte[] png, String name) throws Exception {
		all();
		Livery x = new Livery();
		x.id = UUID.randomUUID().toString().substring(0, 8);
		x.name = clean(name);
		x.at = System.currentTimeMillis();
		Files.createDirectories(dir());
		Files.write(file(x), png);
		list.add(x);
		save();
		use(x);
		return x;
	}

	/** Its name changed, its picture too if given (a new picture: not the one sent, so it is to be sent again). */
	public static synchronized void edit(Livery x, String name, byte[] png) throws Exception {
		String before = x.name;
		x.name = clean(name);
		if (png != null) {
			Files.write(file(x), png);
			x.hash = null;
			x.status = null;
		}
		save();
		if (png == null && x.hash != null && !x.name.equals(before)) {
			Backend.renameLivery(x.name); // (the server renames the one it has of ours now)
		}
		if (isOn(x)) {
			use(x);
		}
	}

	public static synchronized void delete(Livery x) {
		boolean on = isOn(x);
		list.removeIf(l -> l.id.equals(x.id));
		try {
			Files.deleteIfExists(file(x));
		} catch (Exception ignored) {
		}
		save();
		if (on) {
			LapConfig.INSTANCE.carLivery = LapConfig.Livery.DEFAULT;
			LapConfig.INSTANCE.carLiveryHash = null;
			LapConfig.save();
			Backend.sendLivery();
		}
	}

	/** Which of ours is on our car now (by its id, kept in the settings). */
	public static boolean isOn(Livery x) {
		LapConfig c = LapConfig.INSTANCE;
		if (!x.id.equals(c.myLiveryId)) {
			return false;
		}
		return c.carLiveryHash == null ? c.carLivery == LapConfig.Livery.CUSTOM : c.carLiveryHash.equals(x.hash);
	}

	/** Ours on our car: by its hash once approved (everyone sees it), else as our local picture. */
	public static void use(Livery x) {
		LapConfig c = LapConfig.INSTANCE;
		c.myLiveryId = x.id;
		try {
			Files.createDirectories(Liveries.dir());
			Files.copy(file(x), Liveries.ownFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			LapRaceClient.LOG.warn("my livery: {}", e.toString());
		}
		if ("ok".equals(x.status) && x.hash != null) {
			c.carLiveryHash = x.hash;
		} else {
			c.carLiveryHash = null;
		}
		c.carLivery = LapConfig.Livery.CUSTOM;
		Liveries.touchOwn();
		LapConfig.save();
		Backend.sendLivery();
	}

	/** To the judges: the server keeps it as ours now (the one sent before, if not yet approved, gives way to it). */
	public static void send(Livery x) {
		if (!SENDING.add(x.id)) {
			return;
		}
		ERRORS.remove(x.id);
		CompletableFuture.runAsync(() -> {
			try {
				String[] r = Backend.uploadLiveryFull(Files.readAllBytes(file(x)), x.name);
				synchronized (MyLiveries.class) {
					x.hash = r[0];
					x.status = r[1];
					for (Livery l : list) {
						if (l != x && "pending".equals(l.status)) {
							l.status = "replaced";
						}
					}
					save();
				}
			} catch (Exception e) {
				ERRORS.put(x.id, e.getMessage() == null ? "error" : e.getMessage());
			} finally {
				SENDING.remove(x.id);
			}
		});
	}

	/** The server's word on each of ours (on opening the garage). */
	public static void refresh() {
		if (Backend.state() != Backend.State.ONLINE) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				JsonObject m = JsonParser.parseString(new String(Backend.fetchSigned("/api/livery-mine"), StandardCharsets.UTF_8)).getAsJsonObject();
				String cur = m.has("hash") ? m.get("hash").getAsString() : null;
				String st = m.has("status") ? m.get("status").getAsString() : null;
				Set<String> made = new java.util.HashSet<>(), rejected = new java.util.HashSet<>();
				if (m.has("made")) {
					m.getAsJsonArray("made").forEach(e -> made.add(e.getAsString()));
				}
				if (m.has("rejected")) {
					m.getAsJsonArray("rejected").forEach(e -> rejected.add(e.getAsString()));
				}
				synchronized (MyLiveries.class) {
					for (Livery l : all()) {
						Livery x = list.stream().filter(y -> y.id.equals(l.id)).findFirst().orElse(l);
						if (x.hash == null) {
							x.status = null;
						} else if (made.contains(x.hash)) {
							x.status = "ok";
						} else if (rejected.contains(x.hash)) {
							x.status = "no"; // (turned down: gone from the server, kept here; changed, it may be sent again)
						} else if (x.hash.equals(cur)) {
							x.status = st;
						} else {
							x.status = "replaced";
						}
					}
				}
			} catch (Exception e) {
				LapRaceClient.LOG.debug("my liveries: {}", e.toString());
			}
		});
	}

	private static String clean(String name) {
		String n = name == null ? "" : name.replaceAll("\\p{Cntrl}", "").trim();
		if (n.length() > 32) {
			n = n.substring(0, 32).trim();
		}
		return n.isEmpty() ? "Без названия" : n;
	}
}
