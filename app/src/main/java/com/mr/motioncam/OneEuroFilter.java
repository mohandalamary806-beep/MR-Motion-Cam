package com.mr.motioncam;

/**
 * One Euro filter (Casiez et al., CHI 2012): strong smoothing while the head is almost still (no jitter),
 * and the smoothing relaxes automatically while it moves fast (no lag).
 */
final class OneEuroFilter {
    private static final double D_CUTOFF_HZ = 1.0;

    private double xPrev;
    private double dxPrev;
    private boolean initialized;

    void reset() {
        initialized = false;
    }

    private static double alpha(double cutoffHz, double dt) {
        double tau = 1.0 / (2.0 * Math.PI * cutoffHz);
        return 1.0 / (1.0 + tau / dt);
    }

    /**
     * @param x         new sample in degrees (may be any multiple of 360 away; it is unwrapped here)
     * @param dt        seconds since the previous sample
     * @param minCutoff cutoff in Hz when standing still (lower = smoother)
     * @param beta      how fast the cutoff rises with speed (higher = less lag on fast moves)
     */
    double filterAngle(double x, double dt, double minCutoff, double beta) {
        if (!initialized) {
            initialized = true;
            xPrev = x;
            dxPrev = 0.0;
            return x;
        }
        x = xPrev + PoseMath.wrap(x - xPrev); // continuous across the +-180 boundary
        double dx = (x - xPrev) / dt;
        dxPrev += alpha(D_CUTOFF_HZ, dt) * (dx - dxPrev);
        double cutoff = minCutoff + beta * Math.abs(dxPrev);
        xPrev += alpha(cutoff, dt) * (x - xPrev);
        return xPrev;
    }
}
