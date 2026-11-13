package dev.laprace;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Short status messages (track found, race started / aborted, autopilot on / off, track recording). Where they
 * show is a setting: as cards at the side of the screen inside our HUD (right or left, the default), as AlinLib
 * toasts (top right, where Minecraft puts every toast), or in the chat. Long text (race results) always goes to
 * the chat: see {@link #chat}.
 */
public final class Notify {
	public enum Where { RIGHT, LEFT, TOAST, CHAT }

	private static final boolean ALINLIB = FabricLoader.getInstance().isModLoaded("alinlib");
	/** Cards shown now, newest last. */
	public static final List<Card> CARDS = new ArrayList<>();
	/** Ticks a card stays. */
	public static final int LIFE = 80;

	public record Card(String text, long born) {
	}

	private Notify() {
	}

	public static void show(Minecraft mc, Component message) {
		Where w = LapConfig.INSTANCE.notifyWhere;
		// side cards are part of the HUD: when it is not on screen (not in a boat, no race) they would not be seen
		boolean hudShown = LapConfig.INSTANCE.showHud && mc.player != null
				&& (RaceSession.boatOf(mc.player) != null || RaceSession.INSTANCE.racing());
		if ((w == Where.RIGHT || w == Where.LEFT) && hudShown) {
			CARDS.removeIf(c -> c.text().equals(message.getString()));
			CARDS.add(new Card(message.getString(), RaceSession.INSTANCE.now));
			while (CARDS.size() > 4) {
				CARDS.remove(0);
			}
			return;
		}
		if ((w == Where.TOAST || w == Where.RIGHT || w == Where.LEFT) && ALINLIB) {
			try {
				dev.laprace.compat.AlinToasts.show(message);
				return;
			} catch (LinkageError | RuntimeException e) {
				// AlinLib changed its API: fall back to the chat
			}
		}
		chat(mc, message);
	}

	/** Drops cards that have had their time. */
	public static void tick(long now) {
		CARDS.removeIf(c -> now - c.born() > LIFE || now < c.born());
	}

	public static void chat(Minecraft mc, Component message) {
		if (mc.player != null) {
			mc.player.sendSystemMessage(message);
		}
	}
}
