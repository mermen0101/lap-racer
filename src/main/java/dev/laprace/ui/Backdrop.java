package dev.laprace.ui;

import dev.laprace.RaceSession;
import dev.laprace.Track;
import dev.laprace.gfx.Baked;
import dev.laprace.gfx.Canvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;

/**
 * The background of a window, as the site's: soft glows in the theme's accent, and two of the routes faint, half out of
 * the window, with a light running round each, its trail fading behind it. Drawn inside the card, under its content.
 */
public final class Backdrop {
	private static final Baked GLOW = new Baked("ui_glow");
	private static final Baked[] LINES = {new Baked("ui_bg_l"), new Baked("ui_bg_r")};
	/** Which routes: one large on the right, one smaller low on the left. */
	private static final String[] ROUTES = {"route1", "route5"};

	/** A route as drawn in the card: its box and points (GUI units) and the distance to each from the start. */
	private record Side(float x, float y, float w, float h, float[] px, float[] py, float[] at, float len) {
	}

	private static final Side[] sides = new Side[2];
	/** The card's left edge and width, for the fade: the routes keep off its left part, where the text is. */
	private static float cardX, cardW;

	/** How much of the routes shows at x: none over the left half, all from three quarters across. */
	private static float fade(float x) {
		float t = Math.clamp((x - (cardX + cardW * 0.48f)) / (cardW * 0.25f), 0, 1);
		return t * t * (3 - 2 * t);
	}
	private static Object sidesKey;

	private Backdrop() {
	}

	/** The card's background at (x, y, w, h), clipped to it; routes false: the glows only. */
	public static void draw(GuiGraphicsExtractor g, float x, float y, float w, float h, double k, boolean routes) {
		int accent = Ui.ACCENT & 0x00FFFFFF;
		boolean light = Ui.theme.key().equals("light");
		// clip inside the rounded corners
		float in = 8 * Ui.theme.round() * 0.3f;
		g.enableScissor(Math.round(x + in), Math.round(y + in), Math.round(x + w - in), Math.round(y + h - in));
		float big = Math.max(w, h);
		glow(g, x + w * 0.85f, y + h * 0.05f, big * 0.55f, accent | (light ? 0x12 : 0x2C) << 24, k);
		glow(g, x + w * 0.05f, y + h * 1.0f, big * 0.45f, accent | (light ? 0x0A : 0x1A) << 24, k);
		if (routes) {
			routes(g, x, y, w, h, k, accent, light);
		}
		g.disableScissor();
	}

	private static void routes(GuiGraphicsExtractor g, float x, float y, float w, float h, double k, int accent, boolean light) {
		layout(x, y, w, h);
		long now = System.currentTimeMillis();
		int ink = light ? 0x000000 : 0xFFFFFF;
		for (int i = 0; i < 2; i++) {
			Side s = sides[i];
			if (s == null) {
				continue;
			}
			int pw = Math.max(1, Math.round((float) (s.w() * k))), ph = Math.max(1, Math.round((float) (s.h() * k)));
			// the route: one solid thin line (drawn opaque, so the joints do not pile up; faded when drawn)
			LINES[i].ensure(List.of(ROUTES[i], pw, ph, cardX, cardW), () -> {
				Canvas c = new Canvas(pw, ph);
				int m = s.px().length;
				for (int j = 0; j < m; j++) {
					int n = (j + 1) % m;
					c.line((s.px()[j] - s.x()) * k, (s.py()[j] - s.y()) * k, (s.px()[n] - s.x()) * k, (s.py()[n] - s.y()) * k, Math.max(1.0, 0.9 * k),
							0xFFFFFFFF);
				}
				c.fadeColumns(px -> fade((float) (s.x() + px / k)));
				return c;
			});
			g.pose().pushMatrix();
			g.pose().translate(s.x(), s.y());
			g.pose().scale(s.w() / LINES[i].w, s.h() / LINES[i].h);
			g.blit(RenderPipelines.GUI_TEXTURED, LINES[i].id(), 0, 0, 0f, 0f, LINES[i].w, LINES[i].h, LINES[i].w, LINES[i].h,
					ink | (light ? 0x22 : 0x26) << 24);
			g.pose().popMatrix();
			// the light, as the site's: a glowing stretch of the line, bright at its head, fading behind it
			double period = i == 0 ? 22_000 : 17_000;
			float head = (float) ((now % (long) period) / period * s.len());
			float trail = Math.min(70, s.len() * 0.16f);
			for (float d = trail; d >= 0; d -= 0.35f) {
				float[] p = at(s, head - d);
				float a = 1 - d / trail;
				Ui.dot(g, p[0], p[1], 0.65f, accent | Math.round(a * a * 120 * fade(p[0])) << 24, k);
			}
			for (float d = trail * 0.6f; d >= 0; d -= 1.5f) {
				float[] p = at(s, head - d);
				float a = 1 - d / (trail * 0.6f);
				Ui.dot(g, p[0], p[1], 2.2f, accent | Math.round(a * a * 26 * fade(p[0])) << 24, k);
			}
			float[] p = at(s, head);
			glow(g, p[0], p[1], 7, accent | Math.round(0x70 * fade(p[0])) << 24, k);
		}
	}

	private static Track track(String name) {
		for (Track t : RaceSession.INSTANCE.tracks) {
			if (t.name.equals(name)) {
				return t;
			}
		}
		return null;
	}

	/** The routes placed in the card: the first large, its right part out of the card; the second low on the left. */
	private static void layout(float cx, float cy, float cw, float ch) {
		Object key = List.of(cx, cy, cw, ch, RaceSession.INSTANCE.tracks.size());
		if (key.equals(sidesKey)) {
			return;
		}
		sidesKey = key;
		cardX = cx;
		cardW = cw;
		for (int i = 0; i < 2; i++) {
			Track t = track(ROUTES[i]);
			sides[i] = null;
			if (t == null || t.size() < 3) {
				continue;
			}
			float boxW = i == 0 ? cw * 0.50f : cw * 0.38f, boxH = i == 0 ? ch * 0.80f : ch * 0.55f;
			float bx = i == 0 ? cx + cw - boxW * 0.75f : cx + cw * 0.50f;
			float by = i == 0 ? cy - ch * 0.08f : cy + ch - boxH * 0.65f;
			double sx = t.maxX - t.minX, sz = t.maxZ - t.minZ;
			double sc = Math.min(boxW / Math.max(1, sx), boxH / Math.max(1, sz));
			float w = (float) (sx * sc), h = (float) (sz * sc);
			float x0 = bx + (boxW - w) / 2, y0 = by + (boxH - h) / 2;
			int step = Math.max(1, t.size() / 400);
			List<float[]> pts = new ArrayList<>();
			for (int j = 0; j < t.size(); j += step) {
				pts.add(new float[] {(float) (x0 + (t.x[j] - t.minX) * sc), (float) (y0 + (t.z[j] - t.minZ) * sc)});
			}
			float[] px = new float[pts.size()], py = new float[pts.size()], at = new float[pts.size()];
			float len = 0;
			for (int j = 0; j < pts.size(); j++) {
				px[j] = pts.get(j)[0];
				py[j] = pts.get(j)[1];
				if (j > 0) {
					len += (float) Math.hypot(px[j] - px[j - 1], py[j] - py[j - 1]);
				}
				at[j] = len;
			}
			len += (float) Math.hypot(px[0] - px[px.length - 1], py[0] - py[py.length - 1]);
			sides[i] = new Side(x0 - 2, y0 - 2, w + 4, h + 4, px, py, at, len);
		}
	}

	/** The point at distance d along the side's route (wrapping round). */
	private static float[] at(Side s, float d) {
		d = ((d % s.len()) + s.len()) % s.len();
		float[] at = s.at();
		int lo = 0, hi = at.length - 1;
		if (d >= at[hi]) {
			// the closing stretch, from the last point back to the first
			float f = (d - at[hi]) / Math.max(1e-3f, s.len() - at[hi]);
			return new float[] {s.px()[hi] + (s.px()[0] - s.px()[hi]) * f, s.py()[hi] + (s.py()[0] - s.py()[hi]) * f};
		}
		while (hi - lo > 1) {
			int m = (lo + hi) >>> 1;
			if (at[m] <= d) {
				lo = m;
			} else {
				hi = m;
			}
		}
		float f = (d - at[lo]) / Math.max(1e-3f, at[hi] - at[lo]);
		return new float[] {s.px()[lo] + (s.px()[hi] - s.px()[lo]) * f, s.py()[lo] + (s.py()[hi] - s.py()[lo]) * f};
	}

	/** A soft round glow (baked once, tinted). */
	private static void glow(GuiGraphicsExtractor g, float cx, float cy, float r, int color, double k) {
		GLOW.ensure("glow", () -> {
			int n = 128;
			Canvas c = new Canvas(n, n);
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					double d = Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) / (n / 2.0);
					if (d < 1) {
						double a = Math.pow(1 - d, 2.2);
						c.set(x, y, (int) Math.round(a * 255) << 24 | 0xFFFFFF);
					}
				}
			}
			return c;
		});
		g.pose().pushMatrix();
		g.pose().translate(cx - r, cy - r);
		g.pose().scale(2 * r / GLOW.w, 2 * r / GLOW.h);
		g.blit(RenderPipelines.GUI_TEXTURED, GLOW.id(), 0, 0, 0f, 0f, GLOW.w, GLOW.h, GLOW.w, GLOW.h, color);
		g.pose().popMatrix();
	}
}
