package dev.laprace.ui;

import dev.laprace.gfx.Labels;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** A control of the mod's screens: placed in GUI units, drawn in {@link Ui}'s look, clicked through {@link UiScreen}. */
public abstract class El {
	public float x, y, w, h;
	public boolean enabled = true;
	/** In the screen's scrolled band (drawn and clicked only there). */
	public boolean clip;
	/** Hover, eased 0..1. */
	protected float hot;

	/** Held and moved (after a click on it). */
	void drag(double mx, double my) {
	}

	void release() {
	}

	El at(float x, float y, float w, float h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		return this;
	}

	boolean over(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	void frame(double mx, double my) {
		hot = Ui.ease(hot, enabled && over(mx, my) ? 1 : 0);
	}

	abstract void draw(GuiGraphicsExtractor g, double mx, double my, double k);

	/** A left click inside: true if taken. */
	boolean click(double mx, double my) {
		return false;
	}

	// ------------------------------------------------------------------ a button

	public enum Style { PRIMARY, GHOST, DANGER }

	public static final class Button extends El {
		private final Supplier<String> text;
		private final Style style;
		private final Runnable action;

		public Button(Supplier<String> text, Style style, Runnable action) {
			this.text = text;
			this.style = style;
			this.action = action;
		}

		@Override
		void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
			float r = h / 2.6f;
			int fg;
			switch (style) {
				case PRIMARY -> {
					Ui.fill(g, x, y, w, h, r, enabled ? Ui.mix(Ui.ACCENT, Ui.ACCENT_HI, hot) : Ui.CARD3, k);
					fg = enabled ? 0xFFFFFFFF : Ui.DIM;
				}
				case DANGER -> {
					Ui.fill(g, x, y, w, h, r, Ui.mix(0x00E8302A, 0x30E8302A, hot), k);
					Ui.ring(g, x, y, w, h, r, 0.7f, Ui.mix(0x90E8302A, Ui.ACCENT, hot), k);
					fg = Ui.mix(0xFFFF7A74, 0xFFFFFFFF, hot);
				}
				default -> {
					Ui.fill(g, x, y, w, h, r, Ui.mix(Ui.CARD2, Ui.CARD3, hot), k);
					Ui.ring(g, x, y, w, h, r, 0.6f, Ui.mix(Ui.LINE, 0xFF4A5059, hot), k);
					fg = enabled ? Ui.FG : Ui.DIM;
				}
			}
			float cap = Math.min(5.2f, h * 0.26f);
			Ui.text(g, text.get(), Labels.BOLD, cap, x + w / 2, y + (h - cap) / 2, 0, fg, k);
		}

		@Override
		boolean click(double mx, double my) {
			if (enabled) {
				action.run();
			}
			return true;
		}
	}

	// ------------------------------------------------------------------ one of a few (a sliding red pill)

	public static final class Segments extends El {
		private final String[] options;
		private final IntSupplier get;
		private final IntConsumer set;
		private float pill = -1;
		private int hoverSeg = -1;

		public Segments(String[] options, IntSupplier get, IntConsumer set) {
			this.options = options;
			this.get = get;
			this.set = set;
		}

		private int segAt(double mx) {
			return Math.clamp((int) ((mx - x) / (w / options.length)), 0, options.length - 1);
		}

		@Override
		void frame(double mx, double my) {
			super.frame(mx, my);
			hoverSeg = over(mx, my) ? segAt(mx) : -1;
			int sel = get.getAsInt();
			pill = pill < 0 ? sel : Ui.ease(pill, sel);
		}

		@Override
		void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
			float r = h / 2.6f, sw = w / options.length;
			Ui.fill(g, x, y, w, h, r, Ui.CARD2, k);
			Ui.ring(g, x, y, w, h, r, 0.6f, Ui.LINE, k);
			int sel = get.getAsInt();
			if (hoverSeg >= 0 && hoverSeg != sel) {
				Ui.fill(g, x + hoverSeg * sw + 2, y + 2, sw - 4, h - 4, r - 1, Ui.CARD3, k);
			}
			Ui.fill(g, x + pill * sw + 2, y + 2, sw - 4, h - 4, r - 1, Ui.ACCENT, k);
			float cap = Math.min(4.6f, h * 0.24f);
			for (int i = 0; i < options.length; i++) {
				float near = 1 - Math.min(1, Math.abs(pill - i));
				Ui.text(g, options[i], Labels.BOLD, cap, x + i * sw + sw / 2, y + (h - cap) / 2, 0, Ui.mix(Ui.MUTED, 0xFFFFFFFF, Math.max(near, i == hoverSeg ? 0.6f : 0)), k);
			}
		}

		@Override
		boolean click(double mx, double my) {
			set.accept(segAt(mx));
			return true;
		}
	}

	// ------------------------------------------------------------------ a side tab (a section of a screen)

	public static final class Tab extends El {
		private final String text;
		private final java.util.function.BooleanSupplier on;
		private final Runnable action;
		private float lit = -1;

		public Tab(String text, java.util.function.BooleanSupplier on, Runnable action) {
			this.text = text;
			this.on = on;
			this.action = action;
		}

		@Override
		void frame(double mx, double my) {
			super.frame(mx, my);
			float to = on.getAsBoolean() ? 1 : 0;
			lit = lit < 0 ? to : Ui.ease(lit, to);
		}

		@Override
		void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
			Ui.fill(g, x, y, w, h, 5, Ui.mix(Ui.mix(0x00222629, Ui.CARD2, hot), Ui.CARD3, lit), k);
			if (lit > 0.01f) {
				Ui.fill(g, x, y + 3, 2.5f, (h - 6) * lit, 1.2f, Ui.ACCENT, k);
			}
			Ui.text(g, text, Labels.BOLD, 4.2, x + 9, y + (h - 4.2f) / 2, -1, Ui.mix(Ui.MUTED, Ui.FG, Math.max(lit, hot * 0.7f)), k);
		}

		@Override
		boolean click(double mx, double my) {
			action.run();
			return true;
		}
	}

	// ------------------------------------------------------------------ on / off (a switch)

	public static final class Toggle extends El {
		private final java.util.function.BooleanSupplier get;
		private final java.util.function.Consumer<Boolean> set;
		private final Supplier<String> text;
		private float knob = -1;

		/** text: what it says beside the switch (null: nothing). */
		public Toggle(java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set, Supplier<String> text) {
			this.get = get;
			this.set = set;
			this.text = text;
		}

		@Override
		void frame(double mx, double my) {
			super.frame(mx, my);
			float to = get.getAsBoolean() ? 1 : 0;
			knob = knob < 0 ? to : Ui.ease(knob, to);
		}

		@Override
		void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
			float sh = Math.min(h, 13), sw = sh * 1.85f, sx = x + w - sw, sy = y + (h - sh) / 2;
			Ui.fill(g, sx, sy, sw, sh, sh / 2, Ui.mix(Ui.mix(Ui.CARD3, 0xFF3A4048, hot), Ui.ACCENT, knob), k);
			float d = sh - 4;
			Ui.fill(g, sx + 2 + knob * (sw - sh), sy + 2, d, d, d / 2, 0xFFFFFFFF, k);
			if (text != null) {
				Ui.text(g, text.get(), Labels.BOLD, 4, sx - 6, y + (h - 4) / 2, 1, Ui.mix(Ui.MUTED, Ui.FG, knob), k);
			}
		}

		@Override
		boolean click(double mx, double my) {
			set.accept(!get.getAsBoolean());
			return true;
		}
	}

	// ------------------------------------------------------------------ a number on a bar (dragged)

	public static final class Slider extends El {
		private final int min, max;
		private final IntSupplier get;
		private final IntConsumer set;
		private final java.util.function.IntFunction<String> text;
		private boolean dragging;

		public Slider(int min, int max, IntSupplier get, IntConsumer set, java.util.function.IntFunction<String> text) {
			this.min = min;
			this.max = max;
			this.get = get;
			this.set = set;
			this.text = text;
		}

		private static final float VALUE_W = 40;

		private float barX() {
			return x;
		}

		private float barW() {
			return w - VALUE_W - 6;
		}

		@Override
		void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
			float f = (get.getAsInt() - min) / (float) Math.max(1, max - min);
			float bx = barX(), bw = barW(), by = y + h / 2 - 1.5f;
			Ui.fill(g, bx, by, bw, 3, 1.5f, Ui.CARD3, k);
			Ui.fill(g, bx, by, Math.max(3, bw * f), 3, 1.5f, Ui.ACCENT, k);
			float kn = 8 + (dragging ? 2 : hot * 1.5f);
			Ui.fill(g, bx + bw * f - kn / 2, y + h / 2 - kn / 2, kn, kn, kn / 2, 0xFFFFFFFF, k);
			float vx = x + w - VALUE_W;
			Ui.fill(g, vx, y + (h - 14) / 2, VALUE_W, 14, 5, Ui.CARD2, k);
			Ui.text(g, text.apply(get.getAsInt()), Labels.BOLD, 4, vx + VALUE_W / 2, y + (h - 4) / 2, 0, Ui.FG, k);
		}

		private void to(double mx) {
			float f = (float) Math.clamp((mx - barX()) / barW(), 0, 1);
			set.accept(Math.round(min + f * (max - min)));
		}

		@Override
		boolean click(double mx, double my) {
			if (mx >= x + w - VALUE_W) {
				return true;
			}
			dragging = true;
			to(mx);
			return true;
		}

		@Override
		void drag(double mx, double my) {
			if (dragging) {
				to(mx);
			}
		}

		@Override
		void release() {
			dragging = false;
		}

		/** The wheel over it: one step. */
		public void wheel(int d) {
			set.accept(Math.clamp(get.getAsInt() + d, min, max));
		}
	}

	// ------------------------------------------------------------------ a line of text (the game's edit box, in our frame)

	public static final class Text extends El {
		public final net.minecraft.client.gui.components.EditBox box;

		public Text(net.minecraft.client.gui.components.EditBox box) {
			this.box = box;
			box.setBordered(false);
		}

		@Override
		void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
			boolean focus = box.isFocused();
			Ui.fill(g, x, y, w, h, 5, Ui.CARD2, k);
			Ui.ring(g, x, y, w, h, 5, focus ? 0.9f : 0.6f, focus ? Ui.ACCENT : Ui.mix(Ui.LINE, 0xFF4A5059, hot), k);
			box.setX(Math.round(x + 6));
			box.setY(Math.round(y + (h - 8) / 2));
			box.setWidth(Math.round(w - 12));
			box.extractRenderState(g, (int) mx, (int) my, 0);
		}

		@Override
		boolean click(double mx, double my) {
			return true;
		}
	}

	// ------------------------------------------------------------------ a number with − and +

	public static final class Stepper extends El {
		private final Supplier<String> value;
		private final IntConsumer step;
		private float hotL, hotR;

		public Stepper(Supplier<String> value, IntConsumer step) {
			this.value = value;
			this.step = step;
		}

		@Override
		void frame(double mx, double my) {
			super.frame(mx, my);
			boolean in = over(mx, my);
			hotL = Ui.ease(hotL, in && mx < x + h ? 1 : 0);
			hotR = Ui.ease(hotR, in && mx >= x + w - h ? 1 : 0);
		}

		@Override
		void draw(GuiGraphicsExtractor g, double mx, double my, double k) {
			float r = h / 2.6f;
			Ui.fill(g, x, y, w, h, r, Ui.CARD2, k);
			Ui.ring(g, x, y, w, h, r, 0.6f, Ui.LINE, k);
			Ui.fill(g, x + 2, y + 2, h - 4, h - 4, r - 1, Ui.mix(Ui.CARD3, Ui.ACCENT, hotL), k);
			Ui.fill(g, x + w - h + 2, y + 2, h - 4, h - 4, r - 1, Ui.mix(Ui.CARD3, Ui.ACCENT, hotR), k);
			float cap = h * 0.3f;
			Ui.text(g, "−", Labels.BOLD, cap, x + h / 2, y + (h - cap) / 2 - 0.3f, 0, Ui.mix(Ui.FG, 0xFFFFFFFF, hotL), k);
			Ui.text(g, "+", Labels.BOLD, cap, x + w - h / 2, y + (h - cap) / 2 - 0.3f, 0, Ui.mix(Ui.FG, 0xFFFFFFFF, hotR), k);
			float vc = h * 0.3f;
			Ui.text(g, value.get(), Labels.BOLD, vc, x + w / 2, y + (h - vc) / 2, 0, Ui.FG, k);
		}

		@Override
		boolean click(double mx, double my) {
			if (mx < x + h) {
				step.accept(-1);
			} else if (mx >= x + w - h) {
				step.accept(1);
			}
			return true;
		}
	}
}
