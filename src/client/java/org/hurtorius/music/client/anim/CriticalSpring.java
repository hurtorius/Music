package org.hurtorius.music.client.anim;

/**
 * A critically damped spring: the value follows a target as fast as possible without overshooting.
 * The step is the exact solution for a target held constant over the step, so it is stable and gives
 * the same motion at any frame rate.
 */
public final class CriticalSpring {
    private float value;
    private float velocity;

    public CriticalSpring(float value) {
        this.value = value;
    }

    /** Jumps to {@code value} at rest. */
    public void reset(float value) {
        this.value = value;
        this.velocity = 0f;
    }

    /**
     * Moves toward {@code target} for {@code seconds}. {@code omega} (radians per second) sets the
     * speed: the remaining distance falls to about 5% after {@code 4.7 / omega} seconds.
     */
    public float update(float target, float omega, float seconds) {
        if (seconds <= 0f) return value;
        float offset = value - target;
        float decay = (float) Math.exp(-omega * seconds);
        float drive = velocity + omega * offset;
        value = target + (offset + drive * seconds) * decay;
        velocity = (velocity - omega * drive * seconds) * decay;
        return value;
    }

    public float value() {
        return value;
    }

    public float velocity() {
        return velocity;
    }
}
