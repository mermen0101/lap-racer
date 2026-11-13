package dev.laprace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;

/**
 * The boat's engine, the way racing games make it (tools/kick_engine.py): one real exhaust pulse per combustion,
 * in a V8's firing order with the banks on two pitches, rendered as loops every 500 rpm, on throttle and
 * off it. Every tick the two loops either side of the engine's revs are faded in by how close they are and pitched
 * to the revs exactly; on-throttle and overrun loops are faded by the throttle. On top: the turbo whistling as it
 * spools up, and the blow-off valve when the throttle shuts on boost or the gearbox shifts up.
 * <p>
 * The revs heard are the engine model's (Engine), 4000..13000 rpm, the same as on the tachometer.
 */
public final class EngineSound {
	/** Revs of the loops (assets/laprace/sounds/engine/grid.txt: "on 4000 4500 ..." / "off ..."), these if absent. */
	private static int[] ON = range(4000, 13000, 500);
	private static int[] OFF = range(4000, 13000, 1000);
	/**
	 * The revs each recording really is at (grid.txt "real ..."; one per ON entry, OFF on the same grid): the
	 * tachometer's range is spread over the recordings' range, and not in proportion, so the pitch must come from the
	 * real revs - both loops of a crossfade then sound on the same note, not two engines a few semitones apart.
	 */
	private static int[] REAL;

	private static final List<Loop> ON_LOOPS = new ArrayList<>(), OFF_LOOPS = new ArrayList<>();
	private static Loop turbo;
	private static final SoundEvent BLOWOFF = event("engine.blowoff");
	private static final SoundEvent IGNITION = event("engine.ignition");
	/** Ticks since the driver got in (the starter, then the engine catching); -1: running normally. */
	private static int crank = -1;
	private static boolean wasInBoat;
	/** The starter turns this long before the engine catches; the catch's rev blip lasts BLIP ticks after. */
	private static final int CATCH = 14, BLIP = 26;

	private static boolean running;
	private static double load, boost, lastThrottle;
	private static int lastGear = 1;

	private EngineSound() {
	}

	private static int[] range(int from, int to, int step) {
		return java.util.stream.IntStream.iterate(from, r -> r <= to, r -> r + step).toArray();
	}

	private static SoundEvent event(String path) {
		return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, path));
	}

	/** One looping layer; the game reads its volume and pitch every tick. */
	private static final class Loop extends AbstractTickableSoundInstance {
		final double base;
		double real;
		float wantVolume, wantPitch = 1;

		Loop(String path, double base) {
			super(event(path), SoundSource.MASTER, SoundInstance.createUnseededRandom());
			this.base = base;
			this.real = base;
			this.looping = true;
			this.delay = 0;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
			this.volume = 0;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			if (!running) {
				stop();
				return;
			}
			// a little smoothing so a step in the revs does not click
			volume += (wantVolume - volume) * 0.6f;
			pitch = wantPitch;
		}

		void end() {
			stop();
		}
	}

	/**
	 * How the loops are mixed (assets/laprace/sounds/engine/mix.txt, written by the tool that made them): "blend" -
	 * recordings, the two either side of the revs crossfaded across the whole gap; "nearest" - identical synthetic
	 * loops (they would cancel each other if played together), one at a time.
	 */
	private static boolean blend;

	private static void start(Minecraft mc) {
		running = true;
		blend = false;
		REAL = null;
		mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, "sounds/engine/grid.txt")).ifPresent(r -> {
			try (var in = r.open()) {
				for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
					String[] w = line.trim().split("\\s+");
					if (w.length > 1 && (w[0].equals("on") || w[0].equals("off") || w[0].equals("real"))) {
						int[] g = java.util.Arrays.stream(w, 1, w.length).mapToInt(Integer::parseInt).toArray();
						if (w[0].equals("real")) {
							REAL = g;
						} else if (w[0].equals("on")) {
							ON = g;
						} else {
							OFF = g;
						}
					}
				}
			} catch (Exception e) {
				LapRaceClient.LOG.warn("engine sound grid could not be read", e);
			}
		});
		mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, "sounds/engine/mix.txt")).ifPresent(r -> {
			try (var in = r.open()) {
				blend = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim().equals("blend");
			} catch (java.io.IOException e) {
				LapRaceClient.LOG.warn("engine sound mix mode could not be read", e);
			}
		});
		ON_LOOPS.clear();
		OFF_LOOPS.clear();
		for (int r : ON) {
			ON_LOOPS.add(new Loop("engine.on_" + r, r));
		}
		for (int r : OFF) {
			OFF_LOOPS.add(new Loop("engine.off_" + r, r));
		}
		if (REAL != null && REAL.length == ON.length) {
			for (List<Loop> list : List.of(ON_LOOPS, OFF_LOOPS)) {
				for (Loop l : list) {
					for (int i = 0; i < ON.length; i++) {
						if (ON[i] == (int) l.base) {
							l.real = REAL[i];
						}
					}
				}
			}
		}
		turbo = new Loop("engine.turbo", 1);
		for (Loop l : ON_LOOPS) {
			mc.getSoundManager().play(l);
		}
		for (Loop l : OFF_LOOPS) {
			mc.getSoundManager().play(l);
		}
		mc.getSoundManager().play(turbo);
	}

	private static void stopAll() {
		running = false;
		for (Loop l : ON_LOOPS) {
			l.end();
		}
		for (Loop l : OFF_LOOPS) {
			l.end();
		}
		if (turbo != null) {
			turbo.end();
		}
		ON_LOOPS.clear();
		OFF_LOOPS.clear();
		CURRENT.clear();
		turbo = null;
	}

	/** Every client tick, after the engine model. */
	public static void tick(Minecraft mc) {
		// getting in: the starter first, the engine loops wait for it
		if (Engine.inBoat && !wasInBoat && LapConfig.INSTANCE.engineSound && mc.player != null) {
			crank = 0;
			mc.getSoundManager().play(new SimpleSoundInstance(IGNITION.location(), SoundSource.MASTER,
					(float) (LapConfig.INSTANCE.engineVolume / 100.0 * 0.5), 1f, SoundInstance.createUnseededRandom(), false, 0,
					SoundInstance.Attenuation.NONE, 0, 0, 0, true));
		}
		wasInBoat = Engine.inBoat;
		if (!Engine.inBoat) {
			crank = -1;
		}
		boolean want = LapConfig.INSTANCE.engineSound && Engine.inBoat && mc.player != null && !mc.isPaused();
		if (want != running) {
			if (want) {
				start(mc);
			} else {
				stopAll();
			}
		}
		if (!running) {
			return;
		}
		// half: at 100 % the engine is about as loud as the game's own sounds, not louder
		double master = LapConfig.INSTANCE.engineVolume / 100.0 * 0.5;
		// on the rev limiter: the cut-out stutter (every other tick quieter)
		if (Engine.rpm >= Engine.MAX_RPM - 200 && Engine.throttle > 0.5 && (mc.player.tickCount & 1) == 1) {
			master *= 0.35;
		}
		// the revs heard are the revs on the tachometer
		double srpm = Math.clamp(Engine.rpm, Engine.IDLE_RPM * 0.9, Engine.MAX_RPM * 1.05);
		double catching = -1;
		if (crank >= 0 && !mc.isPaused()) {
			crank++;
			if (crank < CATCH) {
				master = 0; // still on the starter
			} else if (crank < CATCH + BLIP) {
				// caught: a blip up to ~8000 in a quarter of a second, falling back onto the real revs
				int f = crank - CATCH;
				double peak = 8000;
				double blip = f < 5 ? Engine.IDLE_RPM + (peak - Engine.IDLE_RPM) * f / 5.0
						: peak + (srpm - peak) * Math.min(1, (f - 5) / (double) (BLIP - 5));
				srpm = Math.max(srpm, blip);
				catching = f < 5 ? 0.9 : 0.15;
			} else {
				crank = -1;
			}
		}
		// the throttle as the exhaust hears it: opens fast, shuts a touch slower; cut while the gearbox shifts
		double thr = Math.clamp(Engine.throttle, 0, 1);
		if (Engine.sinceShift < 2) {
			thr *= 0.2;
		}
		if (catching >= 0) {
			thr = catching;
		}
		load += (thr - load) * (thr > load ? 0.5 : 0.3);
		if (blend) {
			// recordings: the overrun loops are the same recording muffled - both at once, a little out of step, is an
			// echo (the engine "doubled"); one or the other, crossfaded only while the throttle moves
			mix(ON_LOOPS, srpm, master * Math.sqrt(load));
			mix(OFF_LOOPS, srpm, master * 0.95 * Math.sqrt(1 - load));
		} else {
			mix(ON_LOOPS, srpm, master * (0.5 + 0.5 * load));
			mix(OFF_LOOPS, srpm, master * 0.95 * (1 - load));
		}

		// turbo: spools with revs under load, with lag; whistle rises with it
		double target = load * Math.clamp((srpm - 6000) / 5000, 0, 1);
		boost += (target - boost) * (target > boost ? 0.06 : 0.25);
		turbo.wantVolume = (float) (master * 0.12 * boost);
		turbo.wantPitch = (float) Math.clamp(0.6 + boost * 0.9 + (srpm / Engine.MAX_RPM) * 0.2, 0.5, 2.0);

		// blow-off: throttle shut on boost, or a shift up
		boolean lifted = lastThrottle > 0.6 && Engine.throttle < 0.2 && boost > 0.35;
		boolean upshift = Engine.gear > lastGear && boost > 0.3;
		if (lifted || upshift) {
			mc.getSoundManager().play(new SimpleSoundInstance(BLOWOFF.location(), SoundSource.MASTER,
					(float) (master * (upshift ? 0.25 : 0.45) * boost), (float) (0.9 + Math.random() * 0.2),
					SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
			if (lifted) {
				boost *= 0.3;
			}
		}
		lastThrottle = Engine.throttle;
		lastGear = Engine.gear;
		if (DEV && mc.player.tickCount % 40 == 0) {
			int active = 0;
			StringBuilder v = new StringBuilder();
			for (Loop l : ON_LOOPS) {
				active += mc.getSoundManager().isActive(l) ? 1 : 0;
				v.append(String.format(java.util.Locale.ROOT, " %d:%.2f@%.2f", (int) l.base, l.getVolume(), l.getPitch()));
			}
			for (Loop l : OFF_LOOPS) {
				active += mc.getSoundManager().isActive(l) ? 1 : 0;
			}
			LapRaceClient.LOG.info("[engine] rpm {} sound {} load {} boost {} playing {}/{} on:{}", Math.round(Engine.rpm), Math.round(srpm),
					String.format(java.util.Locale.ROOT, "%.2f", load), String.format(java.util.Locale.ROOT, "%.2f", boost), active,
					ON_LOOPS.size() + OFF_LOOPS.size(), v);
		}
	}

	private static final boolean DEV = System.getenv("LAPRACE_DEMO") != null;

	/** The loop now sounding in each list (on / off throttle), kept until the revs are well past the next one. */
	private static final java.util.Map<List<Loop>, Integer> CURRENT = new java.util.IdentityHashMap<>();

	/**
	 * One loop at a time: the one nearest the revs, pitched to them exactly (the loops are 500 rpm apart, so a few
	 * percent at most); the others fade out (the loop's own volume smoothing makes it a crossfade of a few ticks).
	 * Two loops of the same note playing together cancel each other at random - that was the engine dropping out.
	 */
	/** The real revs heard at these tachometer revs: between two loops, log-interpolated between their real revs. */
	private static double heard(List<Loop> loops, double srpm) {
		Loop first = loops.getFirst(), last = loops.getLast();
		if (srpm <= first.base) {
			return first.real * srpm / first.base;
		}
		for (int i = 0; i + 1 < loops.size(); i++) {
			Loop a = loops.get(i), b = loops.get(i + 1);
			if (srpm <= b.base) {
				double f = Math.log(srpm / a.base) / Math.log(b.base / a.base);
				return Math.exp(Math.log(a.real) + f * Math.log(b.real / a.real));
			}
		}
		return last.real * srpm / last.base;
	}

	private static void mix(List<Loop> loops, double srpm, double level) {
		double r = Math.log(srpm);
		double h = heard(loops, srpm);
		if (blend) {
			// the two loops either side of the revs, the weight sliding across the gap (equal power)
			for (int i = 0; i < loops.size(); i++) {
				Loop l = loops.get(i);
				double lo = i > 0 ? loops.get(i - 1).base : -1, hi = i + 1 < loops.size() ? loops.get(i + 1).base : -1;
				double w;
				if (srpm <= l.base) {
					w = lo < 0 ? 1 : srpm <= lo ? 0 : (r - Math.log(lo)) / (Math.log(l.base) - Math.log(lo));
				} else {
					w = hi < 0 ? 1 : srpm >= hi ? 0 : (Math.log(hi) - r) / (Math.log(hi) - Math.log(l.base));
				}
				l.wantVolume = (float) (Math.sqrt(Math.clamp(w, 0, 1)) * level);
				l.wantPitch = (float) Math.clamp(h / l.real, 0.5, 2.0);
			}
			return;
		}
		int best = 0;
		for (int i = 1; i < loops.size(); i++) {
			if (Math.abs(Math.log(loops.get(i).base) - r) < Math.abs(Math.log(loops.get(best).base) - r)) {
				best = i;
			}
		}
		Integer cur = CURRENT.get(loops);
		if (cur != null && cur != best && cur < loops.size()) {
			// stay on the current one until the revs are 60 % of the way to the next loop (no flicker at the edge)
			double here = Math.log(loops.get(cur).base), next = Math.log(loops.get(best).base);
			if (Math.abs(r - here) < 0.6 * Math.abs(next - here)) {
				best = cur;
			}
		}
		CURRENT.put(loops, best);
		for (int i = 0; i < loops.size(); i++) {
			Loop l = loops.get(i);
			l.wantVolume = i == best ? (float) level : 0f;
			l.wantPitch = (float) Math.clamp(h / l.real, 0.5, 2.0);
		}
	}
}
