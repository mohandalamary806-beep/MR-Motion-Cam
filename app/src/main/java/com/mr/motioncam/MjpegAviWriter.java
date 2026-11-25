package com.mr.motioncam;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

/**
 * Writes JPEG frames into an AVI file (codec MJPG) without re-encoding: no encoder is needed on the phone.
 * The header is written first and patched in close() once the frame count and the real average frame rate
 * are known. Positional writes only, so any seekable FileChannel works (including a MediaStore descriptor).
 * No Android classes, so it can be tested on a normal JVM.
 */
final class MjpegAviWriter {
    // fixed layout of the header (offsets in bytes) - see the AVI / RIFF specification
    private static final int OFF_RIFF_SIZE = 4;
    private static final int OFF_AVIH = 32;          // start of the avih data
    private static final int OFF_STRH = 108;         // start of the strh data
    private static final int OFF_STRF = 172;         // start of the strf data
    private static final int OFF_MOVI_LIST_SIZE = 216;
    private static final int OFF_MOVI = 220;         // position of the 'movi' four-cc
    private static final int FIRST_CHUNK = 224;

    private final FileChannel ch;
    private final int width;
    private final int height;
    private final ArrayList<int[]> index = new ArrayList<>();
    private long pos = FIRST_CHUNK;
    private long firstNs = -1;
    private long lastNs = -1;
    private int maxFrame;
    private boolean closed;

    MjpegAviWriter(FileChannel channel, int width, int height) throws IOException {
        this.ch = channel;
        this.width = width;
        this.height = height;
        writeAt(0, header(15.0, 0));
    }

    int frameCount() { return index.size(); }
    long size() { return pos; }

    void addFrame(byte[] jpeg, long nowNanos) throws IOException {
        if (closed) return;
        if (firstNs < 0) firstNs = nowNanos;
        lastNs = nowNanos;
        int pad = jpeg.length & 1;
        ByteBuffer b = ByteBuffer.allocate(8 + jpeg.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'0', '0', 'd', 'c'}).putInt(jpeg.length).put(jpeg);
        if (pad == 1) b.put((byte) 0);
        ((Buffer) b).flip();
        index.add(new int[]{(int) (pos - OFF_MOVI), jpeg.length});
        writeAt(pos, b);
        pos += 8 + jpeg.length + pad;
        if (jpeg.length > maxFrame) maxFrame = jpeg.length;
    }

    /** Writes the index and patches the header. Does not close the channel. */
    void close() throws IOException {
        if (closed) return;
        closed = true;
        int n = index.size();
        ByteBuffer idx = ByteBuffer.allocate(8 + 16 * n).order(ByteOrder.LITTLE_ENDIAN);
        idx.put(new byte[]{'i', 'd', 'x', '1'}).putInt(16 * n);
        for (int[] e : index) {
            idx.put(new byte[]{'0', '0', 'd', 'c'}).putInt(0x10).putInt(e[0]).putInt(e[1]);
        }
        ((Buffer) idx).flip();
        writeAt(pos, idx);
        long end = pos + 8 + 16L * n;

        double fps = 15.0;
        if (n >= 2 && lastNs > firstNs) fps = (n - 1) * 1e9 / (lastNs - firstNs);
        fps = Math.max(1.0, Math.min(120.0, fps));
        writeAt(0, header(fps, n));
        writeAt(OFF_RIFF_SIZE, intBuffer((int) (end - 8)));
        writeAt(OFF_MOVI_LIST_SIZE, intBuffer((int) (pos - OFF_MOVI)));
        ch.force(false);
    }

    private void writeAt(long position, ByteBuffer b) throws IOException {
        long p = position;
        while (b.hasRemaining()) p += ch.write(b, p);
    }

    private ByteBuffer header(double fps, int frames) {
        ByteBuffer b = ByteBuffer.allocate(FIRST_CHUNK).order(ByteOrder.LITTLE_ENDIAN);
        int microPerFrame = (int) Math.round(1_000_000.0 / fps);
        b.put(fourcc("RIFF")).putInt(0).put(fourcc("AVI "));
        b.put(fourcc("LIST")).putInt(192).put(fourcc("hdrl"));
        b.put(fourcc("avih")).putInt(56);
        b.putInt(microPerFrame).putInt((int) (maxFrame * fps)).putInt(0).putInt(0x10)
         .putInt(frames).putInt(0).putInt(1).putInt(Math.max(maxFrame, 1 << 16))
         .putInt(width).putInt(height).putInt(0).putInt(0).putInt(0).putInt(0);
        b.put(fourcc("LIST")).putInt(116).put(fourcc("strl"));
        b.put(fourcc("strh")).putInt(56);
        b.put(fourcc("vids")).put(fourcc("MJPG")).putInt(0).putShort((short) 0).putShort((short) 0)
         .putInt(0).putInt(1000).putInt((int) Math.round(fps * 1000)).putInt(0).putInt(frames)
         .putInt(Math.max(maxFrame, 1 << 16)).putInt(-1).putInt(0)
         .putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);
        b.put(fourcc("strf")).putInt(40);
        b.putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) 24)
         .put(fourcc("MJPG")).putInt(width * height * 3).putInt(0).putInt(0).putInt(0).putInt(0);
        b.put(fourcc("LIST")).putInt(0).put(fourcc("movi"));
        ((Buffer) b).flip();
        return b;
    }

    private static ByteBuffer intBuffer(int v) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(v);
        ((Buffer) b).flip();
        return b;
    }

    private static byte[] fourcc(String s) {
        return new byte[]{(byte) s.charAt(0), (byte) s.charAt(1), (byte) s.charAt(2), (byte) s.charAt(3)};
    }
}
