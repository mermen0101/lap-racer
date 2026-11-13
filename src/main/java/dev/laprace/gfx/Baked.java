package dev.laprace.gfx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A texture drawn once on the CPU ({@link Canvas}) and uploaded; drawn again only when its key changes (size of
 * the screen, settings, the track). Each frame it is a single textured quad.
 */
public final class Baked {
	private final Identifier id;
	private Object key;
	private DynamicTexture tex;
	public int w;
	public int h;

	public Identifier id() {
		return id;
	}

	/** Frees the texture (it is drawn again on the next {@link #ensure}). */
	public void release() {
		if (tex != null) {
			Minecraft.getInstance().getTextureManager().release(id);
			tex = null;
			key = null;
		}
	}

	public Baked(String name) {
		this.id = Identifier.fromNamespaceAndPath("laprace", "baked/" + name);
	}

	/** Makes sure the texture matches {@code key}, (re)drawing it with {@code painter} if not. */
	public Baked ensure(Object key, Supplier<Canvas> painter) {
		if (tex != null && Objects.equals(key, this.key)) {
			return this;
		}
		Canvas c = painter.get();
		Minecraft mc = Minecraft.getInstance();
		if (tex != null) {
			mc.getTextureManager().release(id);
		}
		tex = new DynamicTexture(id::toString, c.toImage());
		mc.getTextureManager().register(id, tex);
		w = c.w;
		h = c.h;
		this.key = key;
		return this;
	}

	/** Draws the whole texture into the GUI rectangle (x, y, gw, gh). */
	public void draw(GuiGraphicsExtractor g, float x, float y, float gw, float gh) {
		drawPart(g, x, y, gw, gh, 1f);
	}

	/** Draws the left {@code frac} of the texture (for bars that fill), keeping its scale. */
	public void drawPart(GuiGraphicsExtractor g, float x, float y, float gw, float gh, float frac) {
		if (tex == null) {
			return;
		}
		int pw = Math.round(w * Math.clamp(frac, 0f, 1f));
		if (pw <= 0) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(gw / w, gh / h);
		g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, pw, h, w, h);
		g.pose().popMatrix();
	}

	/**
	 * Draws the texture rotated by {@code deg} around the GUI point (cx, cy); (pivotX, pivotY) is the point of the
	 * texture (in its own pixels) placed there; {@code k} = texture pixels per GUI unit.
	 */
	public void drawRotated(GuiGraphicsExtractor g, float cx, float cy, float deg, float pivotX, float pivotY, float k) {
		drawRotated(g, cx, cy, deg, pivotX, pivotY, k, -1);
	}

	/** As above, tinted (ARGB multiplier). */
	public void drawRotated(GuiGraphicsExtractor g, float cx, float cy, float deg, float pivotX, float pivotY, float k, int color) {
		if (tex == null) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate((float) Math.toRadians(deg));
		g.pose().scale(1f / k, 1f / k);
		g.pose().translate(-pivotX, -pivotY);
		g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, w, h, w, h, color);
		g.pose().popMatrix();
	}
}
