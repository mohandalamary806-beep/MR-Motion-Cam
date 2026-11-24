package com.mr.motioncam;

/**
 * Turns raw rotation-vector samples into filtered head-tracker angles. No Android classes, so it can be
 * tested off-device. process() must be called from one thread only (the sensor thread); the settings are
 * volatile and can be changed from the UI thread.
 */
final class PosePipeline {
    private static final double MIN_DT = 0.001;
    private static final double MAX_DT = 0.050;
    private static final double DEFAULT_DT = 0.010;
    static final double BETA = 0.15;

    private final double[] matrix = new double[9];
    private final double[] absolute = new double[3];
    private final double[] center = new double[3];
    private final OneEuroFilter yawFilter = new OneEuroFilter();
    private final OneEuroFilter pitchFilter = new OneEuroFilter();
    private final OneEuroFilter rollFilter = new OneEuroFilter();
    private boolean flat;
    private long lastTimestampNs = -1;

    private volatile boolean centerRequested = true;
    private volatile boolean flatMode;
    volatile double sensitivity = 1.0;
    volatile double minCutoffHz = cutoffForSmoothing(25);
    volatile boolean invertYaw;
    volatile boolean invertPitch;

    /** Final angles (degrees) that are sent to OpenTrack. */
    volatile double yaw;
    volatile double pitch;
    volatile double roll;

    /** Smoothing slider 0..90 -> cutoff 6 Hz (very light) .. 0.4 Hz (very smooth). Default 25 = about 2.8 Hz. */
    static double cutoffForSmoothing(int smoothing) {
        double s = PoseMath.clamp(smoothing, 0, 90) / 90.0;
        return 6.0 * Math.pow(0.4 / 6.0, s);
    }

    /** The next sample becomes the neutral pose (and the holding posture is detected again). */
    void requestCenter() {
        centerRequested = true;
    }

    /** True while a recenter is still waiting for the next sensor sample. */
    boolean isCenterPending() {
        return centerRequested;
    }

    boolean isFlatMode() {
        return flatMode;
    }

    /** Forget filter history and the last timestamp (use when tracking starts or resumes). */
    void restart() {
        lastTimestampNs = -1;
        yawFilter.reset();
        pitchFilter.reset();
        rollFilter.reset();
        centerRequested = true;
    }

    /** @return false when the sample was unusable and nothing should be sent. */
    boolean process(float[] rotationVector, long timestampNs) {
        if (rotationVector == null || rotationVector.length < 3) return false;
        PoseMath.rotationMatrixFromVector(rotationVector, matrix);

        double dt = lastTimestampNs < 0 ? DEFAULT_DT : (timestampNs - lastTimestampNs) / 1e9;
        lastTimestampNs = timestampNs;
        dt = PoseMath.clamp(dt, MIN_DT, MAX_DT);

        if (centerRequested) {
            flat = PoseMath.isFlat(matrix);
            flatMode = flat;
            PoseMath.angles(matrix, flat, center);
            yawFilter.reset();
            pitchFilter.reset();
            rollFilter.reset();
            centerRequested = false;
        }

        PoseMath.angles(matrix, flat, absolute);
        double cutoff = minCutoffHz;
        double y = yawFilter.filterAngle(PoseMath.wrap(absolute[0] - center[0]), dt, cutoff, BETA);
        double p = pitchFilter.filterAngle(PoseMath.wrap(absolute[1] - center[1]), dt, cutoff, BETA);
        double r = rollFilter.filterAngle(PoseMath.wrap(absolute[2] - center[2]), dt, cutoff, BETA);

        double s = sensitivity;
        double outYaw = PoseMath.wrap(y) * s * (invertYaw ? -1.0 : 1.0);
        double outPitch = PoseMath.wrap(p) * s * (invertPitch ? -1.0 : 1.0);
        double outRoll = PoseMath.wrap(r) * s;
        if (Double.isNaN(outYaw + outPitch + outRoll) || Double.isInfinite(outYaw + outPitch + outRoll)) return false;
        yaw = outYaw;
        pitch = outPitch;
        roll = outRoll;
        return true;
    }
}
