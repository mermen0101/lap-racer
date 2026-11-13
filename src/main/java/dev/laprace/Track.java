package dev.laprace;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A closed track: its centre line (points about 1 block apart, the first one on the start/finish line, driven in
 * the order of the points). The same JSON as Ice Racer's routes: {"name":..,"loop":true,"pts":[[x,y,z,speed],..]}.
 * <p>
 * Progress along the track ({@code s}, blocks from the start line, 0..length) is the position projected onto the
 * centre line; laps and sectors are crossings of fixed values of {@code s}.
 */
public final class Track {
	/** Bundled tracks (the layouts in the test worlds). */
	/** The location's six routes: the only tracks raced (a user file with one of these names replaces it). */
	private static final String[] BUNDLED = {"route1", "route2", "route3", "route4", "route5", "route6"};

	public final String name;
	public final double[] x;
	public final double[] y;
	public final double[] z;
	/** Racing-line speed at point i, blocks/s (0 = unknown: recorded tracks). Used by the 80 km/h autopilot. */
	public final double[] v;
	/** Distance from the start line to point i along the centre line. */
	public final double[] s;
	public final double length;
	public final double minX, maxX, minZ, maxZ;

	public Track(String name, List<double[]> pts) {
		this.name = name;
		pts = closeLoop(pts);
		int n = pts.size();
		x = new double[n];
		y = new double[n];
		z = new double[n];
		v = new double[n];
		s = new double[n];
		double mnx = Double.MAX_VALUE, mxx = -Double.MAX_VALUE, mnz = Double.MAX_VALUE, mxz = -Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			double[] p = pts.get(i);
			x[i] = p[0];
			y[i] = p[1];
			z[i] = p[2];
			v[i] = p.length > 3 ? p[3] : 0;
			if (i > 0) {
				s[i] = s[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
			}
			mnx = Math.min(mnx, x[i]);
			mxx = Math.max(mxx, x[i]);
			mnz = Math.min(mnz, z[i]);
			mxz = Math.max(mxz, z[i]);
		}
		// closing segment back to the start line
		length = s[n - 1] + Math.hypot(x[0] - x[n - 1], z[0] - z[n - 1]);
		minX = mnx;
		maxX = mxx;
		minZ = mnz;
		maxZ = mxz;
	}

	/**
	 * The lines are racing lines, and where the last point meets the first there is often a step sideways (3 blocks
	 * on ppl10): invisible on the corner map, a visible kink on the radar and the course map. The step is spread
	 * smoothly over the last stretch of the lap, so the loop closes without it.
	 */
	private static List<double[]> closeLoop(List<double[]> in) {
		int n = in.size();
		if (n < 40) {
			return in;
		}
		// the start line's frame: origin at the first point, u along the track there, nrm across it
		double[] f = in.get(0), f5 = in.get(5);
		double ux = f5[0] - f[0], uz = f5[2] - f[2];
		double ul = Math.hypot(ux, uz);
		if (ul < 1e-6) {
			return in;
		}
		ux /= ul;
		uz /= ul;
		double nx = -uz, nz = ux;
		// the last stretch must run into the start along it (true for a closed lap); else leave the line alone
		int m = Math.min(n / 8, 45);
		double[] b0 = in.get(n - m), b1 = in.get(n - m + 3);
		double dx = b1[0] - b0[0], dz = b1[2] - b0[2];
		double dl = Math.hypot(dx, dz);
		double along0 = (b0[0] - f[0]) * ux + (b0[2] - f[2]) * uz;
		if (dl < 1e-6 || (dx * ux + dz * uz) / dl < 0.75 || along0 > 0 || along0 < -80) {
			return in;
		}
		// sideways offset where the stretch begins: from there it eases to 0 at the start (smoothstep), so the
		// step - and any last-moment curl of the recorded line - goes
		// the line's own sideways offset a few points before its end (before any last-moment curl): that much
		// is taken off, more and more towards the end (smoothstep), so the bends of the approach stay as they are
		int tail = 5;
		double[] q = in.get(n - 1 - tail);
		double r = (q[0] - f[0]) * nx + (q[2] - f[2]) * nz;
		if (Math.abs(r) < 0.05 || Math.abs(r) > 12) {
			return in;
		}
		List<double[]> out = new java.util.ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			double[] p = in.get(i).clone();
			int k = i - (n - m);
			if (k > 0) {
				double t = (double) k / m;
				double w = t * t * (3 - 2 * t);
				double side = (p[0] - f[0]) * nx + (p[2] - f[2]) * nz;
				// the last few points: the curl of the recording is dropped, they follow the eased offset
				double want = i > n - 1 - tail ? r * (1 - w) : side - r * w;
				p[0] += nx * (want - side);
				p[2] += nz * (want - side);
			}
			out.add(p);
		}
		return out;
	}

	public int size() {
		return x.length;
	}

	/** Display name: "route1" -> "ROUTE 1", "f1" -> "F1". */
	public String displayName() {
		return displayName(name);
	}

	public static String displayName(String name) {
		String n = name.toUpperCase(java.util.Locale.ROOT);
		if (n.startsWith("ROUTE") && n.length() > 5) {
			return "ROUTE " + n.substring(5);
		}
		return n;
	}

	/** Where a point lies relative to the track. */
	public static final class Projection {
		/** Nearest centre-line point (search hint for the next tick). */
		public int idx;
		/** Progress from the start line, 0 .. length. */
		public double s;
		/** Distance from the centre line (blocks). */
		public double off;
		/** Track direction at that point (unit). */
		public double dx, dz;
	}

	/** Projects (px, pz); {@code hint} is the last idx (-1: search everything). */
	public Projection project(double px, double pz, int hint, Projection out) {
		int n = size();
		int best = -1;
		double bestD = Double.MAX_VALUE;
		if (hint >= 0 && hint < n) {
			for (int k = -60; k <= 60; k++) {
				int i = Math.floorMod(hint + k, n);
				double d = sq(x[i] - px) + sq(z[i] - pz);
				if (d < bestD) {
					bestD = d;
					best = i;
				}
			}
		}
		if (best < 0 || bestD > 18 * 18) {
			for (int i = 0; i < n; i++) {
				double d = sq(x[i] - px) + sq(z[i] - pz);
				if (d < bestD) {
					bestD = d;
					best = i;
				}
			}
		}
		// refine on the segment best -> next (or prev -> best), whichever the point lies along
		int nx = (best + 1) % n;
		int pv = Math.floorMod(best - 1, n);
		double sBest = s[best];
		double segX = x[nx] - x[best], segZ = z[nx] - z[best];
		double t = dotT(px - x[best], pz - z[best], segX, segZ);
		double sOut;
		double ox, oz;
		if (t > 0) {
			double segLen = Math.hypot(segX, segZ);
			t = Math.min(t, 1);
			sOut = sBest + t * segLen;
			ox = x[best] + segX * t;
			oz = z[best] + segZ * t;
		} else {
			double bx = x[best] - x[pv], bz = z[best] - z[pv];
			double tb = dotT(px - x[pv], pz - z[pv], bx, bz);
			tb = Math.clamp(tb, 0, 1);
			double segLen = Math.hypot(bx, bz);
			double sPrev = best == 0 ? length - segLen : s[pv];
			sOut = sPrev + tb * segLen;
			ox = x[pv] + bx * tb;
			oz = z[pv] + bz * tb;
			segX = bx;
			segZ = bz;
		}
		if (sOut >= length) {
			sOut -= length;
		}
		if (sOut < 0) {
			sOut += length;
		}
		double sl = Math.hypot(segX, segZ);
		out.idx = best;
		out.s = sOut;
		out.off = Math.hypot(px - ox, pz - oz);
		out.dx = sl > 1e-9 ? segX / sl : 1;
		out.dz = sl > 1e-9 ? segZ / sl : 0;
		return out;
	}

	private static double dotT(double vx, double vz, double sx, double sz) {
		double l2 = sx * sx + sz * sz;
		return l2 < 1e-12 ? 0 : (vx * sx + vz * sz) / l2;
	}

	private static double sq(double v) {
		return v * v;
	}

	/** Point at progress s (for the minimap and the start line marker). */
	public int indexAt(double sv) {
		int lo = 0, hi = size() - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (s[mid] <= sv) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}

	// ------------------------------------------------------------------ loading

	public static Track parse(String fallbackName, Reader r) {
		JsonObject o = new Gson().fromJson(r, JsonObject.class);
		String name = o.has("name") ? o.get("name").getAsString() : fallbackName;
		JsonArray arr = o.getAsJsonArray("pts");
		List<double[]> pts = new ArrayList<>(arr.size());
		for (JsonElement e : arr) {
			JsonArray p = e.getAsJsonArray();
			pts.add(new double[] {p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble(),
					p.size() > 3 ? p.get(3).getAsDouble() : 0});
		}
		if (pts.size() < 10) {
			throw new IllegalArgumentException("track " + name + " has too few points");
		}
		return new Track(name, pts);
	}

	public static Path userDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("laprace").resolve("tracks");
	}

	/** The six routes: bundled, or config/laprace/tracks/routeN.json where one was re-recorded; other files ignored. */
	public static List<Track> loadAll() {
		List<Track> out = new ArrayList<>();
		for (String n : BUNDLED) {
			Path user = userDir().resolve(n + ".json");
			if (Files.exists(user)) {
				continue; // loaded below
			}
			try (InputStream in = Track.class.getResourceAsStream("/assets/laprace/tracks/" + n + ".json")) {
				if (in != null) {
					out.add(parse(n, new InputStreamReader(in, StandardCharsets.UTF_8)));
				}
			} catch (IOException | RuntimeException e) {
				LapRaceClient.LOG.warn("bundled track {} could not be read", n, e);
			}
		}
		Path dir = userDir();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> files = Files.list(dir)) {
				for (Path f : files.sorted().toList()) {
					String fn = f.getFileName().toString();
					if (!fn.endsWith(".json") || !java.util.Arrays.asList(BUNDLED).contains(fn.substring(0, fn.length() - 5))) {
						continue;
					}
					try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
						out.add(parse(fn.substring(0, fn.length() - 5), r));
					} catch (IOException | RuntimeException e) {
						LapRaceClient.LOG.warn("track file {} could not be read", f, e);
					}
				}
			} catch (IOException e) {
				LapRaceClient.LOG.warn("track folder could not be listed", e);
			}
		}
		return out;
	}

	/** Saves a recorded centre line as config/laprace/tracks/NAME.json. */
	public static void save(String name, List<double[]> pts) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"name\":\"").append(name).append("\",\"loop\":true,\"pts\":[");
		for (int i = 0; i < pts.size(); i++) {
			double[] p = pts.get(i);
			if (i > 0) {
				sb.append(',');
			}
			sb.append(String.format(java.util.Locale.ROOT, "[%.3f,%.1f,%.3f,0]", p[0], p[1], p[2]));
		}
		sb.append("]}");
		Files.createDirectories(userDir());
		Files.writeString(userDir().resolve(name + ".json"), sb.toString(), StandardCharsets.UTF_8);
	}
}
