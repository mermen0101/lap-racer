package dev.laprace.ui;

import dev.laprace.gfx.Labels;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.Supplier;

/** A line of a drop-down menu: its word, a soft highlight under it when hovered; red for one that destroys. */
public final class MenuItem extends El {
	private final Supplier<String> text;
	private final boolean danger;
	private final Runnable action;
	/** A thin line above it (setting it apart from the ones before). */
	private boolean rule;

	public MenuItem(Supplier<String> text, boolean danger, Runnable action) {
		this.text = text;
		this.danger = danger;
		this.action = action;
	}

	public MenuItem rule() {
		this.rule = true;
		return this;
	}

	@Override
	void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
		if (rule) {
			g.fill(Math.round(x + 4), Math.round(y - 1.5f), Math.round(x + w - 4), Math.round(y - 1.5f) + 1, Ui.LINE);
		}
		if (hot > 0.02f) {
			int c = danger ? Ui.ACCENT : Ui.FG;
			Ui.fill(g, x + 2, y, w - 4, h, 4, (Math.round(hot * (danger ? 0.18f : 0.08f) * 255) << 24) | (c & 0xFFFFFF), k);
		}
		Ui.text(g, text.get(), Labels.UI, 4, x + 9, y + (h - 4) / 2, -1, danger ? Ui.mix(Ui.ACCENT, Ui.ACCENT_HI, hot) : Ui.FG, k);
	}

	@Override
	boolean click(double mx, double my) {
		action.run();
		return true;
	}
}
