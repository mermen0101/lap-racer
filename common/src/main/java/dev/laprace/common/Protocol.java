package dev.laprace.common;

/**
 * What the mod and the backend say to each other. Shared source: compiled into both, so they cannot drift apart.
 * <p>
 * Sign-in (Mojang, no passwords):
 * <ol>
 * <li>mod: {@code GET  /api/auth/challenge?name=NAME} -> {@code {"serverId": "...", "dev": false}}</li>
 * <li>mod: joins that serverId with its Mojang session (as when joining any online-mode server); skipped if dev</li>
 * <li>mod: {@code POST /api/auth/verify {"name": "...", "serverId": "..."}} -> backend asks Mojang "has NAME joined
 * serverId?" -> {@code {"token": "...", "uuid": "...", "name": "...", "admin": false}}</li>
 * </ol>
 * Then the mod opens the WebSocket {@code /ws/client?token=TOKEN} and sends JSON messages ({@code "t"} = type):
 * <ul>
 * <li>{@code {"t":"hello","v":VERSION,"mod":"0.1.0","others":true}} - others: send me everyone's position (Ice Racer's
 * chase)</li>
 * <li>{@code {"t":"jam","target":UUID|null}} - kill-a-car only (others are ignored): jam that player's radio / stop</li>
 * <li>{@code {"t":"pos","x":..,"y":..,"z":..,"yaw":..,"v":m/s,"track":"route3"|null,"boat":true,"thr":0..1,"brk":0..1,
 * "gear":1..8,"ts":epoch ms when taken}} - 10 times a second</li>
 * <li>{@code {"t":"lap","ts":..,"track":"route3","time":ms,"sectors":[ms,..],"pit":false,"race":false}} - a lap done</li>
 * </ul>
 * The backend sends the mod ({@code /ws/client}), 10 times a second:
 * <ul>
 * <li>{@code {"t":"others","players":[{"uuid","name","x","y","z","yaw","v","track","boat","ts"}]}} - to those who asked</li>
 * <li>{@code {"t":"jam","level":0..1}} - a kill-a-car jams our radio (1 within 40 blocks of it, 0 beyond 160); sent when
 * it changes and every second while above 0</li>
 * <li>{@code {"t":"jamming","uuid","name","level"}} - to a jamming kill-a-car: how hard it jams</li>
 * <li>{@code {"t":"cars","killACar":[names]}} - on connect: the kill-a-cars (every mod draws their red safety car)</li>
 * </ul>
 * The website listens on {@code /ws/live}: {@code {"t":"live","players":[{"name","uuid","x","z","yaw","v","track"}]}}
 * 10 times a second.
 */
public final class Protocol {
	/** Bumped when a message changes in a way the other side must know about. */
	public static final int VERSION = 3;
	/** Position updates a second (mod to backend, backend to the website). */
	public static final int RATE_HZ = 10;
	/** Default backend address (this computer, until the VPS). */
	public static final String DEFAULT_URL = "https://pplracing.duckdns.org";

	private Protocol() {
	}
}
