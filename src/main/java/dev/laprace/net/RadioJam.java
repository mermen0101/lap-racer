package dev.laprace.net;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;


/**
 * The kill-a-car's jammer on this player's radio: the Simple Voice Chat plugin (loaded only when Simple Voice Chat is
 * installed; the state and the sound are in {@link Jam}, which never touches its classes). The level comes from our
 * server ({@code {"t":"jam","level":0..1}}: how close the kill-a-car jamming us is); our voice going out and every voice
 * coming in pass through it: dropouts and static growing with the level, at 1 nothing but static gets through. Without a
 * jam nothing is touched.
 */
public final class RadioJam implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "laprace";
	}

	@Override
	public void registerEvents(EventRegistration r) {
		r.registerEvent(ClientSoundEvent.class, e -> {
			short[] a = e.getRawAudio();
			short[] out = Jam.apply(a);
			if (out != a) {
				e.setRawAudio(out);
			}
		});
		r.registerEvent(ClientReceiveSoundEvent.class, e -> {
			short[] a = e.getRawAudio();
			if (a == null || a.length == 0) {
				return; // end of a transmission
			}
			short[] out = Jam.apply(a);
			if (out != a) {
				e.setRawAudio(out);
			}
		});
	}
}
