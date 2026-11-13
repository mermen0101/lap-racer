package dev.laprace;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Speed, drift angle, g and ping for the speedometer. Taken from Boat HUD (hibi.boathud, optional, read by
 * reflection: no compile or run dependency) when it is installed, so the numbers are exactly its own; otherwise
 * measured here the same way (speed = horizontal velocity, drift = bow vs. direction of travel).
 */
public final class BoatData {
	public static final boolean BOATHUD = FabricLoader.getInstance().isModLoaded("boathud");
	/** Set by the mixin the first time it hides Boat HUD's panel (shown in the settings as a check). */
	public static boolean boatHudHooked;

	private static boolean broken;
	private static Field hudData, ridingBoat, enabled, speedRate, speedFormat, fSpeed, fDrift, fG, fPing;

	// own measurement (without Boat HUD)
	public static double speed;
	public static double drift;
	public static double g;
	/** Bow angle off the direction of travel, signed: + = bow to the right of where the boat goes. */
	public static double driftSigned;
	/** Acceleration felt in the boat, in g, smoothed: across (+ = to the right of the travel) and along (+ = speeding up). */
	public static double gLat, gLong;
	private static double prevVx, prevVz;
	private static boolean havePrev;
	private static double oldSpeed;

	private BoatData() {
	}

	/** Our speedometer replaces Boat HUD's panel. */
	public static boolean replaceBoatHud() {
		LapConfig c = LapConfig.INSTANCE;
		return c.showHud && c.replaceBoatHud && c.showSpeedo;
	}

	private static boolean ready() {
		if (!BOATHUD || broken) {
			return false;
		}
		if (hudData == null) {
			try {
				ClassLoader cl = BoatData.class.getClassLoader();
				Class<?> common = Class.forName("hibi.boathud.Common", false, cl);
				Class<?> config = Class.forName("hibi.boathud.Config", false, cl);
				Class<?> data = Class.forName("hibi.boathud.HudData", false, cl);
				hudData = common.getField("hudData");
				ridingBoat = common.getField("ridingBoat");
				enabled = config.getField("enabled");
				speedRate = config.getField("speedRate");
				speedFormat = config.getField("speedFormat");
				fSpeed = data.getField("speed");
				fDrift = data.getField("driftAngle");
				fG = data.getField("g");
				fPing = data.getField("ping");
			} catch (ReflectiveOperationException | LinkageError e) {
				broken = true;
				return false;
			}
		}
		return true;
	}

	private static Object data() {
		try {
			return ready() && ridingBoat.getBoolean(null) ? hudData.get(null) : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			return null;
		}
	}

	/** Every client tick: own measurement (used without Boat HUD). */
	public static void tick(Minecraft mc) {
		Entity boat = mc.player == null ? null : RaceSession.boatOf(mc.player);
		if (boat == null) {
			speed = 0;
			havePrev = false;
			gLat = 0;
			gLong = 0;
			drift = 0;
			g = 0;
			oldSpeed = 0;
			return;
		}
		Vec3 v = boat.getDeltaMovement();
		speed = Math.hypot(v.x, v.z) * 20;
		g = (speed - oldSpeed) / 0.05 / 9.81; // change of speed over one tick (0.05 s), in g
		oldSpeed = speed;
		// acceleration from the change of velocity over the tick, split along / across the direction of travel
		if (havePrev && speed > 0.5) {
			double ax = (v.x - prevVx) * 400 / 9.81, az = (v.z - prevVz) * 400 / 9.81; // blocks/tick^2 -> m/s^2 -> g
			double dx = v.x / Math.hypot(v.x, v.z), dz = v.z / Math.hypot(v.x, v.z);
			double along = ax * dx + az * dz;
			double across = ax * -dz + az * dx; // right of the travel (screen: x right, z down)
			gLong += (along - gLong) * 0.3;
			gLat += (across - gLat) * 0.3;
		} else {
			gLong *= 0.7;
			gLat *= 0.7;
		}
		prevVx = v.x;
		prevVz = v.z;
		havePrev = true;
		if (speed > 0.5) {
			double travel = Math.toDegrees(Math.atan2(-v.x, v.z));
			double rel = ((boat.getYRot() - travel) % 360 + 540) % 360 - 180;
			drift = Math.abs(rel);
			driftSigned = rel;
		} else {
			drift = 0;
			driftSigned = 0;
		}
	}

	private static double read(Field f, double fallback) {
		try {
			Object d = data();
			return d != null ? f.getDouble(d) : fallback;
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			return fallback;
		}
	}

	/** Blocks/s. */
	public static double speed() {
		return read(fSpeed, speed);
	}

	public static double drift() {
		return read(fDrift, drift);
	}

	public static double g() {
		return read(fG, g);
	}

	/** Ping in ms, -1 = unknown. */
	public static int ping() {
		try {
			Object d = data();
			return d != null ? fPing.getInt(d) : -1;
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			return -1;
		}
	}

	public static boolean boatHudEnabled() {
		try {
			return ready() && enabled.getBoolean(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** Multiplier blocks/s -> shown unit. */
	public static double unitRate() {
		return switch (LapConfig.INSTANCE.speedUnit) {
			case KMH -> 3.6;
			case MPH -> 2.236936;
			case MS -> 1.0;
			case BOATHUD -> {
				try {
					yield ready() ? speedRate.getDouble(null) : 3.6;
				} catch (ReflectiveOperationException | RuntimeException e) {
					yield 3.6;
				}
			}
		};
	}

	/** "km/h", "mph", "m/s" (for Boat HUD's unit: read from its format string). */
	public static String unitName() {
		return switch (LapConfig.INSTANCE.speedUnit) {
			case KMH -> "km/h";
			case MPH -> "mph";
			case MS -> "m/s";
			case BOATHUD -> {
				try {
					if (ready()) {
						String f = ((String) speedFormat.get(null)).toLowerCase(Locale.ROOT);
						if (f.contains("mph")) {
							yield "mph";
						}
						if (f.contains("m/s")) {
							yield "m/s";
						}
						if (f.contains("kt")) {
							yield "kt";
						}
					}
				} catch (ReflectiveOperationException | RuntimeException e) {
					// fall through
				}
				yield "km/h";
			}
		};
	}
}
