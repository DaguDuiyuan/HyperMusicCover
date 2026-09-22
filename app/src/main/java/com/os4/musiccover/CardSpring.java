package com.os4.musiccover;

/** Frame-rate-independent, lightly underdamped scale response for the square cover. */
final class CardSpring {
    static final float PLAYING = 1f;
    static final float PAUSED = 0.90f;
    private static final double FREQUENCY = 18.0;
    private static final double DAMPING = 0.56;
    private static final double DAMPED = FREQUENCY * Math.sqrt(1.0 - DAMPING * DAMPING);

    float value = PLAYING;
    float velocity;

    void step(float target, float dt) {
        if (dt <= 0f) return;
        double x = value - target;
        double v = velocity;
        double phase = DAMPED * dt;
        double decay = Math.exp(-DAMPING * FREQUENCY * dt);
        double a = x;
        double b = (v + DAMPING * FREQUENCY * x) / DAMPED;
        double cos = Math.cos(phase), sin = Math.sin(phase);
        double wave = a * cos + b * sin;
        value = (float) (target + decay * wave);
        velocity = (float) (decay * (-DAMPING * FREQUENCY * wave
                + DAMPED * (-a * sin + b * cos)));
        if (atRest(target)) snap(target);
    }

    boolean atRest(float target) {
        return Math.abs(value - target) < 0.0005f && Math.abs(velocity) < 0.005f;
    }

    void snap(float target) {
        value = target;
        velocity = 0f;
    }
}
