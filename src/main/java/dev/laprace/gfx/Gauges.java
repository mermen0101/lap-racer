package dev.laprace.gfx;

import dev.laprace.Track;

/**
 * Painters for the baked HUD parts, drawn after Gran Turismo 7's race screen. Sizes in HUD units; {@code k} =
 * image pixels per unit (screen pixels, so textures are drawn 1:1 and stay sharp).
 * Dial angles: 0 = right, clockwise on screen; a dial sweeps 270 degrees from 135 (bottom left) to 405.
 */
public final class Gauges {
	public static final float DIAL = 80;    // dial texture size (units)
	public static final float DIAL_R = 37;  // outer radius
	public static final float ARC_W = 2.0f; // width of the lit arc
	/** Inner half ring (fuel / drift): centre below the dial centre, radius, width of its lit part. */
	public static final float SUB_DY = 7.5f, SUB_R = 13, SUB_W = 1.2f;
	/** Odometer box: top below the dial centre, size. */
	public static final float ODO_DY = 14.5f, ODO_W = 26.4f, ODO_H = 6.3f;

	/** The panel: the rev bar is its bowed top edge; speed | gear below; red mark above, outside. */
	public static final float PANEL_W = 136, PANEL_TOP_PAD = 6, PANEL_BODY = 32.6f, PANEL_BOW = 3.4f;
	public static final float PANEL_H = PANEL_TOP_PAD + PANEL_BODY + 0.5f;
	public static final float PANEL_SPLIT = 0.505f;
	public static final float REV_X0 = 1.0f, REV_H = 5.1f;
	public static final int REV_SEGMENTS = 95;

	public static final float MAP_W = 80, MAP_H = 80;
	public static final float RADAR_R = 36;
	/** Course map page: units per block. */
	public static final double COURSE_SCALE = 0.55;
	public static final float[] WEDGES = {128, 64, 32, 16, 8, 4, 2, 1};

	public static final String UI_FONT = "inter-semibold.ttf";
	private static final int WHITE = 0xFFF4F4F4;
	private static final int GREY = 0xFFB4B8BC;
	private static final int RED = 0xFFE8302A;

	private Gauges() {
	}

	public static double angle(double f) {
		return 135 + 270 * Math.clamp(f, 0, 1);
	}

	// ------------------------------------------------------------------ dials
	// measured on a GT 7 speedometer (R = 94 px there): ring 5 px lit / 1.5 px unlit, shading ~15 px inside it,
	// numbers at 0.84 R, unit 0.56 R above the centre, fuel half ring r 33 px 19 px below, odometer box 67 x 16 px

	private static Canvas dialBase(double k) {
		Canvas c = new Canvas((int) Math.ceil(DIAL * k), (int) Math.ceil(DIAL * k));
		double cx = DIAL / 2 * k, cy = DIAL / 2 * k, r = DIAL_R * k;
		c.innerShade(cx, cy, r - 0.3 * k, 6.5 * k, 135, 405, 0.6);
		c.arc(cx, cy, r - 0.7 * k, r, 135, 405, 0xD8E4E4E4);
		return c;
	}

	private static void ticksAndNumbers(Canvas c, double k, int max, int step, double redFrom, boolean thousands) {
		double cx = DIAL / 2 * k, cy = DIAL / 2 * k, r = DIAL_R * k;
		int minor = Math.max(1, step / 2);
		for (int v = 0; v <= max; v += minor) {
			boolean major = v % step == 0;
			double a = angle((double) v / max);
			boolean red = v >= redFrom;
			c.tick(cx, cy, r - (major ? 4.1 : 3.3) * k, r - 2.2 * k, a, (major ? 0.8 : 0.5) * k,
					red ? RED : major ? WHITE : 0xC8E0E0E0);
			if (major) {
				double lr = 31.1 * k;
				String s = String.valueOf(thousands ? v / 1000 : v);
				double cap = 2.7 * k;
				c.ttf(s, UI_FONT, cx + Math.cos(Math.toRadians(a)) * lr, cy + Math.sin(Math.toRadians(a)) * lr - cap / 2,
						cap, red ? RED : WHITE, 0);
			}
		}
	}

	/** Inner half ring (fuel / drift): thin base, a tick on top, stubs at its ends, labels, an icon in a faint disc. */
	private static void subGauge(Canvas c, double k, String lo, String hi, boolean fuelIcon) {
		double cx = DIAL / 2 * k, sy = (DIAL / 2 + SUB_DY) * k, r = SUB_R * k;
		c.innerShade(cx, sy, r - 0.2 * k, 3.6 * k, 180, 360, 0.55);
		c.arc(cx, sy, r - 0.5 * k, r, 180, 360, 0x90FFFFFF);
		c.tick(cx, sy, r - 1.6 * k, r - 0.2 * k, 270, 0.5 * k, WHITE);
		c.line(cx - r + 0.4 * k, sy, cx - r + 2.6 * k, sy, 0.9 * k, WHITE);
		c.line(cx + r - 2.6 * k, sy, cx + r - 0.4 * k, sy, 0.9 * k, WHITE);
		double cap = 3.2 * k;
		c.ttf(lo, UI_FONT, cx - r - 2.6 * k, sy - cap / 2, cap, WHITE, 0);
		c.ttf(hi, UI_FONT, cx + r + 2.8 * k, sy - cap / 2, cap, WHITE, 0);
		double ir = 4.6 * k, iy = sy - 1.3 * k;
		c.disc(cx, iy, ir, 0x2CFFFFFF);
		if (fuelIcon) {
			c.rect(cx - 1.7 * k, iy - 2.2 * k, cx + 0.8 * k, iy + 2.3 * k, WHITE);
			c.rect(cx - 1.2 * k, iy - 1.7 * k, cx + 0.3 * k, iy - 0.5 * k, 0xFF2A2D30);
			c.line(cx + 0.8 * k, iy - 1.0 * k, cx + 1.9 * k, iy - 0.1 * k, 0.45 * k, WHITE);
			c.line(cx + 1.9 * k, iy - 0.1 * k, cx + 1.9 * k, iy + 1.9 * k, 0.45 * k, WHITE);
		} else {
			c.polygon(new double[] {cx + 2.2 * k, cx - 1.4 * k, cx - 2.2 * k, cx + 1.0 * k},
					new double[] {iy - 2.2 * k, iy - 1.0 * k, iy + 2.2 * k, iy + 1.2 * k}, WHITE);
		}
	}

	/** G-meter in the tachometer (as BMW M's): radius of the 2 g ring, centre offset below the dial centre. */
	public static final float GM_R = 11, GM_DY = 6;

	/** Rings at 1 g and 2 g, a cross, a little shading inside; the dot is drawn every frame. */
	private static void gMeterFace(Canvas c, double k) {
		double cx = DIAL / 2 * k, cy = (DIAL / 2 + GM_DY) * k, r = GM_R * k;
		c.disc(cx, cy, r, 0x40000000);
		c.innerShade(cx, cy, r, 3.0 * k, 0, 360, 0.45);
		c.arc(cx, cy, r - 0.5 * k, r, 0, 360, 0xD0F0F0F0);
		c.arc(cx, cy, r / 2 - 0.4 * k, r / 2, 0, 360, 0x90F0F0F0);
		c.line(cx - r, cy, cx + r, cy, 0.35 * k, 0x70F0F0F0);
		c.line(cx, cy - r, cx, cy + r, 0.35 * k, 0x70F0F0F0);
		c.ttf("1", UI_FONT, cx + r / 2 + 1.0 * k, cy - 3.2 * k, 1.8 * k, 0xB0F0F0F0, -1);
		c.ttf("2", UI_FONT, cx + r + 1.0 * k, cy - 3.2 * k, 1.8 * k, 0xB0F0F0F0, -1);
	}

	private static void odometerBox(Canvas c, double k) {
		double cx = DIAL / 2 * k, oy = (DIAL / 2 + ODO_DY) * k;
		c.roundRect(cx - ODO_W / 2 * k, oy, cx + ODO_W / 2 * k, oy + ODO_H * k, 0.6 * k, 0x80000000, 0, 0);
	}

	public static Canvas speedDial(double k, String unit, int max, int step) {
		Canvas c = dialBase(k);
		ticksAndNumbers(c, k, max, step, Double.MAX_VALUE, false);
		double cap = 2.9 * k;
		c.ttf(unit, UI_FONT, DIAL / 2 * k, (DIAL / 2 - 20.7) * k - cap / 2, cap, WHITE, 0);
		subGauge(c, k, "E", "F", true);
		odometerBox(c, k);
		return c;
	}

	public static Canvas rpmDial(double k, int maxRpm, double redFrom) {
		Canvas c = dialBase(k);
		double cx = DIAL / 2 * k, cy = DIAL / 2 * k, r = DIAL_R * k;
		c.arc(cx, cy, r - 1.4 * k, r, angle(redFrom / maxRpm), angle(1), RED);
		ticksAndNumbers(c, k, maxRpm, 1000, redFrom, true);
		double cap = 2.6 * k;
		c.ttf("x1000rpm", UI_FONT, cx, (DIAL / 2 - 20.7) * k - cap / 2, cap, WHITE, 0);
		gMeterFace(c, k);
		return c;
	}

	/** A slice of a ring, {@code deg} wide, from angle 0; drawn rotated around its centre. */
	public static Canvas wedge(double k, double deg, int color, double r, double width) {
		double size = r * 2 + 2;
		Canvas c = new Canvas((int) Math.ceil(size * k), (int) Math.ceil(size * k));
		double cx = size / 2 * k, cy = size / 2 * k;
		c.arc(cx, cy, (r - width) * k, r * k, -0.25, deg + 0.25, color);
		return c;
	}

	// ------------------------------------------------------------------ panel with the rev bar
	// measured on a GT 7 panel 317 px wide (= PANEL_W units): the rev bar IS the top edge (12 px high, ~95
	// segments, bowed 8 px), the red mark 10 px above it outside the panel, body down to 76 px below the bar's
	// top, divider at 0.505 W

	/** y of the bar's top edge at x, from the panel's top (TOP_PAD above it is room for the red mark). */
	public static double panelTop(double x) {
		double m = 2 * x / PANEL_W - 1;
		return PANEL_TOP_PAD + PANEL_BOW * m * m;
	}

	public static Canvas panel(double k, int lit) {
		Canvas c = new Canvas((int) Math.ceil(PANEL_W * k), (int) Math.ceil(PANEL_H * k));
		if (lit == 0) {
			int n = 48;
			double bottom = PANEL_TOP_PAD + PANEL_BODY;
			double[] xs = new double[n + 8];
			double[] ys = new double[n + 8];
			int p = 0;
			for (int i = 0; i < n; i++) {
				double x = 1.2 + (PANEL_W - 2.4) * i / (n - 1);
				xs[p] = x * k;
				ys[p++] = panelTop(x) * k;
			}
			xs[p] = PANEL_W * k;
			ys[p++] = (panelTop(PANEL_W) + 1.2) * k;
			xs[p] = (PANEL_W - 3.2) * k;
			ys[p++] = (bottom - 2.2) * k;
			xs[p] = (PANEL_W - 4.4) * k;
			ys[p++] = (bottom - 0.6) * k;
			xs[p] = (PANEL_W - 6.0) * k;
			ys[p++] = bottom * k;
			xs[p] = 6.0 * k;
			ys[p++] = bottom * k;
			xs[p] = 4.4 * k;
			ys[p++] = (bottom - 0.6) * k;
			xs[p] = 3.2 * k;
			ys[p++] = (bottom - 2.2) * k;
			xs[p] = 0;
			ys[p] = (panelTop(0) + 1.2) * k;
			c.polygon(xs, ys, 0x8E000000);
			double dx = PANEL_W * PANEL_SPLIT;
			c.rect(dx * k, (PANEL_TOP_PAD + 7.7) * k, (dx + 0.45) * k, (PANEL_TOP_PAD + 29) * k, 0x99FFFFFF);
		}
		int col = lit == 0 ? 0xB0505458 : lit;
		double span = PANEL_W - 2 * REV_X0;
		double seg = span / REV_SEGMENTS;
		for (int i = 0; i < REV_SEGMENTS; i++) {
			double x0 = REV_X0 + i * seg, x1 = x0 + seg * 0.62;
			double t0 = panelTop(x0) + 0.25, t1 = panelTop(x1) + 0.25;
			c.polygon(new double[] {x0 * k, x1 * k, x1 * k, x0 * k},
					new double[] {t0 * k, t1 * k, (t1 + REV_H) * k, (t0 + REV_H) * k}, col);
		}
		return c;
	}

	/** The drift marker above the panel: a small red dot (drawn moving every frame). */
	public static Canvas dot(double k, int color) {
		Canvas c = new Canvas((int) Math.ceil(3 * k), (int) Math.ceil(3 * k));
		c.disc(1.5 * k, 1.5 * k, 1.1 * k, 0x90000000);
		c.disc(1.5 * k, 1.5 * k, 0.9 * k, color);
		return c;
	}

	public static float revCut(double f) {
		double span = PANEL_W - 2 * REV_X0;
		return (float) ((REV_X0 + span * Math.clamp(f, 0, 1)) / PANEL_W);
	}

	// ------------------------------------------------------------------ small parts

	/** GT's timer box: translucent black, rounded corners, no outline. */
	public static Canvas pill(double k, double w, double h) {
		Canvas c = new Canvas((int) Math.ceil(w * k) + 2, (int) Math.ceil(h * k) + 2);
		c.roundRect(1, 1, w * k + 1, h * k + 1, 1.6 * k, 0x40000000, 0, 0);
		return c;
	}

	/** Page arrow of the centre display: a dark disc with a white triangle. */
	public static Canvas pageArrow(double k, boolean right) {
		double r = 3.4;
		Canvas c = new Canvas((int) Math.ceil(2 * r * k) + 2, (int) Math.ceil(2 * r * k) + 2);
		double cx = r * k + 1, cy = r * k + 1;
		c.disc(cx, cy, r * k, 0x8C000000);
		double s = right ? 1 : -1;
		c.polygon(new double[] {cx + s * 1.5 * k, cx - s * 1.0 * k, cx - s * 1.0 * k},
				new double[] {cy, cy - 1.5 * k, cy + 1.5 * k}, WHITE);
		return c;
	}

	/**
	 * Radar page, as GT 7's: four thin rings round our car, a horizontal and a vertical line through it, and a
	 * light band along our lane fading out ahead and behind. Centre of the texture = our car.
	 */
	public static Canvas radar(double k) {
		double size = RADAR_R * 2 + 2;
		Canvas c = new Canvas((int) Math.ceil(size * k), (int) Math.ceil(size * k));
		double cx = size / 2 * k, cy = size / 2 * k, r = RADAR_R * k;
		// three rings (GT shows two or three, the outer one cut by the display's edge)
		for (int i = 1; i <= 3; i++) {
			double ri = r * 0.36 * i;
			c.arc(cx, cy, ri - 0.45 * k, ri, 0, 360, 0xA6FFFFFF);
		}
		c.line(0, cy, c.w, cy, 0.4 * k, 0x99FFFFFF);
		c.line(cx, 0, cx, c.h, 0.4 * k, 0x80FFFFFF);
		return c;
	}

	/**
	 * The dark backdrop of the centre display (GT darkens behind it so the page reads over any background):
	 * an ellipse of black fading out to its edge.
	 */
	public static Canvas shade(double k, double w, double h, double maxAlpha) {
		Canvas c = new Canvas((int) Math.ceil(w * k), (int) Math.ceil(h * k));
		double cx = c.w / 2.0, cy = c.h / 2.0;
		for (int y = 0; y < c.h; y++) {
			for (int x = 0; x < c.w; x++) {
				// a superellipse: a broad flat middle that fades out towards the edges (not a round spot)
				double dx = Math.abs(x + 0.5 - cx) / cx, dy = Math.abs(y + 0.5 - cy) / cy;
				double d = Math.pow(Math.pow(dx, 3) + Math.pow(dy, 3), 1 / 3.0);
				if (d >= 1) {
					continue;
				}
				double f = 1 - d;
				double a = maxAlpha * Math.min(1, f * 2.2) * Math.min(1, f * 2.2);
				c.blend(x, y, 0xFF000000, a);
			}
		}
		return c;
	}

	public static final double COURSE_PAD = 4;
	/** Radar page: units per block (the outer ring is RADAR_R units = RADAR_RANGE blocks away). */
	public static final double RADAR_RANGE = 36;
	public static final double RADAR_SCALE = RADAR_R / RADAR_RANGE;
	/** Width of the road band on the radar, blocks. */
	private static final double RADAR_ROAD = 8;

	/** Pixels per unit for the radar road texture (kept under the size cap like the course map). */
	public static double roadK(double k, Track t) {
		double w = (t.maxX - t.minX) * RADAR_SCALE + 2 * COURSE_PAD, h = (t.maxZ - t.minZ) * RADAR_SCALE + 2 * COURSE_PAD;
		return Math.min(k, COURSE_MAX_PX / Math.max(w, h));
	}

	/**
	 * The road on the radar: the track as a soft translucent band at the radar's scale (drawn rotated round our
	 * boat every frame), so the radar shows the bends around you as GT's does, not just a straight lane.
	 */
	public static Canvas radarRoad(double k, Track t) {
		double pad = COURSE_PAD;
		double w = (t.maxX - t.minX) * RADAR_SCALE + 2 * pad, h = (t.maxZ - t.minZ) * RADAR_SCALE + 2 * pad;
		Canvas c = new Canvas((int) Math.ceil(w * k), (int) Math.ceil(h * k));
		int n = t.size();
		int step = Math.max(1, (int) (0.8 / RADAR_SCALE));
		double lw = RADAR_ROAD * RADAR_SCALE;
		for (int i = 0; i < n; i += step) {
			int j = i + step >= n ? 0 : i + step;
			c.line((pad + (t.x[i] - t.minX) * RADAR_SCALE) * k, (pad + (t.z[i] - t.minZ) * RADAR_SCALE) * k,
					(pad + (t.x[j] - t.minX) * RADAR_SCALE) * k, (pad + (t.z[j] - t.minZ) * RADAR_SCALE) * k, lw * k, 0xFFFFFFFF);
		}
		// drawn opaque (so overlapping pieces do not add up at the joints), then made translucent as a whole
		c.scaleAlpha(0.26);
		return c;
	}
	/**
	 * The pit lane at the course map page's scale: {@code box} = its bounds (minX, minZ) in blocks, the canvas has
	 * COURSE_PAD units around them.
	 */
	public static Canvas pitCourse(double k, java.util.List<double[]> pit, double[] box) {
		double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (double[] p : pit) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minZ = Math.min(minZ, p[2]);
			maxZ = Math.max(maxZ, p[2]);
		}
		box[0] = minX;
		box[1] = minZ;
		double pad = COURSE_PAD;
		Canvas c = new Canvas((int) Math.ceil(((maxX - minX) * COURSE_SCALE + 2 * pad) * k), (int) Math.ceil(((maxZ - minZ) * COURSE_SCALE + 2 * pad) * k));
		for (int i = 0; i + 1 < pit.size(); i++) {
			double[] a = pit.get(i), b = pit.get(i + 1);
			c.line((pad + (a[0] - minX) * COURSE_SCALE) * k, (pad + (a[2] - minZ) * COURSE_SCALE) * k,
					(pad + (b[0] - minX) * COURSE_SCALE) * k, (pad + (b[2] - minZ) * COURSE_SCALE) * k, 2.4 * COURSE_SCALE * k, 0xFFFFFFFF);
		}
		return c;
	}

	/** The course map texture is kept under this size (it covers the whole track at a large scale). */
	static final int COURSE_MAX_PX = 2048;

	/** Image pixels per unit for the course texture: the screen's, unless the track would make it too large. */
	public static double courseK(double k, Track t) {
		double w = (t.maxX - t.minX) * COURSE_SCALE + 2 * COURSE_PAD, h = (t.maxZ - t.minZ) * COURSE_SCALE + 2 * COURSE_PAD;
		return Math.min(k, COURSE_MAX_PX / Math.max(w, h));
	}

	/** Whole track at the course map page's scale (drawn rotated and clipped every frame). */
	public static Canvas course(double k, Track t) {
		double pad = COURSE_PAD;
		double w = (t.maxX - t.minX) * COURSE_SCALE + 2 * pad, h = (t.maxZ - t.minZ) * COURSE_SCALE + 2 * pad;
		Canvas c = new Canvas((int) Math.ceil(w * k), (int) Math.ceil(h * k));
		int n = t.size();
		int step = Math.max(1, (int) (0.8 / COURSE_SCALE));
		for (int pass = 0; pass < 2; pass++) {
			double lw = pass == 0 ? 3.2 : 1.6;
			int col = pass == 0 ? 0x40000000 : 0xF0F6F6F6;
			for (int i = 0; i < n; i += step) {
				int j = i + step >= n ? 0 : i + step;
				c.line((pad + (t.x[i] - t.minX) * COURSE_SCALE) * k, (pad + (t.z[i] - t.minZ) * COURSE_SCALE) * k,
						(pad + (t.x[j] - t.minX) * COURSE_SCALE) * k, (pad + (t.z[j] - t.minZ) * COURSE_SCALE) * k, lw * k, col);
			}
		}
		return c;
	}

	/** Corner map, as GT's: one even white line with a soft dark shadow; no marks on it. */
	public static Canvas map(double k, Track t, int sectors, double[] fit, java.util.List<double[]> pit) {
		Canvas c = new Canvas((int) Math.ceil(MAP_W * k), (int) Math.ceil(MAP_H * k));
		int n = t.size();
		double sc = fit[0], ox = fit[1], oy = fit[2];
		// the pit lane: a thinner grey line under the track (as GT draws it)
		if (pit != null) {
			for (int pass = 0; pass < 2; pass++) {
				double w = pass == 0 ? 1.9 : 0.8;
				int col = pass == 0 ? 0x40000000 : 0xFFB0B4B8;
				for (int i = 0; i + 1 < pit.size(); i++) {
					double[] a = pit.get(i), b = pit.get(i + 1);
					c.line((ox + (a[0] - t.minX) * sc) * k, (oy + (a[2] - t.minZ) * sc) * k,
							(ox + (b[0] - t.minX) * sc) * k, (oy + (b[2] - t.minZ) * sc) * k, w * k, col);
				}
			}
		}
		int step = Math.max(1, (int) (0.8 / Math.max(1e-6, sc)));
		for (int pass = 0; pass < 2; pass++) {
			double w = pass == 0 ? 2.6 : 1.15;
			int col = pass == 0 ? 0x48000000 : 0xFFF8F8F8;
			for (int i = 0; i < n; i += step) {
				int j = i + step >= n ? 0 : i + step;
				c.line((ox + (t.x[i] - t.minX) * sc) * k, (oy + (t.z[i] - t.minZ) * sc) * k,
						(ox + (t.x[j] - t.minX) * sc) * k, (oy + (t.z[j] - t.minZ) * sc) * k, w * k, col);
			}
		}
		return c;
	}

	public static double[] mapFit(Track t) {
		double pad = 4;
		double w = Math.max(1, t.maxX - t.minX), h = Math.max(1, t.maxZ - t.minZ);
		double sc = Math.min((MAP_W - 2 * pad) / w, (MAP_H - 2 * pad) / h);
		return new double[] {sc, (MAP_W - w * sc) / 2, (MAP_H - h * sc) / 2};
	}

	/** GT's car marker: a slim chevron pointing right (rotated into place), centre at (4, 3) units. */
	public static Canvas arrow(double k, int color) {
		Canvas c = new Canvas((int) Math.ceil(8 * k), (int) Math.ceil(6 * k));
		c.polygon(new double[] {7.6 * k, 0.6 * k, 2.4 * k, 0.6 * k}, new double[] {3 * k, 0.5 * k, 3 * k, 5.5 * k}, 0x70000000);
		c.polygon(new double[] {6.9 * k, 1.3 * k, 2.9 * k, 1.3 * k}, new double[] {3 * k, 1.1 * k, 3 * k, 4.9 * k}, color);
		// a lighter ridge down the middle, like GT's markers
		c.line(2.9 * k, 3 * k, 6.4 * k, 3 * k, 0.45 * k, 0x60FFFFFF);
		return c;
	}
}
