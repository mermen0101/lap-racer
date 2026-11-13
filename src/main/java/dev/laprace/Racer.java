package dev.laprace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One boat on the track: where it is, its laps, sectors and the times it reached each bit of the track
 * (for live gaps). Times are in ticks (20 a second, fractional: crossings are interpolated between ticks).
 */
public final class Racer {
	/** A player (not a named empty boat): leaves the board when he leaves the server. */
	public boolean player;
	/** Gap timeline resolution: one sample every this many blocks of progress. */
	public static final double BUCKET = 4.0;

	public final UUID id;
	public String name;
	public final boolean self;
	/** Colour on the board and the map (stable per racer). */
	public final int color;

	// ---- position
	public int idx = -1;
	public double s;
	public double off;
	public double px, pz, prevX, prevZ;
	/** Speed in blocks/s (from how far it moved: other boats' velocity is not sent to clients). */
	public double speed;
	public long lastSeen;
	public boolean inView;
	public int wrongWayTicks;

	// ---- laps
	/** Whole laps since the reference (the race start, or joining): progress = lapOffset * length + s. */
	public int lapOffset;
	/** Highest lapOffset reached (going back over the line and again does not count twice). */
	public int maxLapOffset;
	/** Tick (fractional) the current lap started; < 0: no timed lap running yet. */
	public double lapStart = -1;
	public int lapsDone;
	public final List<Double> lapTimes = new ArrayList<>();
	/** Per finished lap (same order as lapTimes): it had a pit stop ("П" in the lap list). */
	public final List<Boolean> lapPit = new ArrayList<>();
	/** In the pit now (stopped or slow on the pit's black / yellow concrete), and ticks on that concrete. */
	public boolean inPit;
	public int pitTicks;
	/** The lap now running had a pit stop (after leaving the pit: GT's "out lap"). */
	public boolean pitThisLap;
	public double bestLap = -1;
	public double lastLap = -1;
	/** Sector times of the lap now running (seconds; -1 = not yet). */
	public double[] sectors;
	public double[] lastSectors;
	public double[] bestSectors;
	/** Fastest time of each sector over all laps (the optimal lap is their sum). */
	public double[] minSectors;
	/** Which sector boundary the lap has passed (cutting the track is not a lap). */
	public int sectorReached;
	public double sectorStart = -1;
	/** When the lap timer started, by the computer's clock (ms) - for the server's check. */
	public long lapStartMs = -1;
	/** Times (s into the lap) at each bucket of the running lap / of the best lap: the live delta. */
	public double[] lapLine;
	public double[] bestLine;

	// ---- race
	public boolean finished;
	public double finishTick = -1;
	public int finishPlace;
	public boolean dnf;
	/** Tick it reached each bucket of race progress (index = (progress + length) / BUCKET). */
	private double[] timeline = new double[0];
	private int timelineMax = -1;

	public Racer(UUID id, String name, boolean self, int color) {
		this.id = id;
		this.name = name;
		this.self = self;
		this.color = color;
	}

	public void resetTiming(int sectorCount, int buckets) {
		lapStart = -1;
		lapsDone = 0;
		lapTimes.clear();
		lapPit.clear();
		pitThisLap = false;
		bestLap = -1;
		lastLap = -1;
		sectors = new double[sectorCount];
		Arrays.fill(sectors, -1);
		lastSectors = null;
		bestSectors = null;
		minSectors = new double[sectorCount];
		Arrays.fill(minSectors, -1);
		sectorReached = 0;
		sectorStart = -1;
		lapLine = new double[buckets];
		Arrays.fill(lapLine, -1);
		bestLine = null;
		finished = false;
		finishTick = -1;
		finishPlace = 0;
		dnf = false;
		timeline = new double[0];
		timelineMax = -1;
	}

	/** Total progress (blocks) since the reference. */
	public double progress(double length) {
		return lapOffset * length + s;
	}

	/** Records that race progress {@code p} was reached at tick {@code t} (filling skipped buckets). */
	public void markTimeline(double p, double length, double t, double prevP, double prevT) {
		int k = (int) Math.floor((p + length) / BUCKET);
		if (k <= timelineMax || k < 0) {
			return;
		}
		if (k >= timeline.length) {
			timeline = Arrays.copyOf(timeline, Math.max(k + 64, timeline.length * 2));
		}
		for (int j = Math.max(0, timelineMax + 1); j <= k; j++) {
			double pj = j * BUCKET - length;
			double f = p > prevP ? Math.clamp((pj - prevP) / (p - prevP), 0, 1) : 1;
			timeline[j] = prevT + (t - prevT) * f;
		}
		timelineMax = k;
	}

	/** Tick at which this racer reached race progress p, or NaN if it has not (or we did not see it). */
	public double timeAt(double p, double length) {
		int k = (int) Math.floor((p + length) / BUCKET);
		if (k < 0 || k > timelineMax || timelineMax < 0) {
			return Double.NaN;
		}
		return timeline[k];
	}

	public void noteSector(int i, double t) {
		if (minSectors != null && i < minSectors.length && (minSectors[i] < 0 || t < minSectors[i])) {
			minSectors[i] = t;
		}
	}

	/** Sum of the fastest sectors, or -1 until every sector has a time. */
	public double optimalLap() {
		if (minSectors == null) {
			return -1;
		}
		double sum = 0;
		for (double v : minSectors) {
			if (v < 0) {
				return -1;
			}
			sum += v;
		}
		return sum;
	}

	public boolean hasTimeline() {
		return timelineMax >= 0;
	}

	/** Laps shown on the board: the one it is on (1-based), at least 1. */
	public int currentLap() {
		return Math.max(1, maxLapOffset + 1);
	}
}
