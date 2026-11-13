package dev.laprace.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen built of {@link El}s instead of the game's buttons: laid out in {@link #layout()}, drawn over a dark scrim
 * after {@link #back}, clicked on press (sliders dragged). Controls marked {@link El#clip} are drawn and clicked only
 * inside the clip band (a scrolled list). Not a pause screen (the race goes on behind it).
 */
public abstract class UiScreen extends Screen {
	protected final List<El> els = new ArrayList<>();
	/** The band the clipped controls show in (GUI units). */
	protected float clipY0, clipY1;
	/** Fades in on opening. */
	private float shown;
	private El held;

	protected UiScreen(Component title) {
		super(title);
	}

	protected <T extends El> T add(T e, float x, float y, float w, float h) {
		e.at(x, y, w, h);
		els.add(e);
		return e;
	}

	@Override
	protected final void init() {
		els.clear();
		held = null;
		layout();
	}

	/** Places the controls (width / height known). */
	protected abstract void layout();

	/** Under the controls: cards, titles, lists. */
	protected abstract void back(GuiGraphicsExtractor g, double mx, double my, double k);

	/** Over the controls (hints, errors). */
	protected void front(GuiGraphicsExtractor g, double mx, double my, double k) {
	}

	/** Before drawing: move what scrolls. */
	protected void place() {
	}

	protected double k() {
		return minecraft.getWindow().getGuiScale();
	}

	/** Whether a row from y, h high, is wholly in the scrolled band (a row half out is not drawn: no cut controls). */
	protected boolean fits(float y, float h) {
		return y >= clipY0 - 0.5f && y + h <= clipY1 + 0.5f;
	}

	protected float shown() {
		return shown;
	}

	private boolean visible(El e, double my) {
		return !e.clip || (my >= clipY0 && my < clipY1 && fits(e.y, e.h));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		Ui.sync();
		shown = Ui.ease(shown, 1);
		double k = k();
		place();
		g.fill(0, 0, width, height, Ui.mix(0x000A0B0D, Ui.SCRIM, shown));
		g.pose().pushMatrix();
		float lift = (1 - shown) * 8;
		g.pose().translate(0, lift);
		double my = mouseY - lift;
		back(g, mouseX, my, k);
		for (El e : els) {
			if (!e.clip) {
				e.frame(mouseX, my);
				e.draw(g, mouseX, my, k);
			}
		}
		g.enableScissor(0, Math.round(clipY0 + lift), width, Math.round(clipY1 + lift));
		double cmy = my >= clipY0 && my < clipY1 ? my : -1e6;
		for (El e : els) {
			if (e.clip && fits(e.y, e.h)) {
				e.frame(mouseX, e == held ? my : cmy);
				e.draw(g, mouseX, cmy, k);
			}
		}
		g.disableScissor();
		front(g, mouseX, my, k);
		g.pose().popMatrix();
	}

	/** The control a press went to (null: none, or the press never came - the game does not always report it). */
	private El pressed;

	/** The control under (x, y): the one drawn on top (the last added), as clicks go. */
	private El at(double x, double y) {
		List<El> all = List.copyOf(els);
		for (int i = all.size() - 1; i >= 0; i--) {
			El el = all.get(i);
			if (el.enabled && el.over(x, y) && visible(el, y)) {
				return el;
			}
		}
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == 0 || e.button() == 1) {
			El el = at(e.x(), e.y());
			if (el != null && el.click(e.x(), e.y())) {
				held = el;
				pressed = el;
				click();
				focus(el);
				return true;
			}
			setFocused(null);
		}
		return super.mouseClicked(e, doubleClick);
	}

	/** A text field clicked: it takes the keys (the cursor in it); anything else clicked: none does. */
	private void focus(El el) {
		if (el instanceof El.Text t) {
			setFocused(t.box);
			t.box.setFocused(true);
		} else {
			setFocused(null);
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (held != null && (e.button() == 0 || e.button() == 1)) {
			held.drag(e.x(), e.y());
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (held != null) {
			held.release();
			held = null;
		}
		if ((e.button() == 0 || e.button() == 1) && pressed == null) {
			// a release with no press before it: the press was lost - the click counts on the release
			El el = at(e.x(), e.y());
			if (el != null && el.click(e.x(), e.y())) {
				el.release();
				click();
				focus(el);
				return true;
			}
		}
		pressed = null;
		return super.mouseReleased(e);
	}

	protected void click() {
		if (minecraft != null) {
			minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
					net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, 0.25f));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** The game's own blurred background is not wanted: the scrim is ours. */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
	}
}
