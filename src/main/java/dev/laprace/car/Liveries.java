package dev.laprace.car;

import com.mojang.blaze3d.platform.NativeImage;
import dev.laprace.LapConfig;
import dev.laprace.LapRaceClient;
import dev.laprace.net.Backend;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whose car is in which livery: each driver's choice, as the server tells it (a built-in one, or "c:HASH" for their
 * own picture, approved by a judge), so every car is drawn in its own driver's colours. One's own car follows one's
 * own setting at once; one's own picture is config/laprace/livery.png (a template beside it, livery-template.png,
 * to paint over), read again when it changes. Others' pictures are downloaded once and kept in
 * config/laprace/livery-cache.
 */
public final class Liveries {
	private static final Map<String, String> BY_NAME = new ConcurrentHashMap<>();
	private static final Map<String, Identifier> REMOTE = new ConcurrentHashMap<>();
	private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();
	private static final Identifier OWN_ID = Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, "livery/own");
	private static Identifier own;
	private static long ownStamp = -1, ownChecked;
	/** What the last upload came to: null, or a message key's tail (sent, pending, ok, no, error) and its detail. */
	public static volatile String uploadState;
	public static volatile String uploadError;

	private Liveries() {
	}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("laprace");
	}

	public static Path ownFile() {
		return dir().resolve("livery.png");
	}

	// ------------------------------------------------------------------ from the server

	/** Everyone's (on connect). */
	public static void all(com.google.gson.JsonObject map) {
		BY_NAME.clear();
		for (var e : map.entrySet()) {
			BY_NAME.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
		}
	}

	/** One driver's, changed. */
	public static void one(String name, String id) {
		BY_NAME.put(name.toLowerCase(Locale.ROOT), id);
	}

	/** Our own as the server shows it to others (to tell whether our picture is approved). */
	public static String shownOf(String name) {
		return name == null ? null : BY_NAME.get(name.toLowerCase(Locale.ROOT));
	}

	/** What the server should know of our choice: "default", "redbull", "reno" or "custom". */
	public static String choiceId() {
		String h = LapConfig.INSTANCE.carLiveryHash;
		if (h != null && h.matches("[0-9a-f]{24}")) {
			return "c:" + h;
		}
		return LapConfig.INSTANCE.carLivery.name().toLowerCase(Locale.ROOT);
	}

	/** A catalog picture's PNG (from the cache or the server), off the main thread; null if it cannot be had. */
	public static byte[] pictureBytes(String hash) {
		Path cached = dir().resolve("livery-cache").resolve(hash + ".png");
		try {
			if (Files.exists(cached)) {
				return Files.readAllBytes(cached);
			}
			byte[] png = Backend.fetch("/api/livery/" + hash + ".png");
			Files.createDirectories(cached.getParent());
			Files.write(cached, png);
			return png;
		} catch (Exception e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ the textures

	/** The car's texture and its glow for a car driven by this player (null: nobody, as ours). */
	public static Identifier[] of(String player, boolean self) {
		String id;
		if (self || player == null) {
			id = choiceId();
			if (id.startsWith("c:")) {
				Identifier r = remote(id.substring(2));
				return r != null ? new Identifier[] {r, CarRenderer.tex("default_e")} : builtIn("default");
			}
			if (id.equals("custom")) {
				Identifier o = ownTexture();
				return o != null ? new Identifier[] {o, CarRenderer.tex("default_e")} : builtIn("default");
			}
		} else {
			id = BY_NAME.getOrDefault(player.toLowerCase(Locale.ROOT), "default");
			if (id.startsWith("c:")) {
				Identifier r = remote(id.substring(2));
				return r != null ? new Identifier[] {r, CarRenderer.tex("default_e")} : builtIn("default");
			}
		}
		return builtIn(id);
	}

	private static Identifier[] builtIn(String id) {
		if (!id.equals("redbull") && !id.equals("reno")) {
			id = "default";
		}
		return new Identifier[] {CarRenderer.tex(id), CarRenderer.tex(id + "_e")};
	}

	/** Our own picture (read again when the file changes, looked at every 2 s); null without one. */
	private static Identifier ownTexture() {
		long now = System.currentTimeMillis();
		if (now - ownChecked > 2000) {
			ownChecked = now;
			Path f = ownFile();
			try {
				long stamp = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : -1;
				if (stamp != ownStamp) {
					ownStamp = stamp;
					own = stamp < 0 ? null : register(OWN_ID, Files.readAllBytes(f));
				}
			} catch (Exception e) {
				LapRaceClient.LOG.warn("own livery: {}", e.toString());
				own = null;
			}
		}
		return own;
	}

	/** Another driver's picture: from the cache, or downloaded (the default until it is here). */
	private static Identifier remote(String hash) {
		if (!hash.matches("[0-9a-f]{24}")) {
			return null;
		}
		Identifier have = REMOTE.get(hash);
		if (have != null || !LOADING.add(hash)) {
			return have;
		}
		Path cached = dir().resolve("livery-cache").resolve(hash + ".png");
		CompletableFuture.supplyAsync(() -> {
			try {
				if (Files.exists(cached)) {
					return Files.readAllBytes(cached);
				}
				byte[] png = Backend.fetch("/api/livery/" + hash + ".png");
				Files.createDirectories(cached.getParent());
				Files.write(cached, png);
				return png;
			} catch (Exception e) {
				LapRaceClient.LOG.warn("livery {}: {}", hash, e.toString());
				return null;
			}
		}).thenAccept(png -> Minecraft.getInstance().execute(() -> {
			if (png != null) {
				try {
					REMOTE.put(hash, register(Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, "livery/" + hash), png));
				} catch (Exception e) {
					LapRaceClient.LOG.warn("livery {}: {}", hash, e.toString());
				}
			}
			LOADING.remove(hash); // (failed: tried again when next drawn)
		}));
		return null;
	}

	private static Identifier register(Identifier id, byte[] png) throws Exception {
		NativeImage img;
		try (InputStream in = new java.io.ByteArrayInputStream(png)) {
			img = NativeImage.read(in);
		}
		int w = img.getWidth();
		if (w != img.getHeight() || (w != 128 && w != 256 && w != 512)) {
			img.close();
			throw new IllegalArgumentException("a square of 128, 256 or 512 pixels wanted, not " + w + "x" + img.getHeight());
		}
		Minecraft mc = Minecraft.getInstance();
		mc.getTextureManager().release(id);
		mc.getTextureManager().register(id, new DynamicTexture(id::toString, img));
		return id;
	}

	// ------------------------------------------------------------------ one's own picture

	/** The template (the standard livery) beside one's own picture, and one's own made from it if there is none. */
	public static void ensureTemplate() {
		try {
			Files.createDirectories(dir());
			Path tpl = dir().resolve("livery-template.png");
			byte[] std;
			try (InputStream in = Liveries.class.getResourceAsStream("/assets/laprace/textures/entity/f1/default.png")) {
				std = in.readAllBytes();
			}
			if (!Files.exists(tpl)) {
				Files.write(tpl, std);
			}
			if (!Files.exists(ownFile())) {
				Files.write(ownFile(), std);
			}
		} catch (Exception e) {
			LapRaceClient.LOG.warn("livery template: {}", e.toString());
		}
	}

	/**
	 * A picture from anywhere becomes one's own livery (copied in as livery.png, our car in it at once); null if it
	 * is fine, else what is wrong with it (a key's tail).
	 */
	public static String useFile(Path f) {
		try {
			byte[] png = Files.readAllBytes(f);
			if (png.length > 600_000) {
				return "big";
			}
			int[][] tex = CarPicture.texture(png);
			if (tex == null) {
				return "not_png";
			}
			int w = tex[1][0], h = tex[1][1];
			if (w != h || (w != 128 && w != 256 && w != 512)) {
				return "size";
			}
			Files.createDirectories(dir());
			Files.write(ownFile(), png);
			LapConfig.INSTANCE.carLivery = LapConfig.Livery.CUSTOM;
			LapConfig.INSTANCE.carLiveryHash = null;
			if (LapConfig.INSTANCE.ownLiveryTitle == null || LapConfig.INSTANCE.ownLiveryTitle.isBlank()) {
				String n = f.getFileName().toString().replaceAll("(?i)\\.png$", "").replace('_', ' ').trim();
				LapConfig.INSTANCE.ownLiveryTitle = n.length() > 32 ? n.substring(0, 32) : n;
			}
			uploadState = null;
			uploadError = null;
			ownChecked = 0; // (our car: the new picture at once)
			LapConfig.save();
			Backend.sendLivery();
			return null;
		} catch (Exception e) {
			LapRaceClient.LOG.warn("livery file: {}", e.toString());
			return "not_png";
		}
	}

	/** Our own picture changed (livery.png): read again at once. */
	public static void touchOwn() {
		ownChecked = 0;
	}

	/** Sends one's own picture to the server, for the judges. */
	public static void upload() {
		uploadState = "sending";
		uploadError = null;
		CompletableFuture.runAsync(() -> {
			try {
				String status = Backend.uploadLivery(Files.readAllBytes(ownFile()), LapConfig.INSTANCE.ownLiveryTitle);
				uploadState = status;
			} catch (Exception e) {
				uploadState = "error";
				uploadError = e.getMessage();
				LapRaceClient.LOG.warn("livery upload: {}", e.toString());
			}
		});
	}
}
