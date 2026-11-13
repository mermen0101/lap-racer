package dev.laprace.cruise;

import dev.laprace.LapConfig;
import dev.laprace.Notify;
import dev.laprace.PitLane;
import dev.laprace.RaceSession;
import dev.laprace.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * The pit lane speed limiter for a driver at the keys (the autopilot keeps the limit by itself): between the red
 * lines (red wool) the boat goes no faster than the pit limit (80 km/h by default), and on the way in it is slowed
 * a little before them - braking from as far back as the boat needs to be at the limit 8 blocks before the first
 * red line. Throttle is cut above the speed allowed, the brake (S) held when well above it. Only in the pit lane
 * itself: off the racing line and nearer the lane than the track, so cars on the main straight beside the pits are
 * left alone.
 */
public final class PitLimiter {
	/** The limit is reached this many blocks before the first red line. */
	private static final double LEAD = 8;
	/** The boat counts as in the lane within this distance of its line (the boxes lie beside it). */
	private static final double IN_LANE = 7;

	/** On now (for the HUD): the speed allowed, blocks/s. */
	public static boolean active;
	public static double cap;
	private static boolean told;
	private static PitLane scanned;
	private static final Track.Projection PROJ = new Track.Projection();

	private PitLimiter() {
	}

	private static int hint = -1;

	/** Where on the lane the boat is now (lane point), -1 when away from it. */
	public static int laneIndex() {
		return hint;
	}
	private static PitLane hintLane;

	/**
	 * Where on the lane the boat is. The lane runs back alongside itself (the way in beside the way out), so: near
	 * where it was a moment ago; found afresh only among points whose direction is the boat's own.
	 */
	private static int locate(PitLane l, double x, double z, Vec3 v) {
		if (l != hintLane) {
			hintLane = l;
			hint = -1;
		}
		int n = l.pts.size();
		if (hint >= 0) {
			int best = hint;
			double bd = Double.MAX_VALUE;
			for (int i = Math.max(0, hint - 8); i < Math.min(n, hint + 25); i++) {
				double[] q = l.pts.get(i);
				double d = (q[0] - x) * (q[0] - x) + (q[2] - z) * (q[2] - z);
				if (d < bd) {
					bd = d;
					best = i;
				}
			}
			if (bd < 15 * 15) {
				return hint = best;
			}
		}
		double sp = Math.hypot(v.x, v.z);
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i + 1 < n; i++) {
			double[] q = l.pts.get(i), r = l.pts.get(i + 1);
			double d = (q[0] - x) * (q[0] - x) + (q[2] - z) * (q[2] - z);
			if (sp > 0.05 && (r[0] - q[0]) * v.x + (r[2] - q[2]) * v.z < 0) {
				d += 400; // going the other way: the other part of the lane
			}
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return hint = best;
	}

	public static Input apply(LocalPlayer p, Input in) {
		active = false;
		Minecraft mc = Minecraft.getInstance();
		Entity boat = RaceSession.boatOf(p);
		Track t = RaceSession.INSTANCE.track;
		PitLane l = t == null ? null : PitLane.of(t);
		if (Cruise.enabled || boat == null || l == null || mc.level == null) {
			told = false;
			return in;
		}
		double x = boat.getX(), z = boat.getZ();
		Vec3 vel = boat.getDeltaMovement();
		int k = locate(l, x, z, vel);
		double[] q = l.pts.get(k);
		double dLane = Math.hypot(q[0] - x, q[2] - z);
		if (dLane > 30) {
			told = false;
			hint = -1;
			return in;
		}
		// the red lines are found by looking at the world around the pits: once, when the boat is near
		if (!l.fresh() && scanned != l) {
			scanned = l;
			l.scan(mc.level);
		}
		int from = l.limitFrom(), to = l.limitTo();
		if (from < 0 || dLane > IN_LANE || k < l.firstOff || k > to) {
			told = false;
			return in;
		}
		t.project(x, z, -1, PROJ);
		if (PROJ.off < dLane - 0.5) {
			told = false;
			return in; // on the track beside the pit lane
		}
		double limit = LapConfig.INSTANCE.pitLimitKmh / 3.6;
		BlockPos under = BlockPos.containing(x, boat.getY() - 0.5, z);
		float f = mc.level.getBlockState(under).getBlock().getFriction();
		if (k >= from) {
			cap = limit;
		} else {
			// on the way in: as fast as can still come down to the limit before the red lines
			double dist = 0;
			for (int i = k; i < from && i + 1 < l.pts.size(); i++) {
				double[] a = l.pts.get(i), b = l.pts.get(i + 1);
				dist += Math.hypot(b[0] - a[0], b[2] - a[2]);
			}
			cap = Cruise.brakeSpeed(dist - LEAD, f, limit);
		}
		double speed = Math.hypot(vel.x, vel.z) * 20;
		active = true;
		if (!told && k >= from - 40) {
			told = true;
			Notify.show(mc, Component.translatable("laprace.msg.pit_limiter", LapConfig.INSTANCE.pitLimitKmh));
		}
		if (speed > cap + 0.2) {
			return new Input(false, true, in.left(), in.right(), in.jump(), in.shift(), in.sprint()); // brake
		}
		if (speed > cap - 0.6 && in.forward()) {
			return new Input(false, in.backward(), in.left(), in.right(), in.jump(), in.shift(), in.sprint()); // no more throttle
		}
		return in;
	}
}
