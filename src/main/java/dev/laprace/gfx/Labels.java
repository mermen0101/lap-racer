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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Text that changes rarely (labels, page titles, names) baked once per string into a texture, at an exact cap
 * height in HUD units: precise size and placement, real anti-aliasing, an optional thin outline. Kept in a small
 * LRU cache; drawn as one quad, tinted.
 */
public final class Labels {
	public static final String UI = "inter-semibold.ttf";
	public static final String BOLD = "inter-bold.ttf";
	public static final String DIGITS = "michroma.ttf";

	private record Key(String text, String font, double cap, double stretch, double outline, double k, double slant) {
	}

	private record Item(Baked baked, double widthUnits, int padPx, int capTopPx) {
	}

	private static int counter;
	private static final Map<Key, Item> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Item> e) {
			if (size() > 300) {
				e.getValue().baked().release();
				return true;
			}
			return false;
		}
	};

	private Labels() {
	}

	private static Item get(String text, String font, double cap, double stretch, double outline, double k) {
		return get(text, font, cap, stretch, outline, k, 0);
	}

	/** slant: how far the tops lean right per unit of height (italic, drawn so: smooth, not stepped as a skewed quad). */
	private static Item get(String text, String font, double cap, double stretch, double outline, double k, double slant) {
		Key key = new Key(text, font, cap, stretch, outline, k, slant);
		Item e = CACHE.get(key);
		if (e != null) {
			return e;
		}
		Font base = AwtFonts.get(font, 100f);
		FontRenderContext frc = new FontRenderContext(null, true, true);
		double capRatio = base.createGlyphVector(frc, "H").getVisualBounds().getHeight() / 100.0;
		AffineTransform shape = AffineTransform.getScaleInstance(stretch, 1);
		shape.concatenate(AffineTransform.getShearInstance(-slant, 0));
		Font f = base.deriveFont(shape).deriveFont((float) (cap * k / capRatio));
		GlyphVector gv = f.createGlyphVector(frc, text.isEmpty() ? " " : text);
		Rectangle2D adv = gv.getLogicalBounds();
		int pad = (int) Math.ceil(outline * k) + 2;
		double capPx = cap * k;
		// room above the capitals for what rises over them (Й, Ё, accents): else it is cut off
		int over = (int) Math.ceil(capPx * 0.35);
		int w = (int) Math.ceil(adv.getWidth() + slant * capPx * 1.4) + 2 * pad;
		int h = (int) Math.ceil(capPx * 1.4) + 2 * pad + over;
		BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Shape s = gv.getOutline(pad, (float) (pad + over + capPx));
		if (outline > 0) {
			g.setColor(new Color(0, 0, 0, 140));
			g.setStroke(new BasicStroke((float) (outline * k * 2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(s);
		}
		g.setColor(Color.WHITE);
		g.fill(s);
		g.dispose();
		Canvas c = new Canvas(img.getWidth(), img.getHeight());
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int p = img.getRGB(x, y);
				if ((p >>> 24) > 0) {
					c.set(x, y, p);
				}
			}
		}
		Baked b = new Baked("label_" + (counter++));
		b.ensure(key, () -> c);
		e = new Item(b, adv.getWidth() / k, pad, pad + over);
		CACHE.put(key, e);
		return e;
	}

	public static double width(String text, String font, double cap, double k) {
		return get(text, font, cap, 1, 0, k).widthUnits();
	}

	/** Draws text with the top of its capitals at y; align -1 left, 0 centre, 1 right of x. Returns its width. */
	public static double draw(GuiGraphicsExtractor g, String text, String font, double cap, float x, float y, int align,
			int color, double k) {
		return draw(g, text, font, cap, 1, 0, x, y, align, color, k);
	}

	public static double draw(GuiGraphicsExtractor g, String text, String font, double cap, double stretch, double outline,
			float x, float y, int align, int color, double k) {
		return draw(g, text, font, cap, stretch, outline, 0, x, y, align, color, k);
	}

	/** Italic text: its tops leaning right by slant per unit of height. */
	public static double drawSlanted(GuiGraphicsExtractor g, String text, String font, double cap, double slant, float x, float y, int align,
			int color, double k) {
		return draw(g, text, font, cap, 1, 0, slant, x, y, align, color, k);
	}

	private static double draw(GuiGraphicsExtractor g, String text, String font, double cap, double stretch, double outline, double slant,
			float x, float y, int align, int color, double k) {
		if (text.isEmpty()) {
			return 0;
		}
		Item e = get(text, font, cap, stretch, outline, k, slant);
		double w = e.widthUnits();
		double ox = align < 0 ? x : align == 0 ? x - w / 2 : x - w;
		g.pose().pushMatrix();
		float[] at = snap(g, (float) ox, y, k);
		g.pose().translate(at[0], at[1]);
		g.pose().scale((float) (1 / k), (float) (1 / k));
		g.blit(RenderPipelines.GUI_TEXTURED, e.baked().id(), -e.padPx(), -e.capTopPx(), 0f, 0f, e.baked().w, e.baked().h,
				e.baked().w, e.baked().h, color);
		g.pose().popMatrix();
		return w;
	}

	/**
	 * (x, y) moved onto whole screen pixels, when nothing scales or turns what is drawn (a texture baked at the
	 * screen's pixels and drawn between them is smeared, or shifted a pixel against its neighbours).
	 */
	public static float[] snap(GuiGraphicsExtractor g, float x, float y, double k) {
		org.joml.Matrix3x2f m = g.pose();
		if (m.m00 != 1 || m.m11 != 1 || m.m01 != 0 || m.m10 != 0) {
			return new float[] {x, y};
		}
		float ax = x + m.m20, ay = y + m.m21;
		return new float[] {x + (float) (Math.round(ax * k) / k) - ax, y + (float) (Math.round(ay * k) / k) - ay};
	}
}
