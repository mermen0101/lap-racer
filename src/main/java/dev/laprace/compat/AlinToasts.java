package dev.laprace.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import ru.kelcuprum.alinlib.gui.toast.ToastBuilder;

/** AlinLib toasts (only loaded when AlinLib is installed). */
public final class AlinToasts {
	private AlinToasts() {
	}

	public static void show(Component message) {
		new ToastBuilder()
				.setTitle(Component.literal("Lap Race"))
				.setMessage(message)
				.setIcon(Items.OAK_BOAT)
				.setDisplayTime(3500)
				.buildAndShow();
	}
}
