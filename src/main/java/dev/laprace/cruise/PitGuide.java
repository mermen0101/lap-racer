package dev.laprace.cruise;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.laprace.LapConfig;
import dev.laprace.LapRaceClient;
import dev.laprace.PitLane;
import dev.laprace.RaceSession;
import dev.laprace.Track;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * For a driver at the keys: the box to pull into, lit up in the world - its parking place outlined on the ground
 * (a translucent fill and a bright rim, laid along the way in, nose first), a column of light above it that fades
 * upwards and breathes a little (seen from far down the lane), and its number. Shown on the way to it (within 150
 * blocks, not once past it); not while the autopilot drives.
 */
public final class PitGuide {
	private static final double LEN = 5, WID = 3, RIM = 0.18, COLUMN = 14, COL_W = 0.7;
	private static final int COLOR = 0xFFD21A; // the pit box yellow

	private PitGuide() {
	}

	public static void render(LevelRenderContext ctx) {
		try {
			draw(ctx);
		} catch (RuntimeException e) {
			LapRaceClient.LOG.warn("pit box guide: {}", e.toString());
		}
	}

	private static void draw(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (Cruise.enabled || mc.player == null || mc.level == null) {
			return;
		}
		Entity boat = RaceSession.boatOf(mc.player);
		Track t = RaceSession.INSTANCE.track;
		PitLane l = t == null ? null : PitLane.of(t);
		if (boat == null || l == null) {
			return;
		}
		PitLane.Box b = l.chosen();
		if (b == null || Math.hypot(b.x() - boat.getX(), b.z() - boat.getZ()) > 150) {
			return;
		}
		int k = PitLimiter.laneIndex();
		if (k >= 0 && k > b.at() + 3) {
			return; // past it
		}
		// the box's line: the lane's direction there
		double[] a0 = l.pts.get(Math.max(0, b.at() - 4)), a1 = l.pts.get(Math.min(l.pts.size() - 1, b.at() + 4));
		double dl = Math.max(1e-6, Math.hypot(a1[0] - a0[0], a1[2] - a0[2]));
		double dx = (a1[0] - a0[0]) / dl, dz = (a1[2] - a0[2]) / dl; // along
		double nx = -dz, nz = dx; // across
		double y = l.pts.get(b.at())[1] + 0.03;
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		PoseStack ps = ctx.poseStack();
		ps.pushPose();
		ps.translate(b.x() - cam.x, y - cam.y, b.z() - cam.z);
		float breath = (float) (0.85 + 0.15 * Math.sin((mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) * 0.15));
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.debugQuads(), (pose, vc) -> {
			Matrix4f m = pose.pose();
			double hl = LEN / 2, hw = WID / 2;
			// the fill
			quadFlat(vc, m, dx, dz, nx, nz, -hl, hl, -hw, hw, argb(0.22f * breath));
			// the rim: four strips
			int rim = argb(0.9f * breath);
			quadFlat(vc, m, dx, dz, nx, nz, -hl, hl, hw - RIM, hw, rim);
			quadFlat(vc, m, dx, dz, nx, nz, -hl, hl, -hw, -hw + RIM, rim);
			quadFlat(vc, m, dx, dz, nx, nz, hl - RIM, hl, -hw, hw, rim);
			quadFlat(vc, m, dx, dz, nx, nz, -hl, -hl + RIM, -hw, hw, rim);
			// the column of light: four faces, bright at the foot, gone at the top
			int foot = argb(0.38f * breath), top = argb(0f);
			double w = COL_W / 2;
			double[][] c = {{-w, -w}, {w, -w}, {w, w}, {-w, w}};
			for (int i = 0; i < 4; i++) {
				double[] p0 = c[i], p1 = c[(i + 1) % 4];
				column(vc, m, p0[0], p0[1], p1[0], p1[1], foot, top);
			}
		});
		// its number, floating above the spot
		ps.translate(0, 3.2, 0);
		ctx.submitNodeCollector().submitNameTag(ps, Vec3.ZERO, 0,
				Component.translatable("laprace.hud.pit_box_tag", b.number()), true, 0xF000F0, ctx.levelState().cameraRenderState);
		ps.popPose();
	}

	private static int argb(float a) {
		return (Math.round(Math.clamp(a, 0, 1) * 255) << 24) | COLOR;
	}

	/** A flat quad on the ground: from a0 to a1 along the box's line, b0 to b1 across it; both faces. */
	private static void quadFlat(VertexConsumer vc, Matrix4f m, double dx, double dz, double nx, double nz,
			double a0, double a1, double b0, double b1, int col) {
		float[][] p = {
				{(float) (dx * a0 + nx * b0), (float) (dz * a0 + nz * b0)},
				{(float) (dx * a1 + nx * b0), (float) (dz * a1 + nz * b0)},
				{(float) (dx * a1 + nx * b1), (float) (dz * a1 + nz * b1)},
				{(float) (dx * a0 + nx * b1), (float) (dz * a0 + nz * b1)}};
		for (int i = 0; i < 4; i++) {
			vc.addVertex(m, p[i][0], 0, p[i][1]).setColor(col);
		}
		for (int i = 3; i >= 0; i--) {
			vc.addVertex(m, p[i][0], 0, p[i][1]).setColor(col);
		}
	}

	/** One face of the light column, colour fading from foot to top; both sides. */
	private static void column(VertexConsumer vc, Matrix4f m, double x0, double z0, double x1, double z1, int foot, int top) {
		float h = (float) COLUMN;
		vc.addVertex(m, (float) x0, 0, (float) z0).setColor(foot);
		vc.addVertex(m, (float) x1, 0, (float) z1).setColor(foot);
		vc.addVertex(m, (float) x1, h, (float) z1).setColor(top);
		vc.addVertex(m, (float) x0, h, (float) z0).setColor(top);
		vc.addVertex(m, (float) x0, h, (float) z0).setColor(top);
		vc.addVertex(m, (float) x1, h, (float) z1).setColor(top);
		vc.addVertex(m, (float) x1, 0, (float) z1).setColor(foot);
		vc.addVertex(m, (float) x0, 0, (float) z0).setColor(foot);
	}
}
