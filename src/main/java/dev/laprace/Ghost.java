package dev.laprace;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Racing one's own best lap: every lap we drive is recorded (where the boat was, tick by tick); a valid one faster than
 * the track's stored ghost becomes it (kept on disk, per track). While we drive on that track (not in the server's
 * races), a see-through car with nobody in it drives the ghost lap, starting with each of our laps; the HUD shows how
 * far ahead or behind it we are. It is a client-only boat: nothing else sees it, nothing collides with it.
 */
public final class Ghost {
	/** One tick of a lap: ticks since its start, where, which way, how far round the track (s). */
	private record Sample(float t, double x, double y, double z, float yaw, float s) {
	}

	private record Lap(double time, List<Sample> samples) {
	}

	/** A fake entity id (no server entity has a negative one). */
	private static final int ID = -7_000_001;

	private static final Map<String, Lap> BEST = new HashMap<>();
	private static final List<Sample> rec = new ArrayList<>();
	private static String recTrack;
	private static Entity boat;
	/** The gap to the ghost at our place on the track (s; + behind it), NaN when there is none. */
	public static double gap = Double.NaN;

	private Ghost() {
	}

	public static boolean isGhost(Entity e) {
		return e != null && e.getId() == ID;
	}

	/** Shown now: on in the settings, a track, not the server's race (fair for everyone there). */
	private static boolean on(RaceSession s) {
		return LapConfig.INSTANCE.ghostWith != LapConfig.GhostWith.OFF && s.track != null && !s.serverRace;
	}

	/** The lap the ghost drives on this track: ours, or the track record's (from the server; null until it is here). */
	private static Lap shown(RaceSession s) {
		return LapConfig.INSTANCE.ghostWith == LapConfig.GhostWith.RECORD ? record(s.track) : best(s.track.name);
	}

	// ------------------------------------------------------------------ the track record's lap, from the server

	private static final Map<String, Lap> RECORD = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<String, Long> RECORD_ASKED = new java.util.concurrent.ConcurrentHashMap<>();
	/** Whose record the ghost drives (for the HUD), or null. */
	public static volatile String recordBy;

	private static Lap record(Track t) {
		long now = System.currentTimeMillis();
		Long asked = RECORD_ASKED.get(t.name);
		if ((asked == null || now - asked > 600_000) && dev.laprace.net.Backend.state() == dev.laprace.net.Backend.State.ONLINE) {
			RECORD_ASKED.put(t.name, now);
			java.util.concurrent.CompletableFuture.runAsync(() -> {
				try {
					byte[] b = dev.laprace.net.Backend.fetch("/api/record-lap?track=" + t.name);
					com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(new String(b, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
					Lap lap = fromServer(t, o);
					if (lap != null) {
						RECORD.put(t.name, lap);
						recordBy = o.get("name").getAsString();
					}
				} catch (Exception e) {
					LapRaceClient.LOG.debug("record ghost {}: {}", t.name, e.toString());
				}
			});
		}
		return RECORD.get(t.name);
	}

	/** The server's points (10 a second: ms, x, z, ...) as a ghost lap: the height from the track, the heading from the way it went. */
	private static Lap fromServer(Track t, com.google.gson.JsonObject o) {
		var pts = o.getAsJsonArray("pts");
		if (pts.size() < 20) {
			return null;
		}
		List<Sample> out = new ArrayList<>();
		Track.Projection p = new Track.Projection();
		int hint = -1;
		for (int i = 0; i < pts.size(); i++) {
			var a = pts.get(i).getAsJsonArray();
			var b = pts.get(Math.min(pts.size() - 1, i + 1)).getAsJsonArray();
			var c = pts.get(Math.max(0, i - 1)).getAsJsonArray();
			double x = a.get(1).getAsDouble(), z = a.get(2).getAsDouble();
			double dx = b.get(1).getAsDouble() - c.get(1).getAsDouble(), dz = b.get(2).getAsDouble() - c.get(2).getAsDouble();
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			t.project(x, z, hint, p);
			hint = p.idx;
			// round the track from the line: just before it at the start counts below 0, just past it at the end above its length
			double s = p.s;
			if (i < pts.size() / 4 && s > t.length / 2) {
				s -= t.length;
			} else if (i > pts.size() * 3 / 4 && s < t.length / 2) {
				s += t.length;
			}
			out.add(new Sample((float) (a.get(0).getAsDouble() / 50.0), x, t.y[p.idx], z, yaw, (float) s));
		}
		return new Lap(o.get("time").getAsDouble() / 1000.0, out);
	}

	// ------------------------------------------------------------------ recording

	/** Our lap starts (over the line): a new recording. */
	static void lapStart(RaceSession s) {
		rec.clear();
		recTrack = s.track == null ? null : s.track.name;
	}

	/** Our lap is done: kept as the ghost if valid and the best on this track. */
	static void lapDone(RaceSession s, double lapTime, boolean valid) {
		valid |= System.getenv("LAPRACE_GHOSTANY") != null; // (dev: the autopilot's laps too)
		if (!valid || s.track == null || !s.track.name.equals(recTrack) || rec.size() < 20) {
			return;
		}
		Lap best = best(s.track.name);
		if (best == null || lapTime < best.time()) {
			Lap lap = new Lap(lapTime, List.copyOf(rec));
			BEST.put(s.track.name, lap);
			save(s.track.name, lap);
			LapRaceClient.LOG.info("ghost of {}: {} s ({} samples)", s.track.name, String.format(java.util.Locale.ROOT, "%.3f", lapTime), rec.size());
		}
	}

	// ------------------------------------------------------------------ every tick

	public static void tick(Minecraft mc) {
		RaceSession s = RaceSession.INSTANCE;
		Racer me = s.self;
		ClientLevel level = mc.level;
		// record our lap as it goes
		if (me != null && me.lapStart >= 0 && mc.player != null && RaceSession.boatOf(mc.player) != null && s.track != null && s.track.name.equals(recTrack)) {
			Entity b = RaceSession.boatOf(mc.player);
			rec.add(new Sample((float) (s.now - me.lapStart), b.getX(), b.getY(), b.getZ(), b.getYRot(), (float) me.s));
			if (rec.size() > 20 * 60 * 15) {
				rec.clear(); // (a quarter of an hour: not a lap)
			}
		}
		// play the ghost lap, from our lap's start
		Lap lap = level == null || !on(s) ? null : shown(s);
		if (lap == null || me == null || me.lapStart < 0 || me.finished) {
			remove(level);
			gap = Double.NaN;
			return;
		}
		double t = s.now - me.lapStart;
		Sample at = sampleAt(lap.samples(), t);
		if (at == null) {
			remove(level); // past its finish: it waits at the line for our next lap
			gap = Double.NaN;
			return;
		}
		if (boat == null || boat.isRemoved() || boat.level() != level) {
			boat = EntityTypes.OAK_BOAT.create(level, EntitySpawnReason.LOAD);
			if (boat == null) {
				return;
			}
			boat.setId(ID);
			boat.noPhysics = true;
			boat.snapTo(at.x(), at.y(), at.z(), at.yaw(), 0);
			level.addEntity(boat);
		}
		boat.setOldPosAndRot();
		boat.setPos(at.x(), at.y(), at.z());
		boat.setYRot(at.yaw());
		boat.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		gap = gapAt(lap.samples(), me.s, t, s.track.length);
	}

	private static void remove(ClientLevel level) {
		if (boat != null && level != null && !boat.isRemoved()) {
			level.removeEntity(ID, Entity.RemovalReason.DISCARDED);
		}
		boat = null;
	}

	/** Where the ghost was t ticks into its lap (between two samples), null before its start or after its finish. */
	private static Sample sampleAt(List<Sample> l, double t) {
		if (l.isEmpty() || t < l.get(0).t() || t > l.get(l.size() - 1).t()) {
			return null;
		}
		int lo = 0, hi = l.size() - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (l.get(mid).t() <= t) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		Sample a = l.get(lo), b = l.get(hi);
		double f = b.t() == a.t() ? 0 : (t - a.t()) / (b.t() - a.t());
		float dy = net.minecraft.util.Mth.wrapDegrees(b.yaw() - a.yaw());
		return new Sample((float) t, a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f, a.z() + (b.z() - a.z()) * f,
				(float) (a.yaw() + dy * f), (float) (a.s() + (b.s() - a.s()) * f));
	}

	/**
	 * The gap at our place: our time into the lap minus the ghost's when it was there (s). Only past the first stretch
	 * (s near the line both ends of the lap) and before the end.
	 */
	private static double gapAt(List<Sample> l, double s, double t, double length) {
		if (s < 30 || s > length - 10) {
			return Double.NaN;
		}
		for (int i = 1; i < l.size(); i++) {
			Sample a = l.get(i - 1), b = l.get(i);
			if (a.s() <= s && b.s() >= s && b.s() - a.s() < 50) {
				double f = b.s() == a.s() ? 0 : (s - a.s()) / (b.s() - a.s());
				return (t - (a.t() + (b.t() - a.t()) * f)) / 20.0;
			}
		}
		return Double.NaN;
	}

	// ------------------------------------------------------------------ disk

	private static Path file(String track) {
		return FabricLoader.getInstance().getConfigDir().resolve("laprace").resolve("ghosts").resolve(track + ".ghost");
	}

	private static Lap best(String track) {
		if (!BEST.containsKey(track)) {
			BEST.put(track, load(track));
		}
		return BEST.get(track);
	}

	private static Lap load(String track) {
		Path f = file(track);
		if (!Files.exists(f)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(f); DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in))) {
			if (d.readInt() != 1) {
				return null;
			}
			double time = d.readDouble();
			int n = d.readInt();
			List<Sample> l = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				l.add(new Sample(d.readFloat(), d.readDouble(), d.readDouble(), d.readDouble(), d.readFloat(), d.readFloat()));
			}
			return new Lap(time, List.copyOf(l));
		} catch (IOException e) {
			LapRaceClient.LOG.warn("ghost of {}: {}", track, e.toString());
			return null;
		}
	}

	private static void save(String track, Lap lap) {
		Path f = file(track);
		try {
			Files.createDirectories(f.getParent());
			try (OutputStream out = Files.newOutputStream(f); DataOutputStream d = new DataOutputStream(new java.io.BufferedOutputStream(out))) {
				d.writeInt(1);
				d.writeDouble(lap.time());
				d.writeInt(lap.samples().size());
				for (Sample s : lap.samples()) {
					d.writeFloat(s.t());
					d.writeDouble(s.x());
					d.writeDouble(s.y());
					d.writeDouble(s.z());
					d.writeFloat(s.yaw());
					d.writeFloat(s.s());
				}
			}
		} catch (IOException e) {
			LapRaceClient.LOG.warn("ghost of {} not saved: {}", track, e.toString());
		}
	}

	/** Its lap time on this track (s), or -1. */
	public static double time(String track) {
		Lap l = track == null ? null : best(track);
		return l == null ? -1 : l.time();
	}
}
