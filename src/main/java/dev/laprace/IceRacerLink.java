package dev.laprace;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Ice Racer (the autopilot and chase mod), when it is installed too: its state on our dashboard (the AUTOPILOT /
 * CHASE caption, its lamps, the chase page of the centre display) instead of a panel of its own. Through reflection
 * (dev.iceracer.IceRacerApi): neither mod needs the other; if its API ever changes the link switches itself off.
 */
public final class IceRacerLink {
	private static boolean tried;
	private static Method autopilot, recovering, recording, search, chasing, target, state, gap, dist, speed, throttle, brake;

	private IceRacerLink() {
	}

	/** Ice Racer is installed (and its API answers). */
	public static boolean present() {
		if (!tried) {
			tried = true;
			if (FabricLoader.getInstance().isModLoaded("iceracer")) {
				try {
					Class<?> c = Class.forName("dev.iceracer.IceRacerApi");
					autopilot = c.getMethod("autopilot");
					recovering = c.getMethod("recovering");
					recording = c.getMethod("recording");
					search = c.getMethod("search");
					chasing = c.getMethod("chasing");
					target = c.getMethod("chaseTarget");
					state = c.getMethod("chaseState");
					gap = c.getMethod("chaseGapMs");
					dist = c.getMethod("chaseDistance");
					speed = c.getMethod("chaseTargetSpeed");
					try {
						throttle = c.getMethod("throttle");
						brake = c.getMethod("brake");
					} catch (NoSuchMethodException e) {
						throttle = null; // an older Ice Racer: its pedals are not known
					}
				} catch (ReflectiveOperationException | LinkageError e) {
					autopilot = null;
					LapRaceClient.LOG.warn("Ice Racer is installed but its API does not answer: {}", e.toString());
				}
			} else {
				LapRaceClient.LOG.info("Ice Racer link: not installed");
			}
			if (autopilot != null) {
				LapRaceClient.LOG.info("Ice Racer link: on");
			}
		}
		return autopilot != null;
	}

	/** The method is looked up after {@link #present} (which finds the methods on the first call). */
	private static Object call(java.util.function.Supplier<Method> mm) {
		if (!present()) {
			return null;
		}
		Method m = mm.get();
		try {
			return m.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			autopilot = null;
			LapRaceClient.LOG.warn("Ice Racer link off: {}", e.toString());
			return null;
		}
	}

	public static boolean autopilot() {
		return Boolean.TRUE.equals(call(() -> autopilot));
	}

	/** Ice Racer's throttle on the car, 0..1, or -1 (not known: an older Ice Racer, or not installed). */
	public static double throttle() {
		return throttle == null ? -1 : call(() -> throttle) instanceof Number n ? n.doubleValue() : -1;
	}

	public static double brake() {
		return brake == null ? 0 : call(() -> brake) instanceof Number n ? n.doubleValue() : 0;
	}

	public static boolean recovering() {
		return Boolean.TRUE.equals(call(() -> recovering));
	}

	public static boolean recording() {
		return Boolean.TRUE.equals(call(() -> recording));
	}

	/** Its lap search: 0..1, or -1. */
	public static double search() {
		return call(() -> search) instanceof Number n ? n.doubleValue() : -1;
	}

	public static boolean chasing() {
		return Boolean.TRUE.equals(call(() -> chasing));
	}

	public static String target() {
		return (String) call(() -> target);
	}

	/** 0 catching up, 1 behind it, 2 beside, 3 ahead, 4 out of sight, 5 no gap to read. */
	public static int state() {
		return call(() -> state) instanceof Number n ? n.intValue() : 5;
	}

	public static double gapMs() {
		return call(() -> gap) instanceof Number n ? n.doubleValue() : Double.NaN;
	}

	public static double distance() {
		return call(() -> dist) instanceof Number n ? n.doubleValue() : -1;
	}

	/** The target's speed, blocks/s. */
	public static double targetSpeed() {
		return call(() -> speed) instanceof Number n ? n.doubleValue() : 0;
	}
}
