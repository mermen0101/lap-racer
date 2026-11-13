package dev.laprace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The race on this client: the track in use, every boat on it, laps, sectors, positions and gaps.
 * <p>
 * Two modes. PRACTICE (always, when no race runs): everyone's laps are timed, the board is ordered by best lap.
 * RACE (key U): countdown, then N laps; the board is ordered by position on the road, gaps are live time gaps
 * (when the boat behind reaches the point where the one ahead was), the first to finish N laps wins and everyone
 * else finishes at their next pass of the line; then the results.
 * <p>
 * Client side only, so it works on any server: other players are timed while their boats are in view.
 */
public final class RaceSession {
	public enum Phase { PRACTICE, COUNTDOWN, RUNNING, FINISHED }

	public static final int[] COLORS = {
			0xFFE8343A, 0xFF2F8CFF, 0xFFFFC21A, 0xFF35D07F, 0xFFB45CFF,
			0xFFFF7A1A, 0xFF1FD6D6, 0xFFFF5CB8, 0xFFB8E01A, 0xFFE0E0E0};

	public static final RaceSession INSTANCE = new RaceSession();

	public List<Track> tracks = new ArrayList<>();
	public Track track;
	public Phase phase = Phase.PRACTICE;
	/** Client ticks since the world was joined (not counted while paused). */
	public long now;
	public final Map<UUID, Racer> racers = new HashMap<>();
	public List<Racer> order = new ArrayList<>();
	public Racer self;

	// ---- race
	public int raceLaps;
	public long countdownEnd;
	/** Start lights instead of a countdown: the tick the first of the five comes on (one a second), -1 = a countdown. */
	public long lightsFrom = -1;
	public long goTick;
	public long resultsUntil;
	public int finishedCount;
	public double leaderFinishTick = -1;
	/** Fastest sector of anyone this session (purple). */
	public double[] sessionBestSectors;
	public double sessionBestLap = -1;
	private final Set<UUID> gridBehind = new HashSet<>();

	// ---- personal best on this track (kept across sessions)
	public double pbLap = -1;
	public double[] pbSectors;
	public double[] pbLine;

	// ---- for the HUD
	public final List<Popup> popups = new ArrayList<>();
	/** Live delta to the personal best (seconds; NaN = none). */
	public double liveDelta = Double.NaN;
	/** Tick our final lap began (GT shows FINAL LAP over the timer once, ~2 s, then it fades); -1 = not yet. */
	public long finalLapTick = -1;
	public boolean wrongWay;
	/** After crossing the line the timer shows that lap for a few seconds (GT style). */
	public long lapFlashUntil;
	public double lapFlashTime;
	public boolean lapFlashPb;

	private final Track.Projection proj = new Track.Projection();

	/** GT-style warning line (red text with an icon, centre of the screen). */
	public record Message(String text, int color, long until) {
	}

	public final List<Message> messages = new ArrayList<>();
	/** Fuel used per lap (average of our laps), for "fuel left for N laps". */
	public double fuelPerLap = -1;
	private double fuelAtLapStart = -1;
	private long nextFuelWarning;

	/** Another mod drove the car for us on this lap (see LapRaceApi.assisted): it is not sent to the server. */
	public boolean assistedLap;

	public void message(String text, int color, int ticks) {
		messages.removeIf(m -> m.text().equals(text));
		messages.add(new Message(text, color, now + ticks));
	}

	public record Popup(String title, String value, int color, long from, long until, boolean big) {
	}

	private RaceSession() {
	}

	private static LapConfig cfg() {
		return LapConfig.INSTANCE;
	}

	public int sectorCount() {
		return cfg().sectors;
	}

	private int buckets() {
		return track == null ? 1 : (int) Math.ceil(track.length / Racer.BUCKET) + 2;
	}

	public void reloadTracks() {
		tracks = Track.loadAll();
		Track keep = track;
		track = null;
		if (keep != null) {
			for (Track t : tracks) {
				if (t.name.equals(keep.name)) {
					setTrack(t);
				}
			}
		}
	}

	public void setTrack(Track t) {
		if (t == track) {
			return;
		}
		track = t;
		racers.clear();
		order = new ArrayList<>();
		self = null;
		phase = Phase.PRACTICE;
		sessionBestSectors = null;
		sessionBestLap = -1;
		popups.clear();
		Bests.Entry pb = t == null ? null : Bests.get(t.name, sectorCount());
		pbLap = pb != null ? pb.lap : -1;
		pbSectors = pb != null ? pb.sectors : null;
		pbLine = pb != null ? pb.line : null;
		if (t != null) {
			cfg().lastTrack = t.name;
		}
	}

	public void cycleTrack(Minecraft mc) {
		if (tracks.isEmpty()) {
			return;
		}
		int i = track == null ? -1 : tracks.indexOf(track);
		setTrack(tracks.get((i + 1) % tracks.size()));
		LapConfig.save();
		say(mc, Component.translatable("laprace.msg.track", track.displayName()));
	}

	/** Leaves the world: forget the boats, keep the track. */
	public void clearWorld() {
		racers.clear();
		order = new ArrayList<>();
		self = null;
		phase = Phase.PRACTICE;
		popups.clear();
	}

	// ------------------------------------------------------------------ race control

	public void toggleRace(Minecraft mc) {
		if (track == null) {
			say(mc, Component.translatable("laprace.msg.no_track"));
			return;
		}
		if (serverRace && (phase == Phase.COUNTDOWN || phase == Phase.RUNNING)) {
			say(mc, Component.translatable("laprace.race.server_running"));
			return;
		}
		if (phase == Phase.COUNTDOWN || phase == Phase.RUNNING) {
			phase = Phase.PRACTICE;
			resetAllTiming();
			say(mc, Component.translatable("laprace.msg.race_aborted"));
			return;
		}
		raceLaps = cfg().raceLaps;
		phase = Phase.COUNTDOWN;
		if (cfg().startLights) {
			// a second's warning, five lights a second apart, out after 0.2 - 3 s
			lightsFrom = now + 20;
			countdownEnd = lightsFrom + 80 + 4 + new java.util.Random().nextInt(57);
		} else {
			lightsFrom = -1;
			countdownEnd = now + cfg().countdownSeconds * 20L;
		}
		finishedCount = 0;
		leaderFinishTick = -1;
		resetAllTiming();
		say(mc, Component.translatable("laprace.msg.race_start", raceLaps, track.displayName()));
	}

	/** The race now running is the server's (its drivers only, its laps; the R key does not stop it). */
	public boolean serverRace;
	/** The server's race by time: when its time is up (our clock, ms; 0 = by laps). */
	public long raceEndWall;
	/** Its drivers' names, lower case. */
	private java.util.Set<String> serverDrivers = java.util.Set.of();

	/** The server's race counts down (goInMs from now) and we are in it: on its track, its laps, its drivers only. */
	public void startServerRace(Minecraft mc, String trackName, int laps, long goInMs, java.util.List<String> drivers) {
		if (prepareServerRace(mc, trackName, laps, drivers)) {
			serverCountdown(goInMs);
		}
	}

	/** No start time yet: a grid being formed, a rolling start on its way to the line (no countdown shown). */
	public static final long WAITING = Long.MAX_VALUE / 4;

	/** The server's race, we in it, its start not yet timed (grid, rolling): its track, laps, drivers. */
	public boolean prepareServerRace(Minecraft mc, String trackName, int laps, java.util.List<String> drivers) {
		for (Track t : tracks) {
			if (t.name.equals(trackName) && t != track) {
				setTrack(t);
			}
		}
		if (track == null || !track.name.equals(trackName)) {
			say(mc, Component.translatable("laprace.msg.no_track"));
			return false;
		}
		serverRace = true;
		raceEndWall = 0;
		serverDrivers = java.util.Set.copyOf(drivers);
		raceLaps = laps;
		finishedCount = 0;
		leaderFinishTick = -1;
		resetAllTiming();
		// only the race's drivers on the board
		racers.values().removeIf(r -> !r.self && !serverDrivers.contains(r.name.toLowerCase(java.util.Locale.ROOT)));
		phase = Phase.COUNTDOWN;
		countdownEnd = now + WAITING;
		lightsFrom = -1;
		say(mc, Component.translatable("laprace.msg.race_start", raceLaps, track.displayName()));
		return true;
	}

	/** The lights: out goInMs from now. */
	public void serverCountdown(long goInMs) {
		if (phase == Phase.COUNTDOWN) {
			countdownEnd = now + Math.max(0, Math.round(goInMs / 50.0));
		}
	}

	/** Start lights: the first comes on lightsInMs from now, all out goInMs from now. */
	public void serverLights(long lightsInMs, long goInMs) {
		if (phase == Phase.COUNTDOWN) {
			lightsFrom = now + Math.max(0, Math.round(lightsInMs / 50.0));
			countdownEnd = now + Math.max(0, Math.round(goInMs / 50.0));
		}
	}

	/** A rolling start: the first car is over the line - the race is on (now). */
	public void serverGo(Minecraft mc) {
		if (phase == Phase.COUNTDOWN) {
			go(mc);
		}
	}

	/** The server's race was cancelled (or left): back to practice. */
	public void endServerRace() {
		serverRace = false;
		raceEndWall = 0;
		serverDrivers = java.util.Set.of();
		if (phase == Phase.COUNTDOWN || phase == Phase.RUNNING) {
			phase = Phase.PRACTICE;
			resetAllTiming();
		}
	}

	private boolean outOfServerRace(String name) {
		return serverRace && !serverDrivers.contains(name.toLowerCase(java.util.Locale.ROOT));
	}

	public void resetSession(Minecraft mc) {
		phase = Phase.PRACTICE;
		resetAllTiming();
		sessionBestLap = -1;
		sessionBestSectors = null;
		say(mc, Component.translatable("laprace.msg.reset"));
	}

	private void resetAllTiming() {
		for (Racer r : racers.values()) {
			r.resetTiming(sectorCount(), buckets());
			r.lapOffset = 0;
			r.maxLapOffset = 0;
		}
		popups.clear();
		gridBehind.clear();
		assistedLap = false;
	}

	private void go(Minecraft mc) {
		dev.laprace.cruise.RaceStart.lightsOut(mc);
		phase = Phase.RUNNING;
		goTick = now;
		gridBehind.clear();
		// the race's own fastest lap: the practice's would stay on the board with no one holding it
		sessionBestLap = -1;
		sessionBestSectors = null;
		for (Racer r : racers.values()) {
			r.resetTiming(sectorCount(), buckets());
			boolean behind = r.s > track.length / 2;
			r.lapOffset = behind ? -1 : 0;
			r.maxLapOffset = r.lapOffset;
			r.lapStart = now;
			r.lapStartMs = System.currentTimeMillis();
			r.sectorStart = now;
			r.sectorReached = 0;
			if (behind) {
				gridBehind.add(r.id);
			}
			r.markTimeline(r.progress(track.length), track.length, now, r.progress(track.length), now);
		}
		finalLapTick = raceLaps == 1 ? now : -1;
		sound(mc, SoundEvents.NOTE_BLOCK_PLING, 2.0f);
	}

	// ------------------------------------------------------------------ tick

	public void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			if (!racers.isEmpty()) {
				clearWorld();
			}
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		now++;
		popups.removeIf(p -> p.until < now);
		messages.removeIf(m -> m.until() < now);

		Entity myBoat = boatOf(mc.player);
		autoPickTrack(mc, myBoat);
		if (track == null) {
			return;
		}

		if (phase == Phase.COUNTDOWN) {
			long left = countdownEnd - now;
			if (lightsFrom >= 0) {
				long on = now - lightsFrom;
				if (on >= 0 && on <= 80 && on % 20 == 0 && left > 0) {
					sound(mc, SoundEvents.NOTE_BLOCK_HAT, 1.0f); // a light comes on
				}
			} else if (left > 0 && left <= 200 && left % 20 == 0) {
				sound(mc, SoundEvents.NOTE_BLOCK_HAT, 1.0f);
			}
			if (left <= 0) {
				go(mc);
			}
		}

		// who is on the track
		Set<UUID> seen = new HashSet<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof AbstractBoat boat)) {
				continue;
			}
			// the racer is the player in the boat (a pig or any other mob in a boat is not a racer)
			Entity pass = null;
			for (Entity p : boat.getPassengers()) {
				if (p instanceof net.minecraft.world.entity.player.Player) {
					pass = p;
					break;
				}
			}
			if (pass == null && !boat.getPassengers().isEmpty()) {
				continue;
			}
			boolean isSelf = boat == myBoat;
			if (!isSelf && (!cfg().trackOthers || (pass == null && !boat.hasCustomName()))) {
				continue;
			}
			UUID id = pass != null ? pass.getUUID() : boat.getUUID();
			Racer r = racers.get(id);
			double bx = boat.getX(), bz = boat.getZ();
			if (r == null) {
				if (phase == Phase.RUNNING || phase == Phase.FINISHED) {
					continue; // the race has its field
				}
				track.project(bx, bz, -1, proj);
				if (proj.off > cfg().joinDistance) {
					continue;
				}
				String name = pass != null ? pass.getName().getString()
						: boat.getCustomName() != null ? boat.getCustomName().getString() : "Boat";
				if (!isSelf && outOfServerRace(name)) {
					continue; // not in the server's race: not on its board
				}
				r = new Racer(id, name, isSelf, isSelf ? 0 : COLORS[Math.floorMod(id.hashCode(), COLORS.length)]);
				r.resetTiming(sectorCount(), buckets());
				r.idx = proj.idx;
				r.s = proj.s;
				r.off = proj.off;
				r.px = r.prevX = bx;
				r.pz = r.prevZ = bz;
				r.lastSeen = now;
				r.player = pass != null;
				racers.put(id, r);
				seen.add(id);
				if (isSelf && pbLine != null) {
					r.bestLine = pbLine;
				}
				continue;
			}
			seen.add(id);
			update(mc, r, bx, bz);
			pitCheck(mc, r, boat);
		}
		self = null;
		for (Iterator<Racer> it = racers.values().iterator(); it.hasNext(); ) {
			Racer r = it.next();
			r.inView = seen.contains(r.id);
			if (!r.inView) {
				r.speed = 0;
				// in practice: gone from the board as soon as the player has left the server (not in the tab list);
				// one just out of sight stays (with his laps) for 20 minutes
				boolean left = r.player && !r.self && mc.getConnection() != null && mc.getConnection().getPlayerInfo(r.id) == null;
				if (phase == Phase.PRACTICE && (left || now - r.lastSeen > 20L * 60)) {
					it.remove();
					continue;
				}
			}
			if (r.self && r.inView) {
				self = r;
			}
		}

		if (phase == Phase.RUNNING) {
			checkRaceEnd(mc);
		}
		if (phase == Phase.FINISHED && now > resultsUntil) {
			phase = Phase.PRACTICE;
			serverRace = false;
			serverDrivers = java.util.Set.of();
			resetAllTiming();
		}
		sortBoard();

		liveDelta = Double.NaN;
		wrongWay = false;
		if (self != null) {
			wrongWay = self.wrongWayTicks > 20;
			if (wrongWay) {
				message(Component.translatable("laprace.msg.wrong_way").getString(), 0xFFFF4A40, 10);
			}
			fuelWarning();
			double[] ref = self.bestLine != null ? self.bestLine : pbLine;
			if (ref != null && self.lapStart >= 0 && phase != Phase.COUNTDOWN) {
				int j = (int) (self.s / Racer.BUCKET);
				if (j < ref.length && ref[j] >= 0) {
					liveDelta = (now - self.lapStart) / 20.0 - ref[j];
				}
			}
		}
	}

	private final Track.Projection zoneProj = new Track.Projection();

	/**
	 * (x, z) is near some track (100 blocks, as the server's zone) or its pit lane: only there is the position sent to
	 * the server. With no tracks loaded: nowhere.
	 */
	public boolean inZone(double x, double z) {
		for (Track t : tracks) {
			t.project(x, z, -1, zoneProj);
			if (zoneProj.off < 100 || PitLane.near(t, x, z, 30)) {
				return true;
			}
		}
		return false;
	}

	private void autoPickTrack(Minecraft mc, Entity myBoat) {
		if (tracks.isEmpty()) {
			return;
		}
		if (track == null && cfg().lastTrack != null) {
			for (Track t : tracks) {
				if (t.name.equals(cfg().lastTrack)) {
					setTrack(t);
				}
			}
		}
		if (!cfg().autoTrack || phase != Phase.PRACTICE) {
			return;
		}
		Entity at = myBoat != null ? myBoat : mc.player;
		if (now % 20 != 0 && track != null) {
			return;
		}
		if (track != null) {
			track.project(at.getX(), at.getZ(), self != null ? self.idx : -1, proj);
			if (proj.off < 40) {
				return; // still on (or near) the chosen one
			}
			if (dev.laprace.cruise.Cruise.inPits() || PitLane.near(track, at.getX(), at.getZ(), 8)) {
				return; // in this track's pit lane (it may run along another track)
			}
		}
		Track best = null;
		double bestOff = 25;
		for (Track t : tracks) {
			t.project(at.getX(), at.getZ(), -1, proj);
			if (proj.off < bestOff) {
				bestOff = proj.off;
				best = t;
			}
		}
		if (best != null && best != track) {
			setTrack(best);
			say(mc, Component.translatable("laprace.msg.track_auto", best.displayName()));
		}
	}

	private void update(Minecraft mc, Racer r, double bx, double bz) {
		double L = track.length;
		r.prevX = r.px;
		r.prevZ = r.pz;
		r.px = bx;
		r.pz = bz;
		double mx = bx - r.prevX, mz = bz - r.prevZ;
		boolean wasHidden = now - r.lastSeen > 2;
		r.lastSeen = now;
		r.speed = wasHidden ? 0 : Math.hypot(mx, mz) * 20;

		double s0 = r.s;
		double p0 = r.progress(L);
		track.project(bx, bz, r.idx, proj);
		r.idx = proj.idx;
		r.off = proj.off;
		double s1 = proj.s;
		double ds = s1 - s0;
		boolean forwardWrap = false, backWrap = false;
		if (ds < -L / 2) {
			ds += L;
			forwardWrap = true;
		} else if (ds > L / 2) {
			ds -= L;
			backWrap = true;
		}
		r.s = s1;
		if (forwardWrap) {
			r.lapOffset++;
		} else if (backWrap) {
			r.lapOffset--;
		}

		// wrong way: moving against the track direction
		if (r.speed > 4 && (mx * proj.dx + mz * proj.dz) < -0.6 * Math.hypot(mx, mz)) {
			r.wrongWayTicks++;
		} else {
			r.wrongWayTicks = 0;
		}

		if (phase == Phase.COUNTDOWN) {
			return;
		}
		// a jump (teleport, respawn, unseen for long): no timing until the next pass of the line
		if (Math.abs(ds) > 40 || wasHidden && Math.abs(ds) > 15) {
			if (phase == Phase.PRACTICE) {
				r.lapStart = -1;
				r.sectorReached = 0;
			}
			r.maxLapOffset = Math.max(r.maxLapOffset, r.lapOffset);
			return;
		}
		if (r.off > cfg().offTrackDistance && phase == Phase.PRACTICE) {
			r.lapStart = -1; // far off the track: that lap does not count
		}

		double t0 = now - 1, t1 = now;
		int n = sectorCount();
		if (ds > 0) {
			double u0 = s0, u1 = s0 + ds;
			// sector boundaries before the line, then the line, then boundaries of the next lap (tiny tracks only)
			for (int k = 1; k < n; k++) {
				double b = L * k / n;
				if (u0 < b && b <= u1 && !forwardWrap) {
					sectorEvent(mc, r, k, t0 + (t1 - t0) * (b - u0) / ds);
				}
			}
			if (forwardWrap) {
				for (int k = 1; k < n; k++) {
					double b = L * k / n;
					if (u0 < b && b <= L) {
						sectorEvent(mc, r, k, t0 + (t1 - t0) * (b - u0) / ds);
					}
				}
				lineEvent(mc, r, t0 + (t1 - t0) * (L - u0) / ds);
			}
		} else if (ds < 0) {
			for (int k = n - 1; k >= 1; k--) {
				double b = L * k / n;
				boolean crossed = backWrap ? (s1 < b) : (s1 < b && b <= s0);
				if (crossed && r.sectorReached >= k) {
					r.sectorReached = k - 1;
				}
			}
		}

		if (r.lapStart >= 0 && ds > 0) {
			int j = (int) (r.s / Racer.BUCKET);
			if (j < r.lapLine.length && r.lapLine[j] < 0) {
				r.lapLine[j] = (now - r.lapStart) / 20.0;
			}
		}
		if (phase == Phase.RUNNING && !r.finished) {
			r.markTimeline(r.progress(L), L, now, p0, now - 1);
		}
	}

	/**
	 * Pit stop, by the same rule as the BolidF1 avatar's refuelling: black or yellow concrete under the boat. The
	 * chequered start line can be made of it too, so only staying on it (1 s) counts, and not during the start.
	 */
	private void pitCheck(Minecraft mc, Racer r, Entity boat) {
		// anywhere under the boat (it is wider than the pad), as the refuelling counts it
		boolean onPit = false;
		double hw = 1.375 / 2;
		for (int ix = -1; ix <= 1 && !onPit; ix++) {
			for (int iz = -1; iz <= 1 && !onPit; iz++) {
				net.minecraft.world.level.block.state.BlockState under = mc.level.getBlockState(net.minecraft.core.BlockPos.containing(
						boat.getX() + ix * hw, boat.getY() - 0.5, boat.getZ() + iz * hw));
				onPit = under.is(net.minecraft.world.level.block.Blocks.CONCRETE.black())
						|| under.is(net.minecraft.world.level.block.Blocks.CONCRETE.yellow());
			}
		}
		r.pitTicks = onPit ? r.pitTicks + 1 : 0;
		boolean starting = phase == Phase.COUNTDOWN || phase == Phase.RUNNING && now - goTick < 100;
		r.inPit = !starting && r.pitTicks >= 20;
		if (r.inPit) {
			r.pitThisLap = true;
		}
	}

	private void sectorEvent(Minecraft mc, Racer r, int k, double t) {
		if (k != r.sectorReached + 1) {
			return;
		}
		if (r.lapStart >= 0 && r.sectorStart >= 0 && !r.finished) {
			double st = (t - r.sectorStart) / 20.0;
			r.sectors[k - 1] = st;
			r.noteSector(k - 1, st);
			if (r.self) {
				splitPopup(mc, k, st);
			}
			updateSessionSector(k - 1, st);
		}
		r.sectorStart = t;
		r.sectorReached = k;
	}

	private void lineEvent(Minecraft mc, Racer r, double t) {
		int n = sectorCount();
		if (r.lapOffset <= r.maxLapOffset) {
			// back over the line and forward again: not a new lap; keep timing what runs
			return;
		}
		r.maxLapOffset = r.lapOffset;
		if (r.finished) {
			return;
		}
		if (gridBehind.remove(r.id)) {
			// started behind the line: this pass only starts lap 1 (timed from GO)
			r.sectorReached = 0;
			return;
		}
		boolean valid = r.lapStart >= 0 && r.sectorReached == n - 1 && r.sectorStart >= 0;
		if (valid) {
			double lap = (t - r.lapStart) / 20.0;
			double lastSec = (t - r.sectorStart) / 20.0;
			r.sectors[n - 1] = lastSec;
			r.noteSector(n - 1, lastSec);
			if (r.self) {
				splitPopup(mc, n, lastSec);
			}
			updateSessionSector(n - 1, lastSec);
			r.lapsDone++;
			if (r.self) {
				Ghost.lapDone(this, lap, !assistedLap);
			}
			r.lapTimes.add(lap);
			r.lapPit.add(r.pitThisLap);
			r.lastLap = lap;
			r.lastSectors = r.sectors.clone();
			boolean personal = r.bestLap < 0 || lap < r.bestLap;
			boolean overall = sessionBestLap < 0 || lap < sessionBestLap;
			if (personal) {
				r.bestLap = lap;
				r.bestSectors = r.sectors.clone();
				r.bestLine = r.lapLine.clone();
			}
			if (overall) {
				sessionBestLap = lap;
			}
			if (r.self) {
				selfLap(mc, lap, overall);
				if (!assistedLap) {
					dev.laprace.net.Backend.lap(track.name, lap, r.sectors.clone(), r.pitThisLap, phase == Phase.RUNNING, r.lapStartMs);
				}
			}
		}

		if (phase == Phase.RUNNING) {
			boolean distance = serverRace && raceEndWall > 0 ? System.currentTimeMillis() >= raceEndWall : r.lapsDone >= raceLaps;
			boolean done = (valid && distance) || leaderFinishTick >= 0;
			if (done) {
				r.finished = true;
				r.finishTick = t;
				r.finishPlace = ++finishedCount;
				if (leaderFinishTick < 0) {
					leaderFinishTick = t;
				}
				if (r.self) {
					popup("FINISH", "P" + r.finishPlace, r.finishPlace == 1 ? 0xFFFFC21A : 0xFFFFFFFF, 80, true);
					sound(mc, SoundEvents.PLAYER_LEVELUP, 1.0f);
					if (cfg().cooldownLap) {
						dev.laprace.cruise.Cruise.startCooldown(mc);
					}
				}
				return;
			}
		}
		// next lap
		if (r.self) {
			Ghost.lapStart(this);
			assistedLap = false;
			fuelAtLapStart = Engine.fuel;
			if (phase == Phase.RUNNING && r.lapsDone == raceLaps - 1 && raceEndWall == 0) {
				finalLapTick = now;
			}
		}
		r.pitThisLap = r.inPit;
		r.lapStart = t;
		r.lapStartMs = System.currentTimeMillis() - Math.round((now - t) * 50);
		r.sectorStart = t;
		r.sectorReached = 0;
		Arrays.fill(r.sectors, -1);
		Arrays.fill(r.lapLine, -1);
	}

	private void updateSessionSector(int i, double st) {
		if (sessionBestSectors == null || sessionBestSectors.length != sectorCount()) {
			sessionBestSectors = new double[sectorCount()];
			Arrays.fill(sessionBestSectors, -1);
		}
		if (sessionBestSectors[i] < 0 || st < sessionBestSectors[i]) {
			sessionBestSectors[i] = st;
		}
	}

	private void splitPopup(Minecraft mc, int k, double st) {
		if (!cfg().showPopups) {
			return;
		}
		double[] ref = self != null && self.bestSectors != null ? self.bestSectors : pbSectors;
		String value;
		int color;
		if (ref != null && k - 1 < ref.length && ref[k - 1] > 0) {
			double d = st - ref[k - 1];
			value = Fmt.delta(d);
			color = d < 0 ? Hud.GREEN : Hud.YELLOW;
		} else {
			return; // nothing to compare with yet
		}
		if (sessionBestSectors != null && sessionBestSectors[k - 1] >= 0 && st <= sessionBestSectors[k - 1]) {
			color = Hud.PURPLE;
		}
		popup("S" + k, value, color, 60, false);
	}

	private void fuelWarning() {
		if (!Engine.fuelOn() || fuelPerLap <= 0 || now < nextFuelWarning) {
			return;
		}
		double lapsLeft = Engine.fuel / fuelPerLap;
		if (lapsLeft < 2.0) {
			message(Component.translatable("laprace.msg.fuel",
					String.format(java.util.Locale.ROOT, "%.1f", lapsLeft).replace('.', Fmt.gt(0).contains("'") ? '.' : ',')).getString(),
					0xFFFF4A40, 100);
			nextFuelWarning = now + 20L * 30;
		}
	}

	private void selfLap(Minecraft mc, double lap, boolean overall) {
		if (fuelAtLapStart >= 0 && fuelAtLapStart > Engine.fuel) {
			double used = fuelAtLapStart - Engine.fuel;
			fuelPerLap = fuelPerLap < 0 ? used : (fuelPerLap * 0.6 + used * 0.4);
		}
		fuelAtLapStart = Engine.fuel;
		boolean pb = pbLap < 0 || lap < pbLap;
		lapFlashUntil = now + 100;
		lapFlashTime = lap;
		lapFlashPb = pb || lap <= self.bestLap + 1e-9;
		if (pb) {
			pbLap = lap;
			pbSectors = self.sectors.clone();
			pbLine = self.lapLine.clone();
			Bests.put(track.name, lap, pbSectors, pbLine);
			sound(mc, SoundEvents.NOTE_BLOCK_BELL, 1.5f);
		} else {
			sound(mc, SoundEvents.NOTE_BLOCK_PLING, 1.2f);
		}
		if (cfg().chatLapTimes) {
			Notify.chat(mc, Component.translatable("laprace.msg.lap", self.lapsDone, Fmt.time(lap),
					pbLap > 0 ? Fmt.time(pbLap) : "-"));
		}
	}

	private void checkRaceEnd(Minecraft mc) {
		if (serverRace && (self == null || !self.finished)) {
			return; // the server's race: over for us when we finish (or the server ends it), not when nobody is seen
		}
		boolean anyRunning = false;
		for (Racer r : racers.values()) {
			if (!r.finished && !r.dnf) {
				anyRunning = true;
			}
		}
		boolean timeout = leaderFinishTick >= 0 && now - leaderFinishTick > cfg().finishTimeoutSeconds * 20L;
		if (!anyRunning || timeout) {
			for (Racer r : racers.values()) {
				if (!r.finished) {
					r.dnf = true;
				}
			}
			phase = Phase.FINISHED;
			resultsUntil = now + cfg().resultsSeconds * 20L;
			sortBoard();
			StringBuilder sb = new StringBuilder();
			for (Racer r : order) {
				sb.append('\n').append(r.dnf ? "DNF" : "P" + r.finishPlace).append("  ").append(r.name).append("  ")
						.append(r.dnf ? "" : Fmt.time((r.finishTick - goTick) / 20.0))
						.append(r.bestLap > 0 ? "  (best " + Fmt.time(r.bestLap) + ")" : "");
			}
			Notify.chat(mc, Component.translatable("laprace.msg.results").append(sb.toString()));
		}
	}

	// ------------------------------------------------------------------ board

	private void sortBoard() {
		List<Racer> list = new ArrayList<>(racers.values());
		double L = track == null ? 1 : track.length;
		if (phase == Phase.RUNNING || phase == Phase.FINISHED || phase == Phase.COUNTDOWN) {
			list.sort(Comparator
					.comparingInt((Racer r) -> r.dnf ? 2 : r.finished ? 0 : 1)
					.thenComparingInt(r -> r.finished ? r.finishPlace : 0)
					.thenComparingDouble(r -> -r.progress(L)));
		} else {
			list.sort(Comparator
					.comparingDouble((Racer r) -> r.bestLap > 0 ? r.bestLap : Double.MAX_VALUE)
					.thenComparing(r -> r.name));
		}
		order = list;
	}

	public boolean racing() {
		return phase == Phase.RUNNING || phase == Phase.FINISHED || phase == Phase.COUNTDOWN;
	}

	/** Position of r on the board, 1-based. */
	public int positionOf(Racer r) {
		return order.indexOf(r) + 1;
	}

	/** Gap text for the board (relative to the leader or the car ahead, by config). */
	public String gapText(Racer r, int index) {
		return gap(r, index, cfg().gapToLeader);
	}

	/** Gap to the boat directly ahead. */
	public String intervalText(Racer r, int index) {
		return gap(r, index, false);
	}

	/** Gap to the leader. */
	public String leaderText(Racer r) {
		int i = order.indexOf(r);
		return i <= 0 ? "" : gap(r, i, true);
	}

	private String gap(Racer r, int index, boolean toLeader) {
		if (track == null) {
			return "";
		}
		double L = track.length;
		if (!racing()) {
			if (r.bestLap < 0) {
				return "--:--.---";
			}
			if (index == 0) {
				return Fmt.time(r.bestLap);
			}
			Racer ref = toLeader ? order.get(0) : order.get(index - 1);
			return ref.bestLap > 0 ? Fmt.delta(r.bestLap - ref.bestLap) : Fmt.time(r.bestLap);
		}
		if (r.dnf) {
			return "DNF";
		}
		if (index == 0) {
			if (r.finished) {
				return Fmt.time((r.finishTick - goTick) / 20.0);
			}
			return phase == Phase.COUNTDOWN ? "" : "LAP " + Math.min(r.currentLap(), raceLaps);
		}
		Racer ref = toLeader ? order.get(0) : order.get(index - 1);
		if (r.finished && ref.finished) {
			return Fmt.delta((r.finishTick - ref.finishTick) / 20.0);
		}
		double pr = r.progress(L);
		double pa = ref.finished ? Double.MAX_VALUE : ref.progress(L);
		if (!ref.finished && pa - pr >= L) {
			int laps = (int) ((pa - pr) / L);
			return "+" + laps + (laps == 1 ? " LAP" : " LAPS");
		}
		double ta = ref.timeAt(pr, L);
		if (!Double.isNaN(ta)) {
			return Fmt.delta((now - ta) / 20.0);
		}
		if (phase == Phase.COUNTDOWN) {
			return "";
		}
		// no history for the boat ahead: distance / speed
		double gap = (ref.finished ? 0 : pa - pr) / Math.max(5, r.speed);
		return Fmt.delta(gap);
	}

	// ------------------------------------------------------------------ helpers

	public void popup(String title, String value, int color, int ticks, boolean big) {
		if (!cfg().showPopups) {
			return;
		}
		popups.removeIf(p -> p.big == big);
		popups.add(new Popup(title, value, color, now, now + ticks, big));
	}

	public static Entity boatOf(net.minecraft.world.entity.player.Player p) {
		Entity v = p.getVehicle();
		return v instanceof AbstractBoat ? v : null;
	}

	private static void sound(Minecraft mc, Holder<SoundEvent> s, float pitch) {
		if (cfg().sounds) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(s, pitch));
		}
	}

	private static void sound(Minecraft mc, SoundEvent s, float pitch) {
		if (cfg().sounds) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(s, pitch));
		}
	}

	/** A short status: an AlinLib toast if it is installed, else the chat. */
	static void say(Minecraft mc, Component c) {
		Notify.show(mc, c);
	}
}
