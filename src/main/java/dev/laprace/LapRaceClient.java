package dev.laprace;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LapRaceClient implements ClientModInitializer {
	public static final String MOD_ID = "laprace";
	public static final Logger LOG = LoggerFactory.getLogger("Lap Race");

	private static KeyMapping raceKey;
	private static KeyMapping resetKey;
	private static KeyMapping trackKey;
	private static KeyMapping recordKey;
	private static KeyMapping boardKey;
	private static KeyMapping mfdPrev;
	private static KeyMapping boxKey;
	private static KeyMapping garageKey;
	private static KeyMapping okKey;
	private static KeyMapping mfdNext;

	@Override
	public void onInitializeClient() {
		// the chosen pit box lit up in the world for a driver at the keys
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(dev.laprace.cruise.PitGuide::render);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(dev.laprace.cruise.RacingLine::render);
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		raceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.race", InputConstants.KEY_R, category));
		resetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.reset", InputConstants.KEY_Y, category));
		trackKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.track", InputConstants.KEY_RBRACKET, category));
		recordKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.record", InputConstants.KEY_O, category));
		boardKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.board", InputConstants.KEY_I, category));
		// no autopilot key: the 80 km/h autopilot drives only in the pit lane (it takes over by itself there)
		boxKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.pit_box", InputConstants.KEY_B, category));
		garageKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.garage", InputConstants.KEY_G, category));
		okKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.ok", InputConstants.KEY_K, category));
		mfdPrev = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.mfd_prev", InputConstants.KEY_LEFT, category));
		mfdNext = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.laprace.mfd_next", InputConstants.KEY_RIGHT, category));

		LapConfig.load();
		RaceSession.INSTANCE.reloadTracks();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			RaceSession session = RaceSession.INSTANCE;
			if (mc.player != null) {
				while (raceKey.consumeClick()) {
					RaceScreen.open(mc);
				}
				while (resetKey.consumeClick()) {
					session.resetSession(mc);
				}
				while (trackKey.consumeClick()) {
					if (session.racing()) {
						RaceSession.say(mc, net.minecraft.network.chat.Component.translatable("laprace.msg.busy"));
					} else {
						session.cycleTrack(mc);
					}
				}
				// recording a track: the judges' tool (the server says who is one)
				while (recordKey.consumeClick()) {
					if (trackTools(mc)) {
						TrackRecorder.toggle(mc);
					}
				}
				while (okKey.consumeClick()) {
					dev.laprace.net.CrewOk.send(mc);
				}
				while (garageKey.consumeClick()) {
					mc.gui.setScreen(new GarageScreen(null));
				}
				while (boxKey.consumeClick()) {
					PitScreen.open(mc);
				}
				while (mfdPrev.consumeClick()) {
					LapConfig.INSTANCE.mfdPage = Math.floorMod(LapConfig.INSTANCE.mfdPage - 1, Hud.mfdPages());
					LapConfig.save();
				}
				while (mfdNext.consumeClick()) {
					LapConfig.INSTANCE.mfdPage = Math.floorMod(LapConfig.INSTANCE.mfdPage + 1, Hud.mfdPages());
					LapConfig.save();
				}
				while (boardKey.consumeClick()) {
					LapConfig.INSTANCE.showBoard = !LapConfig.INSTANCE.showBoard;
					LapConfig.save();
				}
			}
			BoatData.tick(mc);
			Engine.tick(mc);
			EngineSound.tick(mc);
			dev.laprace.net.Backend.tick(mc);
			TrackRecorder.tick(mc);
			PitLane.tick(mc);
			session.tick(mc);
			Ghost.tick(mc);
			dev.laprace.cruise.RaceStart.tick(mc);
			ServerRace.tick(mc);
			Notify.tick(session.now);
			TrackScan.tick(mc, session.now);
			DevDemo.tick(mc);
		});

		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "race_hud"),
				Hud::extract);
	}

	/** This mod's version (fabric.mod.json). */
	public static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	/** Whether we may record tracks and pit lanes: the judges (and a development run) only; others are told so. */
	private static boolean trackTools(net.minecraft.client.Minecraft mc) {
		if (dev.laprace.net.Backend.admin() || System.getenv("LAPRACE_DEMO") != null) {
			return true;
		}
		Notify.show(mc, net.minecraft.network.chat.Component.translatable("laprace.msg.judges_only"));
		return false;
	}
}
