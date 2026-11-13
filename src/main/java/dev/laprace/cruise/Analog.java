package dev.laprace.cruise;

/**
 * Hand-off between the 80 km/h autopilot (which decides) and the boat mixin (which applies it).
 * Vanilla boats only take on/off keys; the mixin lets us press them partway (needed to hold 80 km/h steadily).
 */
public final class Analog {
	/** The boat the local player is driving (set every tick by the autopilot). */
	public static volatile Object boat;
	/** The autopilot drives this tick. */
	public static volatile boolean active;
	/** Set by the mixin the first time it runs: proves the hook found its target. */
	public static volatile boolean hooked;
	/** -1..1 added to the boat's turn rate this tick (vanilla: A = -1, D = +1). */
	public static volatile float steer;
	/** Blocks/tick^2 along the bow, -0.005..0.04 (vanilla: S = -0.005, W = +0.04). */
	public static volatile float accel;

	private Analog() {
	}
}
