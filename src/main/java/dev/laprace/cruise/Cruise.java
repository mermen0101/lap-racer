package dev.laprace.cruise;

import dev.laprace.LapConfig;
import dev.laprace.LapRaceClient;
import dev.laprace.PitLane;
import dev.laprace.Racer;
import dev.laprace.RaceSession;
import dev.laprace.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Autopilot with one driving speed only: follow the selected track's line at no more than 80 km/h.
 * It drives with {@link Planner} (vanilla boat model, partial throttle to hold the cap); off the ice it turns round
 * and drives back to the nearest ice. There is no other speed and no racing mode.
 * <p>
 * Besides the track it drives the pits (GT style): with "pit stop: automatic" it takes over once you turn into the
 * pit lane, drives it at the pit limit, stops at the box for the stop time (longer the emptier the tank) and
 * drives out, handing back on the track - slower than a good manual stop on purpose. After the finish it can drive
 * a cool-down lap and park in the box (or stop after the line when the track has no pit lane recorded).
 */
public final class Cruise {
	/** The one and only speed on the track: 80 km/h (1 block = 1 m). */
	public static final double LIMIT_BPS = 80.0 / 3.6;
	private static final double CAP = LIMIT_BPS - 0.5;
	private static final int OFF_ICE_TICKS = 8;
	private static final double RECOVER_MIN_OFF = 4.0;
	private static final int ICE_SEARCH_RADIUS = 48;
	private static final double RECOVER_SPEED = 14.0;
	private static final float CAMERA_SMOOTHING = 0.2f;

	public enum Mode { TRACK, COOLDOWN, PIT_IN, PIT_STOP, PIT_OUT, STOPPING }

	public static boolean enabled;
	public static Mode mode = Mode.TRACK;
	public static boolean recovering;
	public static double targetBps;
	/** Throttle the autopilot gave this tick, 0..1 (for the pedal bars). */
	public static double throttle;
	public static double brake;
	/** Pit stop: ticks left standing in the box. */
	public static int stopLeft;

	private static Track track;
	private static List<double[]> pts;
	/** The path the planner follows now: the track's line, or the pit lane (padded with track). */
	private static List<double[]> path;
	private static PitLane lane;
	/** The autopilot was off before it took the car into the pits: hand back after the exit. */
	private static boolean handBack;
	/** Cool-down: park in the box and stay there. */
	private static boolean parkAfterPit;
	private static double coolStartS;
	private static boolean coolHalfway;
	private static int idx = -1;
	private static int dockTicks;
	private static boolean backingUp;
	/** Development: where the car is against the box's spot (along its line, sideways). */
	public static double dockAlong, dockSide;
	/** Development: the longest the box approach planning took in one tick (ms). */
	public static double dockMaxMs, dockSumMs;
	public static int dockN;
	/** Ticks since the car was taken into the pits (for the log). */
	private static int pitClock;
	private static float lastYaw;
	private static boolean haveYaw;
	private static double steerAcc;
	private static double throttleAcc;
	private static int offIceTicks;
	private static double[] iceTarget;
	private static int iceRefresh;
	private static final Planner PLANNER = new Planner();
	private static final GameFloor FLOOR = new GameFloor();

	private Cruise() {
	}

	private static void reset() {
		idx = -1;
		haveYaw = false;
		steerAcc = 0;
		throttleAcc = 0;
		offIceTicks = 0;
		iceTarget = null;
		recovering = false;
		dockFloorReady = false;
		lastDock = null;
		PLANNER.reset();
	}

	public static void toggle(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			return;
		}
		if (enabled) {
			if (inPits()) {
				dev.laprace.Notify.show(Minecraft.getInstance(), Component.translatable("laprace.msg.pit_locked"));
				return;
			}
			disable(p, Component.translatable("laprace.msg.cruise_off"));
			return;
		}
		if (RaceSession.boatOf(p) == null) {
			dev.laprace.Notify.show(Minecraft.getInstance(), Component.translatable("laprace.msg.cruise_no_boat"));
			return;
		}
		if (RaceSession.INSTANCE.track == null) {
			dev.laprace.Notify.show(Minecraft.getInstance(), Component.translatable("laprace.msg.no_track"));
			return;
		}
		enabled = true;
		mode = Mode.TRACK;
		path = null;
		reset();
		dev.laprace.Notify.show(Minecraft.getInstance(), Component.translatable("laprace.msg.cruise_on", RaceSession.INSTANCE.track.displayName()));
	}

	/** A server race's start takes the car (grid or rolling, see RaceStart): on the track, keys locked. */
	static void takeForStart(Minecraft mc) {
		enabled = true;
		mode = Mode.TRACK;
		path = null;
		handBack = false;
		reset();
	}

	public static void disable(LocalPlayer p, Component why) {
		enabled = false;
		mode = Mode.TRACK;
		path = null;
		recovering = false;
		Analog.active = false;
		throttle = 0;
		brake = 0;
		if (p != null && why != null) {
			dev.laprace.Notify.show(Minecraft.getInstance(), why);
		}
	}

	/** After the finish: a cool-down lap, then into the box (or a stop after the line without a pit lane). */
	public static void startCooldown(Minecraft mc) {
		if (mc.player == null || RaceSession.boatOf(mc.player) == null || RaceSession.INSTANCE.track == null) {
			return;
		}
		enabled = true;
		mode = Mode.COOLDOWN;
		path = null;
		handBack = false;
		parkAfterPit = true;
		Racer me = RaceSession.INSTANCE.self;
		coolStartS = me != null ? me.s : 0;
		coolHalfway = false;
		reset();
		dev.laprace.Notify.show(mc, Component.translatable("laprace.msg.cooldown"));
	}

	private static double pitBps() {
		return LapConfig.INSTANCE.pitLimitKmh / 3.6;
	}

	private static void enterPit(Track t, PitLane l, boolean park) {
		lane = l;
		dockTicks = 0;
		pitClock = 0;
		backingUp = false;
		dockAlong = 1e9;
		Minecraft mc = Minecraft.getInstance();
		if (!l.fresh() && mc.level != null) {
			l.scan(mc.level); // the boxes, if not found yet
		}
		path = l.path(t, pitBps(), CAP);
		mode = Mode.PIT_IN;
		parkAfterPit = park;
		idx = -1;
		PLANNER.reset();
	}

	/**
	 * Automatic pit stop: with the setting on and a pit lane recorded, the autopilot takes the car once it has
	 * reached the straight before the red line (not just near the fork, so it cannot happen by accident).
	 */
	private static void pitTrigger(Minecraft mc, Entity boat, Track t) {
		if (LapConfig.INSTANCE.pitMode != LapConfig.PitMode.AUTO || (enabled && mode != Mode.TRACK)) {
			return;
		}
		PitLane l = PitLane.of(t);
		if (l == null) {
			return;
		}
		Vec3 v = boat.getDeltaMovement();
		// only once the car is really on its way into the pits: past the start of the straight before the red line,
		// going its way - the lane point nearest to it must be that one or one after it
		int from = l.entryAt();
		int best = -1;
		double bd = 36;
		for (int k = Math.max(0, from - 20); k < Math.min(l.pts.size() - 1, from + 14); k++) {
			double[] q = l.pts.get(k);
			double d = (q[0] - boat.getX()) * (q[0] - boat.getX()) + (q[2] - boat.getZ()) * (q[2] - boat.getZ());
			if (d < bd) {
				bd = d;
				best = k;
			}
		}
		if (best >= from) {
			int i = best;
			double[] q = l.pts.get(i), r = l.pts.get(i + 1);
			if ((r[0] - q[0]) * v.x + (r[2] - q[2]) * v.z > 0) {
				handBack = !enabled;
				enabled = true;
				reset();
				enterPit(t, l, false);
				LapRaceClient.LOG.info("automatic pit stop: taken at {} {} (lane point {}, entry line {})",
						String.format(java.util.Locale.ROOT, "%.1f", boat.getX()), String.format(java.util.Locale.ROOT, "%.1f", boat.getZ()), i, from);
				dev.laprace.Notify.show(mc, Component.translatable("laprace.msg.pit_auto"));
				return;
			}
		}
	}

	public static boolean backingUp() {
		return backingUp;
	}

	public static PitLane pitLane() {
		return lane;
	}

	public static boolean inPits() {
		return enabled && (mode == Mode.PIT_IN || mode == Mode.PIT_STOP || mode == Mode.PIT_OUT);
	}

	/** Points of path before the box where the hard stop takes over, and after it where the launch hands back. */
	private static final int DOCK_FROM = 70, LAUNCH_TO = 12;
	/** Braking the stop counts on (blocks/s^2); a boat with its bow turned back does about 16. */
	private static final double DOCK_DECEL = 8.0;
	/** Speed of the move over into the box's line (blocks/s): slow enough to stop on the spot in its last 6 blocks. */
	private static final double SHIFT_BPS = 4.5;
	/** The last stretch is planned from this many points before the box, entered at up to this speed (blocks/s). */
	private static final int PLAN_FROM = 40;
	private static final double PLAN_BPS = 12.0;

	/**
	 * Steering and thrust that move the boat at {@code speed} (blocks/s) towards (ex, ez) from where it is - or,
	 * with speed 0, hold it on that point. A boat only pushes along its bow, so the bow is swung to where the
	 * velocity has to change (against the travel to brake: eight times the S key) and the throttle opened once it
	 * points there.
	 */
	private static double[] pointDrive(double ex, double ez, double speed, Vec3 vel, float yaw, double turn, float f) {
		double d = Math.hypot(ex, ez);
		double vx, vz; // wanted velocity, blocks/tick
		if (speed <= 0) {
			double k = Math.min(0.15, 0.5 * d) / Math.max(d, 1e-6);
			vx = ex * k * 0.4;
			vz = ez * k * 0.4;
		} else {
			double v = Math.min(speed, Math.max(1.0, d * 6)) / 20.0;
			vx = d > 1e-6 ? ex / d * v : 0;
			vz = d > 1e-6 ? ez / d * v : 0;
		}
		double ax = (vx - vel.x) * 0.3, az = (vz - vel.z) * 0.3; // the change wanted this tick
		double am = Math.hypot(ax, az);
		if (am < 0.0015) {
			return new double[] {Planner.turnControl(yaw, yaw, turn, f), 0.0};
		}
		double want = Planner.yawOf(ax, az);
		double err = Math.abs(Mth.wrapDegrees(want - yaw));
		double steer = Planner.turnControl(want, yaw, turn, f);
		double thrust;
		if (err < 40) {
			thrust = Math.min(Planner.THRUST_MAX, am * Math.cos(Math.toRadians(err)));
		} else if (err > 150 && am < 0.006) {
			thrust = -0.005; // a little the other way: S does it without turning round
		} else {
			thrust = 0.0;
		}
		return new double[] {steer, thrust};
	}

	private static final double[] DOCK_BOW = {-50, -40, -30, -20, -12, -6, 0, 6, 12, 20, 30, 40, 50};
	private static final double[] DOCK_THRUST = {0.04, 0.02, 0.008, 0.0, -0.005};
	private static final int DOCK_H = 40, DOCK_OWN = 8;

	/**
	 * Steering and thrust for the last stretch into the box: every bow angle (off the box's line) and throttle
	 * held for {@link #DOCK_OWN} ticks, then a calm finish (bow back along the line, S while too fast for the
	 * distance left), simulated {@link #DOCK_H} ticks with the boat's own physics on the floor under it. The
	 * cheapest wins: far from the spot, off the line, past the spot, still moving at the end, bow far round.
	 */
	private static final double[] DOCK_BOW2 = {-30, -15, -6, 0, 6, 15, 30};
	private static final double[] DOCK_THRUST2 = {0.02, 0.0, -0.005};
	private static final int DOCK_SECOND = 12;

	/**
	 * The floor around the box for the box planning: friction and walls, one cell per block, centred on the spot.
	 * Read from the world a few rows a tick while the boat is still coming down the lane (reading it all at once
	 * was a stutter), complete before the last stretch; it does not change under the boat.
	 */
	private static final int DG = 96;
	private static int dockRows;
	private static final float[] DOCK_F = new float[DG * DG];
	private static final boolean[] DOCK_S = new boolean[DG * DG];
	private static int dgx, dgz;
	private static final double[] DOCK_COST = new double[DOCK_BOW.length * DOCK_THRUST.length];

	private static float dockFriction(double x, double z) {
		double h = 0.6875;
		float s = 0;
		int k = 0;
		for (int bx = Mth.floor(x - h); bx <= Mth.floor(x + h); bx++) {
			for (int bz = Mth.floor(z - h); bz <= Mth.floor(z + h); bz++) {
				int ix = bx - dgx, iz = bz - dgz;
				s += ix >= 0 && iz >= 0 && ix < DG && iz < DG ? DOCK_F[iz * DG + ix] : 0.989f;
				k++;
			}
		}
		return s / k;
	}

	private static boolean dockSolid(double x, double z) {
		int ix = Mth.floor(x) - dgx, iz = Mth.floor(z) - dgz;
		return ix >= 0 && iz >= 0 && ix < DG && iz < DG && DOCK_S[iz * DG + ix];
	}

	private static double[] dockPlan(double x0, double z0, double vx0, double vz0, double yaw0, double turn0, PitLane l) {
		dockFloor(l, DG); // whatever is left of it
		return dockPlanOn(x0, z0, vx0, vz0, yaw0, turn0, l);
	}

	/** Reads up to {@code rows} more rows of the floor around the box. */
	private static void dockFloor(PitLane l, int rows) {
		if (!dockFloorReady) {
			dockFloorReady = true;
			dockRows = 0;
			dgx = Mth.floor(l.stopX) - DG / 2;
			dgz = Mth.floor(l.stopZ) - DG / 2;
		}
		for (int r = 0; r < rows && dockRows < DG; r++, dockRows++) {
			int iz = dockRows;
			for (int ix = 0; ix < DG; ix++) {
				double cx = dgx + ix + 0.5, cz = dgz + iz + 0.5;
				DOCK_F[iz * DG + ix] = FLOOR.friction(cx, cz);
				DOCK_S[iz * DG + ix] = FLOOR.solid(cx, cz);
			}
		}
	}

	private static boolean dockFloorReady;
	private static double[] lastDock;

	private static double[] dockPlanOn(double x0, double z0, double vx0, double vz0, double yaw0, double turn0, PitLane l) {
		// first the opening move, finishing calmly; then the second move for the best few openings
		int n = 0;
		for (double o : DOCK_BOW) {
			for (double th : DOCK_THRUST) {
				DOCK_COST[n++] = dockRollout(x0, z0, vx0, vz0, yaw0, turn0, l, o, th, Double.NaN, 0, Double.MAX_VALUE)[0];
			}
		}
		double best = Double.MAX_VALUE, bestSteer = 0, bestThrust = 0;
		for (int pick = 0; pick < 4; pick++) {
			int bi = 0;
			for (int i = 1; i < n; i++) {
				if (DOCK_COST[i] < DOCK_COST[bi]) {
					bi = i;
				}
			}
			if (DOCK_COST[bi] == Double.MAX_VALUE) {
				break;
			}
			DOCK_COST[bi] = Double.MAX_VALUE;
			double o = DOCK_BOW[bi / DOCK_THRUST.length], th = DOCK_THRUST[bi % DOCK_THRUST.length];
			double[] r0 = dockRollout(x0, z0, vx0, vz0, yaw0, turn0, l, o, th, Double.NaN, 0, best);
			if (r0[0] < best) {
				best = r0[0];
				bestSteer = r0[1];
				bestThrust = th;
			}
			for (double o2 : DOCK_BOW2) {
				for (double th2 : DOCK_THRUST2) {
					double[] r = dockRollout(x0, z0, vx0, vz0, yaw0, turn0, l, o, th, o2, th2, best);
					if (r[0] < best) {
						best = r[0];
						bestSteer = r[1];
						bestThrust = th;
					}
				}
			}
		}
		return new double[] {bestSteer, bestThrust};
	}

	/**
	 * One plan for the last stretch: bow o (off the box's line) and throttle th for the first ticks, o2 and th2
	 * for the next, then a calm finish (bow along the line, S while too fast for the distance left); simulated with
	 * the boat's own physics. Returns its cost (stopping early once past {@code bound}) and its first steering.
	 */
	private static double[] dockRollout(double x, double z, double vx, double vz, double yaw, double turn, PitLane l,
			double o, double th, double o2, double th2, double bound) {
		double lineYaw = Planner.yawOf(l.stopDx, l.stopDz);
		double c = 0, first = 0;
		for (int t = 0; t < DOCK_H; t++) {
			float f = dockFriction(x, z);
			double ex = l.stopX - x, ez = l.stopZ - z;
			double along = ex * l.stopDx + ez * l.stopDz, side = ex * l.stopDz - ez * l.stopDx;
			double bowT, a;
			if (t < DOCK_OWN) {
				bowT = lineYaw + o;
				a = th;
			} else if (t < DOCK_OWN + DOCK_SECOND && !Double.isNaN(o2)) {
				bowT = lineYaw + o2;
				a = th2;
			} else {
				// where it will be across the line in half a second, not where it is: a boat drifting over the line
				// turns against the drift in time (it cannot slide sideways back)
				double sidePred = side - (vx * l.stopDz - vz * l.stopDx) * 10;
				bowT = lineYaw + Math.clamp(-sidePred * 15, -25, 25);
				double fwd = (vx * l.stopDx + vz * l.stopDz) * 20;
				a = fwd > allowedSpeed(Math.max(0, along - 0.2), f, 0) + 0.2 ? -0.005 : 0.0;
			}
			double u = Planner.turnControl(bowT, yaw, turn, f);
			if (t == 0) {
				first = u;
			}
			if (a == 0.0) {
				a = 0.005 * Math.abs(u);
			}
			turn = turn * f + u;
			yaw += turn;
			double r = Math.toRadians(yaw);
			vx = vx * f - Math.sin(r) * a;
			vz = vz * f + Math.cos(r) * a;
			x += vx;
			z += vz;
			if (dockSolid(x, z)) {
				c += 5000;
			}
			ex = l.stopX - x;
			ez = l.stopZ - z;
			along = ex * l.stopDx + ez * l.stopDz;
			side = ex * l.stopDz - ez * l.stopDx;
			double vSide = (vx * l.stopDz - vz * l.stopDx) * 20;
			double off = Math.abs(Mth.wrapDegrees(yaw - lineYaw));
			double near = along < 8 ? 2.0 : 0.3;
			c += near * side * side + (along < 6 ? 1.0 * vSide * vSide : along < 12 ? 0.5 * vSide * vSide : 0)
					+ (along < -0.2 ? 40 * along * along : 0) + (off > 35 ? 0.5 * (off - 35) * (off - 35) : 0);
			// faster than it can still stop on the spot from: seen at every step, not only when it is too late (a
			// boat that arrives too fast slides past and has to back up)
			double fwdNow = (vx * l.stopDx + vz * l.stopDz) * 20;
			double canStop = allowedSpeed(Math.max(0, along - 0.3), f, 0) + 0.3;
			if (fwdNow > canStop) {
				c += 4 * (fwdNow - canStop) * (fwdNow - canStop);
			}
			c += 0.5 * Math.max(0, along); // the sooner there the better
			// off the ice before the box (the red wool along the lane's edge, the snow): never a brake to lean on - it
			// is S that slows the car, the boundary is not to be touched (the box's own pad, the last blocks: allowed)
			if (along > 3 && f < 0.97f) {
				c += 200 * (0.97 - f);
			}
			if (c > bound) {
				return new double[] {c, first};
			}
		}
		double ex = l.stopX - x, ez = l.stopZ - z;
		double sp = Math.hypot(vx, vz) * 20;
		double along = ex * l.stopDx + ez * l.stopDz;
		c += 20 * (ex * ex + ez * ez) * (along < 3 ? 1 : 0.3) + 4 * sp * sp * (along < 2 ? 1 : 0);
		return new double[] {c, first};
	}

	/** Bow-forward stopping distance (blocks) from each speed (blocks/tick, by STEP), on the friction it was made for. */
	private static double[] stopTable;
	private static float stopTableF = -1;
	private static final double STEP = 0.005;

	/**
	 * The highest speed (blocks/s) from which a boat still stops within {@code dist} blocks with its bow forward: S
	 * held (-0.005 a tick) and the floor's drag (speed times friction every tick).
	 */
	private static double allowedSpeed(double dist, float f) {
		return allowedSpeed(dist, f, 0);
	}

	/** For the manual pit limiter: the highest speed (blocks/s) that still comes down to {@code end} within {@code dist}. */
	public static double brakeSpeed(double dist, float f, double end) {
		return allowedSpeed(Math.max(0, dist), f, end);
	}

	/** The highest speed (blocks/s) that still comes down to {@code end} (blocks/s) within {@code dist}, bow forward. */
	private static double allowedSpeed(double dist, float f, double end) {
		table(f);
		int ke = Math.min(stopTable.length - 1, (int) Math.round(end / 20 / STEP));
		// the stopping distances grow with the speed: the last one within reach, by halving (called thousands of
		// times a tick by the box planning - a scan along the table was most of its cost)
		double limit = stopTable[ke] + dist;
		int lo = ke, hi = stopTable.length - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (stopTable[mid] <= limit) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo * STEP * 20;
	}

	/** Stop tables by friction, 0.001 apart (the floor under a rollout changes friction often: built once each). */
	private static final double[][] STOP_TABLES = new double[96][];

	private static void table(float f) {
		float fr = Math.clamp(f, 0.9f, 0.995f);
		if (stopTable != null && Math.abs(fr - stopTableF) <= 0.002f) {
			return;
		}
		int q = Math.round((fr - 0.9f) * 1000);
		if (STOP_TABLES[q] == null) {
			stopTable = null;
			allowedSpeed0(0, 0.9f + q / 1000f);
			STOP_TABLES[q] = stopTable;
		}
		stopTable = STOP_TABLES[q];
		stopTableF = 0.9f + q / 1000f;
	}

	private static double allowedSpeed0(double dist, float f) {
		float fr = Math.clamp(f, 0.9f, 0.995f);
		if (stopTable == null || Math.abs(fr - stopTableF) > 0.002f) {
			stopTable = new double[400];
			for (int k = 0; k < stopTable.length; k++) {
				double v = k * STEP, d = 0;
				while (v > 0) {
					d += v;
					v = v * fr - 0.005;
				}
				stopTable[k] = d;
			}
			stopTableF = fr;
		}
		int k = 0;
		while (k + 1 < stopTable.length && stopTable[k + 1] <= dist) {
			k++;
		}
		return k * STEP * 20;
	}

	/** Development: straight into the pit lane from wherever the boat is (the planner finds its way onto it). */
	public static void devPit(Minecraft mc) {
		Track t = RaceSession.INSTANCE.track;
		PitLane l = PitLane.of(t);
		if (t == null || l == null || mc.player == null || RaceSession.boatOf(mc.player) == null) {
			LapRaceClient.LOG.warn("[pittest] no track / lane / boat");
			return;
		}
		handBack = true;
		enabled = true;
		reset();
		enterPit(t, l, false);
		LapRaceClient.LOG.info("[pittest] stop {} at {} {}", l.stopIdx, l.stopX, l.stopZ);
	}

	private static List<double[]> pointsOf(Track t) {
		if (t != track) {
			track = t;
			pts = new ArrayList<>(t.size());
			for (int i = 0; i < t.size(); i++) {
				double v = t.v[i] > 0 ? t.v[i] : LIMIT_BPS; // recorded tracks have no speeds: the cap everywhere
				pts.add(new double[] {t.x[i], t.y[i], t.z[i], v});
			}
			idx = -1;
		}
		return pts;
	}

	private static List<double[]> gridPts;
	/** The grid spot: x, z and the track's way there (dx, dz). */
	private static double[] gridAim;
	private static double gridKeyS = Double.NaN;
	private static int gridKeySide;
	private static Track gridKeyTrack;

	/**
	 * The line to a staggered grid spot: the centre line, moved over to the spot's side over its last 40 blocks. Less over
	 * where the ice is narrower. Same points as the track's (idx stays valid).
	 */
	private static List<double[]> gridLine(Track t, List<double[]> base, Level level) {
		int side = RaceStart.gridSide();
		double slot = RaceStart.gridS();
		if (side == 0) {
			return base;
		}
		if (gridPts != null && gridKeyTrack == t && gridKeySide == side && gridKeyS == slot) {
			return gridPts;
		}
		int n = base.size();
		double L = t.length;
		// how far over: the most of SIDE that still leaves the boat on ice at the spot
		int at = 0;
		double atD = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			double d = Math.abs(t.s[i] - slot);
			d = Math.min(d, L - d);
			if (d < atD) {
				atD = d;
				at = i;
			}
		}
		double[] nAt = normal(base, at);
		double over = 0;
		for (double o = RaceStart.SIDE; o >= 0.99; o -= 0.5) {
			double ex = base.get(at)[0] + nAt[0] * side * (o + 1.0), ez = base.get(at)[2] + nAt[1] * side * (o + 1.0);
			boolean ok = false;
			for (int dy = 1; dy >= -2 && !ok; dy--) {
				BlockPos bp = BlockPos.containing(ex, base.get(at)[1] - 0.5 + dy, ez);
				BlockState under = level.getBlockState(bp);
				ok = (under.is(BlockTags.ICE) || under.is(Blocks.SNOW_BLOCK) || under.is(Blocks.SNOW)) && level.getBlockState(bp.above()).getCollisionShape(level, bp.above()).isEmpty();
			}
			if (ok) {
				over = o;
				break;
			}
		}
		if (System.getenv("LAPRACE_GRIDIDX") != null) {
			StringBuilder sb = new StringBuilder();
			for (int o = -6; o <= 6; o++) {
				double ex = base.get(at)[0] + nAt[0] * o, ez = base.get(at)[2] + nAt[1] * o;
				BlockPos bp = BlockPos.containing(ex, base.get(at)[1] - 0.5, ez);
				sb.append(o).append(':').append(level.getBlockState(bp).getBlock().getDescriptionId().replace("block.minecraft.", ""))
						.append('/').append(level.getBlockState(bp.above()).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
			}
			LapRaceClient.LOG.info("grid across: n {} {} | {}", nAt[0], nAt[1], sb);
		}
		List<double[]> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			double[] q = base.get(i);
			double d = ((slot - t.s[i]) % L + L) % L; // how far before the spot
			// over early, on the throttle (on ice a boat only goes sideways while pushed): in full from 12 blocks out
			double w = d > L - 10 || d <= 12 ? 1 : d < 40 ? (40 - d) / 28 : 0;
			if (w == 0 || over == 0) {
				out.add(q);
				continue;
			}
			double[] nm = normal(base, i);
			out.add(new double[] {q[0] + nm[0] * side * over * w, q[1], q[2] + nm[1] * side * over * w, q[3]});
		}
		double[] c0 = base.get(at), c1 = base.get((at + 1) % n);
		double dl = Math.max(1e-6, Math.hypot(c1[0] - c0[0], c1[2] - c0[2]));
		gridAim = new double[] {c0[0] + nAt[0] * side * over, c0[2] + nAt[1] * side * over, (c1[0] - c0[0]) / dl, (c1[2] - c0[2]) / dl};
		gridPts = out;
		gridKeyTrack = t;
		gridKeySide = side;
		gridKeyS = slot;
		LapRaceClient.LOG.info("grid spot at s={} side {}: {} blocks over (centre {} {}, line y {}, under it {})", slot, side, over,
				String.format(java.util.Locale.ROOT, "%.1f", base.get(at)[0]), String.format(java.util.Locale.ROOT, "%.1f", base.get(at)[2]), base.get(at)[1],
				level.getBlockState(BlockPos.containing(base.get(at)[0], base.get(at)[1] - 0.5, base.get(at)[2])));
		return out;
	}

	/** The unit normal (to the left of the way the line goes) at a line point: x, z. */
	private static double[] normal(List<double[]> line, int i) {
		int n = line.size();
		double[] a = line.get(Math.floorMod(i - 1, n)), b = line.get((i + 1) % n);
		double dx = b[0] - a[0], dz = b[2] - a[2], l = Math.max(1e-6, Math.hypot(dx, dz));
		return new double[] {dz / l, -dx / l};
	}

	/** Called every tick right after vanilla reads the keyboard. */
	public static Input apply(LocalPlayer p, Input original) {
		Entity boat = RaceSession.boatOf(p);
		Analog.boat = boat;
		Minecraft mc = Minecraft.getInstance();
		Track t = RaceSession.INSTANCE.track;
		if (boat != null && t != null && mc.level != null) {
			pitTrigger(mc, boat, t);
		}
		if (!enabled) {
			Analog.active = false;
			haveYaw = false;
			return original;
		}
		if (boat == null || t == null) {
			disable(p, Component.translatable("laprace.msg.cruise_off"));
			return original;
		}
		if (mc.level == null) {
			return original;
		}
		// any steering or brake key from the driver takes over - but not in the pits: there the car is the
		// autopilot's until it is back on the track (no keys, no getting out)
		boolean locked = mode == Mode.PIT_IN || mode == Mode.PIT_STOP || mode == Mode.PIT_OUT || (mode == Mode.TRACK && RaceStart.active());
		if (!locked && (original.left() || original.right() || original.backward())) {
			disable(p, Component.translatable("laprace.msg.cruise_override"));
			return original;
		}

		boolean inPit = mode == Mode.PIT_IN || mode == Mode.PIT_STOP || mode == Mode.PIT_OUT;
		List<double[]> line = inPit ? path : mode == Mode.TRACK ? gridLine(t, pointsOf(t), mc.level) : pointsOf(t);
		Vec3 pos = boat.position();
		Vec3 vel = boat.getDeltaMovement();
		double speedBps = Math.hypot(vel.x, vel.z) * 20.0;
		float yaw = boat.getYRot();
		double turn = haveYaw ? Mth.wrapDegrees(yaw - lastYaw) : 0.0;
		if (Math.abs(turn) > 60.0) {
			turn = 0.0;
		}
		lastYaw = yaw;
		haveYaw = true;
		FLOOR.begin(mc.level, pos.x, pos.y, pos.z);
		// between the red lines the lane and the box need not be ice; the pit road outside them is ice, driven fast
		FLOOR.anySurface = inPit && (idx < 0 || lane.limited(idx - lane.pre));
		float f = FLOOR.friction(pos.x, pos.z);

		idx = nearest(line, pos, idx, speedBps > 1 ? vel.x : -Math.sin(Math.toRadians(yaw)),
				speedBps > 1 ? vel.z : Math.cos(Math.toRadians(yaw)));
		double[] here = line.get(idx);
		double off = Math.hypot(here[0] - pos.x, here[2] - pos.z);

		// ---- the modes: cool-down into the pits, the pit stop itself, stopping after the line
		if (mode == Mode.COOLDOWN) {
			Racer me = RaceSession.INSTANCE.self;
			double s = me != null ? me.s : 0;
			double done = Math.floorMod((long) Math.round(s - coolStartS), (long) Math.max(1, t.length));
			if (done > t.length * 0.5) {
				coolHalfway = true;
			}
			PitLane l = PitLane.of(t);
			if (l != null && coolHalfway) {
				double toEntry = Math.floorMod((long) Math.round(l.entryS - s), (long) Math.max(1, t.length));
				if (toEntry < 4 || toEntry > t.length - 2) {
					enterPit(t, l, true);
					return original;
				}
			} else if (l == null && coolHalfway && done < t.length * 0.1) {
				mode = Mode.STOPPING; // round the lap and over the line: stop there
			}
		}
		if (inPit) {
			pitClock++;
		}
		double dStop = lane != null ? Math.hypot(lane.stopX - pos.x, lane.stopZ - pos.z) : 1e9;
		if (mode == Mode.PIT_IN && idx >= lane.stopIdx - DOCK_FROM) {
			dockTicks++;
		}
		if (mode == Mode.PIT_IN && idx >= lane.stopIdx - 8 && !backingUp
				&& ((Math.abs(dockSide) < 1.25 && Math.abs(dockAlong) < 0.8 && speedBps < 1.5) || (dockTicks > 600 && dStop < 2))) {
			mode = Mode.PIT_STOP;
			double empty = 1 - Math.clamp(dev.laprace.Engine.fuel / dev.laprace.Engine.MAX_FUEL, 0, 1);
			// the automatic stop is a little slower than the autopilot could do it: it stands the handicap longer
			stopLeft = (int) Math.round((LapConfig.INSTANCE.pitStopSeconds + empty * 6 + PitLane.HANDICAP_S) * 20);
		}
		if (mode == Mode.PIT_STOP || mode == Mode.STOPPING) {
			double fwd = (vel.x * -Math.sin(Math.toRadians(yaw)) + vel.z * Math.cos(Math.toRadians(yaw))) * 20;
			double thrust = fwd > 0.3 ? -0.005 : fwd < -0.3 ? 0.006 : 0.0;
			double hold = 0;
			if (mode == Mode.PIT_STOP) {
				// stand on the spot, bow as it is: stop any turning, and the last bit of roll with S (or W if it
				// slides back) - never swing round
				double hx = -Math.sin(Math.toRadians(yaw)), hz = Math.cos(Math.toRadians(yaw));
				double along = (lane.stopX - pos.x) * hx + (lane.stopZ - pos.z) * hz;
				hold = Math.clamp(-turn * f, -1, 1);
				thrust = fwd > 0.3 ? -0.005 : fwd < -0.3 ? 0.006 : along > 0.6 && Math.abs(fwd) < 0.3 ? 0.006 : 0.0;
			}
			Analog.steer = (float) hold;
			Analog.accel = (float) thrust;
			Analog.active = true;
			throttle = 0;
			brake = thrust < 0 ? 1 : 0;
			targetBps = 0;
			if (mode == Mode.STOPPING) {
				if (speedBps < 0.5) {
					disable(p, Component.translatable("laprace.msg.cooldown_done"));
				}
			} else if (--stopLeft <= 0) {
				if (parkAfterPit) {
					disable(p, Component.translatable("laprace.msg.cooldown_done"));
				} else {
					mode = Mode.PIT_OUT;
					PLANNER.reset();
				}
			}
			return new Input(false, thrust < 0, false, false, false, false, false);
		}
		if (mode == Mode.PIT_OUT && idx >= lane.pre + lane.exitAt()) {
			LapRaceClient.LOG.info("pit stop on {}: {} s from taking the car to the end of the lane", t.name,
					String.format(java.util.Locale.ROOT, "%.2f", pitClock / 20.0));
			// past the snow line, back on the track: hand back to the driver at once, or carry on round
			mode = Mode.TRACK;
			path = null;
			idx = -1;
			PLANNER.reset();
			if (handBack) {
				disable(p, Component.translatable("laprace.msg.pit_out"));
				return original;
			}
			return original;
		}

		// the pit limit holds between the red lines (from where they start on the way in to where they end on
		// the way out)
		boolean limited = inPit && lane.limited(idx - lane.pre);
		// coming down the lane: the floor around the box, a few rows a tick, for the last stretch (with the floor
		// rules of the box: any surface)
		if (mode == Mode.PIT_IN && idx < lane.stopIdx - PLAN_FROM) {
			boolean any = FLOOR.anySurface;
			FLOOR.anySurface = true;
			dockFloor(lane, 12);
			FLOOR.anySurface = any;
		}
		// outside the red lines the pit road is driven as fast as it goes (the lane's speeds hold it back)
		double cap = limited ? Math.min(CAP, pitBps()) : inPit ? PitLane.FAST_BPS : CAP;
		if (mode == Mode.TRACK && RaceStart.active()) {
			// the race's start: up to the grid spot, or in the rolling start's line; standing on the spot it holds
			if (RaceStart.hold()) {
				double hx = -Math.sin(Math.toRadians(yaw)), hz = Math.cos(Math.toRadians(yaw));
				double fwd = (vel.x * hx + vel.z * hz) * 20;
				double th = fwd > 0.3 ? -0.005 : fwd < -0.3 ? 0.006 : 0.0;
				Analog.steer = (float) (gridAim != null && RaceStart.gridSide() != 0
						? Planner.turnControl(Planner.yawOf(gridAim[2], gridAim[3]), yaw, turn, f) : Math.clamp(-turn * f, -1, 1));
				Analog.accel = (float) th;
				Analog.active = true;
				throttle = 0;
				brake = th < 0 ? 1 : 0;
				targetBps = 0;
				return new Input(false, th < 0, false, false, false, false, false);
			}
			cap = Math.min(cap, RaceStart.cap(f));
		}
		if (mode == Mode.PIT_IN && idx < lane.stopIdx - PLAN_FROM) {
			// fast down the lane, but never faster than it can still come down to the last stretch's speed by its start
			// (a near box, the first ones: else it arrives too fast, slides over to the lane's edge and brakes on it)
			cap = Math.min(cap, allowedSpeed(Math.max(0, lane.stopIdx - PLAN_FROM - idx - 12), f, PLAN_BPS));
		}
		Planner.W_CHANGE = LapConfig.INSTANCE.cruiseSteeringCalm;
		// on the last stretch into the box the box planning decides everything: the general planner is not needed
		boolean docking = mode == Mode.PIT_IN && !backingUp && idx >= lane.stopIdx - PLAN_FROM;
		double steer = 0, thrust = 0;
		if (!docking) {
			PLANNER.plan(line, idx, pos.x, pos.z, vel.x, vel.z, yaw, turn, FLOOR, cap);
			steer = PLANNER.steer;
			thrust = PLANNER.thrust;
		}
		if (mode == Mode.TRACK && RaceStart.gridSide() != 0 && gridAim != null && !RaceStart.hold()) {
			// the last stretch to the grid spot: straight at it (as into a box), then along the track on it
			double ax = gridAim[0] - pos.x, az = gridAim[1] - pos.z;
			double along = ax * gridAim[2] + az * gridAim[3];
			// (the planner would brake by turning the bow across - on ice that slides it off the spot sideways)
			if (along < 16 && along > -6) {
				// at a point on the spot's lane well ahead: the bow stays near the track's way (braking backs it up along
				// the lane, not across it) while it eases over
				// far: over to the spot's lane on the throttle (on ice the boat goes where it is pushed, so no backing
				// up while the bow points across); near: along the lane, braking along it
				double want = Planner.yawOf(gridAim[2], gridAim[3]);
				steer = Planner.turnControl(want, yaw, turn, f);
				thrust = speedBps > cap + 0.3 ? -0.005 : speedBps < cap - 0.6 ? Planner.THRUST_MAX * 0.6 : 0;
				if (System.getenv("LAPRACE_GRIDIDX") != null && mc.player.tickCount % 5 == 0) {
					LapRaceClient.LOG.info("grid aim: pos {} {} along {} want {} yaw {} steer {} v {}", String.format("%.1f", pos.x), String.format("%.1f", pos.z),
							String.format("%.1f", along), String.format("%.0f", want), String.format("%.0f", yaw), String.format("%.2f", steer), String.format("%.1f", speedBps));
				}
			}
		}
		targetBps = Math.min(here[3], cap);

		// the box, nose first like a car: at speed until the last moment the boat can still stop on the spot with its
		// bow forward (S and the ice's drag, worked out tick by tick), lined up on the box's line for the last
		// stretch, then straight onto it; if it ends up beside the spot, back up a little and pull in again
		if (mode == Mode.PIT_IN && idx >= lane.stopIdx - DOCK_FROM) {
			double ex = lane.stopX - pos.x, ez = lane.stopZ - pos.z;
			double along = ex * lane.stopDx + ez * lane.stopDz; // + still ahead along the box's line
			double side = ex * lane.stopDz - ez * lane.stopDx; // off the line, sideways
			double hx = -Math.sin(Math.toRadians(yaw)), hz = Math.cos(Math.toRadians(yaw));
			double fwd = (vel.x * hx + vel.z * hz) * 20;
			double lineYaw = Planner.yawOf(lane.stopDx, lane.stopDz);
			if (along < 1.5 && Math.abs(side) > 1.25 && speedBps < 0.8) {
				backingUp = true; // beside the spot: back along the line and come in again
			}
			if (backingUp && (along > 5 || Math.abs(side) < 0.3)) {
				backingUp = false;
			}
			dockAlong = along;
			dockSide = side;
			double want;
			double vd;
			if (backingUp) {
				// reversing: bow kept along the line, the stern steered so the bow points at the line
				want = lineYaw + Math.clamp(side * 12, -20, 20);
				vd = -2.0;
				steer = Planner.turnControl(want, yaw, turn, f);
				thrust = fwd > vd + 0.2 ? -0.005 : 0.0;
			} else {
				// straight in the lane down to the move-over speed, over into the box's line at it, then onto the spot
				if (idx < lane.stopIdx - PLAN_FROM) {
					vd = Math.min(cap, allowedSpeed(lane.stopIdx - PLAN_FROM - idx, f, PLAN_BPS));
				} else if (idx < lane.shiftFrom) {
					vd = Math.min(cap, allowedSpeed(lane.shiftFrom - idx, f, SHIFT_BPS));
				} else if (idx < lane.lineFrom) {
					vd = SHIFT_BPS;
				} else {
					vd = Math.min(SHIFT_BPS, allowedSpeed(Math.max(0, along - 0.25), f, 0));
				}
				// and never faster than it can still stop on the spot from (bow forward, S and drag)
				double toSpot = idx < lane.lineFrom ? Math.max(along, lane.stopIdx - idx) : along;
				vd = Math.min(vd, allowedSpeed(Math.max(0, toSpot - 0.4), f, 0));
				if (along < -0.3) {
					vd = -1.0;
				} else if (Math.abs(along) < 0.3 && idx >= lane.lineFrom) {
					vd = 0;
				}
				// the line to hold: the path in the lane, the box's line from where the straight onto the spot starts
				double rx, rz, dx, dz;
				if (idx < lane.lineFrom) {
					double[] r0 = line.get(Math.min(line.size() - 1, idx + 1)), r1 = line.get(Math.min(line.size() - 1, idx + 4));
					double dl = Math.max(1e-6, Math.hypot(r1[0] - r0[0], r1[2] - r0[2]));
					rx = r0[0];
					rz = r0[2];
					dx = (r1[0] - r0[0]) / dl;
					dz = (r1[2] - r0[2]) / dl;
				} else {
					rx = lane.stopX;
					rz = lane.stopZ;
					dx = lane.stopDx;
					dz = lane.stopDz;
				}
				double toLine = (rx - pos.x) * dz - (rz - pos.z) * dx; // how far the line is, along (dz, -dx)
				// across to the line no faster than the boat can still stop crossing it (about 0.8 blocks/s^2 sideways
				// once it brakes): it must not shoot past the line
				double gain = idx >= lane.lineFrom ? 1.0 : 1.2;
				double vLatMax = Math.min(1 + 0.5 * Math.abs(vd), Math.sqrt(2 * 0.8 * Math.abs(toLine)));
				double vLat = Math.clamp(toLine * gain, -vLatMax, vLatMax);
				double wx = (dx * vd + dz * vLat) / 20, wz = (dz * vd - dx * vLat) / 20; // wanted velocity, blocks/tick
				double ax = (wx - vel.x) * 0.3, az = (wz - vel.z) * 0.3;
				double am = Math.hypot(ax, az);
				double lineHere = Planner.yawOf(dx, dz);
				boolean push = ax * dx + az * dz >= 0;
				// turning in: sideways the boat only moves with W, so the turn-in wins over a little braking
				double aLat = ax * dz - az * dx;
				if (!push && idx >= lane.shiftFrom && idx < lane.lineFrom && Math.abs(aLat) > Math.abs(ax * dx + az * dz)) {
					push = true;
					ax = aLat * dz * 0.8 + dx * 0.0005;
					az = -aLat * dx * 0.8 + dz * 0.0005;
					am = Math.hypot(ax, az);
				}
				// W pushes along the bow, S against it: the bow goes along the push wanted (against it for S),
				// never more than 35 degrees off the line - so it never turns round
				double bow = am < 0.0008 ? lineHere : push ? Planner.yawOf(ax, az) : Planner.yawOf(-ax, -az);
				// turning in to the box at walking pace the bow may point well into it; at speed it stays near the line
				double maxOff = idx >= lane.shiftFrom && idx < lane.lineFrom ? 50 : idx >= lane.lineFrom ? 30 : 20;
				bow = lineHere + Math.clamp(Mth.wrapDegrees(bow - lineHere), -maxOff, maxOff);
				want = bow;
				// before the move over to the box's line the general planner drives (it knows the lane's edges and gets
				// out of a slide from the bends; this simple hold of the line did not, and slid onto the edge); its top
				// speed is held down to what still comes down to the last stretch's speed in time (cap above)
				if (idx >= lane.shiftFrom) {
					steer = Planner.turnControl(bow, yaw, turn, f);
					double err = Math.abs(Mth.wrapDegrees(bow - yaw));
					if (am < 0.0008 || err > 35) {
						thrust = 0.0;
					} else if (push) {
						thrust = Math.min(Planner.THRUST_MAX, am * Math.cos(Math.toRadians(err)) + 0.002);
					} else {
						thrust = am > 0.002 ? -0.005 : 0.0;
					}
				}
			}
			// the last stretch (turning in and onto the spot): plan it - try bow angles and throttles, simulate the
			// boat 2 s ahead on the real floor, keep what ends standing on the spot, nose roughly forward
			if (!backingUp && idx >= lane.stopIdx - PLAN_FROM) {
				long t0 = System.nanoTime();
				// planned every other tick (a tenth of a second); in between its steering and throttle are held
				double[] u = lastDock != null && (dockTicks & 1) == 1 ? lastDock : dockPlan(pos.x, pos.z, vel.x, vel.z, yaw, turn, lane);
				lastDock = u;
				double ms = (System.nanoTime() - t0) / 1e6;
				dockMaxMs = Math.max(dockMaxMs, ms);
				dockSumMs += ms;
				dockN++;
				steer = u[0];
				thrust = u[1];
				// the last few blocks: the bow held along the box's line, whatever the plan's steering (it would
				// trade the nose for the last bit of position and stop it turned 15-30 degrees off)
				if (along < 4 && Math.abs(side) < 1.5) {
					// a little onto the box's line (aimed 4 blocks past the spot, at most 10 degrees off), less and less
					// towards the spot itself, where the bow stands straight along the line
					double tx = lane.stopX + lane.stopDx * 4 - pos.x, tz = lane.stopZ + lane.stopDz * 4 - pos.z;
					double fade = Math.clamp((along - 0.5) / 2.5, 0, 1);
					double aim = lineYaw + fade * Math.clamp(Mth.wrapDegrees(Planner.yawOf(tx, tz) - lineYaw), -10, 10);
					steer = Planner.turnControl(aim, yaw, turn, f);
				}
			}
			targetBps = Math.abs(vd);
		} else if (mode == Mode.PIT_OUT && idx < lane.stopIdx + LAUNCH_TO) {
			double[] aim = line.get(Math.min(line.size() - 1, idx + 5));
			double want = Planner.yawOf(aim[0] - pos.x, aim[2] - pos.z);
			steer = Planner.turnControl(want, yaw, turn, f);
			thrust = Math.abs(Mth.wrapDegrees(want - yaw)) < 35 ? Planner.THRUST_MAX : 0.01;
			targetBps = cap;
		}
		// off the ice and away from the line: drive back to the nearest ice (not in the pits: they need not be ice)
		boolean onIce = mc.level.getBlockState(BlockPos.containing(pos.x, pos.y - 0.5, pos.z)).is(BlockTags.ICE);
		offIceTicks = onIce || inPit ? 0 : offIceTicks + 1;
		recovering = false;
		if (offIceTicks >= OFF_ICE_TICKS && off > RECOVER_MIN_OFF) {
			if (iceTarget == null || iceRefresh-- <= 0) {
				iceTarget = findNearestIce(mc.level, pos, ICE_SEARCH_RADIUS);
				iceRefresh = 10;
			}
			if (iceTarget != null) {
				double want = Planner.yawOf(iceTarget[0] - pos.x, iceTarget[2] - pos.z);
				steer = Planner.turnControl(want, yaw, turn, f);
				boolean facing = Math.abs(Mth.wrapDegrees(want - yaw)) < 30.0;
				thrust = (facing && speedBps < RECOVER_SPEED) ? Planner.THRUST_MAX : 0.0;
				targetBps = RECOVER_SPEED;
				recovering = true;
				PLANNER.reset();
			}
		} else {
			iceTarget = null;
		}
		// never over the cap (80 km/h, or the pit limit in the pits)
		if (speedBps > (limited ? Math.min(LIMIT_BPS, pitBps()) : inPit ? PitLane.FAST_BPS : LIMIT_BPS) && thrust > 0) {
			thrust = 0.0;
		}
		// the race's start: over its speed (the grid spot coming, the car ahead too close) - brake, do not just roll
		if (mode == Mode.TRACK && RaceStart.active() && speedBps > cap + 0.3) {
			thrust = -0.005;
		}
		throttle = Math.max(0, thrust) / Planner.THRUST_MAX;
		brake = thrust < 0 ? 1 : 0;

		if (LapConfig.INSTANCE.cruiseCameraFollowsTravel) {
			float look = speedBps > 3.0 ? (float) Planner.yawOf(vel.x, vel.z) : yaw;
			p.setYRot(Mth.rotLerp(CAMERA_SMOOTHING, p.getYRot(), look));
		}

		Analog.steer = (float) steer;
		Analog.accel = (float) thrust;
		Analog.active = true;

		// fallback keys (only if the boat hook did not load): the same values, pulsed
		steerAcc += steer;
		boolean right = steerAcc >= 0.5;
		boolean left = steerAcc <= -0.5;
		if (right) {
			steerAcc -= 1.0;
		}
		if (left) {
			steerAcc += 1.0;
		}
		throttleAcc += Math.max(thrust, 0.0) / Planner.THRUST_MAX;
		boolean forward = throttleAcc >= 1.0;
		if (forward) {
			throttleAcc -= 1.0;
		}
		return new Input(forward, thrust < 0.0, left, right, false, false, false);
	}

	/** Nearest line point: near the last one, or (first time / lost) over the whole line going our way. */
	private static int nearest(List<double[]> line, Vec3 pos, int from, double hx, double hz) {
		int n = line.size();
		int best = -1;
		double bestD = Double.MAX_VALUE;
		if (from >= 0) {
			for (int k = -5; k <= 80; k++) {
				int i = Math.floorMod(from + k, n);
				double[] q = line.get(i);
				double d = sq(q[0] - pos.x) + sq(q[2] - pos.z);
				if (d < bestD) {
					bestD = d;
					best = i;
				}
			}
			if (bestD < 12 * 12) {
				return best;
			}
		}
		best = 0;
		bestD = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			double[] q = line.get(i);
			double[] r = line.get((i + 1) % n);
			double d = sq(q[0] - pos.x) + sq(q[2] - pos.z);
			if ((r[0] - q[0]) * hx + (r[2] - q[2]) * hz < 0) {
				d += 900.0; // the wrong way: only if nothing else is within 30 blocks
			}
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		PLANNER.reset();
		return best;
	}

	private static double[] findNearestIce(Level level, Vec3 pos, int maxR) {
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		int cx = Mth.floor(pos.x), cy = Mth.floor(pos.y), cz = Mth.floor(pos.z);
		double bestD = Double.MAX_VALUE;
		double[] best = null;
		int foundRing = -1;
		for (int r = 1; r <= maxR; r++) {
			if (best != null && r > foundRing + 1) {
				break;
			}
			for (int dx = -r; dx <= r; dx++) {
				int step = Math.abs(dx) == r ? 1 : 2 * r;
				for (int dz = -r; dz <= r; dz += step) {
					int x = cx + dx, z = cz + dz;
					for (int y = cy - 3; y <= cy + 1; y++) {
						mp.set(x, y, z);
						if (!level.getBlockState(mp).is(BlockTags.ICE)) {
							continue;
						}
						mp.set(x, y + 1, z);
						if (!level.getBlockState(mp).getCollisionShape(level, mp).isEmpty()) {
							continue;
						}
						double d = sq(x + 0.5 - pos.x) + sq(z + 0.5 - pos.z);
						if (d < bestD) {
							bestD = d;
							best = new double[] {x + 0.5, y + 1.0, z + 0.5};
							if (foundRing < 0) {
								foundRing = r;
							}
						}
						break;
					}
				}
			}
		}
		return best;
	}

	private static double sq(double v) {
		return v * v;
	}

	/**
	 * Floor for the planner, read from the world. Cached across ticks (blocks of a track do not change while you
	 * drive it): forgotten only when the level or the boat's height changes, or every 30 s, and kept in primitive
	 * maps, since the planner asks tens of thousands of times a tick.
	 */
	static final class GameFloor implements Planner.Floor {
		private Level level;
		private int y = Integer.MIN_VALUE;
		private long age;
		private final LongFloatMap cache = new LongFloatMap(8192);
		private final LongFloatMap solidCache = new LongFloatMap(8192);
		private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		/** In the pits: any floor without a wall on it is drivable (the lane and the box need not be ice). */
		boolean anySurface;
		/**
		 * A window of plain arrays around the boat on top of the maps: the planner's ~10^5 questions a tick become
		 * array reads. Filled lazily from the maps, moved when the boat nears its edge.
		 */
		private static final int GRID = 160;
		private final float[] gFloor = new float[GRID * GRID];
		private final byte[] gSolid = new byte[GRID * GRID]; // 0 unknown, 1 free, 2 solid
		private int gx0 = Integer.MIN_VALUE, gz0 = Integer.MIN_VALUE;

		void begin(Level level, double boatX, double boatY, double boatZ) {
			int ny = Mth.floor(boatY + 0.01);
			boolean reset = level != this.level || ny != y || ++age > 600 || cache.size() > 200_000;
			if (reset) {
				cache.clear();
				solidCache.clear();
				age = 0;
			}
			this.level = level;
			this.y = ny;
			int bx = Mth.floor(boatX), bz = Mth.floor(boatZ);
			int half = GRID / 2;
			if (reset || Math.abs(bx - (gx0 + half)) > half - 60 || Math.abs(bz - (gz0 + half)) > half - 60) {
				gx0 = bx - half;
				gz0 = bz - half;
				java.util.Arrays.fill(gFloor, Float.NaN);
				java.util.Arrays.fill(gSolid, (byte) 0);
			}
		}

		private float lookup(double x, double z) {
			int bx = Mth.floor(x), bz = Mth.floor(z);
			int ix = bx - gx0, iz = bz - gz0;
			if (ix >= 0 && iz >= 0 && ix < GRID && iz < GRID) {
				int i = iz * GRID + ix;
				float v = gFloor[i];
				if (Float.isNaN(v)) {
					v = slowLookup(bx, bz);
					gFloor[i] = v;
				}
				return v;
			}
			return slowLookup(bx, bz);
		}

		private float slowLookup(int bx, int bz) {
			long key = ((long) bx << 32) ^ (bz & 0xffffffffL);
			float v = cache.get(key);
			if (Float.isNaN(v)) {
				BlockPos at = new BlockPos(bx, y, bz);
				BlockPos below = at.below();
				BlockState floor = level.getBlockState(below);
				BlockState body = level.getBlockState(at);
				float fr = floor.isAir() ? 0.9f : floor.is(Blocks.SNOW) ? 0.98f : floor.getBlock().getFriction();
				// track = blue or packed ice (snow markings and patterns set into the ice count too); plain ice is
				// run-off; a pressure plate marks the boundary and is still in
				boolean plate = body.getBlock() instanceof BasePressurePlateBlock;
				boolean ok = plate || (body.getCollisionShape(level, at).isEmpty()
						&& (isTrackIce(floor) || floor.is(Blocks.SNOW)
								|| (!floor.isAir() && !floor.is(Blocks.ICE) && enclosed(below))));
				v = ok ? fr : -fr;
				cache.put(key, v);
			}
			return v;
		}

		private boolean enclosed(BlockPos p) {
			int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
			for (int[] d : dirs) {
				boolean found = false;
				for (int k = 1; k <= 3 && !found; k++) {
					found = isTrackIce(level.getBlockState(p.offset(d[0] * k, 0, d[1] * k)));
				}
				if (!found) {
					return false;
				}
			}
			return true;
		}

		private static boolean isTrackIce(BlockState s) {
			return s.is(Blocks.BLUE_ICE) || s.is(Blocks.PACKED_ICE);
		}

		@Override
		public float friction(double x, double z) {
			double h = 0.6875;
			float s = 0;
			int k = 0;
			for (int bx = Mth.floor(x - h); bx <= Mth.floor(x + h); bx++) {
				for (int bz = Mth.floor(z - h); bz <= Mth.floor(z + h); bz++) {
					s += Math.abs(lookup(bx + 0.5, bz + 0.5));
					k++;
				}
			}
			return s / k;
		}

		@Override
		public boolean drivable(double x, double z) {
			if (anySurface) {
				return !solid(x, z);
			}
			return lookup(x, z) > 0;
		}

		@Override
		public boolean solid(double x, double z) {
			int bx = Mth.floor(x), bz = Mth.floor(z);
			int ix = bx - gx0, iz = bz - gz0;
			if (ix >= 0 && iz >= 0 && ix < GRID && iz < GRID) {
				int i = iz * GRID + ix;
				byte b = gSolid[i];
				if (b == 0) {
					b = slowSolid(bx, bz) ? (byte) 2 : (byte) 1;
					gSolid[i] = b;
				}
				return b == 2;
			}
			return slowSolid(bx, bz);
		}

		private boolean slowSolid(int bx, int bz) {
			long key = ((long) bx << 32) ^ (bz & 0xffffffffL);
			float v = solidCache.get(key);
			if (Float.isNaN(v)) {
				mp.set(bx, y, bz);
				BlockState body = level.getBlockState(mp);
				boolean solid = !body.getCollisionShape(level, mp).isEmpty() && !(body.getBlock() instanceof BasePressurePlateBlock);
				v = solid ? 1f : 0f;
				solidCache.put(key, v);
			}
			return v > 0.5f;
		}
	}
}
