package dev.laprace;

import dev.laprace.car.CarPicture;
import dev.laprace.car.FilePick;
import dev.laprace.car.Liveries;
import dev.laprace.car.MyLiveries;
import dev.laprace.gfx.Baked;
import dev.laprace.gfx.Labels;
import dev.laprace.net.Backend;
import dev.laprace.ui.CarCard;
import dev.laprace.ui.Catcher;
import dev.laprace.ui.El;
import dev.laprace.ui.Ui;
import dev.laprace.ui.UiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The garage: the liveries, each as the car in it, in three tabs - ours (made from any PNG on the computer: "Upload",
 * the system's file window, then a window with the car in it and its name, "Create"; each with a "⋯" for sending it to
 * the judges, changing or deleting it), the mod's own, and the catalog (everyone's approved ones).
 */
public final class GarageScreen extends UiScreen {
	private static final float PAD = 16, GAP = 8;
	private static final int COLS = 4, ROWS = 2;
	/** The "⋯" menu: its width, a line's height, its height (three lines, a rule before the last). */
	private static final float MENU_W = 84, ITEM_H = 13, MENU_H = 3 * 13 + 9;
	private final Screen parent;
	private float cx0, cy0, W, H, gridY, cw, ch;
	/** 0 ours, 1 the mod's own, 2 the catalog. */
	private static int tab;
	private static int page;
	private static volatile String siteCode;
	/** Whose "⋯" menu is open (ours: its id), and whether "delete" waits for a second click. */
	private String menuFor;
	private boolean confirmDelete;
	private boolean picking;
	private String fileError;
	private long fileErrorAt;
	private float[] menuAt = {0, 0};
	private final Map<String, Baked> pics = new HashMap<>();

	public GarageScreen(Screen parent) {
		super(Component.translatable("laprace.garage.title"));
		this.parent = parent;
		MyLiveries.refresh();
	}

	private static String t(String key) {
		return I18n.get("laprace.garage." + key);
	}

	/** Back from the livery window: ours shown, their states asked for again. */
	void reopened() {
		tab = 0;
		MyLiveries.refresh();
	}

	// ------------------------------------------------------------------ the layout

	@Override
	protected void layout() {
		W = Math.min(width - 20, 470);
		H = Math.min(height - 20, 300);
		cx0 = (width - W) / 2f;
		cy0 = (height - H) / 2f;
		float inner = W - 2 * PAD;
		cw = (inner - (COLS - 1) * GAP) / COLS;
		gridY = cy0 + 66;
		ch = Math.min(cw * 0.62f + 22, (H - 66 - 66 - GAP) / ROWS);
		MyLiveries.Livery open = menuFor == null ? null : MyLiveries.all().stream().filter(l -> l.id.equals(menuFor)).findFirst().orElse(null);
		// the tabs
		add(new El.Segments(new String[] {t("tab_mine"), t("tab_std"), t("tab_catalog")}, () -> tab, i -> {
			tab = i;
			page = 0;
			closeMenu();
		}), cx0 + PAD, cy0 + 42, Math.min(inner, 300), 16);
		// the cards of the tab, a page at a time
		List<CarCard> cards = new ArrayList<>();
		if (tab == 0) {
			cards.add(new CarCard(() -> null, () -> picking ? t("picking") : t("upload"), () -> t("upload_note"), () -> false, this::upload).plus());
			for (MyLiveries.Livery l : mine()) {
				cards.add(new CarCard(() -> pic("my_" + l.id + "_" + fileStamp(l), () -> read(MyLiveries.file(l))), () -> l.name, () -> stateOf(l),
						() -> MyLiveries.isOn(l), () -> {
							MyLiveries.use(l);
							closeMenu();
						}).menu(() -> openMenu(l)));
			}
		} else if (tab == 1) {
			for (LapConfig.Livery lv : new LapConfig.Livery[] {LapConfig.Livery.DEFAULT, LapConfig.Livery.REDBULL, LapConfig.Livery.RENO}) {
				String id = lv.name().toLowerCase(java.util.Locale.ROOT);
				cards.add(new CarCard(() -> pic("std_" + id, () -> resource(id)), () -> I18n.get("laprace.config.car_livery." + id), () -> "",
						() -> LapConfig.INSTANCE.carLiveryHash == null && LapConfig.INSTANCE.carLivery == lv, () -> {
							LapConfig.INSTANCE.carLivery = lv;
							LapConfig.INSTANCE.carLiveryHash = null;
							Backend.sendLivery();
						}));
			}
		} else {
			catalog();
			for (String[] c : CATALOG) {
				String hash = c[0], by = c[1], title = c[2];
				cards.add(new CarCard(() -> catalogPic(hash), () -> title != null ? title : t("untitled"), () -> I18n.get("laprace.garage.catalog_by", by),
						() -> hash.equals(LapConfig.INSTANCE.carLiveryHash), () -> {
							LapConfig.INSTANCE.carLiveryHash = hash;
							Backend.sendLivery();
						}));
			}
		}
		int per = COLS * ROWS, pages = Math.max(1, (cards.size() + per - 1) / per);
		page = Math.clamp(page, 0, pages - 1);
		for (int i = 0; i < per && page * per + i < cards.size(); i++) {
			add(cards.get(page * per + i), cx0 + PAD + (i % COLS) * (cw + GAP), gridY + (i / COLS) * (ch + GAP), cw, ch);
		}
		if (pages > 1) {
			add(new El.Button(() -> "‹", El.Style.GHOST, () -> {
				page = Math.floorMod(page - 1, pages);
				closeMenu();
			}), cx0 + W - PAD - 40, cy0 + 44, 18, 13);
			add(new El.Button(() -> "›", El.Style.GHOST, () -> {
				page = Math.floorMod(page + 1, pages);
				closeMenu();
			}), cx0 + W - PAD - 18, cy0 + 44, 18, 13);
		}
		add(new El.Button(() -> siteCode != null ? siteCode : t("site_code"), El.Style.GHOST, GarageScreen::getCode), cx0 + PAD, cy0 + H - 28, 120, 20);
		add(new El.Button(() -> I18n.get("gui.done"), El.Style.PRIMARY, this::onClose), cx0 + W - PAD - 110, cy0 + H - 28, 110, 20);
		// the "⋯" menu last (on top of the cards): its backing catches a click anywhere else (closing it)
		if (open != null) {
			float mx0 = menuAt[0], my0 = menuAt[1];
			add(new Catcher(this::closeMenu).panel(mx0, my0, MENU_W, MENU_H), 0, 0, width, height);
			add(new dev.laprace.ui.MenuItem(() -> MyLiveries.SENDING.contains(open.id) ? I18n.get("laprace.livery.sending") : t("menu_send"), false, () -> {
				MyLiveries.send(open);
				closeMenu();
			}), mx0 + 2, my0 + 3, MENU_W - 4, ITEM_H);
			add(new dev.laprace.ui.MenuItem(() -> t("menu_edit"), false, () -> {
				closeMenu();
				minecraft.gui.setScreen(new LiveryEditScreen(this, open));
			}), mx0 + 2, my0 + 3 + ITEM_H, MENU_W - 4, ITEM_H);
			add(new dev.laprace.ui.MenuItem(() -> confirmDelete ? t("menu_delete_sure") : t("menu_delete"), true, () -> {
				if (!confirmDelete) {
					confirmDelete = true;
					return;
				}
				MyLiveries.delete(open);
				closeMenu();
			}).rule(), mx0 + 2, my0 + 6 + 2 * ITEM_H, MENU_W - 4, ITEM_H);
		}
	}

	/** Ours, newest first (as the cards stand, after the upload card). */
	private static List<MyLiveries.Livery> mine() {
		List<MyLiveries.Livery> l = new ArrayList<>(MyLiveries.all());
		l.sort((a, b) -> Long.compare(b.at, a.at));
		return l;
	}

	private void openMenu(MyLiveries.Livery l) {
		// beside its card's corner (its place in the grid: after the upload card)
		int i = (mine().indexOf(l) + 1) % (COLS * ROWS);
		// right under its "⋯", flush with the card's right edge
		float x = cx0 + PAD + (i % COLS) * (cw + GAP) + cw - 5 - MENU_W, y = gridY + (i / COLS) * (ch + GAP) + 18;
		menuAt = new float[] {x, y};
		menuFor = l.id;
		confirmDelete = false;
		rebuildWidgets();
	}

	/** Development: the menu of our newest livery open. */
	void devMenu() {
		List<MyLiveries.Livery> l = mine();
		if (!l.isEmpty()) {
			tab = 0;
			openMenu(l.get(0));
		}
	}

	private void closeMenu() {
		menuFor = null;
		confirmDelete = false;
		rebuildWidgets();
	}

	// ------------------------------------------------------------------ uploading: a file, then the livery window

	private void upload() {
		if (picking) {
			return;
		}
		picking = true;
		FilePick.png(null).thenAccept(f -> minecraft.execute(() -> {
			picking = false;
			if (f != null) {
				fromFile(f);
			}
		}));
	}

	private void fromFile(Path f) {
		try {
			byte[] png = Files.readAllBytes(f);
			String err = MyLiveries.check(png);
			if (err != null) {
				fileError = t("file_" + err);
				fileErrorAt = System.currentTimeMillis();
				return;
			}
			minecraft.gui.setScreen(new LiveryEditScreen(this, png, f.getFileName().toString()));
		} catch (Exception e) {
			fileError = t("file_not_png");
			fileErrorAt = System.currentTimeMillis();
		}
	}

	/** A PNG dropped on the window: as if chosen with "Upload". */
	@Override
	public void onFilesDrop(List<Path> files) {
		for (Path f : files) {
			if (f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
				fromFile(f);
				return;
			}
		}
		fileError = t("file_not_png");
		fileErrorAt = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------ the words under our cards

	private static String stateOf(MyLiveries.Livery l) {
		if (MyLiveries.SENDING.contains(l.id)) {
			return I18n.get("laprace.livery.sending");
		}
		String err = MyLiveries.ERRORS.get(l.id);
		if (err != null) {
			return err.length() > 26 ? err.substring(0, 26) + "…" : err;
		}
		return t(l.status == null ? "own_local" : "own_" + l.status);
	}

	// ------------------------------------------------------------------ pictures: the car in each, drawn once

	private static long fileStamp(MyLiveries.Livery l) {
		try {
			return Files.getLastModifiedTime(MyLiveries.file(l)).toMillis();
		} catch (Exception e) {
			return 0;
		}
	}

	private static byte[] read(Path p) {
		try {
			return Files.readAllBytes(p);
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] resource(String id) {
		try (InputStream in = GarageScreen.class.getResourceAsStream("/assets/laprace/textures/entity/f1/" + dev.laprace.car.CarRenderer.file(id) + ".png")) {
			return in.readAllBytes();
		} catch (Exception e) {
			return null;
		}
	}

	private Baked pic(String key, java.util.function.Supplier<byte[]> bytes) {
		double k = k();
		int pw = Math.max(16, (int) Math.round(150 * k)), ph = Math.max(12, (int) Math.round(108 * k));
		Baked b = pics.computeIfAbsent(key, kk -> new Baked("garage_" + Integer.toHexString(kk.hashCode())));
		return b.ensure(List.of(key, pw, ph), () -> {
			byte[] png = bytes.get();
			int[][] tex = png == null ? null : CarPicture.texture(png);
			return tex == null ? new dev.laprace.gfx.Canvas(1, 1) : CarPicture.draw(tex, pw, ph, 2.3f, 0.36f, 1.42f);
		});
	}

	// ------------------------------------------------------------------ the catalog: from the server

	private static volatile List<String[]> CATALOG = List.of();
	private static long catalogAsked;
	private static final Map<String, byte[]> BYTES = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Set<String> LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private void catalog() {
		long now = System.currentTimeMillis();
		if (now - catalogAsked < 30_000 || Backend.state() != Backend.State.ONLINE) {
			return;
		}
		catalogAsked = now;
		CompletableFuture.runAsync(() -> {
			try {
				byte[] b = Backend.fetch("/api/liveries");
				com.google.gson.JsonArray a = com.google.gson.JsonParser.parseString(new String(b, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
				List<String[]> l = new ArrayList<>();
				for (var e : a) {
					var o = e.getAsJsonObject();
					String title = o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : null;
					l.add(new String[] {o.get("hash").getAsString(), o.get("name").getAsString(), title});
				}
				CATALOG = List.copyOf(l);
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> {
					if (mc.gui.screen() instanceof GarageScreen gs) {
						gs.rebuildWidgets();
					}
				});
			} catch (Exception e) {
				LapRaceClient.LOG.debug("livery catalog: {}", e.toString());
			}
		});
	}

	private Baked catalogPic(String hash) {
		byte[] png = BYTES.get(hash);
		if (png == null) {
			if (LOADING.add(hash)) {
				CompletableFuture.runAsync(() -> {
					byte[] b = Liveries.pictureBytes(hash);
					if (b != null) {
						BYTES.put(hash, b);
					}
					LOADING.remove(hash);
				});
			}
			return null;
		}
		return pic("cat_" + hash, () -> png);
	}

	// ------------------------------------------------------------------ the site

	private static void getCode() {
		siteCode = I18n.get("laprace.livery.sending");
		CompletableFuture.runAsync(() -> {
			try {
				String code = Backend.siteCode();
				siteCode = I18n.get("laprace.site.code", code);
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> mc.keyboardHandler.setClipboard(code));
			} catch (Exception e) {
				siteCode = e.getMessage() == null ? I18n.get("laprace.livery.error") : e.getMessage();
			}
		});
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void back(GuiGraphicsExtractor g, double mx, double my, double k) {
		Ui.card(g, cx0, cy0, W, H, k);
		Ui.text(g, t("kicker").toUpperCase(java.util.Locale.ROOT), Labels.BOLD, 3.6, cx0 + PAD, cy0 + 12, -1, Ui.ACCENT, k);
		Ui.title(g, t("title"), Labels.BOLD, 8, cx0 + PAD, cy0 + 20, -1, Ui.FG, k);
		boolean err = fileError != null && System.currentTimeMillis() - fileErrorAt < 6000;
		String hint = err ? fileError : tab == 0 ? t("mine_hint") : tab == 2 && CATALOG.isEmpty() ? t("catalog_empty") : "";
		Ui.text(g, hint, Labels.UI, 3.6, cx0 + PAD, cy0 + H - 42, -1, err ? Ui.ACCENT : Ui.MUTED, k);
	}

	@Override
	public void onClose() {
		LapConfig.save();
		for (Baked b : pics.values()) {
			b.release();
		}
		minecraft.gui.setScreen(parent);
	}
}
