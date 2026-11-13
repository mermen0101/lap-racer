package dev.laprace;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Personal best lap per track (time, sectors, the lap's timeline for the live delta): config/laprace/bests.json. */
public final class Bests {
	public static final class Entry {
		public double lap;
		public double[] sectors;
		public double[] line;
	}

	private static Map<String, Entry> map;

	private Bests() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("laprace").resolve("bests.json");
	}

	private static Map<String, Entry> map() {
		if (map == null) {
			map = new HashMap<>();
			try {
				Path f = file();
				if (Files.exists(f)) {
					Map<String, Entry> m = new Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8),
							new TypeToken<Map<String, Entry>>() { }.getType());
					if (m != null) {
						map = m;
					}
				}
			} catch (Exception e) {
				LapRaceClient.LOG.warn("bests.json could not be read", e);
			}
		}
		return map;
	}

	/** The entry, or null if none (or it was timed with another number of sectors). */
	public static Entry get(String track, int sectors) {
		Entry e = map().get(track);
		if (e == null || e.lap <= 0) {
			return null;
		}
		if (e.sectors != null && e.sectors.length != sectors) {
			e.sectors = null; // other sector setup: keep the lap, drop the splits
		}
		return e;
	}

	public static void put(String track, double lap, double[] sectors, double[] line) {
		Entry e = new Entry();
		e.lap = lap;
		e.sectors = sectors;
		e.line = line;
		map().put(track, e);
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new Gson().toJson(map()), StandardCharsets.UTF_8);
		} catch (Exception ex) {
			LapRaceClient.LOG.warn("bests.json could not be written", ex);
		}
	}
}
