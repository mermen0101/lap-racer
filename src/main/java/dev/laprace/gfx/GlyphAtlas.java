package dev.laprace.gfx;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * Numbers drawn from a baked strip of glyphs (digits and a few signs) in one of our TTF fonts, at an exact cap
 * height in HUD units, optionally stretched wider and with a thin dark outline baked in. Digits share one advance
 * (tabular), so a running time never shifts. One textured quad per character, tinted by the draw colour.
 */
public final class GlyphAtlas {
	private static final String CHARS = "0123456789:,.'-+% ";

	private final String file;
	private final double cap;      // cap height, units
	private final double stretch;  // horizontal stretch
	private final double outline;  // outline width, units (0 = none)
	private final Baked baked;
	private double px = -1;
	/** Per char: x in the strip (image px), width (image px), advance (units). */
	private final int[] gx = new int[CHARS.length()];
	private final int[] gw = new int[CHARS.length()];
	private final double[] adv = new double[CHARS.length()];
	private int stripH;
	/** Image px from the strip's top to the top of the capitals. */
	private int capTop;

	public GlyphAtlas(String name, String file, double cap, double stretch, double outline) {
		this.file = file;
		this.cap = cap;
		this.stretch = stretch;
		this.outline = outline;
		this.baked = new Baked("glyphs_" + name);
	}

	private void ensure(double k) {
		if (k == px) {
			return;
		}
		px = k;
		baked.ensure(k, () -> bake(k));
	}

	private Canvas bake(double k) {
		Font base = AwtFonts.get(file, 100f);
		FontRenderContext frc = new FontRenderContext(null, true, true);
		double capRatio = base.createGlyphVector(frc, "H").getVisualBounds().getHeight() / 100.0;
		float size = (float) (cap * k / capRatio);
		Font font = base.deriveFont(AffineTransform.getScaleInstance(stretch, 1)).deriveFont(size);
		double ol = outline * k;
		int pad = (int) Math.ceil(ol) + 2;
		double capPx = cap * k;
		capTop = pad;
		stripH = (int) Math.ceil(capPx * 1.25) + 2 * pad;
		double digitAdv = 0;
		for (char c = '0'; c <= '9'; c++) {
			digitAdv = Math.max(digitAdv, font.createGlyphVector(frc, String.valueOf(c)).getGlyphMetrics(0).getAdvance());
		}
		int x = 0;
		double[] ink = new double[CHARS.length()];
		for (int i = 0; i < CHARS.length(); i++) {
			GlyphVector gv = font.createGlyphVector(frc, CHARS.substring(i, i + 1));
			Rectangle2D b = gv.getVisualBounds();
			double a = Character.isDigit(CHARS.charAt(i)) ? digitAdv : gv.getGlyphMetrics(0).getAdvance();
			if (CHARS.charAt(i) == ' ') {
				a = digitAdv * 0.5;
			}
			int w = (int) Math.ceil(Math.max(a, b.getMaxX() + 1)) + 2 * pad;
			gx[i] = x;
			gw[i] = w;
			adv[i] = a / k;
			ink[i] = b.getX();
			x += w + 1;
		}
		BufferedImage img = new BufferedImage(Math.max(1, x), stripH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		for (int i = 0; i < CHARS.length(); i++) {
			GlyphVector gv = font.createGlyphVector(frc, CHARS.substring(i, i + 1));
			// digits centred in their tabular cell
			double cell = adv[i] * k;
			double ox = gx[i] + pad + (Character.isDigit(CHARS.charAt(i))
					? (cell - gv.getVisualBounds().getWidth()) / 2 - ink[i] : 0);
			Shape s = gv.getOutline((float) ox, (float) (pad + capPx));
			if (ol > 0) {
				g.setColor(new Color(0, 0, 0, 150));
				g.setStroke(new BasicStroke((float) (ol * 2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(s);
			}
			g.setColor(Color.WHITE);
			g.fill(s);
		}
		g.dispose();
		Canvas c = new Canvas(img.getWidth(), img.getHeight());
		for (int yy = 0; yy < img.getHeight(); yy++) {
			for (int xx = 0; xx < img.getWidth(); xx++) {
				int p = img.getRGB(xx, yy);
				if ((p >>> 24) > 0) {
					c.set(xx, yy, p);
				}
			}
		}
		return c;
	}

	/** Width of the text in units. */
	public double width(String s, double k) {
		ensure(k);
		double w = 0;
		for (int i = 0; i < s.length(); i++) {
			int idx = CHARS.indexOf(s.charAt(i));
			w += idx < 0 ? adv[0] : adv[idx];
		}
		return w;
	}

	/**
	 * Draws {@code s} with the top of its capitals at y; align -1 left, 0 centre, 1 right of x. {@code k} = screen
	 * pixels per unit. Returns the width in units.
	 */
	public double draw(GuiGraphicsExtractor g, String s, float x, float y, int align, int color, double k) {
		ensure(k);
		double w = width(s, k);
		double cx = align < 0 ? x : align == 0 ? x - w / 2 : x - w;
		int pad = gx.length > 0 ? (int) Math.ceil(outline * k) + 2 : 0;
		for (int i = 0; i < s.length(); i++) {
			int idx = CHARS.indexOf(s.charAt(i));
			if (idx < 0) {
				idx = CHARS.indexOf(' ');
			}
			if (s.charAt(i) != ' ') {
				g.pose().pushMatrix();
				g.pose().translate((float) cx, y);
				g.pose().scale((float) (1 / k), (float) (1 / k));
				g.blit(RenderPipelines.GUI_TEXTURED, baked.id(), -pad, -capTop, gx[idx], 0, gw[idx], stripH, baked.w, baked.h, color);
				g.pose().popMatrix();
			}
			cx += adv[idx];
		}
		return w;
	}
}
