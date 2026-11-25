package com.mr.motioncam;

/** Reads the pixel size of a JPEG from its SOF marker without decoding it. */
final class JpegInfo {
    private JpegInfo() { }

    /** @return {width, height}, or null if the data is not a readable JPEG. */
    static int[] dimensions(byte[] j) {
        if (j == null || j.length < 4 || (j[0] & 0xFF) != 0xFF || (j[1] & 0xFF) != 0xD8) return null;
        int p = 2;
        while (p + 8 < j.length) {
            if ((j[p] & 0xFF) != 0xFF) { p++; continue; }
            int marker = j[p + 1] & 0xFF;
            if (marker == 0xFF) { p++; continue; }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { p += 2; continue; }
            int len = ((j[p + 2] & 0xFF) << 8) | (j[p + 3] & 0xFF);
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int h = ((j[p + 5] & 0xFF) << 8) | (j[p + 6] & 0xFF);
                int w = ((j[p + 7] & 0xFF) << 8) | (j[p + 8] & 0xFF);
                return (w > 0 && h > 0) ? new int[]{w, h} : null;
            }
            p += 2 + len;
        }
        return null;
    }
}
