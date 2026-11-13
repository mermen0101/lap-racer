package dev.laprace.car;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.laprace.Engine;
import dev.laprace.LapConfig;
import dev.laprace.LapRaceClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/** Draws every boat as the F1 car (F1Model), its rider as the driver in their own skin. */
public final class CarRenderer {
	private CarRenderer() {
	}

	/** The wheels, steering and exhaust of one boat, carried from frame to frame. */
	private static final class Track {
		float last = Float.NaN, wheel, steer, fireAt = -100, swim;
		int gear;
	}

	private static final Map<Integer, Track> TRACKS = new HashMap<>();
	/** Rider's feet above the boat (blocks), last seen: for an empty boat. */
	private static float riderLift = 0.15f;
	private static boolean loggedLift;
	private static final float WHEEL_RADIUS = 4.5f / 16;

	static Identifier tex(String name) {
		return Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, "textures/entity/f1/" + file(name) + ".png");
	}

	/** The picture's file name for a livery id: the ids the server knows ("redbull", "reno") stay, the files are named by colour. */
	public static String file(String name) {
		if (name.startsWith("redbull")) return "blue" + name.substring(7);
		if (name.startsWith("reno")) return "yellow" + name.substring(4);
		return name;
	}

	/** When the boat's render state is filled (every frame). */
	public static void extract(AbstractBoat boat, BoatRenderState state, float partial) {
		CarState c = ((CarState.Holder) state).laprace$car();
		Minecraft mc = Minecraft.getInstance();
		Entity rider = boat.getFirstPassenger();
		// the kill-a-car has a car of its own: the red safety car (everyone sees it so)
		boolean safety = rider instanceof net.minecraft.world.entity.player.Player pl
				&& (pl == mc.player ? dev.laprace.net.Backend.killACar() : dev.laprace.net.Backend.killACar(pl.getGameProfile().name()));
		c.model = safety && F1Model.get(F1Model.SAFETY) != null ? F1Model.SAFETY : F1Model.F1;
		c.ghost = dev.laprace.Ghost.isGhost(boat);
		c.draw = LapConfig.INSTANCE.carModel && F1Model.get(c.model) != null;
		if (!c.draw) {
			return;
		}
		if (c.model.equals(F1Model.SAFETY)) {
			c.car = Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, "textures/entity/safetycar/red.png");
			c.glow = Identifier.fromNamespaceAndPath(LapRaceClient.MOD_ID, "textures/entity/safetycar/red_e.png");
		} else {
			// each car in its own driver's livery (as the server tells; ours as we chose)
			String who = rider instanceof net.minecraft.world.entity.player.Player pl ? pl.getGameProfile().name() : null;
			Identifier[] lv = Liveries.of(who, rider == mc.player || rider == null);
			c.car = lv[0];
			c.glow = lv[1];
		}
		float boatYaw = boat.getYRot(partial);
		if (rider != null) {
			riderLift = (float) (Mth.lerp(partial, rider.yo, rider.getY()) - Mth.lerp(partial, boat.yo, boat.getY()));
			if (!loggedLift && System.getenv("LAPRACE_DEMO") != null) {
				loggedLift = true;
				LapRaceClient.LOG.info("[car] rider sits {} above the boat", riderLift);
			}
		}
		c.lift = riderLift + 7f / 16;
		c.skin = rider instanceof AbstractClientPlayer p ? p.getSkin().body().texturePath() : null;
		c.noHead = rider != null && rider == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
		if (rider instanceof net.minecraft.world.entity.LivingEntity le) {
			c.headYaw = Math.clamp(Mth.wrapDegrees(Mth.rotLerp(partial, le.yHeadRotO, le.yHeadRot) - boatYaw), -80, 80);
			c.headPitch = Math.clamp(rider.getXRot(partial), -60, 60);
		} else {
			c.headYaw = c.headPitch = 0;
		}

		Track t = TRACKS.computeIfAbsent(boat.getId(), k -> new Track());
		float now = boat.tickCount + partial;
		float dt = Float.isNaN(t.last) ? 0 : Math.clamp(now - t.last, 0, 5);
		t.last = now;
		double yr = Math.toRadians(boatYaw);
		var v = boat.getDeltaMovement();
		double forward = -Math.sin(yr) * v.x + Math.cos(yr) * v.z; // blocks a tick
		t.wheel = (float) ((t.wheel + Math.toDegrees(forward * dt / WHEEL_RADIUS)) % 360);
		// steering from how fast the boat turns (yaw rises turning right)
		float rate = Mth.wrapDegrees(boat.getYRot() - boat.yRotO);
		float target = Math.clamp(rate / 4f, -1, 1);
		t.steer += (target - t.steer) * Math.min(1, 0.35f * dt);
		if (rider == mc.player) {
			if (Engine.gear != t.gear) {
				t.fireAt = now;
			}
			t.gear = Engine.gear;
		}
		// afloat (not on ice or land): the wheels fold into the swimming pose, over half a second
		t.swim = Math.clamp(t.swim + (boat.isInWater() ? dt : -dt) / 20f, 0, 0.5f);
		c.wheel = t.wheel;
		c.steer = t.steer;
		c.fire = (now - t.fireAt) / 20f;
		c.swim = t.swim;

		// the steering wheel's display (render.lua): speed in m/s (two digits), gear 1..8, revs, fuel
		double mps = v.horizontalDistance() * 20;
		c.speed = (int) Math.min(99, Math.floor(mps));
		if (rider == mc.player && Engine.inBoat) {
			c.gear = Engine.gear;
			c.rpm = (float) Engine.rpm;
			c.fuel = Engine.fuelOn() ? (float) Math.clamp(Engine.fuel / Engine.MAX_FUEL, 0, 1) : 1;
		} else {
			// someone else's car: the same gearbox, from the speed alone
			c.gear = Engine.gearFor(mps);
			c.rpm = (float) Engine.rpmFor(mps, c.gear);
			c.fuel = 1;
		}
		c.fuelFlash = c.fuel <= 0.2f && (mc.level.getGameTime() % 20) >= 10;
		if (TRACKS.size() > 256) {
			TRACKS.clear();
		}
	}

	private static final String SEC1 = "SteeringWheelUISec1", SEC2 = "SteeringWheelUISec2", SEC3 = "SteeringWheelUISec3";

	/** The display parts' texture shifts, as render.lua sets them (SPEED_UV, GEAR_UV, RPM_UV, fuel bar, lights). */
	private static F1Model.Pose display(F1Model model, CarState c) {
		if (model.parts == null) {
			model.parts = new int[] {model.index("SteeringWheelUITens"), model.index("SteeringWheelUIUnits"), model.index("SteeringWheelUIGear"),
					model.index("SteeringWheelUIRPM"), model.index("SteeringWheelUIFuel"), model.index("SteeringWheelUISec1"),
					model.index("SteeringWheelUISec2"), model.index("SteeringWheelUISec3"),
					model.index("UITens"), model.index("UIUnits"), model.index("UIGear"), model.index("UIRPM")};
		}
		int[] parts = model.parts;
		F1Model.Pose p = new F1Model.Pose(model.groups.size());
		if (parts[8] >= 0) {
			// the safety car's (its state.lua; the interior texture is the right half of the atlas: u halved)
			int st = parts[8], su = parts[9], sg = parts[10], sr = parts[11];
			p.dv[st] = (79 + 5 * (c.speed / 10)) / 128f;
			p.dv[su] = (79 + 5 * (c.speed % 10)) / 128f;
			int gi = Math.clamp(c.gear, 1, 8) - 1;
			p.du[sg] = (3 + 3 * (gi % 4)) / 256f;
			p.dv[sg] = (122 + 3 * (gi / 4)) / 128f;
			p.du[sr] = 3 / 256f;
			p.dv[sr] = (111 + Math.clamp((int) (c.rpm / (Engine.MAX_RPM / 10f)), 0, 10)) / 128f;
			return p;
		}
		int tens = parts[0], units = parts[1], gear = parts[2], rpm = parts[3], fuel = parts[4];
		if (tens >= 0) {
			p.du[tens] = 125 / 128f;
			p.dv[tens] = (40 + 5 * (c.speed / 10)) / 128f;
		}
		if (units >= 0) {
			p.du[units] = 125 / 128f;
			p.dv[units] = (40 + 5 * (c.speed % 10)) / 128f;
		}
		if (gear >= 0) {
			p.du[gear] = 113 / 128f;
			p.dv[gear] = (50 + Math.clamp(c.gear, 1, 8)) / 128f;
		}
		if (rpm >= 0) {
			int i = Math.clamp((int) (c.rpm / (Engine.MAX_RPM / 10f)), 0, 10);
			p.du[rpm] = 113 / 128f;
			p.dv[rpm] = (40 + i) / 128f;
		}
		if (fuel >= 0) {
			p.len[fuel] = Math.max(0.001f, c.fuel);
			p.tint[fuel] = c.fuelFlash ? 0xFF000000 : -1;
		}
		return p;
	}

	/** Camera lowered in the car (blocks), as the avatar's camera height setting; 0: not in a car. */
	public static double cameraDrop(Entity e) {
		return LapConfig.INSTANCE.carModel && e != null && e.getVehicle() instanceof AbstractBoat ? LapConfig.INSTANCE.carCamera / 100.0 : 0;
	}

	/** Instead of the boat; false: draw the boat. */
	public static boolean submit(BoatRenderState state, PoseStack ps, SubmitNodeCollector out) {
		CarState c = ((CarState.Holder) state).laprace$car();
		F1Model model = F1Model.get(c.model);
		if (!c.draw || model == null) {
			return false;
		}
		try {
			F1Model.Pose ms = model.pose(c.steer, c.wheel, c.fire, c.swim, c.headYaw, c.headPitch, display(model, c));
			// the sector lights on the steering wheel are not used (the sectors go on the minimap)
			boolean[] hidden = c.skin == null ? model.hidden("DriverFP", "Driver", SEC1, SEC2, SEC3)
					: c.noHead ? model.hidden("Head", SEC1, SEC2, SEC3) : model.hidden(SEC1, SEC2, SEC3);
			int light = state.lightCoords;
			ps.pushPose();
			ps.translate(0, c.lift, 0);
			ps.rotateDegrees(Axis.YP, 180 - state.yRot);
			ps.scale(1 / 16f, 1 / 16f, 1 / 16f);
			if (c.ghost) {
				// the ghost: the car alone, see-through
				out.submitCustomGeometry(ps, RenderTypes.entityTranslucent(c.car), (pose, vc) -> {
					F1Model.alpha = Math.round(dev.laprace.LapConfig.INSTANCE.ghostOpacity * 2.55f);
					try {
						model.draw(pose.pose(), pose.normal(), vc, F1Model.CAR, light, OverlayTexture.NO_OVERLAY, ms, hidden);
					} finally {
						F1Model.alpha = 0xFF;
					}
				});
				ps.popPose();
				return true;
			}
			out.submitCustomGeometry(ps, RenderTypes.entityCutout(c.car), (pose, vc) ->
					model.draw(pose.pose(), pose.normal(), vc, F1Model.CAR, light, OverlayTexture.NO_OVERLAY, ms, hidden));
			out.submitCustomGeometry(ps, RenderTypes.eyes(c.glow), (pose, vc) ->
					model.draw(pose.pose(), pose.normal(), vc, F1Model.CAR, 0xF000F0, OverlayTexture.NO_OVERLAY, ms, hidden));
			if (c.skin != null) {
				Identifier skin = c.skin;
				out.submitCustomGeometry(ps, RenderTypes.entityCutout(skin), (pose, vc) ->
						model.draw(pose.pose(), pose.normal(), vc, F1Model.SKIN, light, OverlayTexture.NO_OVERLAY, ms, hidden));
			}
			ps.popPose();
			return true;
		} catch (RuntimeException e) {
			LapRaceClient.LOG.error("F1 car could not be drawn, back to boats", e);
			LapConfig.INSTANCE.carModel = false;
			return false;
		}
	}
}
