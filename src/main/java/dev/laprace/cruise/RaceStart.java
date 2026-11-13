package dev.laprace.cruise;

import dev.laprace.RaceSession;
import dev.laprace.Racer;
import dev.laprace.ServerRace;
import dev.laprace.Track;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Set;

/**
 * The autopilot at a server race's start, the car locked to it (no keys) until it hands back:
 * <ul>
 * <li>grid: round the track to its own spot behind the start line (staggered left and right, {@link #GAP} blocks apart,
 * in the order the drivers signed up or qualified), stops there and holds until the lights go out;</li>
 * <li>rolling (as in Gran Turismo): round to the line at 80 km/h, keeping its distance to the car ahead; over the line
 * the car is the driver's - and the first car over it starts the race for everyone.</li>
 * </ul>
 */
public final class RaceStart {
	/**
	 * Grid spots, staggered two by two: the first this far behind the line, each next one this much further back and on
	 * the other side of the track (so a car is GAP·2 behind the one ahead on its own side) (blocks).
	 */
	private static final double FIRST = 6, GAP = 4;
	/** How far off the centre line a grid spot is (blocks), less if the ice there is narrower. */
	static final double SIDE = 1.8; // (the car ends up ~1.5 further out than its line: ~3.3 blocks off centre)
	/** Rolling start: the distance kept to the car ahead (blocks). */
	private static final double KEEP = 9;

	public enum Kind { GRID, ROLLING }

	private static Kind kind;
	private static double slotS;
	/** The grid spot's side: +1 / -1 (alternating from the pole). */
	private static int side;
	private static boolean parked, readySent;
	private static Set<String> drivers = Set.of();
	private static double lastS = -1;

	private RaceStart() {
	}

	public static boolean active() {
		return kind != null && Cruise.enabled;
	}

	public static boolean parked() {
		return parked;
	}

	/** Grid: to spot number index (0 = pole). */
	public static void grid(Minecraft mc, int index, Set<String> names) {
		Track t = RaceSession.INSTANCE.track;
		if (t == null || mc.player == null || RaceSession.boatOf(mc.player) == null) {
			return;
		}
		kind = Kind.GRID;
		if (System.getenv("LAPRACE_GRIDIDX") != null) {
			index = Integer.parseInt(System.getenv("LAPRACE_GRIDIDX")); // dev: test another spot
		}
		slotS = Math.floorMod(Math.round(-(FIRST + GAP * index) * 10), Math.round(t.length * 10)) / 10.0;
		side = index % 2 == 0 ? 1 : -1;
		parked = false;
		readySent = false;
		drivers = names;
		take(mc);
	}

	/** Rolling start speed (blocks/s). */
	private static double rollBps = Cruise.LIMIT_BPS;

	/** Rolling: at kmh (at most the autopilot's 80) to the line. */
	public static void rolling(Minecraft mc, Set<String> names, int kmh) {
		rollBps = Math.min(Cruise.LIMIT_BPS, kmh / 3.6);
		if (RaceSession.INSTANCE.track == null || mc.player == null || RaceSession.boatOf(mc.player) == null) {
			return;
		}
		kind = Kind.ROLLING;
		parked = false;
		drivers = names;
		lastS = -1;
		take(mc);
	}

	private static void take(Minecraft mc) {
		if (!Cruise.enabled || Cruise.mode != Cruise.Mode.TRACK) {
			Cruise.takeForStart(mc);
		}
	}

	/** Hands the car back to the driver (lights out on the grid; over the line in a rolling start; race gone). */
	public static void release(Minecraft mc) {
		boolean was = kind != null;
		kind = null;
		parked = false;
		if (was && Cruise.enabled && Cruise.mode == Cruise.Mode.TRACK) {
			Cruise.disable(mc.player, null);
		}
	}

	/** The lights are out: a car held on the grid is the driver's. */
	public static void lightsOut(Minecraft mc) {
		if (kind == Kind.GRID) {
			release(mc);
		}
	}

	/** Driving to a grid spot: its place on the track (s) and side (+1 / -1); 0 = not on the grid. */
	static int gridSide() {
		return active() && kind == Kind.GRID ? side : 0;
	}

	static double gridS() {
		return slotS;
	}

	/** Standing on the grid spot: hold the car there. */
	static boolean hold() {
		return kind == Kind.GRID && parked;
	}

	/** The top speed now (blocks/s): coming down to the grid spot, or the rolling start's distance to the car ahead. */
	static double cap(float f) {
		RaceSession s = RaceSession.INSTANCE;
		Racer me = s.self;
		Track t = s.track;
		if (me == null || t == null) {
			return Cruise.LIMIT_BPS;
		}
		if (kind == Kind.GRID) {
			double to = Math.floorMod(Math.round((slotS - me.s) * 10), Math.round(t.length * 10)) / 10.0;
			if (to > t.length - 30) {
				to = 0; // a little past the spot: stop where it is
			}
			return Math.min(8, Cruise.brakeSpeed(to - 3, f, 0));
		}
		// rolling: no closer than KEEP to whoever of the race is ahead on the track
		double gap = Double.MAX_VALUE;
		for (Racer r : s.racers.values()) {
			if (r.self || !drivers.contains(r.name.toLowerCase(Locale.ROOT))) {
				continue;
			}
			double d = Math.floorMod(Math.round((r.s - me.s) * 10), Math.round(t.length * 10)) / 10.0;
			if (d > 0 && d < gap) {
				gap = d;
			}
		}
		return gap > 40 ? rollBps : Math.clamp((gap - KEEP) * 2.5 + 0.0, 0, rollBps);
	}

	/** Every tick: parked on the grid spot (say so to the server), over the line in a rolling start (hand back, go). */
	public static void tick(Minecraft mc) {
		if (kind == null) {
			return;
		}
		if (!Cruise.enabled || mc.player == null || RaceSession.boatOf(mc.player) == null) {
			kind = null;
			parked = false;
			return;
		}
		RaceSession s = RaceSession.INSTANCE;
		Racer me = s.self;
		Track t = s.track;
		if (me == null || t == null) {
			return;
		}
		var v = RaceSession.boatOf(mc.player).getDeltaMovement();
		double speed = Math.hypot(v.x, v.z) * 20;
		if (kind == Kind.GRID) {
			double to = Math.floorMod(Math.round((slotS - me.s) * 10), Math.round(t.length * 10)) / 10.0;
			if (!parked && (to < 4.0 || to > t.length - 30) && speed < 1.5) {
				parked = true;
				dev.laprace.LapRaceClient.LOG.info("grid: parked at {} {}", String.format(Locale.ROOT, "%.2f", mc.player.getX()),
						String.format(Locale.ROOT, "%.2f", mc.player.getZ()));
			}
			if (parked && !readySent) {
				readySent = true;
				ServerRace.simple("ready");
			}
		} else {
			// over the line: from the end of the lap to its start
			if (lastS >= 0 && lastS > t.length * 0.8 && me.s < t.length * 0.2) {
				ServerRace.simple("go");
				release(mc);
				return;
			}
			lastS = me.s;
		}
	}
}
