package dev.laprace.car;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.laprace.LapRaceClient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The F1 car of the Figura avatar "F1" by CoBeHok, baked by tools/bake_f1.py into assets/laprace/models/f1.json:
 * quads by group in the model's rest space (Blockbench pixels, Y up, the nose to -Z), each group's pivot and
 * rotation, and the animations Steering (the driver's pose, steering wheel and front wheels; time 0 full left,
 * 1 straight, 2 full right), Gas (wheels: 360 degrees a second) and ExFire (the exhaust flame).
 * Animations are applied the Blockbench way: a group's animated angles are added to its own (X and Y negated),
 * positions with X negated.
 */
public final class F1Model {
	/** Alpha of the vertices drawn now (the ghost: see-through); 0xFF otherwise. */
	public static int alpha = 0xFF;

	public static final int SKIN = 0, CAR = 1;
	/**
	 * The models: the F1 car, and the kill-a-car's own - the Safety Car of CoBeHok's avatar of that name, baked by the
	 * same tool (tools/bake_f1.py safetycar), recoloured red; its outside and interior textures side by side in one.
	 */
	public static final String F1 = "f1", SAFETY = "safetycar";

	public static final class Group {
		final String name;
		final int parent;
		final float px, py, pz;
		final float rx, ry, rz;
		/** Quads: per quad 1 texture kind, 3 normal, 4 x (x y z u v) = 24 floats. */
		final float[] quads;

		Group(String name, int parent, float[] pivot, float[] rot, float[] quads) {
			this.name = name;
			this.parent = parent;
			px = pivot[0];
			py = pivot[1];
			pz = pivot[2];
			rx = rot[0];
			ry = rot[1];
			rz = rot[2];
			this.quads = quads;
		}
	}

	/** One boat's posed model: the groups' transforms and the display's look. */
	public static final class Pose {
		final Matrix4f[] ms;
		/** Texture shift (0..1 of the texture), colour (ARGB), length along the part (fuel bar). */
		final float[] du, dv, len;
		final int[] tint;

		Pose(int n) {
			ms = new Matrix4f[n];
			du = new float[n];
			dv = new float[n];
			len = new float[n];
			tint = new int[n];
			java.util.Arrays.fill(len, 1);
			java.util.Arrays.fill(tint, -1);
		}
	}

	int index(String name) {
		for (int i = 0; i < groups.size(); i++) {
			if (groups.get(i).name.equals(name)) {
				return i;
			}
		}
		return -1;
	}

	private static final Map<String, F1Model> MODELS = new HashMap<>();
	private static final java.util.Set<String> FAILED = new java.util.HashSet<>();
	/** The display parts' indexes (CarRenderer), looked up once. */
	int[] parts;

	final List<Group> groups = new ArrayList<>();
	final Map<String, Group> byName = new HashMap<>();
	/** animation -> group -> channel ("rotation" / "position") -> keyframes [time, x, y, z, smooth]. */
	final Map<String, Map<String, Map<String, List<float[]>>>> anims = new HashMap<>();

	public static F1Model get() {
		return get(F1);
	}

	public static F1Model get(String name) {
		F1Model m = MODELS.get(name);
		if (m == null && !FAILED.contains(name)) {
			try {
				MODELS.put(name, m = load(name));
			} catch (Exception e) {
				FAILED.add(name);
				LapRaceClient.LOG.error("Car model {} could not be loaded", name, e);
			}
		}
		return m;
	}

	private static float[] floats(JsonArray a) {
		float[] f = new float[a.size()];
		for (int i = 0; i < f.length; i++) {
			f[i] = a.get(i).getAsFloat();
		}
		return f;
	}

	private static F1Model load(String name) throws Exception {
		var res = Minecraft.getInstance().getResourceManager()
				.getResource(Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, "models/" + name + ".json")).orElseThrow();
		JsonObject root;
		try (var in = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
			root = JsonParser.parseReader(in).getAsJsonObject();
		}
		F1Model m = new F1Model();
		for (JsonElement ge : root.getAsJsonArray("groups")) {
			JsonObject g = ge.getAsJsonObject();
			JsonArray qs = g.getAsJsonArray("quads");
			float[] q = new float[qs.size() * 24];
			int o = 0;
			for (JsonElement qe : qs) {
				JsonObject quad = qe.getAsJsonObject();
				q[o++] = quad.get("tex").getAsString().equals("skin") ? SKIN : CAR;
				for (float f : floats(quad.getAsJsonArray("n"))) {
					q[o++] = f;
				}
				JsonArray vs = quad.getAsJsonArray("v");
				for (int i = 0; i < 4; i++) {
					for (float f : floats(vs.get(i).getAsJsonArray())) {
						q[o++] = f;
					}
				}
			}
			Group gr = new Group(g.get("name").getAsString(), g.get("parent").getAsInt(), floats(g.getAsJsonArray("pivot")),
					floats(g.getAsJsonArray("rot")), q);
			m.groups.add(gr);
			m.byName.putIfAbsent(gr.name, gr);
		}
		for (var ae : root.getAsJsonObject("animations").entrySet()) {
			Map<String, Map<String, List<float[]>>> bones = new HashMap<>();
			for (var be : ae.getValue().getAsJsonObject().getAsJsonObject("bones").entrySet()) {
				Map<String, List<float[]>> ch = new HashMap<>();
				for (var ce : be.getValue().getAsJsonObject().entrySet()) {
					List<float[]> keys = new ArrayList<>();
					for (JsonElement k : ce.getValue().getAsJsonArray()) {
						keys.add(floats(k.getAsJsonArray()));
					}
					ch.put(ce.getKey(), keys);
				}
				bones.put(be.getKey(), ch);
			}
			m.anims.put(ae.getKey(), bones);
		}
		return m;
	}

	/** One channel of one animation at a time (linear between keyframes, held at the ends). */
	private static void sample(List<float[]> keys, float t, float[] out) {
		if (keys == null || keys.isEmpty()) {
			return;
		}
		float[] a = keys.getFirst(), b = keys.getLast();
		if (t <= a[0]) {
			out[0] += a[1];
			out[1] += a[2];
			out[2] += a[3];
			return;
		}
		if (t >= b[0]) {
			out[0] += b[1];
			out[1] += b[2];
			out[2] += b[3];
			return;
		}
		for (int i = 0; i + 1 < keys.size(); i++) {
			float[] k0 = keys.get(i), k1 = keys.get(i + 1);
			if (t <= k1[0]) {
				float f = k1[0] > k0[0] ? (t - k0[0]) / (k1[0] - k0[0]) : 1;
				if (k0[4] == 0) {
					f = 0;
				}
				out[0] += k0[1] + (k1[1] - k0[1]) * f;
				out[1] += k0[2] + (k1[2] - k0[2]) * f;
				out[2] += k0[3] + (k1[3] - k0[3]) * f;
				return;
			}
		}
	}

	private void addAnim(String anim, Group g, float t, float[] rot, float[] pos) {
		var bones = anims.get(anim);
		if (bones == null) {
			return;
		}
		var ch = bones.get(g.name);
		if (ch == null) {
			return;
		}
		sample(ch.get("rotation"), t, rot);
		sample(ch.get("position"), t, pos);
	}

	/** Blockbench euler (ZYX), degrees. */
	private static Matrix4f euler(Matrix4f m, float x, float y, float z) {
		return m.rotateZ((float) Math.toRadians(z)).rotateY((float) Math.toRadians(y)).rotateX((float) Math.toRadians(x));
	}

	/**
	 * Every group's transform (rest space to rest space) for this frame. steer -1 (left) .. 1 (right), wheel: the
	 * wheels' turn in degrees, fire: seconds since the exhaust fired (big = none), head yaw / pitch relative to the car.
	 */
	Pose pose(float steer, float wheel, float fire, float swim, float headYaw, float headPitch, Pose look) {
		float[] rot = new float[3], pos = new float[3];
		Matrix4f[] ms = look.ms;
		for (int gi = 0; gi < ms.length; gi++) {
			Group g = groups.get(gi);
			rot[0] = rot[1] = rot[2] = 0;
			pos[0] = pos[1] = pos[2] = 0;
			addAnim("Steering", g, 1 + Math.clamp(steer, -1, 1), rot, pos);
			if (g.name.startsWith("Wheel")) {
				rot[0] += wheel;
			}
			if (fire < 0.17f) {
				addAnim("ExFire", g, fire, rot, pos);
			}
			if (swim > 0) {
				addAnim("Swimming", g, swim, rot, pos);
			}
			Matrix4f m = ms[gi] = g.parent >= 0 ? new Matrix4f(ms[g.parent]) : new Matrix4f();
			m.translate(-pos[0], pos[1], pos[2]).translate(g.px, g.py, g.pz);
			// the group's angles plus the animated ones, against its own (already baked into the vertices)
			euler(m, g.rx - rot[0], g.ry - rot[1], g.rz + rot[2]);
			if (g.name.equals("Head")) {
				m.rotateY((float) Math.toRadians(-headYaw)).rotateX((float) Math.toRadians(-headPitch));
			}
			if (look.len[gi] != 1) {
				m.scale(1, look.len[gi], 1);
			}
			Matrix4f undo = euler(new Matrix4f(), g.rx, g.ry, g.rz).invert();
			m.mul(undo).translate(-g.px, -g.py, -g.pz);
		}
		return look;
	}

	/** Groups not drawn: these and everything under them. */
	boolean[] hidden(String... names) {
		boolean[] h = new boolean[groups.size()];
		for (int i = 0; i < h.length; i++) {
			Group g = groups.get(i);
			h[i] = (g.parent >= 0 && h[g.parent]) || java.util.Arrays.asList(names).contains(g.name);
		}
		return h;
	}

	/** The quads of one texture kind, posed, into the buffer; pose: the car's place in the world (pixels). */
	void draw(Matrix4f pose, Matrix3f normalMat, VertexConsumer vc, int kind, int light, int overlay, Pose look, boolean[] hidden) {
		Matrix4f[] ms = look.ms;
		Matrix4f full = new Matrix4f();
		Vector3f p = new Vector3f(), n = new Vector3f();
		for (int gi = 0; gi < ms.length; gi++) {
			Group g = groups.get(gi);
			if (g.quads.length == 0 || hidden[gi]) {
				continue;
			}
			pose.mul(ms[gi], full);
			Matrix3f nm = new Matrix3f(normalMat).mul(new Matrix3f(ms[gi]));
			float[] q = g.quads;
			for (int o = 0; o < q.length; o += 24) {
				if ((int) q[o] != kind) {
					continue;
				}
				nm.transform(n.set(q[o + 1], q[o + 2], q[o + 3])).normalize();
				for (int i = 0; i < 4; i++) {
					int v = o + 4 + i * 5;
					full.transformPosition(p.set(q[v], q[v + 1], q[v + 2]));
					vc.addVertex(p.x, p.y, p.z, alpha == 0xFF ? look.tint[gi] : (look.tint[gi] & 0xFFFFFF) | alpha << 24, q[v + 3] + look.du[gi], q[v + 4] + look.dv[gi], overlay, light, n.x, n.y, n.z);
				}
			}
		}
	}
}
