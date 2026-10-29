package io.github.ekkzy.evebox.ui;

/**
 * One position/velocity channel, advanced with the closed-form solution of
 * x'' + 2*zeta*omega*x' + omega^2*x = 0 around its latest target.
 * Retargeting changes only the equilibrium. There is no numerical substep loop.
 */
final class UiSpring {
    float value, velocity, target;
    private double frequency, ratio;
    private float distanceLimit = .001f, speedLimit = .008f;

    UiSpring(float response, float damping, float initial) {
        parameters(response, damping);
        snap(initial);
    }

    void parameters(float response, float damping) {
        if (!Float.isFinite(response) || response <= 0 || !Float.isFinite(damping) || damping <= 0)
            throw new IllegalArgumentException("Finite positive motion parameters required");
        frequency = 2.0 * Math.PI / response;
        ratio = damping;
    }

    void snap(float next) {
        if (!Float.isFinite(next)) throw new IllegalArgumentException("Finite position required");
        target = next;
        value = next;
        velocity = 0;
    }

    /** Scroll tracks express the same stopping budget in their own pixel units. */
    void tolerance(float unitsPerProgress) {
        if (!Float.isFinite(unitsPerProgress)) throw new IllegalArgumentException("Finite motion range required");
        float units = Math.max(1, Math.abs(unitsPerProgress));
        distanceLimit = units * .001f;
        speedLimit = units * .008f;
    }

    boolean step(float elapsedSeconds) {
        if (settled()) { snap(target); return false; }
        if (!Float.isFinite(elapsedSeconds) || elapsedSeconds <= 0) return true;
        // A suspended UI resumes from its presentation instead of skipping its transition.
        double time = Math.min(elapsedSeconds, .05f);
        double offset = (double) value - target;
        double speed = velocity;
        double positionAfter, speedAfter;
        if (Math.abs(ratio - 1) < .00001) {
            double linear = speed + frequency * offset;
            double attenuation = Math.exp(-frequency * time);
            positionAfter = (offset + linear * time) * attenuation;
            speedAfter = (speed - frequency * linear * time) * attenuation;
        } else if (ratio < 1) {
            double decay = ratio * frequency;
            double angular = frequency * Math.sqrt(1 - ratio * ratio);
            double sinePart = (speed + decay * offset) / angular;
            double sine = Math.sin(angular * time), cosine = Math.cos(angular * time);
            double attenuation = Math.exp(-decay * time);
            positionAfter = attenuation * (offset * cosine + sinePart * sine);
            speedAfter = attenuation * ((sinePart * angular - decay * offset) * cosine
                    - (offset * angular + decay * sinePart) * sine);
        } else {
            double roots = ratio + Math.sqrt(ratio * ratio - 1);
            double slow = -frequency / roots, fast = -frequency * roots;
            double slowWeight = (speed - fast * offset) / (slow - fast);
            double fastWeight = offset - slowWeight;
            double slowPart = slowWeight * Math.exp(slow * time);
            double fastPart = fastWeight * Math.exp(fast * time);
            positionAfter = slowPart + fastPart;
            speedAfter = slow * slowPart + fast * fastPart;
        }
        value = (float) (target + positionAfter);
        velocity = (float) speedAfter;
        if (settled()) { snap(target); return false; }
        return true;
    }

    boolean settled() {
        return Math.abs((double) value - target) <= distanceLimit && Math.abs(velocity) <= speedLimit;
    }
}
