package dev.laprace;

import dev.laprace.car.CarPicture;
import dev.laprace.car.FilePick;
import dev.laprace.car.MyLiveries;
import dev.laprace.gfx.Baked;
import dev.laprace.gfx.Labels;
import dev.laprace.ui.El;
import dev.laprace.ui.Ui;
import dev.laprace.ui.UiScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;

/**
 * A livery of ours being made or changed: the car in the picture, large, its name, and "Create" (or "Save"); a
 * changed one may take another picture too.
 */
public final class LiveryEditScreen extends UiScreen {
	private static final float PAD = 16;
	private final GarageScreen parent;
	/** The one changed, or null: a new one. */
	private final MyLiveries.Livery editing;
	private byte[] png;
	private boolean newPicture;
	private String name;
	private String error;
	private boolean picking;
	private final Baked picture = new Baked("livery_edit");
	private int pictureVersion;
	private float cx0, cy0, W, H;

	/** A new one from this picture (its file name for a start of its name). */
	public LiveryEditScreen(GarageScreen parent, byte[] png, String fileName) {
		super(Component.translatable("laprace.garage.new_title"));
		this.parent = parent;
		this.editing = null;
		this.png = png;
		String n = fileName == null ? "" : fileName.replaceAll("(?i)\\.png$", "").replace('_', ' ').trim();
		this.name = n.length() > 32 ? n.substring(0, 32) : n;
	}

	/** One of ours, to change. */
	public LiveryEditScreen(GarageScreen parent, MyLiveries.Livery l) {
		super(Component.translatable("laprace.garage.edit_title"));
		this.parent = parent;
		this.editing = l;
		try {
			this.png = Files.readAllBytes(MyLiveries.file(l));
		} catch (Exception e) {
			this.png = new byte[0];
		}
		this.name = l.name;
	}

	private static String t(String key) {
		return I18n.get("laprace.garage." + key);
	}

	@Override
	protected void layout() {
		W = Math.min(width - 20, 380);
		H = Math.min(height - 20, 270);
		cx0 = (width - W) / 2f;
		cy0 = (height - H) / 2f;
		float inner = W - 2 * PAD, by = cy0 + H - 30;
		EditBox box = new EditBox(font, 0, 0, 100, 10, Component.literal(""));
		box.setMaxLength(32);
		box.setHint(Component.literal(t("name_hint")));
		box.setValue(name == null ? "" : name);
		box.setResponder(v -> name = v);
		addWidget(box);
		add(new El.Text(box), cx0 + PAD, by - 26, inner, 18);
		setFocused(box);
		float bw = 110;
		add(new El.Button(() -> editing == null ? t("create") : t("save"), El.Style.PRIMARY, this::done), cx0 + W - PAD - bw, by, bw, 20);
		add(new El.Button(() -> I18n.get("gui.cancel"), El.Style.GHOST, this::onClose), cx0 + W - PAD - 2 * bw - 8, by, bw, 20);
		if (editing != null) {
			add(new El.Button(() -> picking ? t("picking") : t("replace"), El.Style.GHOST, this::replace), cx0 + PAD, by, bw, 20);
		}
	}

	private void replace() {
		if (picking) {
			return;
		}
		picking = true;
		FilePick.png(MyLiveries.dir()).thenAccept(f -> minecraft.execute(() -> {
			picking = false;
			if (f == null) {
				return;
			}
			try {
				byte[] b = Files.readAllBytes(f);
				String err = MyLiveries.check(b);
				if (err != null) {
					error = t("file_" + err);
					return;
				}
				png = b;
				newPicture = true;
				pictureVersion++;
				error = null;
			} catch (Exception e) {
				error = t("file_not_png");
			}
		}));
	}

	/** Development: as if "Create" were pressed. */
	void devDone() {
		done();
	}

	private void done() {
		try {
			if (editing == null) {
				MyLiveries.create(png, name);
			} else {
				MyLiveries.edit(editing, name, newPicture ? png : null);
			}
			parent.reopened();
			minecraft.gui.setScreen(parent);
		} catch (Exception e) {
			error = e.getMessage();
		}
	}

	private Baked picture() {
		double k = k();
		int pw = Math.max(16, (int) Math.round((W - 2 * PAD) * k)), ph = Math.max(12, (int) Math.round((H - 112) * k));
		return picture.ensure(java.util.List.of(pictureVersion, pw, ph), () -> {
			int[][] tex = CarPicture.texture(png);
			return tex == null ? new dev.laprace.gfx.Canvas(1, 1) : CarPicture.draw(tex, pw, ph, 2.3f, 0.36f, 1.35f);
		});
	}

	@Override
	protected void back(GuiGraphicsExtractor g, double mx, double my, double k) {
		Ui.card(g, cx0, cy0, W, H, k);
		Ui.text(g, t("kicker").toUpperCase(java.util.Locale.ROOT), Labels.BOLD, 3.6, cx0 + PAD, cy0 + 12, -1, Ui.ACCENT, k);
		Ui.title(g, editing == null ? t("new_title") : t("edit_title"), Labels.BOLD, 7, cx0 + PAD, cy0 + 20, -1, Ui.FG, k);
		picture().draw(g, cx0 + PAD, cy0 + 42, W - 2 * PAD, H - 112);
		if (error != null) {
			Ui.text(g, error, Labels.UI, 3.8, cx0 + PAD, cy0 + H - 66, -1, Ui.ACCENT, k);
		}
	}

	@Override
	public void onClose() {
		picture.release();
		minecraft.gui.setScreen(parent);
	}
}
