package dev.laprace.cruise;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.laprace.LapConfig;
import dev.laprace.LapRaceClient;
import dev.laprace.RaceSession;
import dev.laprace.Track;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The racing line on the ice: the autopilot's lap of the route (resources laprace/lines/ROUTE.line, "x z speed" a
 * tick, one flying lap), drawn as a thin ribbon on the track for some way ahead of the car. White where the speed
 * holds, red where the line loses speed (the corners to brake for), green where it gains it; it fades out in the
 * distance and right under the car.
 */
public final class RacingLine {
	/** How far ahead it is drawn (blocks): the player's choice. */
	private static double ahead = 70;
	private static final double BEHIND = 3, HALF_W = 0.22, LIFT = 0.045;

	/** A route's line: points, their height (from the track), speeds (m/s), distance along it to each, its length. */
	private record Line(double[] x, double[] y, double[] z, double[] v, double[] at, double len) {
		int size() {
			return x.length;
		}
	}

	private static final Map<String, Line> LINES = new HashMap<>();
	private static Line line;
	private static String lineOf;
	private static int hint = -1;

	private RacingLine() {
	}

	public static void render(LevelRenderContext ctx) {
		try {
			draw(ctx);
		} catch (RuntimeException e) {
			LapRaceClient.LOG.warn("racing line: {}", e.toString());
		}
	}

	private static Line lineFor(Track t) {
		if (!t.name.equals(lineOf)) {
			lineOf = t.name;
			hint = -1;
			line = LINES.computeIfAbsent(t.name, n -> load(t));
		}
		return line;
	}

	/** The line's file, its points set on the track's height (nearest centre-line point); null if the route has none. */
	private static Line load(Track t) {
		if (!t.name.matches("[A-Za-z0-9_]+")) {
			return null;
		}
		List<double[]> pts = new ArrayList<>();
		try (InputStream in = RacingLine.class.getResourceAsStream("/laprace/lines/" + t.name + ".line")) {
			if (in == null) {
				return null;
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String s; (s = r.readLine()) != null;) {
				String[] f = s.trim().split("\\s+");
				if (f.length >= 3) {
					pts.add(new double[] {Double.parseDouble(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2])});
				}
			}
		} catch (Exception e) {
			LapRaceClient.LOG.warn("racing line {}: {}", t.name, e.toString());
			return null;
		}
		if (pts.size() < 10) {
			return null;
		}
		int n = pts.size();
		double[] x = new double[n], y = new double[n], z = new double[n], v = new double[n];
		Track.Projection p = new Track.Projection();
		int h = -1;
		for (int i = 0; i < n; i++) {
			x[i] = pts.get(i)[0];
			z[i] = pts.get(i)[1];
			v[i] = pts.get(i)[2];
			t.project(x[i], z[i], h, p);
			h = p.idx;
			y[i] = t.y[p.idx];
		}
		double[] at = new double[n];
		for (int i = 1; i < n; i++) {
			at[i] = at[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
		}
		double len = at[n - 1] + Math.hypot(x[0] - x[n - 1], z[0] - z[n - 1]);
		return new Line(x, y, z, v, at, len);
	}

	private static void draw(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		LapConfig cfg = LapConfig.INSTANCE;
		// off by choice, or by the server's race (its creator may forbid the line)
		if (cfg.lineMode == LapConfig.LineMode.OFF || dev.laprace.ServerRace.lineForbidden() || mc.player == null || mc.level == null) {
			return;
		}
		Entity boat = RaceSession.boatOf(mc.player);
		Track t = RaceSession.INSTANCE.track;
		if (boat == null || t == null) {
			return;
		}
		Line l = lineFor(t);
		if (l == null) {
			return;
		}
		int n = l.size();
		// where the car is, between ticks (as the camera sees it), and how far along the line that is
		float part = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double bx = net.minecraft.util.Mth.lerp(part, boat.xo, boat.getX()), bz = net.minecraft.util.Mth.lerp(part, boat.zo, boat.getZ());
		int at = nearest(l, bx, bz, hint);
		if (Math.hypot(l.x()[at] - bx, l.z()[at] - bz) > 25) {
			hint = -1;
			return; // off the route
		}
		hint = at;
		double sCar = along(l, at, bx, bz);
		// the points to draw: from a little behind the car to some way ahead, each with its distance from the car
		List<Integer> idx = new ArrayList<>();
		List<Double> dist = new ArrayList<>();
		int first = at;
		while (first != Math.floorMod(at + 1, n) && ahead(l, l.at()[Math.floorMod(first - 1, n)], sCar) > -BEHIND - 2) {
			first = Math.floorMod(first - 1, n);
		}
		for (int k = 0; k < n; k++) {
			int i = Math.floorMod(first + k, n);
			double d = ahead(l, l.at()[i], sCar);
			if (k > 0 && d > ahead + 2) {
				break;
			}
			idx.add(i);
			dist.add(d);
		}
		if (idx.size() < 3) {
			return;
		}
		ahead = switch (cfg.lineReach) {
			case SHORT -> 40;
			case LONG -> 110;
			default -> 70;
		};
		boolean band = cfg.lineStyle != LapConfig.LineStyle.ARROWS, arrows = cfg.lineStyle != LapConfig.LineStyle.RIBBON;
		boolean solid = cfg.lineStyle == LapConfig.LineStyle.RIBBON;
		double vNow = boat.getDeltaMovement().horizontalDistance() * 20; // m/s, as the line's
		float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 90.0));
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		PoseStack ps = ctx.poseStack();
		ps.pushPose();
		ps.translate(-cam.x, -cam.y, -cam.z);
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.debugQuads(), (pose, vc) -> {
			Matrix4f m = pose.pose();
			// the glow under it: a soft band, bright in the middle, gone at its edges
			float[] prev = null;
			for (int k = 0; band && k < idx.size(); k++) {
				int i = idx.get(k);
				double[] dir = dir(l, i);
				int c = color(l, i, dist.get(k), vNow, pulse);
				float[] cur = {(float) l.x()[i], (float) l.z()[i], (float) (l.y()[i] + LIFT), (float) dir[0], (float) dir[1], Float.intBitsToFloat(c)};
				if (prev != null) {
					band(vc, m, prev, cur, solid);
				}
				prev = cur;
			}
			// the chevrons: fixed on the ice (every STEP from the line's start), so the car drives over them
			int k = 0;
			double s0 = Math.ceil((sCar - BEHIND) / STEP) * STEP;
			for (double sa = s0; arrows && sa < sCar + ahead; sa += STEP) {
				double d = sa - sCar;
				while (k + 2 < dist.size() && dist.get(k + 1) < d) {
					k++;
				}
				if (dist.get(k) > d || dist.get(k + 1) < d) {
					continue;
				}
				int i = idx.get(k), j = idx.get(k + 1);
				double f = Math.clamp((d - dist.get(k)) / Math.max(1e-6, dist.get(k + 1) - dist.get(k)), 0, 1);
				double x = l.x()[i] + (l.x()[j] - l.x()[i]) * f, z = l.z()[i] + (l.z()[j] - l.z()[i]) * f;
				double y = l.y()[i] + (l.y()[j] - l.y()[i]) * f + LIFT + 0.01;
				double[] di = dir(l, i), dj = dir(l, j);
				double hx = di[0] + (dj[0] - di[0]) * f, hz = di[1] + (dj[1] - di[1]) * f, hl = Math.max(1e-6, Math.hypot(hx, hz));
				int c = color(l, f < 0.5 ? i : j, d, vNow, pulse);
				chevron(vc, m, x, y, z, new double[] {hx / hl, hz / hl}, c);
			}
		});
		ps.popPose();
	}

	private static final double STEP = 1.9, BRAKE = 14; // m/s^2: how hard a driver can slow on the ice, with some margin

	private static final int GO = 0x2BD96B, EASE = 0xFFC83D, STOP = 0xFF3B30;

	/** The way at point i (unit x, z). */
	private static double[] dir(Line l, int i) {
		int n = l.size();
		int i0 = Math.floorMod(i - 2, n), i1 = Math.floorMod(i + 2, n);
		double dx = l.x()[i1] - l.x()[i0], dz = l.z()[i1] - l.z()[i0];
		double len = Math.max(1e-6, Math.hypot(dx, dz));
		return new double[] {dx / len, dz / len};
	}

	/**
	 * The colour at point i, d blocks ahead, for a car at vNow: green where it may go as fast as it is going (or
	 * faster), yellow where it is going too fast for it but has the room to slow down, red where it must brake now
	 * to make it (pulsing when it is already late). Faded under the car and in the distance.
	 */
	private static int color(Line l, int i, double d, double vNow, float pulse) {
		LapConfig cfg = LapConfig.INSTANCE;
		boolean brakesOnly = cfg.lineMode == LapConfig.LineMode.BRAKING;
		float a = 1;
		int c;
		if (cfg.lineColor == LapConfig.LineColor.PROFILE) {
			// the line's own: red where it loses speed, green where it gains it, whatever the car does
			int n = l.size();
			double dv = l.v()[Math.floorMod(i + 10, n)] - l.v()[i];
			c = dv < -0.6 ? mix(0xF2F2F2, STOP, (float) Math.min(1, (-dv - 0.6) / 1.5)) : dv > 0.3 ? mix(0xF2F2F2, GO, (float) Math.min(1, (dv - 0.3) / 1.0)) : 0xF2F2F2;
			if (brakesOnly) {
				a *= (float) Math.clamp((-dv - 0.3) / 0.8, 0, 1);
			}
			return fade(a, d) | c;
		}
		double vt = l.v()[i];
		if (vNow <= vt * 1.02) {
			if (brakesOnly) {
				return 0; // nothing to slow down for here
			}
			c = GO;
		} else {
			double need = (vNow * vNow - vt * vt) / (2 * BRAKE); // blocks to slow down to it
			double slack = Math.max(0, d) - need;
			if (slack > 30) {
				c = mix(GO, EASE, 0.85f);
			} else if (slack > 0) {
				c = mix(STOP, EASE, (float) (slack / 30));
			} else {
				c = mix(STOP, 0xFFFFFF, 0.25f * pulse); // late
			}
		}
		return fade(a, d) | c;
	}

	/** The alpha byte at d blocks ahead: faded under the car and in the distance. */
	private static int fade(float a, double d) {
		a *= (float) Math.clamp((d + BEHIND) / 5, 0, 1);
		a *= (float) Math.clamp((ahead - d) / 25, 0, 1);
		return Math.round(a * 255) << 24;
	}

	/** The glow band from p to q (each: x z y dirx dirz colour): a bright core and soft sides; both faces. */
	private static void band(VertexConsumer vc, Matrix4f m, float[] p, float[] q, boolean solid) {
		int cp = Float.floatToRawIntBits(p[5]), cq = Float.floatToRawIntBits(q[5]);
		float core = solid ? 0.8f : 0.42f; // the ribbon alone: brighter, it is all there is
		int coreP = (Math.round((cp >>> 24) * core) << 24) | (cp & 0xFFFFFF);
		int coreQ = (Math.round((cq >>> 24) * core) << 24) | (cq & 0xFFFFFF);
		int edgeP = cp & 0xFFFFFF, edgeQ = cq & 0xFFFFFF; // alpha 0
		float[] w = {-0.62f, -0.13f, 0.13f, 0.62f};
		int[][] cols = {{edgeP, edgeQ}, {coreP, coreQ}, {coreP, coreQ}, {edgeP, edgeQ}};
		for (int s = 0; s < 3; s++) {
			float a0 = w[s], a1 = w[s + 1];
			float[][] v = {
					{p[0] - p[4] * a0, p[1] + p[3] * a0}, {p[0] - p[4] * a1, p[1] + p[3] * a1},
					{q[0] - q[4] * a1, q[1] + q[3] * a1}, {q[0] - q[4] * a0, q[1] + q[3] * a0}};
			int[] c = {cols[s][0], cols[s + 1][0], cols[s + 1][1], cols[s][1]};
			float[] y = {p[2], p[2], q[2], q[2]};
			for (int t = 0; t < 4; t++) {
				vc.addVertex(m, v[t][0], y[t], v[t][1]).setColor(c[t]);
			}
			for (int t = 3; t >= 0; t--) {
				vc.addVertex(m, v[t][0], y[t], v[t][1]).setColor(c[t]);
			}
		}
	}

	/** A chevron at (x, y, z) pointing along dir: two arms meeting ahead; both faces. */
	private static void chevron(VertexConsumer vc, Matrix4f m, double x, double y, double z, double[] dir, int col) {
		double fx = dir[0], fz = dir[1], nx = -fz, nz = fx;
		double tip = 0.32, back = 0.22, half = 0.46, thick = 0.24;
		double tx = x + fx * tip, tz = z + fz * tip;
		for (int side = -1; side <= 1; side += 2) {
			double ex = x - fx * back + nx * half * side, ez = z - fz * back + nz * half * side;
			double[][] q = {{tx, tz}, {ex, ez}, {ex - fx * thick, ez - fz * thick}, {tx - fx * thick, tz - fz * thick}};
			for (int t = 0; t < 4; t++) {
				vc.addVertex(m, (float) q[t][0], (float) y, (float) q[t][1]).setColor(col);
			}
			for (int t = 3; t >= 0; t--) {
				vc.addVertex(m, (float) q[t][0], (float) y, (float) q[t][1]).setColor(col);
			}
		}
	}

	private static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return r << 16 | g << 8 | bl;
	}

	/** How far along the line (x, z) is, near its point i: on the segment before or after it, whichever is nearer. */
	private static double along(Line l, int i, double x, double z) {
		int n = l.size();
		double best = l.at()[i], bestD = Double.MAX_VALUE;
		for (int side = -1; side <= 0; side++) {
			int a = Math.floorMod(i + side, n), b = Math.floorMod(a + 1, n);
			double vx = l.x()[b] - l.x()[a], vz = l.z()[b] - l.z()[a], l2 = vx * vx + vz * vz;
			double t = l2 < 1e-9 ? 0 : Math.clamp(((x - l.x()[a]) * vx + (z - l.z()[a]) * vz) / l2, 0, 1);
			double d = Math.hypot(l.x()[a] + vx * t - x, l.z()[a] + vz * t - z);
			if (d < bestD) {
				bestD = d;
				best = l.at()[a] + Math.sqrt(l2) * t;
			}
		}
		return best;
	}

	/** Distance from the car (at sCar) along the line to the point at distance s from its start: -len/2 .. len/2. */
	private static double ahead(Line l, double s, double sCar) {
		double d = s - sCar;
		d -= Math.floor(d / l.len() + 0.5) * l.len();
		return d;
	}

	private static int nearest(Line l, double px, double pz, int hint) {
		int n = l.size(), best = 0;
		double bestD = Double.MAX_VALUE;
		boolean near = hint >= 0 && hint < n;
		int lo = near ? hint - 80 : 0, hi = near ? hint + 80 : n - 1;
		for (int k = lo; k <= hi; k++) {
			int i = Math.floorMod(k, n);
			double d = (l.x()[i] - px) * (l.x()[i] - px) + (l.z()[i] - pz) * (l.z()[i] - pz);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		if (near && bestD > 400) {
			return nearest(l, px, pz, -1);
		}
		return best;
	}
}
