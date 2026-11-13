package dev.laprace;

import dev.laprace.cruise.Cruise;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The car behind the dials: revs, gear, fuel and odometer. A boat has none of these, so they are made up:
 * <ul>
 *   <li>8 gears, each for a band of speed (GEAR_TOP, m/s): short low gears, longer high ones. A boat on ice
 *       gains speed slower and slower, so the low gears go by in half a second each and the high ones take
 *       seconds (as a racer suggested: "the first ones quick, the later ones slower");</li>
 *   <li>revs follow the speed within the gear's band: 7000 at its bottom, 11500 at its top, where the next gear
 *       comes in (1st from idle 4000; 8th on to 12800); limiter 13000, 7000 in water. Down a gear 1.5 m/s under the
 *       band, the revs jumping up as in a blip;</li>
 *   <li>fuel 384, virtual: burnt by the distance driven, more on the throttle than coasting (a full tank lasts
 *       about 27 km flat out at a rate of 1, see {@link #fuelRate()}); filled standing still on a pit box (black or
 *       yellow concrete), a full tank in {@link #REFILL_S} s. Empty: the engine sputters, a quarter of its power.
 *       Odometer: distance driven.</li>
 * </ul>
 */
public final class Engine {
	public static final int IDLE_RPM = 4000;
	public static final int MAX_RPM = 13000;
	private static final int WATER_MAX_RPM = 7000;
	public static final int SHIFT_UP_RPM = 11500;
	private static final int SHIFT_UP_TARGET_RPM = 7000;
	/** Top of each gear's speed band, m/s (the boat's own top speed on blue ice is ~72.7). */
	private static final double[] GEAR_TOP = {8, 15, 22, 30, 38, 47, 57, 72.7};
	private static final double DOWN_MARGIN = 1.5;
	public static final int MAX_FUEL = 384;
	/**
	 * Fuel per block at full throttle, at a rate of 1: a full tank lasts ~27 km flat out (~30 at a race's pace), so 40
	 * laps of the league's season-10 track (ppl10, 1.98 km; "racer", 1.61 km) take two stops, as they did then.
	 */
	private static final double BURN_PER_BLOCK = MAX_FUEL / 27000.0;
	/** A full tank standing in the box (s); the automatic pit stop waits as long for an empty one. */
	public static final double REFILL_S = 6;

	public static double rpm;
	public static double prevRpm;
	/** 1..8. */
	public static int gear = 1;
	public static double fuel = MAX_FUEL;

	/**
	 * Fuel is counted: on in the settings, and not with Ice Racer installed - the autopilot mod only the kill-a-car
	 * has; it never refuels.
	 */
	/** How fast fuel burns: the server event's rate while we race in it, else our own setting. */
	public static double fuelRate() {
		double r = ServerRace.fuelRateRule();
		return r > 0 ? r : LapConfig.INSTANCE.fuelRate;
	}

	/** The engine's power: full, or none with the tank empty (the car only steers, rolling on). */
	public static float power() {
		return empty() ? 0f : 1f;
	}

	/** The tank is dry: no throttle, no reverse, no push from the oars - steering alone. */
	public static boolean empty() {
		return fuelOn() && fuel <= 0;
	}

	public static boolean fuelOn() {
		return (LapConfig.INSTANCE.fuel || ServerRace.fuelRule()) && !IceRacerLink.present();
	}
	/** Kilometres driven since the game started. */
	public static double odometerKm;
	public static boolean inBoat;
	/** Throttle and brake as the driver (or the autopilot) gives them, 0..1, for the pedal bars. */
	public static double throttle;
	public static double brake;
	/** Ticks since the last gear change (the gear digit flashes after a shift). */
	public static int sinceShift = 100;
	/** GT's red box next to the gear: the gear for the slower section coming up (0 = none). */
	public static int suggestGear;

	private Engine() {
	}

	public static void tick(Minecraft mc) {
		Entity boat = mc.player == null ? null : RaceSession.boatOf(mc.player);
		inBoat = boat != null;
		prevRpm = rpm;
		sinceShift++;
		if (boat == null) {
			rpm = 0;
			gear = 1;
			throttle = 0;
			brake = 0;
			return;
		}
		boolean accel;
		double irThrottle = IceRacerLink.present() ? IceRacerLink.throttle() : -1;
		if (Cruise.enabled) {
			throttle = Cruise.throttle;
			brake = Cruise.brake;
			accel = throttle > 0.5;
		} else if (irThrottle >= 0 && (IceRacerLink.autopilot() || IceRacerLink.chasing())) {
			// Ice Racer drives: its pedals (not the keys) - for the bars and the server's log
			throttle = irThrottle;
			brake = IceRacerLink.brake();
			accel = throttle > 0.5;
		} else {
			accel = mc.options.keyUp.isDown();
			throttle = accel ? 1 : 0;
			brake = mc.options.keyDown.isDown() ? 1 : 0;
		}
		Vec3 v = boat.getDeltaMovement();
		double speed = Math.hypot(v.x, v.z) * 20; // m/s
		odometerKm += speed / 20 / 1000;
		boolean water = boat.isInWater();

		// gears: by speed band (one step a tick)
		if (gear < GEAR_TOP.length && speed >= GEAR_TOP[gear - 1]) {
			gear++;
			rpm = Math.min(rpm, SHIFT_UP_TARGET_RPM);
			sinceShift = 0;
		} else if (gear > 1 && speed < bandLow(gear) - DOWN_MARGIN) {
			gear--;
			sinceShift = 0;
			rpm = Math.max(rpm, rpmFor(speed, gear)); // the blip
		}
		// revs: from the speed within the gear's band; on the throttle at a standstill the engine still revs a bit
		double target = rpmFor(speed, gear);
		if (accel && gear == 1) {
			target = Math.max(target, 6500);
		}
		rpm += (target - rpm) * (target > rpm ? 0.5 : 0.3);
		rpm = Math.clamp(rpm, IDLE_RPM, water ? WATER_MAX_RPM : MAX_RPM);

		if (fuelOn()) {
			tickFuel(mc, boat, speed / 20);
		} else {
			fuel = MAX_FUEL;
		}
		suggestGear = suggest(speed);
	}

	/** blocks: driven this tick. */
	private static void tickFuel(Minecraft mc, Entity boat, double blocks) {
		double r = 1.375 / 2;
		Vec3 p = boat.position();
		boolean pit = false;
		for (int ix = -1; ix <= 1 && !pit; ix++) {
			for (int iz = -1; iz <= 1 && !pit; iz++) {
				BlockPos at = BlockPos.containing(p.x + ix * r, p.y - 1.0, p.z + iz * r);
				var st = mc.level.getBlockState(at);
				pit = st.is(net.minecraft.world.level.block.Blocks.CONCRETE.black()) || st.is(net.minecraft.world.level.block.Blocks.CONCRETE.yellow());
			}
		}
		if (pit && blocks < 0.05) {
			fuel = Math.min(MAX_FUEL, fuel + MAX_FUEL / (REFILL_S * 20)); // standing in the box: filling up
		} else {
			fuel = Math.max(0, fuel - blocks * BURN_PER_BLOCK * (0.35 + 0.65 * throttle) * fuelRate());
		}
	}

	/** The racing line's slowest speed over the next ~2.5 s; if well under ours, the gear that suits it. */
	private static int suggest(double speed) {
		RaceSession s = RaceSession.INSTANCE;
		Track t = s.track;
		Racer me = s.self;
		if (t == null || me == null || me.idx < 0 || speed < 5) {
			return 0;
		}
		double reach = Math.clamp(speed * 2.5, 15, 120);
		int n = t.size();
		double vmin = Double.MAX_VALUE;
		double run = 0;
		for (int k = 1, j = me.idx; k < n && run < reach; k++) {
			int jn = (j + 1) % n;
			run += Math.hypot(t.x[jn] - t.x[j], t.z[jn] - t.z[j]);
			j = jn;
			if (t.v[j] > 0) {
				vmin = Math.min(vmin, t.v[j]);
			}
		}
		if (vmin == Double.MAX_VALUE || vmin > speed - 4) {
			return 0;
		}
		int g = gearFor(vmin);
		return g < gear ? g : 0;
	}

	private static double bandLow(int g) {
		return g <= 1 ? 0 : GEAR_TOP[g - 2];
	}

	/** The gear for this speed (m/s), as the gearbox would settle on it. */
	public static int gearFor(double speed) {
		for (int g = 1; g < GEAR_TOP.length; g++) {
			if (speed < GEAR_TOP[g - 1]) {
				return g;
			}
		}
		return GEAR_TOP.length;
	}

	/** Revs at this speed (m/s) in this gear. */
	public static double rpmFor(double speed, int g) {
		double lo = bandLow(g), hi = GEAR_TOP[g - 1];
		double f = Math.clamp((speed - lo) / (hi - lo), 0, 1.2);
		double from = g == 1 ? IDLE_RPM : SHIFT_UP_TARGET_RPM, to = g == GEAR_TOP.length ? 12800 : SHIFT_UP_RPM;
		return Math.clamp(from + (to - from) * f, IDLE_RPM, MAX_RPM);
	}

	/** Revs between two ticks (the needle moves every frame). */
	public static double rpm(float partial) {
		return prevRpm + (rpm - prevRpm) * partial;
	}
}
