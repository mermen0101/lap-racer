package dev.laprace;

import dev.laprace.gfx.Baked;
import dev.laprace.gfx.Canvas;
import dev.laprace.gfx.Labels;
import dev.laprace.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/**
 * Box selection: the pits seen from above, laid along the lane (driving left to right), each box with its number.
 * Click a box (or arrows + Enter) and it is yours on this track: the automatic pit stop drives into it.
 */
public final class PitScreen extends Screen {
	private static final Baked MAP = new Baked("pit_select");
	private static final int ACCENT = 0xFFE8302A;

	private final PitLane lane;
	private int hover = -1;
	private int selected;
	private String note;

	/** Map area on the screen (GUI units) and pixels per block in the texture. */
	private float mx0, my0, mw, mh;
	/** The card around it all. */
	private float cx, cy, cw, ch;
	private double ppb;

	public PitScreen(PitLane lane) {
		super(Component.translatable("laprace.pits.title"));
		this.lane = lane;
	}

	public static void open(Minecraft mc) {
		Track t = RaceSession.INSTANCE.track;
		if (t == null) {
			Notify.show(mc, Component.translatable("laprace.msg.no_track"));
			return;
		}
		mc.gui.setScreen(new PitScreen(PitLane.of(t)));
	}

	@Override
	protected void init() {
		note = null;
		if (lane == null) {
			note = "laprace.pits.no_lane";
			return;
		}
		// look at the pits again (they may have been rebuilt); the saved boxes do if they are not loaded
		boolean seen = lane.fresh() || (minecraft.level != null && lane.scan(minecraft.level));
		if (!seen && !lane.scanned()) {
			note = "laprace.pits.not_loaded";
			return;
		}
		if (lane.boxes().isEmpty() || lane.map() == null) {
			note = "laprace.pits.no_boxes";
			return;
		}
		PitLane.Box c = lane.chosen();
		selected = c != null ? c.number() : 1;
		PitLane.PitMap m = lane.map();
		double gs = minecraft.getWindow().getGuiScale();
		// a card: the header (track, your box), the map, a hint under it
		float availW = width - 60, availH = height - 20 - 54 - 34;
		double unitsPerBlock = Math.min(availW / m.w(), availH / m.h());
		mw = (float) (m.w() * unitsPerBlock);
		mh = (float) (m.h() * unitsPerBlock);
		cw = Math.max(mw + 32, 260);
		ch = 54 + mh + 34;
		cx = (width - cw) / 2f;
		cy = (height - ch) / 2f;
		mx0 = (width - mw) / 2f;
		my0 = cy + 54;
		ppb = unitsPerBlock * gs;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private double gs() {
		return minecraft.getWindow().getGuiScale();
	}

	/** The game's blurred background is not wanted: the scrim is ours (as on the other screens). */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		Ui.sync();
		double k = gs();
		g.fill(0, 0, width, height, Ui.SCRIM);
		Track t = RaceSession.INSTANCE.track;
		if (note != null) {
			// no map: a small card with what is wrong
			float w = Math.min(width - 40, 320), h = 70, x = (width - w) / 2f, y = (height - h) / 2f;
			Ui.card(g, x, y, w, h, k);
			Ui.text(g, Component.translatable("laprace.pits.kicker").getString().toUpperCase(java.util.Locale.ROOT), Labels.BOLD, 3.6, x + 16, y + 14, -1, Ui.ACCENT, k);
			Ui.text(g, t != null ? t.displayName() : "", Labels.BOLD, 7, x + 16, y + 22, -1, Ui.FG, k);
			String msg = Component.translatable(note).getString();
			Ui.text(g, msg.length() > 70 ? msg.substring(0, 70) + "…" : msg, Labels.UI, 4, x + 16, y + 44, -1, Ui.MUTED, k);
			return;
		}
		Ui.card(g, cx, cy, cw, ch, k, false);
		Ui.text(g, Component.translatable("laprace.pits.kicker").getString().toUpperCase(java.util.Locale.ROOT), Labels.BOLD, 3.6, cx + 16, cy + 14, -1, Ui.ACCENT, k);
		Ui.title(g, t != null ? t.displayName() : "", Labels.BOLD, 9, cx + 16, cy + 22, -1, Ui.FG, k);
		hover = boxAt(mouseX, mouseY);
		int sel = selected, hov = hover;
		int pw = (int) Math.round(mw * k), ph = (int) Math.round(mh * k);
		MAP.ensure(List.of(lane.track, pw, ph, sel, hov), () -> paint(pw, ph, sel, hov));
		Ui.fill(g, mx0 - 4, my0 - 4, mw + 8, mh + 8, 6, 0xFF111316, k);
		MAP.draw(g, mx0, my0, mw, mh);

		// your box: a red pill in the header's right corner
		PitLane.Box b = lane.boxes().get(selected - 1);
		String line = Component.translatable("laprace.pits.yours", b.number()).getString();
		float lw = (float) Ui.width(line, Labels.BOLD, 4.6, k) + 18;
		Ui.fill(g, cx + cw - 16 - lw, cy + 18, lw, 16, 8, ACCENT, k);
		Ui.text(g, line, Labels.BOLD, 4.6, cx + cw - 16 - lw / 2, cy + 23.7f, 0, 0xFFFFFFFF, k);
		Ui.text(g, Component.translatable("laprace.pits.hint").getString(), Labels.UI, 3.8, width / 2f, my0 + mh + 14, 0, Ui.MUTED, k);
	}

	/** The map: pits from above, the lane, the boxes with their numbers; the chosen one in red. */
	private Canvas paint(int pw, int ph, int sel, int hov) {
		PitLane.PitMap m = lane.map();
		Canvas c = new Canvas(Math.max(1, pw), Math.max(1, ph));
		double s = pw / (double) m.w();
		for (int v = 0; v < m.h(); v++) {
			for (int u = 0; u < m.w(); u++) {
				int col = switch (m.at(u, v)) {
					case '1', '6' -> 0xFF566070;
					case '2' -> 0xFFD8DCE2;
					case '3' -> 0xFF9A3430;
					case '4' -> 0xFF383D46;
					case '5' -> 0xFF1C1F24;
					default -> 0;
				};
				if (col != 0) {
					c.rect(u * s, v * s, (u + 1) * s, (v + 1) * s, col);
				}
			}
		}
		// driving direction along the bottom edge
		double ay = ph - 1.6 * s;
		c.line(pw * 0.06, ay, pw * 0.94, ay, Math.max(1, 0.25 * s), 0x80FFFFFF);
		c.polygon(new double[] {pw * 0.94 + 0.9 * s, pw * 0.94 - 0.6 * s, pw * 0.94 - 0.6 * s},
				new double[] {ay, ay - 0.8 * s, ay + 0.8 * s}, 0x80FFFFFF);

		for (PitLane.Box b : lane.boxes()) {
			double[] q = m.toMap(b.x(), b.z());
			double cx = q[0] * s, cy = q[1] * s;
			boolean isSel = b.number() == sel, isHov = b.number() == hov;
			// the spot: the black + yellow pair (two blocks along the lane)
			c.roundRect(cx - 1.1 * s, cy - 0.6 * s, cx + 1.1 * s, cy + 0.6 * s, 0.3 * s,
					isSel ? ACCENT : 0x00000000, Math.max(1, 0.18 * s), isSel || isHov ? 0xFFFFFFFF : 0xC0FFFFFF);
			// the number plate, in the garage above the spot
			double ty = cy - 3.6 * s, half = 1.9 * s;
			c.roundRect(cx - half, ty - 1.3 * s, cx + half, ty + 1.3 * s, 0.4 * s,
					isSel ? ACCENT : isHov ? 0xF0F0F0F0 : 0xE0262A31, isSel || isHov ? 0 : Math.max(1, 0.12 * s), 0x80FFFFFF);
			double cap = 1.4 * s;
			c.ttf(Integer.toString(b.number()), Labels.BOLD, cx, ty - cap / 2, cap, isHov && !isSel ? 0xFF15171A : 0xFFFFFFFF, 0);
		}
		return c;
	}

	/** Box under the mouse: the nearest along the lane, anywhere over the map's height. */
	private int boxAt(double x, double y) {
		if (note != null || x < mx0 || x > mx0 + mw || y < my0 || y > my0 + mh) {
			return -1;
		}
		PitLane.PitMap m = lane.map();
		double u = (x - mx0) / mw * m.w();
		int best = -1;
		double bd = 5.0;
		for (PitLane.Box b : lane.boxes()) {
			double d = Math.abs(m.toMap(b.x(), b.z())[0] - u);
			if (d < bd) {
				bd = d;
				best = b.number();
			}
		}
		return best;
	}

	/** Development: a left click on box n's number plate, as the mouse would do it. */
	public void devClick(int n) {
		pressed = -1;
		PitLane.Box b = lane.boxes().get(n - 1);
		PitLane.PitMap m = lane.map();
		double[] q = m.toMap(b.x(), b.z());
		double x = mx0 + q[0] / m.w() * mw, y = my0 + (q[1] - 3.6) / m.h() * mh;
		mouseClicked(new MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
	}

	public int selected() {
		return selected;
	}

	private void choose(int n) {
		if (n < 1 || n > lane.boxes().size()) {
			return;
		}
		selected = n;
		LapConfig.INSTANCE.pitBoxes.put(lane.track, n);
		LapConfig.save();
	}

	/** A box was clicked (pressed): take it on release, so a press that the click does not report still counts. */
	private int pressed = -1;

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		int b = boxAt(e.x(), e.y());
		if (b > 0 && (e.button() == 0 || e.button() == 1)) {
			pressed = b;
			take(b);
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		int b = boxAt(e.x(), e.y());
		if (b > 0 && (e.button() == 0 || e.button() == 1) && pressed != b) {
			take(b);
			return true;
		}
		pressed = -1;
		return super.mouseReleased(e);
	}

	/** Clicking a box is the choice: it is yours, said so, and the screen closes. */
	private void take(int b) {
		choose(b);
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (note == null) {
			switch (e.key()) {
				case InputConstants.KEY_LEFT, InputConstants.KEY_A -> {
					choose(Math.max(1, selected - 1));
					return true;
				}
				case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> {
					choose(Math.min(lane.boxes().size(), selected + 1));
					return true;
				}
				case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_SPACE -> {
					onClose();
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(e);
	}

	@Override
	public void onClose() {
		if (note == null && lane != null) {
			Notify.show(minecraft, Component.translatable("laprace.msg.pit_box", selected));
		}
		MAP.release();
		super.onClose();
	}
}
