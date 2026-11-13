package dev.laprace.cruise;

import java.util.List;

/**
 * Model-predictive driver for a vanilla boat, speed-limited mode only (holds a cap such as 80 km/h with partial
 * throttle, brakes with the bow turned back). No Minecraft classes: the floor comes in through {@link Floor}.
 *
 * Vanilla boat on a floor with friction f, per tick:
 *   turn  = turn * f + steer          (steer: A = -1, D = +1; here anything in between)
 *   yaw  += turn
 *   vel   = vel * f + thrust * bow    (thrust: W = 0.04, S = -0.005, turning alone = 0.005)
 *   pos  += vel
 * Every tick it tries a set of plans ("hold the bow at this angle off the travel, with this much throttle" for the
 * first ticks, then a line follower), simulates each 2 s ahead and keeps the cheapest: distance from the line,
 * velocity off the line's, speed off the target, leaving the ice, hitting walls, changing the plan.
 */
public final class Planner {
	public interface Floor {
		/** Friction of the floor at (x, z), as the boat sees it. */
		float friction(double x, double z);

		/** False on grass and other floors the boat should never be on. */
		boolean drivable(double x, double z);

		/** True where something solid stands at boat level (walls, fences, glass). */
		boolean solid(double x, double z);
	}

	// ---- tuning (the speed-limited set) -------------------------------
	static final double[] OFFSETS = {-80, -60, -45, -32, -20, -10, -4, 0, 4, 10, 20, 32, 45, 60, 80};
	/** Bow turned back: thrust brakes (8x the S key). */
	static final double[] BRAKE_OFFSETS = {-120, 120, -150, 150};
	public static final double THRUST_MAX = 0.04; // vanilla W
	/** Throttle levels; the in-between ones hold 80 km/h on blue ice (~0.012) without sawing. */
	static final double[] THRUSTS = {THRUST_MAX, 0.03, 0.02, 0.012, 0.006, 0.0, -0.005};
	static final int H_OWN = 14;
	public static final int H = 40;
	/** Offsets whose every throttle level is tried (second stage of the search). */
	static final int TOP_OFFSETS = 4;
	static final double W_OFF = 1.0;
	static final double W_VEL = 0.3;
	static final double W_OVER = 2.0;
	static final double W_UNDER = 3.0;
	static final double W_ICE = 1000.0;
	static final double W_EDGE = 300.0;
	static final double W_WALL = 20000.0;
	public static final double HALF_WIDTH = 0.7;
	static final double SEGMENT_STEP = 0.5;
	static final double SOLID_MARGIN = 0.4;
	static final double START_SPEED = 3.0;
	static final double REJOIN_SPEED = 10.0;
	static final double W_OUTSIDE = 0.01;
	static final double W_SPIN = 300.0;
	static final double W_INSIDE = 10.0;
	static final double FOLLOW_LOOK = 4.0;
	static final double TURN_MAX = 12.0;
	static final double TURN_BRAKE = 0.7;
	static final double TURN_GAIN = 0.35;
	static final double FOLLOW_GAIN = 2.0;
	static final double FOLLOW_MAX = 70.0;
	/** Per step of plan change from last tick (settings: steering calm). */
	public static double W_CHANGE = 10.0;

	// ---- result -------------------------------------------------------
	public double steer;
	public double thrust;

	private int prevO = -1;
	private int prevT = -1;
	private double firstSteer;

	public void reset() {
		prevO = -1;
		prevT = -1;
	}

	/**
	 * @param pts      line points {x, y, z, speed blocks/s}, about 1 block apart, closed loop
	 * @param idx      nearest line point to the boat
	 * @param turn     the boat's current turn rate, deg/tick
	 * @param speedCap blocks/s
	 */
	public void plan(List<double[]> pts, int idx, double x, double z, double vx, double vz,
			double yaw, double turn, Floor floor, double speedCap) {
		double sp0 = Math.hypot(vx, vz);
		double[] ahead = pts.get(step(idx, 8, pts.size()));
		double toLine = yawOf(ahead[0] - x, ahead[2] - z);
		boolean lost = sp0 < REJOIN_SPEED / 20.0 && Math.abs(wrap(yawOf(vx, vz) - toLine)) > 60.0;
		if (sp0 < START_SPEED / 20.0 || lost) {
			// moving off, or slow and going the wrong way: point the bow at the line ahead, then go
			double[] q = pts.get(step(idx, 6, pts.size()));
			double want = yawOf(q[0] - x, q[2] - z);
			steer = turnControl(want, yaw, turn, floor.friction(x, z));
			thrust = Math.abs(wrap(want - yaw)) < 30.0 ? THRUST_MAX : 0.0;
			prevO = -1;
			return;
		}
		double best = Double.MAX_VALUE;
		int bestO = 7;
		int bestT = 2;
		double bestSteer = 0;
		int nOff = OFFSETS.length + BRAKE_OFFSETS.length;
		// two stages instead of every offset x every throttle (109 rollouts): all offsets at the throttle used last
		// tick, then every throttle for the few best offsets (~50 rollouts; the throttle rarely changes the best
		// offset, so the choice is the same almost always)
		int t0 = prevT >= 0 ? prevT : 2;
		double[] stage = new double[nOff];
		for (int o = 0; o < nOff; o++) {
			double offset = o < OFFSETS.length ? OFFSETS[o] : BRAKE_OFFSETS[o - OFFSETS.length];
			int t = o >= OFFSETS.length ? 0 : t0; // a turned-back bow only makes sense with full thrust
			double c = rollout(pts, idx, x, z, vx, vz, yaw, turn, floor, speedCap, offset, THRUSTS[t]);
			if (prevO >= 0) {
				c += W_CHANGE * (Math.abs(o - prevO) + 2 * Math.abs(t - prevT));
			}
			stage[o] = c;
			if (c < best) {
				best = c;
				bestO = o;
				bestT = t;
				bestSteer = firstSteer;
			}
		}
		for (int pick = 0; pick < TOP_OFFSETS; pick++) {
			int o = -1;
			for (int i = 0; i < OFFSETS.length; i++) {
				if (stage[i] < Double.MAX_VALUE && (o < 0 || stage[i] < stage[o])) {
					o = i;
				}
			}
			if (o < 0) {
				break;
			}
			stage[o] = Double.MAX_VALUE;
			for (int t = 0; t < THRUSTS.length; t++) {
				if (t == t0) {
					continue;
				}
				double c = rollout(pts, idx, x, z, vx, vz, yaw, turn, floor, speedCap, OFFSETS[o], THRUSTS[t]);
				if (prevO >= 0) {
					c += W_CHANGE * (Math.abs(o - prevO) + 2 * Math.abs(t - prevT));
				}
				if (c < best) {
					best = c;
					bestO = o;
					bestT = t;
					bestSteer = firstSteer;
				}
			}
		}
		prevO = bestO;
		prevT = bestT;
		steer = bestSteer;
		thrust = THRUSTS[bestT];
	}

	private double rollout(List<double[]> pts, int idx, double x, double z, double vx, double vz,
			double yaw, double turn, Floor floor, double speedCap, double offset, double thr) {
		int n = pts.size();
		int j = idx;
		double c = 0;
		firstSteer = 0;
		boolean touching = solidHull(floor, x, z, HALF_WIDTH);
		double rel = Math.hypot(vx, vz) > 0.15 ? wrap(yaw - yawOf(vx, vz)) : 0.0;
		double lastTravel = yawOf(vx, vz);
		for (int t = 0; t < H; t++) {
			float f = floor.friction(x, z);
			double sp = Math.hypot(vx, vz);
			double vref = Math.min(pts.get(j)[3], speedCap) / 20.0;
			double bow;
			double a;
			if (t < H_OWN) {
				bow = heading(pts, j, x, z, vx, vz, sp) + offset;
				a = thr;
			} else {
				double head = heading(pts, j, x, z, vx, vz, sp);
				int k = step(j, 3 + (int) (sp * FOLLOW_LOOK), n);
				double[] q = pts.get(k);
				double want = yawOf(q[0] - x, q[2] - z);
				bow = head + clamp(FOLLOW_GAIN * wrap(want - head), -FOLLOW_MAX, FOLLOW_MAX);
				a = sp < vref ? 0.04 : 0.0;
			}
			double u = turnControl(bow, yaw, turn, f);
			if (t == 0) {
				firstSteer = u;
			}
			if (a == 0.0) {
				a = 0.005 * Math.abs(u);
			}
			turn = turn * f + u;
			yaw += turn;
			rel += turn;
			double r = Math.toRadians(yaw);
			vx = vx * f - Math.sin(r) * a;
			vz = vz * f + Math.cos(r) * a;
			int steps = 1 + (int) (Math.hypot(vx, vz) / SEGMENT_STEP);
			if (sweepHits(floor, x, z, vx, vz, touching ? HALF_WIDTH - 0.15 : HALF_WIDTH + SOLID_MARGIN)) {
				c += W_WALL;
			}
			for (int k = 1; k < steps; k++) {
				if (!floor.drivable(x + vx * k / steps, z + vz * k / steps)) {
					c += W_ICE;
				}
			}
			x += vx;
			z += vz;
			if (Math.hypot(vx, vz) > 0.15) {
				double travel = yawOf(vx, vz);
				rel -= wrap(travel - lastTravel);
				lastTravel = travel;
				if (Math.abs(rel) > 185.0) {
					c += W_SPIN;
				}
			}

			double[] p = pts.get(j);
			double d2 = sq(p[0] - x) + sq(p[2] - z);
			for (int guard = 0; guard < 8; guard++) {
				int jn = step(j, 1, n);
				double[] pn = pts.get(jn);
				double dn = sq(pn[0] - x) + sq(pn[2] - z);
				if (dn > d2) {
					break;
				}
				j = jn;
				d2 = dn;
			}
			double[] a2 = pts.get(step(j, -2, n));
			double[] b2 = pts.get(step(j, 2, n));
			double lx = b2[0] - a2[0];
			double lz = b2[2] - a2[2];
			double ll = Math.max(1e-9, Math.hypot(lx, lz));
			sp = Math.hypot(vx, vz);
			vref = Math.min(pts.get(j)[3], speedCap) / 20.0;
			double vr = Math.min(sp, vref);
			double ev = sp - vref;
			double[] pa = pts.get(step(j, -4, n));
			double[] pb = pts.get(step(j, 4, n));
			double bend = wrap(yawOf(pb[0] - p[0], pb[2] - p[2]) - yawOf(p[0] - pa[0], p[2] - pa[2]));
			double bendWeight = Math.min(1.0, Math.abs(bend) / 10.0);
			if (Math.abs(bend) > 1.0) {
				double mx = (pa[0] + pb[0]) / 2 - p[0];
				double mz = (pa[2] + pb[2]) / 2 - p[2];
				double ml = Math.hypot(mx, mz);
				if (ml > 1e-6) {
					double inside = ((x - p[0]) * mx + (z - p[2]) * mz) / ml;
					if (inside > 0) {
						c += W_INSIDE * inside * inside * bendWeight;
					}
				}
				if (sp > 0.15) {
					double out = -Math.signum(bend) * wrap(yaw - yawOf(vx, vz));
					if (out > 0) {
						c += W_OUTSIDE * out * out * bendWeight;
					}
				}
			}
			c += W_OFF * d2
					+ W_VEL * 400 * (sq(vx - vr * lx / ll) + sq(vz - vr * lz / ll))
					+ 400 * (ev > 0 ? W_OVER * ev * ev : W_UNDER * ev * ev);
			if (!floor.drivable(x, z)) {
				c += W_ICE;
			} else {
				for (int k = 0; k < 4; k++) {
					if (!floor.drivable(x + ((k & 1) == 0 ? -HALF_WIDTH : HALF_WIDTH),
							z + ((k & 2) == 0 ? -HALF_WIDTH : HALF_WIDTH))) {
						c += W_EDGE;
					}
				}
			}
		}
		return c;
	}

	/** Does a tick's move hit something solid, swept like vanilla (larger axis first, then the other)? */
	static boolean sweepHits(Floor floor, double x, double z, double vx, double vz, double h) {
		boolean zFirst = Math.abs(vx) < Math.abs(vz);
		double a1 = zFirst ? vz : vx, a2 = zFirst ? vx : vz;
		int s1 = 1 + (int) (Math.abs(a1) / 0.25), s2 = 1 + (int) (Math.abs(a2) / 0.25);
		for (int k = 1; k <= s1; k++) {
			double d = a1 * k / s1;
			if (zFirst ? solidHull(floor, x, z + d, h) : solidHull(floor, x + d, z, h)) {
				return true;
			}
		}
		double bx = zFirst ? x : x + a1, bz = zFirst ? z + a1 : z;
		for (int k = 1; k <= s2; k++) {
			double d = a2 * k / s2;
			if (zFirst ? solidHull(floor, bx + d, bz, h) : solidHull(floor, bx, bz + d, h)) {
				return true;
			}
		}
		return false;
	}

	static boolean solidHull(Floor floor, double x, double z, double h) {
		int x0 = (int) Math.floor(x - h);
		int x1 = (int) Math.floor(x + h);
		int z0 = (int) Math.floor(z - h);
		int z1 = (int) Math.floor(z + h);
		for (int bx = x0; bx <= x1; bx++) {
			for (int bz = z0; bz <= z1; bz++) {
				if (floor.solid(bx + 0.5, bz + 0.5)) {
					return true;
				}
			}
		}
		return false;
	}

	private static double heading(List<double[]> pts, int j, double x, double z, double vx, double vz, double sp) {
		if (sp > 0.15) {
			return yawOf(vx, vz);
		}
		double[] q = pts.get(step(j, 6, pts.size()));
		return yawOf(q[0] - x, q[2] - z);
	}

	/** Steering (-1..1) that brings the bow to {@code target} as fast as vanilla turning allows, without overshoot. */
	public static double turnControl(double target, double yaw, double turn, double f) {
		double e = wrap(target - yaw);
		double want = Math.copySign(Math.min(TURN_MAX, Math.min(Math.sqrt(2 * TURN_BRAKE * Math.abs(e)),
				TURN_GAIN * Math.abs(e))), e);
		return clamp(want - turn * f, -1, 1);
	}

	public static double yawOf(double x, double z) {
		return Math.toDegrees(Math.atan2(-x, z));
	}

	static int step(int i, int k, int n) {
		return Math.floorMod(i + k, n);
	}

	static double wrap(double a) {
		a = (a + 180.0) % 360.0;
		if (a < 0) {
			a += 360.0;
		}
		return a - 180.0;
	}

	static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static double sq(double v) {
		return v * v;
	}
}
