package com.mr.motioncam;

/**
 * Pure math (no Android classes) so it can be unit-tested off-device.
 *
 * The rotation matrix R is row-major and maps device coordinates to the world frame (x = East, y = North,
 * z = Up), exactly like SensorManager.getRotationMatrixFromVector(). The columns of R are the device axes
 * expressed in world coordinates: device x = (R0,R3,R6), y = (R1,R4,R7), z = (R2,R5,R8).
 */
final class PoseMath {
    private PoseMath() { }

    /** Same formula as SensorManager.getRotationMatrixFromVector(). Accepts 3 or 4+ values (x, y, z[, w]). */
    static void rotationMatrixFromVector(float[] v, double[] r) {
        double q1 = v[0], q2 = v[1], q3 = v[2];
        double q0 = v.length >= 4 ? v[3] : Math.sqrt(Math.max(0.0, 1.0 - q1 * q1 - q2 * q2 - q3 * q3));
        double sqQ1 = 2 * q1 * q1, sqQ2 = 2 * q2 * q2, sqQ3 = 2 * q3 * q3;
        double q1q2 = 2 * q1 * q2, q3q0 = 2 * q3 * q0, q1q3 = 2 * q1 * q3;
        double q2q0 = 2 * q2 * q0, q2q3 = 2 * q2 * q3, q1q0 = 2 * q1 * q0;
        r[0] = 1 - sqQ2 - sqQ3; r[1] = q1q2 - q3q0;     r[2] = q1q3 + q2q0;
        r[3] = q1q2 + q3q0;     r[4] = 1 - sqQ1 - sqQ3; r[5] = q2q3 - q1q0;
        r[6] = q1q3 - q2q0;     r[7] = q2q3 + q1q0;     r[8] = 1 - sqQ1 - sqQ2;
    }

    /** True when the screen faces (mostly) up or down, i.e. the phone lies flat like on a table. */
    static boolean isFlat(double[] r) {
        return Math.abs(r[8]) > 0.7;
    }

    /**
     * Head-tracker angles in degrees: out[0] = yaw, out[1] = pitch, out[2] = roll.
     *
     * Yaw is the heading of the "forward" direction around the world's vertical axis, so it stays stable
     * however the phone is held (no gimbal problem when the phone stands upright).
     *  - flat   : forward = top edge of the phone (+y), up = screen normal (+z). Identical to Android's
     *             SensorManager.getOrientation(): azimuth, pitch, roll.
     *  - upright: forward = through the screen away from the user (-z), up = top edge (+y). This is the
     *             "phone as a camera / on the head" posture. Same sign convention as flat mode.
     */
    static void angles(double[] r, boolean flat, double[] out) {
        double fx, fy, fz, rightZ, upZ;
        if (flat) {
            fx = r[1]; fy = r[4]; fz = r[7];
            rightZ = r[6]; upZ = r[8];
        } else {
            fx = -r[2]; fy = -r[5]; fz = -r[8];
            rightZ = r[6]; upZ = r[7];
        }
        out[0] = Math.toDegrees(Math.atan2(fx, fy));
        out[1] = -Math.toDegrees(Math.asin(clamp(fz, -1.0, 1.0)));
        out[2] = Math.toDegrees(Math.atan2(-rightZ, upZ));
    }

    static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d > 180.0) d -= 360.0;
        else if (d < -180.0) d += 360.0;
        return d;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
