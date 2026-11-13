package dev.laprace;

import dev.laprace.cruise.Cruise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Development only (environment variable LAPRACE_DEMO=1): once in a boat on a track, turns the 80 km/h autopilot
 * on, starts a race and takes screenshots (run/screenshots) at fixed moments, so the HUD can be checked without
 * anyone at the keyboard. Does nothing in a normal game.
 */
public final class DevDemo {
	private static final boolean ON = "1".equals(System.getenv("LAPRACE_DEMO"));
	/** LAPRACE_DEMO=pits: into a boat before the pit entry, the box screen, then an automatic stop in box 4. */
	private static final boolean PITS = "pits".equals(System.getenv("LAPRACE_DEMO"));
	private static long t = -1;
	private static int shot;

	private DevDemo() {
	}

	private static void cmd(Minecraft mc, String c) {
		var srv = mc.getSingleplayerServer();
		if (srv != null) {
			srv.execute(() -> srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), c));
		}
	}

	/** LAPRACE_DEMO=pitsall: every box in turn, each judged (on the concrete, nose first, no spin, time). */
	private static final boolean ALL = "pitsall".equals(System.getenv("LAPRACE_DEMO"));
	private static final int[] BOXES = System.getenv("LAPRACE_BOXES") == null ? new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}
			: java.util.Arrays.stream(System.getenv("LAPRACE_BOXES").split(",")).mapToInt(Integer::parseInt).toArray();
	private static int bi, box = BOXES[0], phase, phaseT;
	private static double spin, startT, lastYaw;
	private static boolean judged, backed;
	private static double maxOff, lastSp;

	/** LAPRACE_TRACK: the track whose pits pitsall goes through (route3 if not set). */
	private static final String ALL_TRACK = System.getenv("LAPRACE_TRACK") == null ? "route3" : System.getenv("LAPRACE_TRACK");

	/** "x y z yaw": lane point i of the track's pits, facing along the lane (for tp). */
	private static String laneSpot(int i) {
		for (Track tr : RaceSession.INSTANCE.tracks) {
			if (tr.name.equals(ALL_TRACK)) {
				PitLane l = PitLane.of(tr);
				i = Math.clamp(i < 0 ? l.entryAt() : i, 0, l.pts.size() - 4);
				double[] a0 = l.pts.get(i), a1 = l.pts.get(i + 3);
				return String.format(java.util.Locale.ROOT, "%.1f -59.5 %.1f %.0f", a0[0], a0[2], Math.toDegrees(Math.atan2(-(a1[0] - a0[0]), a1[2] - a0[2])));
			}
		}
		throw new IllegalStateException("no track " + ALL_TRACK);
	}

	private static void all(Minecraft mc) {
		var b = mc.player.getVehicle();
		t++;
		if (t == 40) {
			String[] at = ALL_TRACK.equals("route3") ? new String[] {"168.5", "-59.5", "216.5", "160"} : laneSpot(-1).split(" ");
			if (b != null) {
				cmd(mc, "execute as @p on vehicle run tp @s " + String.join(" ", at) + " 0");
			} else {
				cmd(mc, "tp @a " + String.join(" ", at) + " 0");
				cmd(mc, "summon minecraft:oak_boat " + at[0] + " " + at[1] + " " + at[2] + " {Rotation:[" + at[3] + "f,0f]}");
			}
		}
		if (t == 100 && b == null) {
			cmd(mc, "ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		}
		if (t == 150) {
			for (Track tr : RaceSession.INSTANCE.tracks) {
				if (tr.name.equals(ALL_TRACK)) {
					RaceSession.INSTANCE.setTrack(tr);
				}
			}
		}
		if (t == 200) {
			// the box screen: is the mouse free, does a click on a number take?
			PitScreen.open(mc);
		}
		if (t == 215 && mc.gui.screen() instanceof PitScreen ps) {
			boolean grabbed = mc.mouseHandler.isMouseGrabbed();
			ps.devClick(7);
			LapRaceClient.LOG.info("[pitall] screen: mouse grabbed={}, click on 7 -> selected {}", grabbed, ps.selected());
			Screenshot.grab(mc, false);
		}
		if (t == 240 && mc.gui.screen() != null) {
			mc.gui.screen().onClose();
		}
		if (bi >= BOXES.length && t > 300 && System.getenv("LAPRACE_TRACK") != null) {
			mc.stop(); // a run for one track's pits: done
			return;
		}
		if (t < 260 || b == null || bi >= BOXES.length) {
			return;
		}
		phaseT++;
		switch (phase) {
			case 0 -> { // to the start, settle
				LapConfig.INSTANCE.pitBoxes.put(ALL_TRACK, box);
				// where the autopilot takes over
				cmd(mc, "execute as @p on vehicle run tp @s " + (ALL_TRACK.equals("route3") ? "205.8 -59.5 286.7 154" : laneSpot(-1)) + " 0");
				phase = 1;
				phaseT = 0;
			}
			case 1 -> {
				if (phaseT == 30) {
					Cruise.devPit(mc);
					startT = t;
					spin = 0;
					maxOff = 0;
					backed = false;
					judged = false;
					phase = 2;
				}
			}
			case 2 -> {
				PitLane l = Cruise.pitLane();
				double spNow = b.getDeltaMovement().horizontalDistance() * 20;
				if (Cruise.mode == Cruise.Mode.PIT_IN && t % 4 == 0) {
					LapRaceClient.LOG.info("[pitall]   box {} TRACE {} {} v {} heading {} along {} side {}", box, String.format("%.1f", b.getX()), String.format("%.1f", b.getZ()),
							String.format("%.1f", b.getDeltaMovement().horizontalDistance() * 20), Math.round(b.getYRot()), String.format("%.1f", Cruise.dockAlong), String.format("%.1f", Cruise.dockSide));
				}
				if (Cruise.inPits() && lastSp - spNow > 0.6 && !Cruise.backingUp()) {
					StringBuilder under = new StringBuilder();
					for (int ix = -1; ix <= 1; ix += 2) {
						for (int iz = -1; iz <= 1; iz += 2) {
							under.append(mc.level.getBlockState(net.minecraft.core.BlockPos.containing(b.getX() + ix * 0.69, b.getY() - 0.3, b.getZ() + iz * 0.69)).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
						}
					}
					LapRaceClient.LOG.info("[pitall]   box {} SLOW {} -> {} at {} {} heading {} mode {} along {} brake {} under {}", box, String.format("%.1f", lastSp), String.format("%.1f", spNow),
							String.format("%.1f", b.getX()), String.format("%.1f", b.getZ()), Math.round(b.getYRot()), Cruise.mode, String.format("%.1f", Cruise.dockAlong),
							dev.laprace.Engine.brake, under);
				}
				lastSp = spNow;
				if (b.horizontalCollision && Cruise.inPits()) {
					var v = b.getDeltaMovement();
					LapRaceClient.LOG.info("[pitall]   box {} WALL at {} {} speed {} heading {} mode {} along {}", box, String.format("%.1f", b.getX()), String.format("%.1f", b.getZ()),
							String.format("%.1f", v.horizontalDistance() * 20), Math.round(b.getYRot()), Cruise.mode, String.format("%.1f", Cruise.dockAlong));
				}
				if (Cruise.mode == Cruise.Mode.PIT_IN && Cruise.dockAlong > 0.5 && Cruise.dockAlong < 25) {
					maxOff = Math.max(maxOff, Math.abs(net.minecraft.util.Mth.wrapDegrees(b.getYRot() - Math.toDegrees(Math.atan2(-l.stopDx, l.stopDz)))));
					backed |= Cruise.backingUp();
				}
				if (Cruise.mode == Cruise.Mode.PIT_IN && t % 5 == 0 && Math.abs(Cruise.dockAlong) < 25) {
					LapRaceClient.LOG.info("[pitall]   box {} along {} side {} v {} yaw-off {}", box, String.format("%.2f", Cruise.dockAlong),
							String.format("%.2f", Cruise.dockSide), String.format("%.1f", b.getDeltaMovement().horizontalDistance() * 20),
							Math.round(net.minecraft.util.Mth.wrapDegrees(b.getYRot() - Math.toDegrees(Math.atan2(-l.stopDx, l.stopDz)))));
				}
				if (Cruise.mode == Cruise.Mode.PIT_STOP) {
					if (!judged) {
						judged = true;
						PitLane.Box pb = l.chosen();
						double err = Math.hypot(b.getX() - pb.x(), b.getZ() - pb.z());
						boolean onPad = false;
						for (int ix = -1; ix <= 1; ix++) {
							for (int iz = -1; iz <= 1; iz++) {
								var under = mc.level.getBlockState(net.minecraft.core.BlockPos.containing(b.getX() + ix * 0.6875, b.getY() - 0.3, b.getZ() + iz * 0.6875));
								onPad |= under.is(net.minecraft.world.level.block.Blocks.CONCRETE.black()) || under.is(net.minecraft.world.level.block.Blocks.CONCRETE.yellow());
							}
						}
						double[] a0 = l.pts.get(Math.max(0, pb.at() - 3)), a1 = l.pts.get(Math.min(l.pts.size() - 1, pb.at() + 3));
						double laneYaw = Math.toDegrees(Math.atan2(-(a1[0] - a0[0]), a1[2] - a0[2]));
						double nose = Math.abs(net.minecraft.util.Mth.wrapDegrees(b.getYRot() - laneYaw));
						LapRaceClient.LOG.info("[pitall] box {}: stopped after {} s, {} from the spot, on the pad {}, nose {} deg off the lane, at most {} deg off on the way in, backed up {}",
								box, String.format("%.1f", (t - startT) / 20.0), String.format("%.2f", err), onPad, Math.round(nose), Math.round(maxOff), backed);
						lastYaw = b.getYRot();
					} else {
						spin += Math.abs(net.minecraft.util.Mth.wrapDegrees(b.getYRot() - lastYaw));
						lastYaw = b.getYRot();
					}
				} else if (judged && Cruise.mode != Cruise.Mode.PIT_STOP) {
					LapRaceClient.LOG.info("[pitall] box {}: turned {} deg while standing; planning {} ms a tick on average, {} at most", box, Math.round(spin),
							String.format("%.2f", Cruise.dockSumMs / Math.max(1, Cruise.dockN)), String.format("%.2f", Cruise.dockMaxMs));
					Cruise.dockMaxMs = 0;
					Cruise.dockSumMs = 0;
					Cruise.dockN = 0;
					phase = 3;
					phaseT = 0;
				} else if (phaseT > 1200) {
					LapRaceClient.LOG.info("[pitall] box {}: never stopped (mode {})", box, Cruise.mode);
					phase = 3;
					phaseT = 0;
				}
			}
			case 3 -> {
				if (!Cruise.inPits() || phaseT > 900) {
					LapRaceClient.LOG.info("[pitall] box {}: out, {} s in all", box, String.format("%.1f", (t - startT) / 20.0));
					if (Cruise.enabled) {
						Cruise.disable(mc.player, null);
					}
					bi++;
					box = bi < BOXES.length ? BOXES[bi] : 99;
					phase = 0;
					phaseT = 0;
				}
			}
			default -> {
			}
		}
	}

	private static final boolean TRIG = "1".equals(System.getenv("LAPRACE_PITTRIG"));

	private static void pits(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		t++;
		if (t == 40) {
			// in a boat already (the saved player is): move the boat with the player in it; if not, a new boat
			if (mc.player.getVehicle() != null) {
				cmd(mc, "execute as @p on vehicle run tp @s 168 -59.5 216 180 0");
			} else {
				cmd(mc, "tp @a 168 -59 216 180 0");
				cmd(mc, "summon minecraft:oak_boat 168 -59.5 216 {Rotation:[180f,0f]}");
			}
		}
		if (t == 100 && mc.player.getVehicle() == null) {
			cmd(mc, "ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		}
		if (t == 190) {
			LapRaceClient.LOG.info("[pittest] boat at {} {}", mc.player.getVehicle() == null ? "none" : String.format("%.1f", mc.player.getVehicle().getX()),
					mc.player.getVehicle() == null ? "" : String.format("%.1f", mc.player.getVehicle().getZ()));
		}
		if (t == 200) {
			LapConfig.INSTANCE.pitBoxes.put("route3", 4);
			PitScreen.open(mc);
		}
		if (t == 240) {
			Screenshot.grab(mc, false);
		}
		if (t == 260 && mc.gui.screen() != null) {
			mc.gui.screen().onClose();
		}
		if (TRIG && t == 270) {
			cmd(mc, "execute as @p on vehicle run tp @s 216.9 -59.5 311.6 156 0"); // on the pit road before the straight
		}
		if (TRIG && t > 280 && t < 400 && !Cruise.inPits()) {
			mc.options.keyUp.setDown(true);
		}
		if (TRIG && (Cruise.inPits() || t == 400)) {
			mc.options.keyUp.setDown(false);
		}
		if (t == 280) {
			if (TRIG) {
				// drive up the pit road by hand (W held): the automatic stop must take the car at the red line only
				mc.options.keyUp.setDown(true);
			} else {
				Cruise.devPit(mc);
			}
		}
		if (t > 280 && t % 10 == 0 && mc.player.getVehicle() != null) {
			var b = mc.player.getVehicle();
			LapRaceClient.LOG.info("[pittest] t={} mode={} pos={} {} v={}", t, Cruise.mode, String.format("%.2f", b.getX()),
					String.format("%.2f", b.getZ()), String.format("%.1f", b.getDeltaMovement().horizontalDistance() * 72));
		}
		if (t == 400 || t == 520 || t == 640 || t == 760 || t == 900) {
			Screenshot.grab(mc, false);
		}
	}

	/** LAPRACE_DEMO=car: the F1 car from behind, the side, the front and first person, an empty one beside it. */
	private static final boolean CAR = "car".equals(System.getenv("LAPRACE_DEMO"));

	private static void car(Minecraft mc) {
		t++;
		if (t == 40) {
			cmd(mc, "tp @a 168.5 -59 216.5 160 10");
			cmd(mc, "summon minecraft:oak_boat 168.5 -59.5 216.5 {Rotation:[160f,0f]}");
			cmd(mc, "summon minecraft:birch_boat 172.5 -59.5 215.5 {Rotation:[100f,0f]}");
			cmd(mc, "time set noon");
		}
		if (t == 100) {
			cmd(mc, "ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		}
		if (t == 120) {
			for (Track tr : RaceSession.INSTANCE.tracks) {
				if (tr.name.equals("route3")) {
					RaceSession.INSTANCE.setTrack(tr);
				}
			}
		}
		float yaw = mc.player.getVehicle() != null ? mc.player.getVehicle().getYRot() : 160;
		String[] views = {"back", "side", "front", "first"};
		for (int i = 0; i < views.length; i++) {
			int at = 140 + i * 30;
			if (i == 1 && t > at && t < at + 20) {
				// the side view: hold the look turned (riding, the game pulls it back with the boat)
				mc.player.setYRot(yaw + 85);
				mc.player.setYHeadRot(yaw + 85);
			}
			if (t == at) {
				mc.options.setCameraType(i == 3 ? net.minecraft.client.CameraType.FIRST_PERSON
						: i == 2 ? net.minecraft.client.CameraType.THIRD_PERSON_FRONT : net.minecraft.client.CameraType.THIRD_PERSON_BACK);
				mc.player.setYRot(yaw + (i == 1 ? 80 : 0));
				mc.player.setXRot(i == 3 ? 10 : 20);
			}
			if (t == at + 20) {
				LapRaceClient.LOG.info("[car] shot {}", views[i]);
				Screenshot.grab(mc, false);
			}
		}
		if (t == 280) {
			mc.stop();
		}
	}

	/** LAPRACE_DEMO=drive: into a boat on route3 and the autopilot laps it for good (practice), for the live map. */
	private static final boolean DRIVE = "drive".equals(System.getenv("LAPRACE_DEMO"));

	private static void drive(Minecraft mc) {
		t++;
		if (t == 40) {
			cmd(mc, "tp @a 205.8 -59 286.7 154 0");
			cmd(mc, "summon minecraft:oak_boat 205.8 -59.5 286.7 {Rotation:[154f,0f]}");
		}
		if (t == 100) {
			cmd(mc, "ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		}
		if (t == 130) {
			for (Track tr : RaceSession.INSTANCE.tracks) {
				if (tr.name.equals("route3")) {
					RaceSession.INSTANCE.setTrack(tr);
				}
			}
		}
		if (t == 160 && !Cruise.enabled) {
			Cruise.toggle(mc);
		}
	}

	private static boolean cleaned;
	private static int cleanWait;

	/** Demos summon boats: before one starts, every empty boat in the world goes (the ridden one stays). */
	private static void cleanBoats(Minecraft mc) {
		var srv = mc.getSingleplayerServer();
		if (srv == null) {
			return;
		}
		srv.execute(() -> {
			int n = 0;
			for (var level : srv.getAllLevels()) {
				java.util.List<net.minecraft.world.entity.Entity> empty = new java.util.ArrayList<>();
				for (var e : level.getAllEntities()) {
					if (e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat && !e.isVehicle()) {
						empty.add(e);
					}
				}
				for (var e : empty) {
					e.discard();
					n++;
				}
			}
			LapRaceClient.LOG.info("[demo] removed {} empty boats", n);
		});
	}

	/** LAPRACE_DEMO=limiter: a driver at the keys (W held, A/D along the pit lane) into the pits, the limiter checked. */
	private static final boolean LIMITER = "limiter".equals(System.getenv("LAPRACE_DEMO"));

	private static void limiter(Minecraft mc) {
		t++;
		if (t == 30) {
			cleaned = true;
			cleanBoats(mc);
		}
		if (t == 40) {
			LapConfig.INSTANCE.pitMode = LapConfig.PitMode.MANUAL; // no automatic pit stop: the keys drive
			for (Track tr : RaceSession.INSTANCE.tracks) {
				if (tr.name.equals("route3")) {
					RaceSession.INSTANCE.setTrack(tr);
				}
			}
		}
		if (t == 50) {
			// start on the lane well before it leaves the track, bow along it
			PitLane l0 = PitLane.of(RaceSession.INSTANCE.track);
			int s0 = Math.max(0, l0.firstOff - 60);
			double[] a0 = l0.pts.get(s0), a1 = l0.pts.get(s0 + 3);
			float yaw = (float) Math.toDegrees(Math.atan2(-(a1[0] - a0[0]), a1[2] - a0[2]));
			String at = String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", a0[0], a0[1], a0[2]);
			cmd(mc, "tp @a " + at + " " + yaw + " 0");
			cmd(mc, "summon minecraft:oak_boat " + at + " {Rotation:[" + yaw + "f,0f]}");
			LapRaceClient.LOG.info("[limiter] start at lane {} (leaves the track at {})", s0, l0.firstOff);
		}
		if (t == 100) {
			cmd(mc, "ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		}
		var boat = mc.player.getVehicle();
		PitLane l = PitLane.of(RaceSession.INSTANCE.track);
		if (t < 130 || boat == null || l == null) {
			return;
		}
		// the "driver": W held, steering at a lane point a few blocks ahead
		int k = nearFrom(l, boat.getX(), boat.getZ());
		double[] aim = l.pts.get(Math.min(l.pts.size() - 1, k + 6));
		double want = Math.toDegrees(Math.atan2(-(aim[0] - boat.getX()), aim[2] - boat.getZ()));
		double err = net.minecraft.util.Mth.wrapDegrees(want - boat.getYRot());
		boolean end = k >= l.pts.size() - 3 || t > 1400;
		mc.options.keyUp.setDown(!end);
		mc.options.keyLeft.setDown(!end && err < -4);
		mc.options.keyRight.setDown(!end && err > 4);
		// the box guide, seen on the way in (third person)
		PitLane.Box box = l.chosen();
		if (box != null) {
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			for (int before : new int[] {40, 12}) {
				if (k >= box.at() - before && !shots.contains(before)) {
					shots.add(before);
					LapRaceClient.LOG.info("[limiter] shot {} points before box {}", before, box.number());
					Screenshot.grab(mc, false);
				}
			}
		}
		if (t % 5 == 0) {
			var v = boat.getDeltaMovement();
			LapRaceClient.LOG.info("[limiter] lane {} (red {}..{}) speed {} km/h, limiter {} cap {} km/h", k, l.limitFrom(), l.limitTo(),
					Math.round(Math.hypot(v.x, v.z) * 72), dev.laprace.cruise.PitLimiter.active,
					Math.round(dev.laprace.cruise.PitLimiter.cap * 3.6));
		}
		if (end && t % 20 == 0) {
			mc.options.keyUp.setDown(false);
			LapRaceClient.LOG.info("[limiter] done");
			mc.stop();
		}
	}

	private static int laneHint = -1;
	private static final java.util.Set<Integer> shots = new java.util.HashSet<>();

	/** Nearest lane point near the last one (the lane runs back alongside itself: a global search can jump). */
	private static int nearFrom(PitLane l, double x, double z) {
		if (laneHint < 0) {
			laneHint = l.nearest(x, z);
		}
		int best = laneHint;
		double bd = Double.MAX_VALUE;
		for (int i = Math.max(0, laneHint - 5); i < Math.min(l.pts.size(), laneHint + 15); i++) {
			double[] q = l.pts.get(i);
			double d = (q[0] - x) * (q[0] - x) + (q[2] - z) * (q[2] - z);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return laneHint = best;
	}

	/** LAPRACE_DEMO=race-grid / race-rolling: a server race of one, its start driven by the autopilot. */
	private static final String RSTART = System.getenv("LAPRACE_DEMO") != null && System.getenv("LAPRACE_DEMO").startsWith("race-")
			? System.getenv("LAPRACE_DEMO").substring(5) : null;
	private static int rt;
	private static String rLast = "";

	private static void raceStart(Minecraft mc) {
		var b = mc.player.getVehicle();
		rt++;
		if (rt == 40) {
			if (b != null) {
				cmd(mc, "execute as @p on vehicle run tp @s 168.5 -59.5 216.5 160 0");
			} else {
				cmd(mc, "tp @a 168.5 -59 216.5 160 0");
				cmd(mc, "summon minecraft:oak_boat 168.5 -59.5 216.5 {Rotation:[160f,0f]}");
			}
		}
		if (rt == 100 && b == null) {
			cmd(mc, "ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		}
		if (rt == 150) {
			for (Track tr : RaceSession.INSTANCE.tracks) {
				if (tr.name.equals("route3")) {
					RaceSession.INSTANCE.setTrack(tr);
				}
			}
			cmd(mc, "execute as @p on vehicle run tp @s 115.3 -59.5 212.9 -159 0"); // on route3, 150 blocks before the line
		}
		if (rt == 160) {
			mc.gui.setScreen(new SettingsScreen(null));
		}
		if (rt == 175) {
			net.minecraft.client.Screenshot.grab(mc, false);
		}
		if (rt == 185 && mc.gui.screen() != null) {
			mc.gui.screen().onClose();
		}
		if (rt == 200) {
			ServerRace.simple("cancel");
		}
		if (rt == 215 || rt == 262 || rt == 330) {
			RaceScreen.open(mc);
		}
		if ("lights".equals(RSTART) && (rt == 335 || rt == 362)) {
			net.minecraft.client.Screenshot.grab(mc, false);
		}
		if (rt == 225 || rt == 272 || rt == 340) {
			net.minecraft.client.Screenshot.grab(mc, false);
		}
		if ((rt == 230 || rt == 277 || rt == 345) && mc.gui.screen() != null) {
			mc.gui.screen().onClose();
		}
		if (rt == 240) {
			if ("lights".equals(RSTART)) {
				com.google.gson.JsonObject o = new com.google.gson.JsonObject();
				o.addProperty("format", "race");
				o.addProperty("laps", 1);
				o.addProperty("start", "self");
				o.addProperty("lights", true);
				ServerRace.create("amateur", "route3", o);
			} else if ("quali".equals(RSTART)) {
				ServerRace.createQuali("amateur", "route3", 1);
			} else {
				ServerRace.create("amateur", "route3", 1, RSTART);
			}
		}
		if (rt == 280) {
			ServerRace.simple("start");
		}
		ServerRace r = ServerRace.current();
		String st = (r == null ? "none" : r.status) + " phase " + RaceSession.INSTANCE.phase + " cruise " + dev.laprace.cruise.Cruise.enabled
				+ " start " + dev.laprace.cruise.RaceStart.active() + " parked " + dev.laprace.cruise.RaceStart.parked();
		if (!st.equals(rLast) || rt % 100 == 0) {
			rLast = st;
			Racer me = RaceSession.INSTANCE.self;
			LapRaceClient.LOG.info("[racestart] t {} {} | s {} v {} at {} {}", rt, st, me == null ? "-" : String.format("%.1f", me.s),
					b == null ? "-" : String.format("%.1f", b.getDeltaMovement().horizontalDistance() * 20),
					b == null ? "-" : String.format("%.1f", b.getX()), b == null ? "-" : String.format("%.1f", b.getZ()));
		}
	}

	/** LAPRACE_DEMO=ui: the screens - a settings text field clicked and typed into, the event setup, the pit boxes. */
	private static final boolean UI = "ui".equals(System.getenv("LAPRACE_DEMO"));
	private static int ut;

	private static void ui(Minecraft mc) {
		ut++;
		if (ut == 40) {
			for (Track tr : RaceSession.INSTANCE.tracks) {
				if (tr.name.equals("route3")) {
					RaceSession.INSTANCE.setTrack(tr);
				}
			}
			cmd(mc, "tp @a 168.5 -59 216.5 160 0");
		}
		if (ut == 100) {
			mc.gui.setScreen(new SettingsScreen(null));
		}
		if (ut == 105 && mc.gui.screen() instanceof SettingsScreen ss) {
			dev.laprace.ui.El.Text t = ss.devSection(8);
			if (t == null) {
				LapRaceClient.LOG.info("[ui] no text field in section 8");
			} else {
				double x = t.x + t.w / 2, y = t.y + t.h / 2;
				var info = new net.minecraft.client.input.MouseButtonInfo(1, 0); // (this mouse's left button arrives as 1)
				ss.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y, info), false);
				ss.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(x, y, info));
				LapRaceClient.LOG.info("[ui] text field clicked: focused {}, value '{}'", t.box.isFocused(), t.box.getValue());
				t.box.moveCursorToEnd(false);
				t.box.insertText("/x");
				LapRaceClient.LOG.info("[ui] typed: value '{}', config {}", t.box.getValue(), LapConfig.INSTANCE.serverUrl);
				t.box.deleteChars(-2);
			}
		}
		if (ut == 115 || ut == 160 || ut == 205) {
			net.minecraft.client.Screenshot.grab(mc, false);
		}
		if ((ut == 125 || ut == 170) && mc.gui.screen() != null) {
			mc.gui.screen().onClose();
		}
		if (ut == 140) {
			RaceScreen.open(mc);
		}
		if (ut == 185) {
			PitScreen.open(mc);
		}
	}

	/** LAPRACE_DEMO=ghost: the autopilot drives laps; once one is the ghost, an empty tank slows us and the ghost pulls away. */
	private static final boolean GHOST = "ghost".equals(System.getenv("LAPRACE_DEMO"));
	/** LAPRACE_DEMO=line: as ghost (the autopilot drives route3), the racing line shown; screenshots on the way. */
	public static final boolean LINE = "line".equals(System.getenv("LAPRACE_DEMO"));
	/** LAPRACE_DEMO=settings: every section of the settings, a screenshot of each (its top and its bottom). */
	private static final boolean SETTINGS = "settings".equals(System.getenv("LAPRACE_DEMO"));
	/** LAPRACE_DEMO=garage: the garage open, a screenshot. */
	private static final boolean GARAGE = "garage".equals(System.getenv("LAPRACE_DEMO"));
	private static int st;

	private static void settings(Minecraft mc) {
		st++;
		if (st < 80) {
			return;
		}
		int step = (st - 80) / 25, phase = (st - 80) % 25;
		int section = step / 2;
		boolean bottom = step % 2 == 1;
		if (section >= 9) {
			if (phase == 0 && step == 18) {
				mc.gui.setScreen(null);
			}
			return;
		}
		if (phase == 0) {
			if (!(mc.gui.screen() instanceof SettingsScreen)) {
				mc.gui.setScreen(new SettingsScreen(null));
			}
			SettingsScreen s = (SettingsScreen) mc.gui.screen();
			s.devSection(section);
			if (!s.devScroll(bottom ? 1 : 0) && bottom) {
				st += 24; // nothing below: on to the next
			}
		}
		if (phase == 18) {
			net.minecraft.client.Screenshot.grab(mc, false);
		}
	}
	private static int gt, slowAt = -1;

	private static void ghost(Minecraft mc) {
		gt++;
		var b = mc.player.getVehicle();
		if (gt == 40) {
			if (b != null) {
				cmd(mc, "execute as @p on vehicle run tp @s 115.3 -59.5 212.9 -159 0");
			} else {
				cmd(mc, "tp @a 115.3 -59 212.9 -159 0");
				cmd(mc, "summon minecraft:oak_boat 115.3 -59.5 212.9 {Rotation:[-159f,0f]}");
			}
		}
		if (gt == 100 && b == null) {
			cmd(mc, "ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		}
		if (gt == 150) {
			for (Track tr : RaceSession.INSTANCE.tracks) {
				if (tr.name.equals("route3")) {
					RaceSession.INSTANCE.setTrack(tr);
				}
			}
		}
		if (gt == 200) {
			dev.laprace.cruise.Cruise.toggle(mc);
		}
		Racer me = RaceSession.INSTANCE.self;
		if (gt % 100 == 0) {
			var gb = mc.level.getEntity(-7_000_001);
			LapRaceClient.LOG.info("[ghost] t {} laps {} gap {} ghost {} cruise {} entity {}", gt, me == null ? -1 : me.lapsDone, Ghost.gap, Ghost.time("route3"), dev.laprace.cruise.Cruise.enabled,
					gb == null ? "none" : String.format("%.1f %.1f %.1f, %.1f from us", gb.getX(), gb.getY(), gb.getZ(), gb.distanceTo(mc.player)));
		}
		if (slowAt < 0 && me != null && me.lapStart >= 0 && RaceSession.INSTANCE.now - me.lapStart > 300 && Ghost.time("route3") > 0) {
			slowAt = gt;
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		}
		if (slowAt > 0 && gt - slowAt < 30) {
			if (!LINE) {
				Engine.fuel = 0; // (kept empty: no power)
			}
		}
		if (slowAt > 0 && (gt - slowAt) % 40 == 0 && gt - slowAt <= 200) {
			var gb = mc.level.getEntity(-7_000_001);
			LapRaceClient.LOG.info("[ghost] +{} gap {} entity {}", gt - slowAt, Ghost.gap, gb == null ? "none" : String.format("%.1f from us", gb.distanceTo(mc.player)));
		}
		if (slowAt > 0 && (gt - slowAt == 60 || gt - slowAt == 120)) {
			net.minecraft.client.Screenshot.grab(mc, false);
		}
	}

	/** LAPRACE_DEMO=themes: each window theme, the event setup and the settings, a screenshot of each. */
	private static final boolean THEMES = "themes".equals(System.getenv("LAPRACE_DEMO"));
	private static int tt;

	private static void themes(Minecraft mc) {
		tt++;
		if (tt == 40) {
			for (Track tr : RaceSession.INSTANCE.tracks) {
				if (tr.name.equals("route3")) {
					RaceSession.INSTANCE.setTrack(tr);
				}
			}
		}
		int i = (tt - 100) / 60, phase = (tt - 100) % 60;
		if (tt < 100 || i >= dev.laprace.ui.Ui.THEMES.length) {
			if (tt == 100 + 60 * dev.laprace.ui.Ui.THEMES.length) {
				LapConfig.INSTANCE.uiTheme = 0;
				mc.gui.setScreen(null);
			}
			return;
		}
		if (phase == 0) {
			LapConfig.INSTANCE.uiTheme = i;
			RaceScreen.open(mc);
		}
		if (phase == 20 || phase == 50) {
			net.minecraft.client.Screenshot.grab(mc, false);
		}
		if (phase == 30) {
			SettingsScreen ss = new SettingsScreen(null);
			mc.gui.setScreen(ss);
			ss.devSection(2);
		}
	}

	public static void tick(Minecraft mc) {
		if (THEMES) {
			if (mc.player != null && mc.level != null) {
				themes(mc);
			}
			return;
		}
		if ("garage2".equals(System.getenv("LAPRACE_DEMO"))) {
			if (mc.player != null && mc.level != null) {
				st++;
				if (st == 80) {
					try {
						byte[] png = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getenv("LAPRACE_PNG")));
						mc.gui.setScreen(new LiveryEditScreen(new GarageScreen(null), png, "my_cool_livery.png"));
					} catch (Exception e) {
						LapRaceClient.LOG.warn("demo: {}", e.toString());
					}
				}
				if (st == 110 || st == 200) {
					net.minecraft.client.Screenshot.grab(mc, false);
				}
				if (st == 140 && mc.gui.screen() instanceof LiveryEditScreen les) {
					les.devDone();
				}
				if (st == 170 && mc.gui.screen() instanceof GarageScreen gs) {
					gs.devMenu();
				}
			}
			return;
		}
		if ("crewok".equals(System.getenv("LAPRACE_DEMO"))) {
			if (mc.player != null && mc.level != null) {
				st++;
				if (st == 80) {
					dev.laprace.net.CrewOk.received("Drv");
				}
				if (st == 90) {
					dev.laprace.net.CrewOk.received("TheCopsy");
				}
				if (st == 110) {
					net.minecraft.client.Screenshot.grab(mc, false);
				}
			}
			return;
		}
		if (GARAGE) {
			if (mc.player != null && mc.level != null) {
				st++;
				if (st == 80) {
					mc.gui.setScreen(new GarageScreen(null));
				}
				if (st == 120 || st == 160) {
					net.minecraft.client.Screenshot.grab(mc, false);
				}
			}
			return;
		}
		if (SETTINGS) {
			if (mc.player != null && mc.level != null) {
				settings(mc);
			}
			return;
		}
		if (GHOST || LINE) {
			if (mc.player != null && mc.level != null && !mc.isPaused()) {
				ghost(mc);
				if (LINE && gt >= 300 && gt <= 900 && gt % 120 == 0) {
					mc.options.setCameraType(gt % 240 == 0 ? net.minecraft.client.CameraType.THIRD_PERSON_BACK : net.minecraft.client.CameraType.FIRST_PERSON);
				}
				if (LINE && gt >= 330 && gt <= 930 && (gt - 330) % 120 == 0) {
					net.minecraft.client.Screenshot.grab(mc, false);
				}
			}
			return;
		}
		if (UI) {
			if (mc.player != null && mc.level != null) {
				ui(mc);
			}
			return;
		}
		if (LIMITER) {
			if (!mc.isPaused() && mc.player != null && mc.level != null) {
				limiter(mc);
			}
			return;
		}
		// once the world around has loaded (at the very first tick the boats are not there yet)
		if (!cleaned && System.getenv("LAPRACE_DEMO") != null && mc.player != null && mc.level != null && ++cleanWait > 30) {
			cleaned = true;
			cleanBoats(mc);
		}
		if (DRIVE) {
			if (!mc.isPaused() && mc.player != null && mc.level != null) {
				drive(mc);
			}
			return;
		}
		if (CAR) {
			if (!mc.isPaused() && mc.player != null && mc.level != null) {
				car(mc);
			}
			return;
		}
		if (RSTART != null) {
			if (!mc.isPaused() && mc.player != null && mc.level != null) {
				raceStart(mc);
			}
			return;
		}
		if (ALL) {
			if (!mc.isPaused() && mc.player != null && mc.level != null) {
				if (mc.player.tickCount % 40 == 0 && RaceSession.boatOf(mc.player) != null) {
					var v = RaceSession.boatOf(mc.player).getDeltaMovement();
					LapRaceClient.LOG.info("[fuel] {} of {} rate {} power {} speed {} throttle {}", String.format("%.1f", Engine.fuel), Engine.MAX_FUEL,
							Engine.fuelRate(), Engine.power(), String.format("%.1f", Math.hypot(v.x, v.z) * 20), Engine.throttle);
				}
				if (System.getenv("LAPRACE_FUEL") != null && mc.player.tickCount == 300) {
					Engine.fuel = Double.parseDouble(System.getenv("LAPRACE_FUEL")); // dev: start the run with this much
				}
				all(mc);
			}
			return;
		}
		if (PITS) {
			if (!mc.isPaused()) {
				pits(mc);
			}
			return;
		}
		if (!ON || mc.player == null || mc.level == null || mc.isPaused()) {
			return;
		}
		RaceSession s = RaceSession.INSTANCE;
		if (t < 0) {
			if (RaceSession.boatOf(mc.player) == null || s.track == null) {
				return;
			}
			t = 0;
		}
		t++;
		if (t == 60 && !Cruise.enabled) {
			Cruise.toggle(mc);
		}
		if (t == 100) {
			LapConfig.INSTANCE.raceLaps = 2;
			s.toggleRace(mc);
		}
		long[] at = {150, 260, 700, 1300, 1900, 2500, 3100, 3700, 4300};
		for (long a : at) {
			if (t == a) {
				if (shot >= 1) {
					LapConfig.INSTANCE.mfdPage = (LapConfig.INSTANCE.mfdPage + 1) % Hud.mfdPages();
				}
				Screenshot.grab(mc, false);
				shot++;
			}
		}
	}
}
