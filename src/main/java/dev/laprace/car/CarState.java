package dev.laprace.car;

import net.minecraft.resources.Identifier;

/** What the car renderer needs of one boat for one frame (filled when the boat's render state is extracted). */
public final class CarState {
	public boolean draw;
	/** The ghost (one's best lap): drawn see-through, nobody in it. */
	public boolean ghost;
	/** The car's origin above the boat's (blocks): the rider's feet + 7 px, as in the avatar. */
	public float lift;
	/** The rider's skin; null: nobody (or no player) at the wheel - no driver drawn. */
	public Identifier skin;
	/** The driver's head is not drawn (the camera is in it). */
	public boolean noHead;
	public float steer, wheel, fire = 9, headYaw, headPitch;
	/** Seconds into the Swimming pose (0 on land / ice, up to 0.5 afloat). */
	public float swim;
	/** The display on the steering wheel: speed (m/s, as the avatar shows it), gear, revs, fuel 0..1. */
	public int speed, gear = 1;
	public float rpm, fuel = 1;
	public boolean fuelFlash;
	public Identifier car, glow;
	/** The model: F1Model.F1 or F1Model.SAFETY (the kill-a-car's). */
	public String model = "f1";

	/** Implemented by BoatRenderState (mixin). */
	public interface Holder {
		CarState laprace$car();
	}

	/** Implemented by AvatarRenderState (mixin): the player sits in a car, the car draws them. */
	public interface Driver {
		boolean laprace$inCar();

		void laprace$setInCar(boolean v);
	}
}
