package dev.laprace.net;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The jam on this player's radio (the kill-a-car's jammer): the level from our server and what it does to a 20 ms
 * voice frame. No Simple Voice Chat classes here ({@link RadioJam} is the plugin), so the mod runs without it.
 */
public final class Jam {
	private Jam() {
	}

	/** From the server: 0 = no jam ... 1 = nothing gets through. */
	static volatile double target;
	/** Followed smoothly (a frame is 20 ms). */
	private static volatile double level;

	/** The jam level now (the server's, eased). */
	public static double level() {
		return level;
	}

	public static void set(double v) {
		target = Math.max(0, Math.min(1, v));
	}

	/** One 20 ms frame through the jammer (the same array when there is no jam). */
	public static short[] apply(short[] a) {
		double t = target, l = level;
		l += (t - l) * 0.15; // about 120 ms to follow
		if (t == 0 && l < 0.01) {
			l = 0;
		}
		level = l;
		if (a == null || a.length == 0 || l <= 0) {
			return a;
		}
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		short[] o = new short[a.length];
		// a dropout: the whole frame is static (always at full jam)
		boolean drop = l >= 0.97 || rnd.nextDouble() < l * 0.55;
		double voice = drop ? 0 : 1 - 0.75 * l;
		double hiss = 600 + 2600 * l;
		int crush = l > 0.5 ? (int) (1 << (int) (4 + 6 * (l - 0.5))) : 1; // fewer bits: the voice turns to tin
		double lp = 0;
		for (int i = 0; i < a.length; i++) {
			// static: noise, slightly low-passed, with crackles
			lp += (rnd.nextGaussian() - lp) * 0.6;
			double n = lp * hiss;
			if (rnd.nextDouble() < 0.002 * (1 + 4 * l)) {
				n += (rnd.nextBoolean() ? 1 : -1) * (8000 + 12000 * rnd.nextDouble()) * l;
			}
			int v = a[i];
			if (crush > 1) {
				v = v / crush * crush;
			}
			double s = v * voice + n;
			o[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(s)));
		}
		return o;
	}
}
