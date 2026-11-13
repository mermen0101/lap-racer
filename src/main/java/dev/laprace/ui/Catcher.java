package dev.laprace.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** An invisible control over a whole area: a click anywhere there that nothing else took (closing a menu, say). */
public final class Catcher extends El {
	private final Runnable action;
	/** A panel it draws (x, y, w, h: a menu's backing), or null. */
	private float[] panel;

	public Catcher(Runnable action) {
		this.action = action;
	}

	public Catcher panel(float x, float y, float w, float h) {
		this.panel = new float[] {x, y, w, h};
		return this;
	}

	@Override
	void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
		if (panel != null) {
			Ui.shadow(g, panel[0], panel[1], panel[2], panel[3], 10, k);
			Ui.fill(g, panel[0], panel[1], panel[2], panel[3], 6, Ui.CARD2 | 0xFF000000, k);
			Ui.ring(g, panel[0], panel[1], panel[2], panel[3], 6, 0.6f, Ui.LINE, k);
		}
	}

	@Override
	boolean click(double mx, double my) {
		action.run();
		return true;
	}
}
