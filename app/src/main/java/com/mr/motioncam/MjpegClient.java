package com.mr.motioncam;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;

/** Reads the Hub's MJPEG stream (multipart/x-mixed-replace, one Content-Length per part). No Android classes. */
final class MjpegClient {
    interface Listener {
        void onFrame(byte[] jpeg);
        void onStatus(String status);
    }

    /** Lets the app open the connection through a specific Android Network (Wi-Fi), tests use a plain one. */
    interface Connector {
        URLConnection open(URL url) throws IOException;
    }

    /** Own interface instead of java.util.function.BooleanSupplier (that one needs Android 7+, minSdk here is 23). */
    interface Running {
        boolean get();
    }

    private static final int MAX_FRAME_BYTES = 8_000_000;

    private final URL url;
    private final Connector connector;
    private final Listener listener;
    private volatile boolean running;
    private volatile HttpURLConnection current;
    private Thread thread;

    MjpegClient(URL url, Connector connector, Listener listener) {
        this.url = url;
        this.connector = connector;
        this.listener = listener;
    }

    void start() {
        running = true;
        thread = new Thread(this::run, "mr-live-view");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        HttpURLConnection c = current;
        if (c != null) c.disconnect();
        if (thread != null) thread.interrupt();
    }

    private void run() {
        while (running) {
            HttpURLConnection c = null;
            try {
                listener.onStatus("connecting");
                c = (HttpURLConnection) connector.open(url);
                current = c;
                c.setConnectTimeout(3000);
                c.setReadTimeout(5000);
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                listener.onStatus("live");
                parse(new BufferedInputStream(c.getInputStream(), 1 << 16), listener, () -> running);
            } catch (Exception e) {
                if (running) {
                    listener.onStatus("reconnecting (" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
                }
            } finally {
                current = null;
                if (c != null) c.disconnect();
            }
            if (!running) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Parses parts like:  --mrframe CRLF Content-Type: image/jpeg CRLF Content-Length: N CRLF CRLF <N bytes> CRLF */
    static void parse(InputStream in, Listener listener, Running running) throws IOException {
        while (running.get()) {
            String line = readLine(in);
            if (line.isEmpty() || !line.startsWith("--")) continue;
            int length = -1;
            while (true) {
                String header = readLine(in);
                if (header.isEmpty()) break;
                if (header.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try {
                        length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                    } catch (NumberFormatException e) {
                        throw new IOException("bad Content-Length");
                    }
                }
            }
            if (length <= 0 || length > MAX_FRAME_BYTES) throw new IOException("bad frame length " + length);
            byte[] frame = new byte[length];
            int off = 0;
            while (off < length) {
                int r = in.read(frame, off, length - off);
                if (r < 0) throw new IOException("stream ended");
                off += r;
            }
            listener.onFrame(frame);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("stream ended");
            if (b == '\n') break;
            if (b != '\r' && sb.length() < 512) sb.append((char) b);
        }
        return sb.toString();
    }
}
