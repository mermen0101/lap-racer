package dev.laprace;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A track's pit lane: the path from the track into the pits, past the boxes and back out onto the track. It comes
 * with the mod: assets/laprace/pits/TRACK.json.
 * <p>
 * The boxes are found in the world: a black and a yellow concrete block side by side next to the lane is one box
 * (the spot where the car stands and refuels). They are numbered along the lane from the entry. The file carries
 * the boxes as they were when it was made, with a little top-down map of the pits for the box selection screen, so
 * neither needs the pits' chunks loaded; once the pits are seen loaded, what is in the world replaces them.
 * <p>
 * With the environment variable LAPRACE_PIT_DUMP set, what was found in the world is written to
 * pitdump/TRACK.json in the game directory (that is how the bundled files are made).
 */
public final class PitLane {
	public final String track;
	/** x, y, z of the path, about 1 block apart, from the entry (on the track) to the exit (on the track). */
	public final List<double[]> pts;
	/** Index of the lane point at the boxes (used when no boxes are found in the world). */
	public final int box;
	/** Progress along the track where the lane leaves it and where it joins it again. */
	public final double entryS, exitS;
	/** First point of the lane clearly off the track (it has left the racing line). */
	public final int firstOff;

	/** A box: where the car stands, numbered 1.. along the lane; {@code at} is the nearest lane point. */
	public record Box(int number, double x, double z, int at) {
	}

	/**
	 * Top-down picture of the pits, one cell per block, laid along the lane: cell (u, v) is at world
	 * {@code o + (u + 0.5) * dir + (v + 0.5) * nrm}. Boxes are on the low-v side (top of the picture), the lane
	 * runs left to right in the driving direction. Cells: see {@link #cell}.
	 */
	public record PitMap(double ox, double oz, double dx, double dz, double nx, double nz, int w, int h, String cells) {
		public char at(int u, int v) {
			return u < 0 || v < 0 || u >= w || v >= h ? '0' : cells.charAt(v * w + u);
		}

		/** World point to map cell coordinates (fractional). */
		public double[] toMap(double x, double z) {
			double rx = x - ox, rz = z - oz;
			return new double[] {rx * dx + rz * dz, rx * nx + rz * nz};
		}
	}

	private List<Box> boxes;
	/** Lane points where the pit speed limit starts and ends (the red lines); -1 if none were found. */
	private int limitFrom = -1, limitTo = -1;
	/** Lane point where the pits end: just past the snow line that parts the exit from the track (or the lane's end). */
	private int exitAt = -1;
	/** Looked at in the world this session (the saved boxes may be from before the pits were rebuilt). */
	private boolean fresh;
	private PitMap map;

	private static PitLane cached;

	private PitLane(String track, List<double[]> pts, int box, Track t) {
		this.track = track;
		this.pts = pts;
		this.box = Math.clamp(box, 1, pts.size() - 2);
		Track.Projection p = new Track.Projection();
		double[] a = pts.get(0), b = pts.get(pts.size() - 1);
		entryS = t.project(a[0], a[2], -1, p).s;
		exitS = t.project(b[0], b[2], -1, p).s;
		int fo = this.box;
		int hint = -1;
		for (int i = 0; i < this.box; i++) {
			double[] q = pts.get(i);
			t.project(q[0], q[2], hint, p);
			hint = p.idx;
			if (p.off >= 4) {
				fo = i;
				break;
			}
		}
		firstOff = fo;
	}

	/** The pit lane of this track, or null if it has none. */
	public static PitLane of(Track t) {
		if (t == null) {
			return null;
		}
		if (cached != null && cached.track.equals(t.name)) {
			return cached;
		}
		cached = null;
		try (java.io.InputStream in = PitLane.class.getResourceAsStream("/assets/laprace/pits/" + t.name + ".json")) {
			if (in == null) {
				return null;
			}
			JsonObject o = new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
			List<double[]> pts = new ArrayList<>();
			for (var e : o.getAsJsonArray("pts")) {
				JsonArray a = e.getAsJsonArray();
				pts.add(new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()});
			}
			if (pts.size() >= 10) {
				PitLane l = new PitLane(t.name, pts, o.get("box").getAsInt(), t);
				if (o.has("boxes")) {
					List<Box> bs = new ArrayList<>();
					for (var e : o.getAsJsonArray("boxes")) {
						JsonArray a = e.getAsJsonArray();
						double x = a.get(0).getAsDouble(), z = a.get(1).getAsDouble();
						bs.add(new Box(bs.size() + 1, x, z, l.nearest(x, z)));
					}
					l.boxes = bs;
				}
				if (o.has("limit")) {
					l.limitFrom = o.getAsJsonArray("limit").get(0).getAsInt();
					l.limitTo = o.getAsJsonArray("limit").get(1).getAsInt();
					if (o.getAsJsonArray("limit").size() > 2) {
						l.exitAt = o.getAsJsonArray("limit").get(2).getAsInt();
					}
				}
				if (o.has("map")) {
					JsonObject m = o.getAsJsonObject("map");
					l.map = new PitMap(m.get("ox").getAsDouble(), m.get("oz").getAsDouble(), m.get("dx").getAsDouble(),
							m.get("dz").getAsDouble(), m.get("nx").getAsDouble(), m.get("nz").getAsDouble(),
							m.get("w").getAsInt(), m.get("h").getAsInt(), m.get("cells").getAsString());
				}
				cached = l;
			}
		} catch (Exception e) {
			LapRaceClient.LOG.warn("pit lane of {} could not be read", t.name, e);
		}
		return cached;
	}

	private static void write(String track, List<double[]> pts, int box, List<Box> boxes, PitMap map, int limitFromW, int limitToW, int exitW) throws java.io.IOException {
		StringBuilder sb = new StringBuilder("{\"track\":\"").append(track).append("\",\"box\":").append(box);
		if (boxes != null) {
			sb.append(",\"boxes\":[");
			for (int i = 0; i < boxes.size(); i++) {
				sb.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "[%.2f,%.2f]", boxes.get(i).x, boxes.get(i).z));
			}
			sb.append("]");
		}
		sb.append(",\"limit\":[").append(limitFromW).append(',').append(limitToW).append(',').append(exitW).append(']');
		if (map != null) {
			sb.append(String.format(Locale.ROOT, ",\"map\":{\"ox\":%.4f,\"oz\":%.4f,\"dx\":%.6f,\"dz\":%.6f,\"nx\":%.6f,\"nz\":%.6f,\"w\":%d,\"h\":%d,\"cells\":\"%s\"}",
					map.ox, map.oz, map.dx, map.dz, map.nx, map.nz, map.w, map.h, map.cells));
		}
		sb.append(",\"pts\":[");
		for (int i = 0; i < pts.size(); i++) {
			double[] p = pts.get(i);
			sb.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "[%.3f,%.1f,%.3f]", p[0], p[1], p[2]));
		}
		sb.append("]}");
		Path f = FabricLoader.getInstance().getGameDir().resolve("pitdump").resolve(track + ".json");
		Files.createDirectories(f.getParent());
		Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
	}

	/** Whether x, z is within {@code dist} of this track's pit lane. */
	public static boolean near(Track t, double x, double z, double dist) {
		PitLane l = of(t);
		if (l == null) {
			return false;
		}
		double[] q = l.pts.get(l.nearest(x, z));
		return Math.hypot(q[0] - x, q[2] - z) < dist;
	}

	/** Nearest lane point to x, z. */
	public int nearest(double x, double z) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < pts.size(); i++) {
			double[] q = pts.get(i);
			double d = (q[0] - x) * (q[0] - x) + (q[2] - z) * (q[2] - z);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ boxes

	/** The boxes found (empty until the pits have been seen loaded). */
	public List<Box> boxes() {
		return boxes == null ? List.of() : boxes;
	}

	/** First and last lane point of the red lines (the speed limit), -1 if they were not found. */
	public int limitFrom() {
		return limitFrom;
	}

	public int limitTo() {
		return limitTo;
	}

	/** Whether lane point i is inside the speed-limited part (all of it when no red lines were found). */
	public boolean limited(int i) {
		return limitFrom < 0 || (i >= limitFrom && i <= limitTo);
	}

	/**
	 * Index of the lane point where the autopilot takes the car: where the red lines start (the car is really in
	 * the pits there, not just near the fork), or just off the track if there are none.
	 */
	public int entryAt() {
		if (entryAt < 0) {
			entryAt = findEntry();
		}
		return entryAt;
	}

	private int entryAt = -1;
	/** A stretch of lane counts as straight with its bend radius above this (blocks). */
	private static final double STRAIGHT_R = 300;

	/**
	 * The start of the straight that leads to the red line: back from the line through any bend before it, then
	 * back along the straight to where it begins - never on the track itself.
	 */
	private int findEntry() {
		int line = limitFrom >= 0 ? limitFrom : box;
		int i = line;
		while (i > firstOff && radius(i, 10) < STRAIGHT_R) {
			i--; // the bend into the line
		}
		while (i > firstOff && radius(i, 10) >= STRAIGHT_R) {
			i--; // the straight
		}
		int at = Math.max(firstOff, i);
		LapRaceClient.LOG.info("pit lane of {}: taken over at point {} (red line at {})", track, at, limitFrom);
		return at;
	}

	/** Index of the lane point where the autopilot hands the car back. */
	public int exitAt() {
		return exitAt >= 0 ? Math.min(exitAt, pts.size() - 1) : pts.size() - 1;
	}

	/** Whether the boxes were looked for in the world this session. */
	public boolean fresh() {
		return fresh;
	}

	public boolean scanned() {
		return boxes != null;
	}

	public PitMap map() {
		return map;
	}

	/** How far from the lane a box may be (blocks). */
	private static final int BOX_REACH = 12;

	/** Floor block y under the lane (the boat stands on it). */
	private int floorY() {
		double[] ys = new double[pts.size()];
		for (int i = 0; i < ys.length; i++) {
			ys[i] = pts.get(i)[1];
		}
		java.util.Arrays.sort(ys);
		return Mth.floor(ys[ys.length / 2] + 0.01) - 1;
	}

	private static boolean isBlack(BlockState s) {
		return s.is(Blocks.CONCRETE.black());
	}

	private static boolean isYellow(BlockState s) {
		return s.is(Blocks.CONCRETE.yellow());
	}

	/**
	 * Looks for the boxes along the lane and draws the pits' map, if the chunks around the lane are loaded.
	 * Returns false when the pits are not loaded (try again closer).
	 */
	public boolean scan(Level level) {
		int fy = floorY();
		LongOpenHashSet seen = new LongOpenHashSet();
		List<double[]> found = new ArrayList<>();
		boolean[] loaded = new boolean[pts.size()];
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		for (int pi = 0; pi < pts.size(); pi++) {
			double[] q = pts.get(pi);
			int qx = Mth.floor(q[0]), qz = Mth.floor(q[2]);
			loaded[pi] = ready(level, qx - BOX_REACH, qz - BOX_REACH) && ready(level, qx + BOX_REACH, qz - BOX_REACH)
					&& ready(level, qx - BOX_REACH, qz + BOX_REACH) && ready(level, qx + BOX_REACH, qz + BOX_REACH);
			if (!loaded[pi]) {
				continue;
			}
			for (int dz = -BOX_REACH; dz <= BOX_REACH; dz++) {
				for (int dx = -BOX_REACH; dx <= BOX_REACH; dx++) {
					if (dx * dx + dz * dz > BOX_REACH * BOX_REACH) {
						continue;
					}
					int x = qx + dx, z = qz + dz;
					if (!seen.add(BlockPos.asLong(x, 0, z))) {
						continue;
					}
					for (int y = fy - 1; y <= fy + 1; y++) {
						mp.set(x, y, z);
						if (!isBlack(level.getBlockState(mp))) {
							continue;
						}
						for (int k = 0; k < 4; k++) {
							int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
							mp.set(nx, y, nz);
							if (isYellow(level.getBlockState(mp))) {
								found.add(new double[] {(x + nx) / 2.0 + 0.5, (z + nz) / 2.0 + 0.5});
								break;
							}
						}
					}
				}
			}
		}
		found.sort(Comparator.comparingInt(b -> nearest(b[0], b[1])));
		// complete when the lane is loaded all along the boxes and a good way either side (the far ends of a long
		// pit road need not be: no boxes there); with no boxes found, only when all of it was loaded
		int from = 0, to = pts.size() - 1;
		if (!found.isEmpty()) {
			from = Math.max(0, nearest(found.get(0)[0], found.get(0)[1]) - 40);
			to = Math.min(pts.size() - 1, nearest(found.get(found.size() - 1)[0], found.get(found.size() - 1)[1]) + 40);
		}
		for (int i = from; i <= to; i++) {
			if (!loaded[i]) {
				return false;
			}
		}
		List<Box> bs = new ArrayList<>();
		for (double[] b : found) {
			bs.add(new Box(bs.size() + 1, b[0], b[1], nearest(b[0], b[1])));
		}
		boxes = bs;
		fresh = true;
		findLimit(level, fy);
		entryAt = -1;
		map = bs.isEmpty() ? null : drawMap(level, fy, bs);
		if (System.getenv("LAPRACE_PIT_DUMP") != null) {
			try {
				write(track, pts, box, boxes, map, limitFrom, limitTo, exitAt);
			} catch (Exception e) {
				LapRaceClient.LOG.warn("pit boxes of {} could not be dumped", track, e);
			}
		}
		LapRaceClient.LOG.info("pit lane of {}: {} boxes", track, bs.size());
		return true;
	}

	private static boolean isRed(BlockState s) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().equals("red_wool");
	}

	/** The red lines along the lane: the limit starts at the first lane point with red beside it, ends at the last. */
	private void findLimit(Level level, int fy) {
		limitFrom = -1;
		limitTo = -1;
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		for (int i = 0; i < pts.size(); i++) {
			double[] q = pts.get(i), n = normal(i);
			boolean red = false;
			for (int k = -9; k <= 9 && !red; k++) {
				int x = Mth.floor(q[0] + n[0] * k), z = Mth.floor(q[2] + n[1] * k);
				if (!ready(level, x, z)) {
					continue;
				}
				for (int y = fy; y <= fy + 1 && !red; y++) {
					red = isRed(level.getBlockState(mp.set(x, y, z)));
				}
			}
			if (red) {
				if (limitFrom < 0) {
					limitFrom = i;
				}
				limitTo = i;
			}
		}
		// the exit: past the last snow line beside the lane after the boxes (it parts the exit from the track)
		int after = Math.max(limitTo, boxes == null || boxes.isEmpty() ? box : boxes.get(boxes.size() - 1).at);
		int lastSnow = -1;
		for (int i = Math.max(0, after); i < pts.size(); i++) {
			double[] q = pts.get(i), n = normal(i);
			boolean snow = false;
			for (int k = -7; k <= 7 && !snow; k++) {
				int x = Mth.floor(q[0] + n[0] * k), z = Mth.floor(q[2] + n[1] * k);
				if (!ready(level, x, z)) {
					continue;
				}
				for (int y = fy; y <= fy + 1 && !snow; y++) {
					BlockState st = level.getBlockState(mp.set(x, y, z));
					snow = st.is(Blocks.SNOW) || st.is(Blocks.SNOW_BLOCK);
				}
			}
			if (snow) {
				lastSnow = i;
			}
		}
		exitAt = lastSnow >= 0 ? Math.min(pts.size() - 1, lastSnow + 2) : -1;
		LapRaceClient.LOG.info("pit lane of {}: limit from point {} to {}, hand back at {} of {}", track, limitFrom, limitTo,
				exitAt, pts.size());
	}

	/** The chunk at block x, z has arrived (the client answers "loaded" with an empty chunk until it does). */
	private static boolean ready(Level level, int x, int z) {
		return level.getChunkSource().getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null;
	}

	/** The pits along the boxes, laid along the lane. */
	private PitMap drawMap(Level level, int fy, List<Box> bs) {
		Box first = bs.get(0), last = bs.get(bs.size() - 1);
		double dx, dz;
		if (bs.size() > 1) {
			dx = last.x - first.x;
			dz = last.z - first.z;
		} else {
			double[] a = pts.get(Math.max(0, first.at - 3)), b = pts.get(Math.min(pts.size() - 1, first.at + 3));
			dx = b[0] - a[0];
			dz = b[2] - a[2];
		}
		double len = Math.hypot(dx, dz);
		dx /= len;
		dz /= len;
		double nx = -dz, nz = dx;
		// boxes on the low-v side (top of the picture)
		double[] lp = pts.get(first.at);
		if ((first.x - lp[0]) * nx + (first.z - lp[2]) * nz > 0) {
			nx = -nx;
			nz = -nz;
		}
		double u0 = -26, u1 = (last.x - first.x) * dx + (last.z - first.z) * dz + 26;
		double v0 = Double.MAX_VALUE, v1 = -Double.MAX_VALUE;
		for (Box b : bs) {
			double v = (b.x - first.x) * nx + (b.z - first.z) * nz;
			v0 = Math.min(v0, v - 6);
			v1 = Math.max(v1, v + 6);
		}
		for (double[] q : pts) {
			double u = (q[0] - first.x) * dx + (q[2] - first.z) * dz;
			if (u >= u0 && u <= u1) {
				double v = (q[0] - first.x) * nx + (q[2] - first.z) * nz;
				v0 = Math.min(v0, v - 7);
				v1 = Math.max(v1, v + 7);
			}
		}
		v0 = Math.max(v0, -40);
		v1 = Math.min(v1, 40);
		double ox = first.x + u0 * dx + v0 * nx, oz = first.z + u0 * dz + v0 * nz;
		int w = (int) Math.ceil(u1 - u0), h = (int) Math.ceil(v1 - v0);
		StringBuilder cells = new StringBuilder(w * h);
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		for (int v = 0; v < h; v++) {
			for (int u = 0; u < w; u++) {
				double x = ox + (u + 0.5) * dx + (v + 0.5) * nx, z = oz + (u + 0.5) * dz + (v + 0.5) * nz;
				cells.append(cell(level, mp, Mth.floor(x), fy, Mth.floor(z)));
			}
		}
		return new PitMap(ox, oz, dx, dz, nx, nz, w, h, cells.toString());
	}

	/**
	 * What one column of the pits is: '0' nothing (grass, void), '1' road (ice), '2' white line, '3' red, '4' other
	 * floor, '5' wall, '6' box concrete.
	 */
	private static char cell(Level level, BlockPos.MutableBlockPos mp, int x, int fy, int z) {
		if (!ready(level, x, z)) {
			return '0';
		}
		mp.set(x, fy + 1, z);
		BlockState above = level.getBlockState(mp);
		if (!above.isAir()) {
			var shape = above.getCollisionShape(level, mp);
			if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > 0.3) {
				return '5';
			}
		}
		mp.set(x, fy, z);
		BlockState floor = level.getBlockState(mp);
		if (floor.isAir()) {
			mp.set(x, fy - 1, z);
			floor = level.getBlockState(mp);
			if (floor.isAir()) {
				return '0';
			}
		}
		if (isBlack(floor) || isYellow(floor)) {
			return '6';
		}
		if (floor.is(BlockTags.ICE)) {
			return '1';
		}
		MapColor c = floor.getMapColor(level, mp);
		if (c == MapColor.SNOW || c == MapColor.WOOL || c == MapColor.QUARTZ) {
			return '2';
		}
		if (c == MapColor.COLOR_RED || c == MapColor.TERRACOTTA_RED || c == MapColor.NETHER) {
			return '3';
		}
		if (c == MapColor.GRASS || c == MapColor.DIRT || c == MapColor.PLANT || c == MapColor.WATER || c == MapColor.NONE) {
			return '0';
		}
		return '4';
	}

	/** The box this driver chose for this track (or the one nearest the lane's box point). */
	public Box chosen() {
		List<Box> bs = boxes();
		if (bs.isEmpty()) {
			return null;
		}
		Integer n = LapConfig.INSTANCE.pitBoxes.get(track);
		if (n != null && n >= 1 && n <= bs.size()) {
			return bs.get(n - 1);
		}
		Box best = bs.get(0);
		for (Box b : bs) {
			if (Math.abs(b.at - box) < Math.abs(best.at - box)) {
				best = b;
			}
		}
		return best;
	}

	/** Every second or so: find the boxes once the pits are loaded around the driver. */
	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.player.tickCount % 20 != 7 || mc.player.tickCount < 100) {
			return;
		}
		PitLane l = of(RaceSession.INSTANCE.track);
		if (l == null || l.fresh) {
			return;
		}
		double[] q = l.pts.get(l.box);
		if (Math.hypot(q[0] - mc.player.getX(), q[2] - mc.player.getZ()) < 96) {
			l.scan(mc.level);
		}
	}

	// ------------------------------------------------------------------ the path the autopilot drives

	/** Points of track put before the lane in {@link #path}. */
	public int pre;
	/** Index in {@link #path} where the car stops, and the spot itself. */
	public int stopIdx;
	public double stopX, stopZ;
	/** Path indices where the move over into the box's line starts, and where the straight onto the spot starts. */
	public int shiftFrom, lineFrom;
	/** Direction of the box's line (the way the car drives along it), unit. */
	public double stopDx, stopDz = 1;

	/**
	 * The lane as a path for the planner: a stretch of track before the entry, the lane - swinging over into the
	 * chosen box and back - then a stretch of track after the exit (the planner looks ahead, and must not run off
	 * the end), with speeds: the pit limit, slowing to a crawl at the box. Points: x, y, z, speed (blocks/s).
	 */
	public List<double[]> path(Track t, double pitBps, double trackCap) {
		List<double[]> out = new ArrayList<>();
		Track.Projection p = new Track.Projection();
		int n = t.size();
		int in = t.project(pts.get(0)[0], pts.get(0)[2], -1, p).idx;
		for (int k = 15; k > 0; k--) {
			int i = Math.floorMod(in - k, n);
			out.add(new double[] {t.x[i], t.y[i], t.z[i], Math.min(pitBps, t.v[i] > 0 ? t.v[i] : trackCap)});
		}
		pre = out.size();

		// the stop: the chosen box, reached by shifting the lane sideways around it
		Box b = chosen();
		int j = b != null ? b.at : box;
		double lat = 0, bnx = 0, bnz = 0;
		if (b != null) {
			double[] nrm = normal(j);
			bnx = nrm[0];
			bnz = nrm[1];
			double[] q = pts.get(j);
			lat = (b.x - q[0]) * bnx + (b.z - q[2]) * bnz;
		}
		// over into the box's line well before it (and back out after): a gentle shift, then straight at the spot
		// the move into the box's line happens between the box before and this one (and out between this one and the
		// next): not over a neighbour's spot
		int gap = 30;
		for (Box o : boxes()) {
			if (b != null && o != b) {
				gap = Math.min(gap, Math.abs(o.at - j));
			}
		}
		// in: over into the box's line between the box before and the last 8 blocks, which are straight along the
		// line (lined up, it brakes straight onto the spot); out: 3 blocks straight, then back before the next box
		int flatIn = 8, flatOut = 3;
		int rampIn = Math.clamp(gap - 1 - flatIn, 4, 12), rampOut = Math.clamp(gap - 1 - flatOut, 4, 12);
		for (int i = 0; i < pts.size(); i++) {
			double[] q = pts.get(i);
			double w = 0;
			int d = Math.abs(i - j), flat = i <= j ? flatIn : flatOut, ramp = i <= j ? rampIn : rampOut;
			if (d <= flat) {
				w = 1;
			} else if (d < flat + ramp) {
				double s = 1 - (d - flat) / (double) ramp;
				w = s * s * (3 - 2 * s);
			}
			double[] nrm = normal(i);
			double x = q[0] + nrm[0] * lat * w, z = q[2] + nrm[1] * lat * w;
			if (i == j && b != null) {
				x = b.x;
				z = b.z;
			}
			// on ice a boat brakes at little more than 1 block/s^2: slow down early enough to stop at the box
			out.add(new double[] {x, q[1], z, 0});
		}
		double[] v = laneSpeeds(pitBps, j, b != null);
		for (int i = 0; i < pts.size(); i++) {
			out.get(pre + i)[3] = v[i];
		}
		stopIdx = pre + j;
		lineFrom = stopIdx - flatIn;
		shiftFrom = lineFrom - rampIn;
		double[] a0 = pts.get(Math.max(0, j - 4)), a1 = pts.get(Math.min(pts.size() - 1, j + 4));
		double dl = Math.max(1e-6, Math.hypot(a1[0] - a0[0], a1[2] - a0[2]));
		stopDx = (a1[0] - a0[0]) / dl;
		stopDz = (a1[2] - a0[2]) / dl;
		stopX = out.get(stopIdx)[0];
		stopZ = out.get(stopIdx)[2];

		int ex = t.project(pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[2], -1, p).idx;
		for (int k = 1; k <= 100; k++) {
			int i = (ex + k) % n;
			out.add(new double[] {t.x[i], t.y[i], t.z[i], Math.min(trackCap, t.v[i] > 0 ? t.v[i] : trackCap)});
		}
		return out;
	}

	/** Fastest a boat goes on blue ice (blocks/s), and how hard it corners (blocks/s^2, from the recorded tracks). */
	public static final double FAST_BPS = 58, GRIP = 14;
	/** The automatic stop loses this much to the autopilot's own best run (s): it stands that much longer. */
	public static final double HANDICAP_S = "0".equals(System.getenv("LAPRACE_PIT_HANDICAP")) ? 0.0 : 2.0;

	/**
	 * Target speeds along the lane: the pit limit between the red lines; elsewhere as fast as the bends allow,
	 * braking in time for the red line (on ice a boat brakes hard with its bow swung back).
	 */
	private double[] laneSpeeds(double pitBps, int j, boolean stop) {
		int n = pts.size();
		double[] v = new double[n];
		for (int i = 0; i < n; i++) {
			v[i] = Math.min(FAST_BPS, Math.sqrt(GRIP * radius(i)));
			if (limited(i)) {
				v[i] = Math.min(v[i], pitBps);
			}
			// the hard stop at the box is the autopilot's own (Cruise); here only what the lane allows
		}
		// brake in time (backwards: the speed a point may have to still make the next one); with the bow swung
		// back a boat brakes at up to 16 blocks/s^2 plus the ice's drag - counted at half of that
		for (int i = n - 2; i >= 0; i--) {
			double brake = 0.5 * (16 + 0.22 * v[i + 1]);
			v[i] = Math.min(v[i], Math.sqrt(v[i + 1] * v[i + 1] + 2 * brake));
		}
		return v;
	}

	/** Radius of the lane's bend at point i (large on straights). */
	private double radius(int i) {
		return radius(i, 6);
	}

	private double radius(int i, int w) {
		double[] a = pts.get(Math.max(0, i - w)), b = pts.get(i), c = pts.get(Math.min(pts.size() - 1, i + w));
		double ab = Math.hypot(b[0] - a[0], b[2] - a[2]), bc = Math.hypot(c[0] - b[0], c[2] - b[2]), ca = Math.hypot(a[0] - c[0], a[2] - c[2]);
		double area = Math.abs((b[0] - a[0]) * (c[2] - a[2]) - (c[0] - a[0]) * (b[2] - a[2])) / 2;
		return area < 1e-6 ? 1e6 : ab * bc * ca / (4 * area);
	}

	/** Unit normal of the lane at point i (to the left of the driving direction, in x/z). */
	private double[] normal(int i) {
		double[] a = pts.get(Math.max(0, i - 2)), b = pts.get(Math.min(pts.size() - 1, i + 2));
		double dx = b[0] - a[0], dz = b[2] - a[2];
		double len = Math.max(1e-6, Math.hypot(dx, dz));
		return new double[] {-dz / len, dx / len};
	}
}
