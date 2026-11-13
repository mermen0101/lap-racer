package dev.laprace.ui;

import dev.laprace.Track;
import dev.laprace.gfx.Baked;
import dev.laprace.gfx.Canvas;
import dev.laprace.gfx.Labels;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's own look for its screens (as the site: dark cards, thin lines, one red): rounded shapes baked once per size
 * in white and tinted when drawn (so a hover or a colour change costs nothing), text through {@link Labels}.
 */
public final class Ui {
	/**
	 * A look for the screens: colours, how round the corners are, slanted titles, the card's trim (0 plain, 1 a red and
	 * white stripe along its top, 2 a glowing edge).
	 */
	public record Theme(String key, int scrim, int card, int card2, int card3, int line, int fg, int muted, int dim, int accent,
			int accentHi, float round, boolean slant, int trim) {
	}

	public static final Theme[] THEMES = {
			new Theme("graphite", 0xC80A0B0D, 0xF01A1D21, 0xFF22262B, 0xFF2C3137, 0xFF2E3238, 0xFFECEEF1, 0xFF9AA1AB, 0xFF6B7280,
					0xFFE8302A, 0xFFFF4B44, 1f, true, 0),
			new Theme("track", 0xD0050506, 0xF60C0D0F, 0xFF16181B, 0xFF22252A, 0xFF2A2D32, 0xFFF4F5F6, 0xFF8E949C, 0xFF5E646C,
					0xFFE10600, 0xFFFF2A1F, 0.25f, true, 2),
			new Theme("glass", 0xA0060A12, 0xB8162030, 0xC0223048, 0xD02C3C58, 0x704A86C8, 0xFFEAF4FF, 0xFF9DB4CC, 0xFF6A819A,
					0xFF2FA8FF, 0xFF66C3FF, 1.5f, true, 2),
			new Theme("light", 0x900A0B0D, 0xF6F4F5F7, 0xFFFFFFFF, 0xFFDDE1E6, 0xFFD3D7DC, 0xFF15171A, 0xFF5F6670, 0xFF9AA1AB,
					0xFFE8302A, 0xFFFF4B44, 1f, true, 0),
	};

	public static Theme theme = THEMES[0];
	public static int SCRIM, CARD, CARD2, CARD3, LINE, FG, MUTED, DIM, ACCENT, ACCENT_HI;
	public static final int GOLD = 0xFFFFC21A;
	public static final int GREEN = 0xFF3FB950;

	static {
		use(THEMES[0]);
	}

	/** Takes the theme the settings say (every frame of a screen: cheap). */
	public static void sync() {
		int i = Math.clamp(dev.laprace.LapConfig.INSTANCE.uiTheme, 0, THEMES.length - 1);
		if (theme != THEMES[i]) {
			use(THEMES[i]);
		}
	}

	private static void use(Theme t) {
		theme = t;
		SCRIM = t.scrim();
		CARD = t.card();
		CARD2 = t.card2();
		CARD3 = t.card3();
		LINE = t.line();
		FG = t.fg();
		MUTED = t.muted();
		DIM = t.dim();
		ACCENT = t.accent();
		ACCENT_HI = t.accentHi();
	}

	private record Key(int w, int h, int r, int ring, int kind) {
	}

	private static int counter;
	private static final Map<Key, Baked> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Baked> e) {
			if (size() > 160) {
				e.getValue().release();
				return true;
			}
			return false;
		}
	};

	private Ui() {
	}

	private static Baked shape(int pw, int ph, int pr, int ring, int kind) {
		Key key = new Key(pw, ph, pr, ring, kind);
		Baked b = CACHE.get(key);
		if (b == null) {
			b = new Baked("ui_" + (counter++));
			CACHE.put(key, b);
		}
		return b.ensure(key, () -> {
			Canvas c = new Canvas(Math.max(1, pw), Math.max(1, ph));
			if (kind == 1) {
				// a soft shadow: rings of falling alpha
				for (int i = 0; i < pr; i++) {
					double a = 0.35 * Math.pow(1 - i / (double) pr, 2);
					c.roundRect(pr - i, pr - i, pw - pr + i, ph - pr + i, pr, 0, 1.2, (int) (a * 255) << 24 | 0xFFFFFF);
				}
			} else if (ring > 0) {
				c.roundRect(0.5, 0.5, pw - 0.5, ph - 0.5, pr, 0, ring, 0xFFFFFFFF);
			} else {
				c.roundRect(0, 0, pw, ph, pr, 0xFFFFFFFF, 0, 0);
			}
			return c;
		});
	}

	private static void blit(GuiGraphicsExtractor g, Baked b, float x, float y, double k, int color) {
		// on whole screen pixels: a shape baked at the screen's pixels and drawn between them is smeared or shifted
		float[] at = Labels.snap(g, x, y, k);
		x = at[0];
		y = at[1];
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale((float) (1 / k), (float) (1 / k));
		g.blit(RenderPipelines.GUI_TEXTURED, b.id(), 0, 0, 0f, 0f, b.w, b.h, b.w, b.h, color);
		g.pose().popMatrix();
	}

	/** A filled rounded rectangle. */
	public static void fill(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int color, double k) {
		if ((color >>> 24) == 0) {
			return;
		}
		r = Math.min(r * theme.round(), Math.min(w, h) / 2);
		int pw = Math.round((float) (w * k)), ph = Math.round((float) (h * k));
		blit(g, shape(pw, ph, Math.round((float) (r * k)), 0, 0), x, y, k, color);
	}

	/** A round dot (round in every theme). */
	public static void dot(GuiGraphicsExtractor g, float cx, float cy, float r, int color, double k) {
		int d = Math.max(1, Math.round((float) (2 * r * k)));
		blit(g, shape(d, d, (d + 1) / 2, 0, 0), cx - r, cy - r, k, color);
	}

	/** A rounded outline, width in GUI units. */
	public static void ring(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, float width, int color, double k) {
		if ((color >>> 24) == 0) {
			return;
		}
		r = Math.min(r * theme.round(), Math.min(w, h) / 2);
		int pw = Math.round((float) (w * k)), ph = Math.round((float) (h * k));
		blit(g, shape(pw, ph, Math.round((float) (r * k)), Math.max(1, Math.round((float) (width * k))), 0), x, y, k, color);
	}

	/** A soft shadow around a box (spread in GUI units). */
	public static void shadow(GuiGraphicsExtractor g, float x, float y, float w, float h, float spread, double k) {
		int s = Math.round((float) (spread * k));
		int pw = Math.round((float) (w * k)) + 2 * s, ph = Math.round((float) (h * k)) + 2 * s;
		blit(g, shape(pw, ph, s, 0, 1), x - spread, y - spread, k, 0xFF000000);
	}

	/** A card: shadow, fill, hairline border. */
	public static void card(GuiGraphicsExtractor g, float x, float y, float w, float h, double k) {
		card(g, x, y, w, h, k, true);
	}

	/** A card; routes false: its background without the routes (a card that shows a map of its own). */
	public static void card(GuiGraphicsExtractor g, float x, float y, float w, float h, double k, boolean routes) {
		shadow(g, x, y, w, h, 14, k);
		fill(g, x, y, w, h, 8, CARD, k);
		Backdrop.draw(g, x, y, w, h, k, routes);
		ring(g, x, y, w, h, 8, theme.trim() == 2 ? 0.9f : 0.6f, LINE, k);
		if (theme.trim() == 1) {
			// a red stripe along the top, a short white one after it (slanted ends)
			slanted(g, x + 10, y, 64, 2.4f, ACCENT, k);
			slanted(g, x + 78, y, 16, 2.4f, 0xFFFFFFFF, k);
		} else if (theme.trim() == 2) {
			// a glow along the top edge, in the theme's accent
			fill(g, x + 14, y, w - 28, 1.2f, 0.6f, ACCENT & 0x00FFFFFF | 0xB0000000, k);
			fill(g, x + 40, y - 0.6f, w - 80, 2.4f, 1.2f, ACCENT & 0x00FFFFFF | 0x30000000, k);
		}
	}

	/** A parallelogram (its sides leaning right), as the stripes: baked smooth at the screen's pixels. */
	public static void slanted(GuiGraphicsExtractor g, float x, float y, float w, float h, int color, double k) {
		int pw = Math.max(1, Math.round((float) (w * k))), ph = Math.max(1, Math.round((float) (h * k)));
		int lean = Math.round((float) (h * 0.6f * k));
		List<Integer> key = List.of(pw, ph, lean);
		Baked b = SLANTS.computeIfAbsent(key, kk -> new Baked("ui_sl" + (counter++)));
		b.ensure(key, () -> {
			Canvas c = new Canvas(pw + lean, ph);
			c.polygon(new double[] {lean, pw + lean, pw, 0}, new double[] {0, 0, ph, ph}, 0xFFFFFFFF);
			return c;
		});
		blit(g, b, x, y, k, color);
	}

	private static final java.util.Map<List<Integer>, Baked> SLANTS = new java.util.HashMap<>();

	/**
	 * A title as the site's: upper case, slanted, its last word (or a long word's end) in the accent, the site's two
	 * stripes under it. Left-aligned at x; returns its width.
	 */
	public static double title(GuiGraphicsExtractor g, String s, String font, double cap, float x, float y, int align, int color, double k) {
		String t = s.toUpperCase(java.util.Locale.ROOT).trim();
		int cut = t.lastIndexOf(' ');
		if (cut < 0) {
			cut = t.length() > 5 ? (int) Math.ceil(t.length() * 0.5) : 0;
		} else {
			cut++;
		}
		String a = t.substring(0, cut), b = t.substring(cut);
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		// the slant baked into the letters (smooth), not the quad skewed (stepped edges)
		double sl = theme.slant() ? 0.2 : 0;
		double wa = a.isEmpty() ? 0 : Labels.drawSlanted(g, a, font, cap, sl, 0, 0, -1, color, k);
		double wb = Labels.drawSlanted(g, b, font, cap, sl, (float) wa, 0, -1, ACCENT, k);
		g.pose().popMatrix();
		// the stripes: a long one and a short, fainter one
		float sy = (float) (y + cap + 2.6);
		slanted(g, x, sy, 34, 2.2f, ACCENT, k);
		slanted(g, x + 37, sy, 10, 2.2f, Ui.mix(ACCENT, FG, 0.35f), k);
		return wa + wb;
	}

	public static double text(GuiGraphicsExtractor g, String s, String font, double cap, float x, float y, int align, int color, double k) {
		return Labels.draw(g, s, font, cap, x, y, align, color, k);
	}

	public static double width(String s, String font, double cap, double k) {
		return Labels.width(s, font, cap, k);
	}

	/** A small caps-like label: upper case, muted. */
	public static void label(GuiGraphicsExtractor g, String s, float x, float y, int align, double k) {
		Labels.draw(g, s.toUpperCase(java.util.Locale.ROOT), Labels.BOLD, 3.6, x, y, align, MUTED, k);
	}

	/** Mixes two colours (t 0..1). */
	public static int mix(int a, int b, float t) {
		t = Math.clamp(t, 0f, 1f);
		int r = 0;
		for (int sh = 0; sh < 32; sh += 8) {
			int ca = a >>> sh & 0xFF, cb = b >>> sh & 0xFF;
			r |= Math.round(ca + (cb - ca) * t) << sh;
		}
		return r;
	}

	/** Eases v toward to (every frame): smooth hovers and slides. */
	public static float ease(float v, float to) {
		float d = to - v;
		return Math.abs(d) < 0.002f ? to : v + d * 0.28f;
	}

	// ------------------------------------------------------------------ the track's shape

	private static final Baked TRACK = new Baked("ui_track");

	/** The track's outline fitted into the box, its start line in red. */
	public static void track(GuiGraphicsExtractor g, Track t, float x, float y, float w, float h, double k) {
		if (t == null || t.size() < 2) {
			return;
		}
		double sx = t.maxX - t.minX, sz = t.maxZ - t.minZ;
		double s = Math.min((w - 4) / Math.max(1, sx), (h - 4) / Math.max(1, sz));
		float dw = (float) (sx * s + 4), dh = (float) (sz * s + 4);
		int pw = Math.max(1, Math.round((float) (dw * k))), ph = Math.max(1, Math.round((float) (dh * k)));
		int ink = FG;
		TRACK.ensure(java.util.List.of(t.name, pw, ph, ink), () -> {
			Canvas c = new Canvas(pw, ph);
			double q = s * k, pad = 2 * k;
			int n = t.size();
			int step = Math.max(1, (int) (0.7 / q));
			for (int pass = 0; pass < 2; pass++) {
				for (int i = 0; i < n; i += step) {
					int j = i + step >= n ? 0 : i + step;
					c.line(pad + (t.x[i] - t.minX) * q, pad + (t.z[i] - t.minZ) * q, pad + (t.x[j] - t.minX) * q, pad + (t.z[j] - t.minZ) * q,
							(pass == 0 ? 2.6 : 1.2) * k, pass == 0 ? 0x50000000 : ink);
				}
			}
			c.disc(pad + (t.x[0] - t.minX) * q, pad + (t.z[0] - t.minZ) * q, 1.9 * k, ACCENT);
			return c;
		});
		TRACK.draw(g, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh);
	}
}
