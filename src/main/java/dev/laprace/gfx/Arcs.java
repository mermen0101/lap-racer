package dev.laprace.gfx;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws a partial ring (a dial's lit arc, the countdown ring) with a handful of quads: the span is split into
 * pieces of 128, 64, ... 1 degrees (each baked once) and each piece is drawn rotated into place.
 */
public final class Arcs {
	private final Baked[] pieces = new Baked[Gauges.WEDGES.length];
	private final double r;
	private final double width;
	private final int color;

	public Arcs(String name, double r, double width, int color) {
		for (int i = 0; i < pieces.length; i++) {
			pieces[i] = new Baked(name + "_" + i);
		}
		this.r = r;
		this.width = width;
		this.color = color;
	}

	/** Ring from angle a0 to a1 (degrees, clockwise) around (cx, cy); px = screen pixels per unit. */
	public void draw(GuiGraphicsExtractor g, float cx, float cy, double a0, double a1, double px) {
		double span = a1 - a0;
		if (span < 0.5) {
			return;
		}
		float pivot = (float) ((r + 1) * px);
		double at = a0;
		for (int i = 0; i < pieces.length && span >= 0.5; i++) {
			double w = Gauges.WEDGES[i];
			while (span >= w - 1e-6) {
				final int idx = i;
				pieces[i].ensure(px, () -> Gauges.wedge(px, Gauges.WEDGES[idx], color, r, width))
						.drawRotated(g, cx, cy, (float) at, pivot, pivot, (float) px);
				at += w;
				span -= w;
			}
		}
	}
}
