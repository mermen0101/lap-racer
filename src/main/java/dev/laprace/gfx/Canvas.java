package dev.laprace.gfx;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Software drawing into an image (anti-aliased discs, rings, arcs, thick lines, polygons, a small pixel font).
 * Used to bake the static parts of the HUD (dial faces, needles, the rev bar, the track map) once, at screen
 * resolution, so each frame draws them with a single textured quad instead of hundreds of fills.
 */
public final class Canvas {
	public final int w;
	public final int h;
	private final int[] px;

	public Canvas(int w, int h) {
		this.w = Math.max(1, w);
		this.h = Math.max(1, h);
		this.px = new int[this.w * this.h];
	}

	/**
	 * Shading along the inside of a ring (GT's dials): black with {@code maxAlpha} at radius r, fading to nothing
	 * {@code width} further in, over the angles a0..a1 (soft at their ends).
	 */
	public void innerShade(double cx, double cy, double r, double width, double a0, double a1, double maxAlpha) {
		double mid = (a0 + a1) / 2, half = (a1 - a0) / 2;
		int x0 = (int) Math.floor(cx - r), x1 = (int) Math.ceil(cx + r);
		int y0 = (int) Math.floor(cy - r), y1 = (int) Math.ceil(cy + r);
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
				double d = Math.hypot(dx, dy);
				if (d > r || d < r - width) {
					continue;
				}
				double t = (r - d) / width;
				double a = maxAlpha * Math.pow(1 - t, 1.6);
				double ang = Math.abs(wrap(Math.toDegrees(Math.atan2(dy, dx)) - mid));
				a *= Math.clamp((half - ang) / 8.0, 0, 1);
				blend(x, y, 0xFF000000, a);
			}
		}
	}

	/** Multiplies every pixel's alpha by f. */
	public void scaleAlpha(double f) {
		for (int i = 0; i < px.length; i++) {
			int a = (int) Math.round((px[i] >>> 24) * f);
			px[i] = (a << 24) | (px[i] & 0xFFFFFF);
		}
	}

	/** Sets a pixel outright (ARGB). */
	/** Scales the alpha of each column by fade(x) (0..1). */
	public void fadeColumns(java.util.function.IntToDoubleFunction fade) {
		for (int x = 0; x < w; x++) {
			double f = Math.clamp(fade.applyAsDouble(x), 0, 1);
			if (f >= 1) {
				continue;
			}
			for (int y = 0; y < h; y++) {
				int c = px[y * w + x];
				px[y * w + x] = (int) Math.round((c >>> 24) * f) << 24 | (c & 0xFFFFFF);
			}
		}
	}

	public void set(int x, int y, int argb) {
		if (x >= 0 && y >= 0 && x < w && y < h) {
			px[y * w + x] = argb;
		}
	}

	/** Blends colour (ARGB) at coverage a (0..1) over the pixel. */
	public void blend(int x, int y, int argb, double a) {
		if (x < 0 || y < 0 || x >= w || y >= h || a <= 0) {
			return;
		}
		double sa = ((argb >>> 24) / 255.0) * Math.min(1, a);
		if (sa <= 0) {
			return;
		}
		int d = px[y * w + x];
		double da = (d >>> 24) / 255.0;
		double oa = sa + da * (1 - sa);
		int r = mix((argb >> 16) & 255, (d >> 16) & 255, sa, da, oa);
		int gg = mix((argb >> 8) & 255, (d >> 8) & 255, sa, da, oa);
		int b = mix(argb & 255, d & 255, sa, da, oa);
		px[y * w + x] = ((int) Math.round(oa * 255) << 24) | (r << 16) | (gg << 8) | b;
	}

	private static int mix(int s, int d, double sa, double da, double oa) {
		if (oa <= 0) {
			return 0;
		}
		return (int) Math.round((s * sa + d * da * (1 - sa)) / oa);
	}

	public void rect(double x0, double y0, double x1, double y1, int argb) {
		for (int y = (int) Math.floor(y0); y < Math.ceil(y1); y++) {
			double cy = Math.min(y + 1, y1) - Math.max(y, y0);
			for (int x = (int) Math.floor(x0); x < Math.ceil(x1); x++) {
				double cx = Math.min(x + 1, x1) - Math.max(x, x0);
				blend(x, y, argb, cx * cy);
			}
		}
	}

	/**
	 * Ring between radii rIn and rOut, from angle a0 to a1 (degrees, 0 = right, clockwise on screen).
	 * Full ring when a1 - a0 >= 360.
	 */
	public void arc(double cx, double cy, double rIn, double rOut, double a0, double a1, int argb) {
		boolean full = a1 - a0 >= 360;
		double mid = (a0 + a1) / 2;
		double half = (a1 - a0) / 2;
		int x0 = (int) Math.floor(cx - rOut - 1), x1 = (int) Math.ceil(cx + rOut + 1);
		int y0 = (int) Math.floor(cy - rOut - 1), y1 = (int) Math.ceil(cy + rOut + 1);
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
				double r = Math.hypot(dx, dy);
				double cov = Math.clamp(rOut - r + 0.5, 0, 1) * Math.clamp(r - rIn + 0.5, 0, 1);
				if (cov <= 0) {
					continue;
				}
				if (!full) {
					double ang = Math.toDegrees(Math.atan2(dy, dx));
					double diff = Math.abs(wrap(ang - mid));
					double edge = (half - diff) * Math.toRadians(1) * Math.max(r, 1); // pixels inside the angle edge
					cov *= Math.clamp(edge + 0.5, 0, 1);
				}
				blend(x, y, argb, cov);
			}
		}
	}

	public void disc(double cx, double cy, double r, int argb) {
		arc(cx, cy, -1, r, 0, 360, argb);
	}

	/** Signed distance to a rounded rectangle (negative inside). */
	private static double sdRound(double px, double py, double x0, double y0, double x1, double y1, double r) {
		double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
		double hx = (x1 - x0) / 2 - r, hy = (y1 - y0) / 2 - r;
		double qx = Math.abs(px - cx) - hx, qy = Math.abs(py - cy) - hy;
		double ox = Math.max(qx, 0), oy = Math.max(qy, 0);
		return Math.hypot(ox, oy) + Math.min(Math.max(qx, qy), 0) - r;
	}

	/**
	 * Rounded rectangle, anti-aliased: {@code fill} inside, and a {@code border}-wide ring of {@code line} on its
	 * edge (no overlap between the two, so translucent colours stay exact).
	 */
	public void roundRect(double x0, double y0, double x1, double y1, double r, int fill, double border, int line) {
		for (int y = (int) Math.floor(y0) - 1; y <= Math.ceil(y1); y++) {
			for (int x = (int) Math.floor(x0) - 1; x <= Math.ceil(x1); x++) {
				double d = sdRound(x + 0.5, y + 0.5, x0, y0, x1, y1, r);
				double outer = Math.clamp(0.5 - d, 0, 1);
				if (outer <= 0) {
					continue;
				}
				if (border > 0) {
					double inner = Math.clamp(0.5 - (d + border), 0, 1);
					blend(x, y, fill, inner);
					blend(x, y, line, outer - inner);
				} else {
					blend(x, y, fill, outer);
				}
			}
		}
	}

	/** Thick line with round ends. */
	public void line(double ax, double ay, double bx, double by, double width, int argb) {
		double hw = width / 2;
		int x0 = (int) Math.floor(Math.min(ax, bx) - hw - 1), x1 = (int) Math.ceil(Math.max(ax, bx) + hw + 1);
		int y0 = (int) Math.floor(Math.min(ay, by) - hw - 1), y1 = (int) Math.ceil(Math.max(ay, by) + hw + 1);
		double vx = bx - ax, vy = by - ay;
		double l2 = vx * vx + vy * vy;
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				double px0 = x + 0.5 - ax, py0 = y + 0.5 - ay;
				double t = l2 < 1e-9 ? 0 : Math.clamp((px0 * vx + py0 * vy) / l2, 0, 1);
				double d = Math.hypot(px0 - vx * t, py0 - vy * t);
				blend(x, y, argb, Math.clamp(hw - d + 0.5, 0, 1));
			}
		}
	}

	/** Line from radius r0 to r1 at angle a (degrees) around (cx, cy): dial ticks. */
	public void tick(double cx, double cy, double r0, double r1, double a, double width, int argb) {
		double c = Math.cos(Math.toRadians(a)), s = Math.sin(Math.toRadians(a));
		line(cx + c * r0, cy + s * r0, cx + c * r1, cy + s * r1, width, argb);
	}

	/** Convex polygon (points in order), anti-aliased by 4x4 supersampling. */
	public void polygon(double[] xs, double[] ys, int argb) {
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (int i = 0; i < xs.length; i++) {
			minX = Math.min(minX, xs[i]);
			maxX = Math.max(maxX, xs[i]);
			minY = Math.min(minY, ys[i]);
			maxY = Math.max(maxY, ys[i]);
		}
		for (int y = (int) Math.floor(minY); y <= Math.ceil(maxY); y++) {
			for (int x = (int) Math.floor(minX); x <= Math.ceil(maxX); x++) {
				int in = 0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						if (inside(xs, ys, x + (sx + 0.5) / 4, y + (sy + 0.5) / 4)) {
							in++;
						}
					}
				}
				blend(x, y, argb, in / 16.0);
			}
		}
	}

	private static boolean inside(double[] xs, double[] ys, double x, double y) {
		boolean c = false;
		for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
			if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
				c = !c;
			}
		}
		return c;
	}

	/** Pixel-font text; s = image pixels per font pixel; align -1 left, 0 centre, 1 right of x. */
	public void text(String t, double x, double y, double s, int argb, int align) {
		double tw = PixelFont.width(t) * s;
		double ox = align < 0 ? x : align == 0 ? x - tw / 2 : x - tw;
		for (int i = 0; i < t.length(); i++) {
			String[] g = PixelFont.glyph(t.charAt(i));
			for (int r = 0; r < g.length; r++) {
				for (int c = 0; c < g[r].length(); c++) {
					if (g[r].charAt(c) == '#') {
						rect(ox + c * s, y + r * s, ox + (c + 1) * s, y + (r + 1) * s, argb);
					}
				}
			}
			ox += (PixelFont.advance(t.charAt(i))) * s;
		}
	}

	/**
	 * Anti-aliased TrueType text (one of our font files) with its cap height {@code capPx} image pixels;
	 * (x, y) is the top of the capitals; align -1 left, 0 centre, 1 right.
	 */
	public void ttf(String t, String file, double x, double y, double capPx, int argb, int align) {
		java.awt.Font font = AwtFonts.get(file, (float) (capPx / 0.72));
		java.awt.image.BufferedImage probe = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D pg = probe.createGraphics();
		pg.setFont(font);
		java.awt.FontMetrics fm = pg.getFontMetrics();
		int tw = Math.max(1, fm.stringWidth(t));
		int asc = fm.getAscent();
		int th = asc + fm.getDescent();
		pg.dispose();
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(tw + 4, th + 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setFont(font);
		g.setColor(java.awt.Color.WHITE);
		g.drawString(t, 2, 2 + asc);
		g.dispose();
		double cap = capPx;
		double ox = (align < 0 ? x : align == 0 ? x - tw / 2.0 : x - tw) - 2;
		double oy = y - (asc - cap) - 2;
		int ix = (int) Math.round(ox), iy = (int) Math.round(oy);
		for (int yy = 0; yy < img.getHeight(); yy++) {
			for (int xx = 0; xx < img.getWidth(); xx++) {
				int a = img.getRGB(xx, yy) >>> 24;
				if (a > 0) {
					blend(ix + xx, iy + yy, argb, a / 255.0);
				}
			}
		}
	}

	public NativeImage toImage() {
		NativeImage img = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img.setPixel(x, y, px[y * w + x]);
			}
		}
		return img;
	}

	static double wrap(double a) {
		a = (a + 180.0) % 360.0;
		if (a < 0) {
			a += 360.0;
		}
		return a - 180.0;
	}
}
