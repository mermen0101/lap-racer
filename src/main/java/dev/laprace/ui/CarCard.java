package dev.laprace.ui;

import dev.laprace.gfx.Baked;
import dev.laprace.gfx.Labels;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A livery in the garage: the car in it (a picture, made elsewhere), its name and a word under it; the chosen one
 * outlined in the accent. A click chooses it. One of ours has a "⋯" in its corner when hovered (its menu); the
 * "upload" card shows a plus instead of a car.
 */
public final class CarCard extends El {
	private final Supplier<Baked> picture;
	private final Supplier<String> title, note;
	private final BooleanSupplier chosen;
	private final Runnable action;
	/** The "⋯" menu (ours), or null. */
	private Runnable menu;
	private boolean plus;
	private float on = -1;

	public CarCard(Supplier<Baked> picture, Supplier<String> title, Supplier<String> note, BooleanSupplier chosen, Runnable action) {
		this.picture = picture;
		this.title = title;
		this.note = note;
		this.chosen = chosen;
		this.action = action;
	}

	/** With a "⋯" in the corner, shown on hover: its menu. */
	public CarCard menu(Runnable menu) {
		this.menu = menu;
		return this;
	}

	/** The card that adds one: a big plus where the car would be. */
	public CarCard plus() {
		this.plus = true;
		return this;
	}

	private float dotX() {
		return x + w - 20;
	}

	private float dotY() {
		return y + 5;
	}

	private boolean overDot(double mx, double my) {
		return menu != null && mx >= dotX() && mx < dotX() + 15 && my >= dotY() && my < dotY() + 11;
	}

	@Override
	void frame(double mx, double my) {
		super.frame(mx, my);
		float to = chosen.getAsBoolean() ? 1 : 0;
		on = on < 0 ? to : Ui.ease(on, to);
	}

	@Override
	void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
		Ui.fill(g, x, y, w, h, 7, Ui.mix(Ui.CARD2, Ui.CARD3, hot * 0.6f), k);
		Ui.ring(g, x, y, w, h, 7, 1 + on * 0.6f, Ui.mix(plus ? Ui.mix(Ui.LINE, Ui.ACCENT, hot) : Ui.LINE, Ui.ACCENT, on), k);
		float ph = h - 24;
		if (plus) {
			float cx = x + w / 2, cy = y + ph / 2 + 2;
			int c = Ui.mix(Ui.MUTED, Ui.ACCENT, hot);
			Ui.fill(g, cx - 9, cy - 1.2f, 18, 2.4f, 1.2f, c, k);
			Ui.fill(g, cx - 1.2f, cy - 9, 2.4f, 18, 1.2f, c, k);
		} else {
			Baked b = picture.get();
			if (b != null) {
				b.draw(g, x + 4, y + 4, w - 8, ph - 4);
			} else {
				Ui.text(g, "…", Labels.BOLD, 6, x + w / 2, y + ph / 2 - 3, 0, Ui.MUTED, k);
			}
		}
		Ui.text(g, title.get(), Labels.BOLD, 4.4, x + 8, y + ph + 3, -1, Ui.FG, k);
		String n = note.get();
		if (n != null && !n.isEmpty()) {
			Ui.text(g, n, Labels.UI, 3.6, x + 8, y + ph + 11, -1, on > 0.5f ? Ui.ACCENT : Ui.MUTED, k);
		}
		// the menu's "⋯", while hovered
		if (menu != null && hot > 0.05f) {
			boolean over = overDot(mx, my);
			int a = Math.round(hot * 255);
			Ui.fill(g, dotX(), dotY(), 15, 11, 5.5f, (Math.round(a * (over ? 0.95f : 0.75f)) << 24) | (over ? Ui.ACCENT : Ui.CARD3) & 0xFFFFFF, k);
			for (int i = -1; i <= 1; i++) {
				Ui.dot(g, dotX() + 7.5f + i * 3.4f, dotY() + 5.5f, 0.95f, (a << 24) | 0xFFFFFF, k);
			}
		}
	}

	@Override
	boolean click(double mx, double my) {
		if (overDot(mx, my)) {
			menu.run();
		} else {
			action.run();
		}
		return true;
	}
}
