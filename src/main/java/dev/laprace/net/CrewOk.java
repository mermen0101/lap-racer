package dev.laprace.net;

import dev.laprace.Notify;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The driver's "OK" to their pit crew: one key, one signal (when the voice does not get through). The driver sends
 * it; the crew (the judges' teams) see it on the side of the screen for a few seconds. A kill-a-car's jam stops it.
 */
public final class CrewOk {
	/** Shown this long (ms). */
	public static final long SHOW_MS = 6000;

	/** A signal from a driver of ours: who, when it came (ms). */
	public record Signal(String from, long at) {
	}

	private static final List<Signal> signals = new ArrayList<>();

	private CrewOk() {
	}

	/** Our "OK" to our crew (the key). */
	public static void send(Minecraft mc) {
		if (!Backend.sendOk()) {
			Notify.show(mc, Component.translatable("laprace.ok.offline"));
		}
	}

	/** The server's word on our "OK". */
	static void result(boolean ok, int crew, String why) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> Notify.show(mc, ok ? Component.translatable("laprace.ok.sent", crew)
				: Component.translatable("laprace.ok.failed." + (why == null ? "offline" : why))));
	}

	/** A driver of ours signalled. */
	public static synchronized void received(String from) {
		signals.removeIf(s -> s.from().equalsIgnoreCase(from));
		signals.add(new Signal(from, System.currentTimeMillis()));
	}

	/** The signals still shown, oldest first. */
	public static synchronized List<Signal> shown() {
		long now = System.currentTimeMillis();
		signals.removeIf(s -> now - s.at() > SHOW_MS);
		return List.copyOf(signals);
	}
}
