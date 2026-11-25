package com.mr.motioncam;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Talks to the PC Hub over UDP (default port = pose port + 1).
 * Wire format, one datagram per command (plain ASCII):  MRC1 <pin|-> <seq> <COMMAND> [args]
 *   PING          -> MRC1 PONG <seq> cam=<0|1> paused=<0|1> stream=<port> profile=<name> held=<n>
 *   ACT <name> <1|0> (button down / up) -> MRC1 ACK <seq> <name> ok|unknown|dup
 *   PAUSE <1|0>   pauses / resumes the Hub's head-to-mouse camera
 *   BYE           releases everything that is held
 * Every command is sent twice (30 ms apart) with the same sequence number; the Hub ignores the duplicate.
 * No Android classes are used, so this can be tested on a normal JVM.
 */
final class ControlClient {
    private final DatagramSocket socket;
    private final InetAddress host;
    private final int port;
    private final String pin;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mr-control-tx");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger seq = new AtomicInteger(new Random().nextInt(900_000) + 1000);
    private volatile boolean running;

    volatile long lastPongMs;
    volatile boolean hubCamera;
    volatile boolean hubPaused;
    volatile int streamPort;
    volatile int heldCount;
    volatile String profile = "";
    volatile String lastAck = "";

    ControlClient(DatagramSocket socket, InetAddress host, int port, String pin) {
        this.socket = socket;
        this.host = host;
        this.port = port;
        String p = pin == null ? "" : pin.replaceAll("\\s", "");
        this.pin = p.isEmpty() ? "-" : p;
    }

    void start() {
        running = true;
        Thread rx = new Thread(this::receiveLoop, "mr-control-rx");
        rx.setDaemon(true);
        rx.start();
        exec.scheduleWithFixedDelay(() -> sendRaw(frame("PING")), 0, 1000, TimeUnit.MILLISECONDS);
    }

    boolean isConnected() {
        long t = lastPongMs;
        return t != 0 && System.currentTimeMillis() - t < 3500;
    }

    void act(String name, boolean down) {
        send("ACT " + name + " " + (down ? 1 : 0));
    }

    void setPaused(boolean paused) {
        send("PAUSE " + (paused ? 1 : 0));
    }

    void send(String command) {
        if (!running) return;
        final byte[] data = frame(command);
        try {
            exec.execute(() -> sendRaw(data));
            exec.schedule(() -> sendRaw(data), 30, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { }
    }

    void stop() {
        if (!running) return;
        final byte[] bye = frame("BYE");
        try {
            exec.execute(() -> {
                sendRaw(bye);
                sendRaw(bye);
            });
        } catch (RejectedExecutionException ignored) { }
        running = false;
        exec.shutdown();
        try {
            exec.awaitTermination(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        socket.close();
    }

    private byte[] frame(String command) {
        return ("MRC1 " + pin + " " + seq.incrementAndGet() + " " + command).getBytes(StandardCharsets.US_ASCII);
    }

    private void sendRaw(byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, host, port));
        } catch (IOException ignored) { }
    }

    private void receiveLoop() {
        byte[] buf = new byte[512];
        try {
            socket.setSoTimeout(1000);
        } catch (Exception ignored) { }
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (!running) return;
                continue;
            }
            if (!host.equals(p.getAddress())) continue;
            parseReply(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
        }
    }

    void parseReply(String text) {
        String[] t = text.trim().split("\\s+");
        if (t.length < 3 || !t[0].equals("MRC1")) return;
        if (t[1].equals("PONG")) {
            for (int i = 3; i < t.length; i++) {
                int eq = t[i].indexOf('=');
                if (eq <= 0) continue;
                String k = t[i].substring(0, eq);
                String v = t[i].substring(eq + 1);
                try {
                    if (k.equals("cam")) hubCamera = v.equals("1");
                    else if (k.equals("paused")) hubPaused = v.equals("1");
                    else if (k.equals("stream")) streamPort = Integer.parseInt(v);
                    else if (k.equals("held")) heldCount = Integer.parseInt(v);
                    else if (k.equals("profile")) profile = v;
                } catch (NumberFormatException ignored) { }
            }
            lastPongMs = System.currentTimeMillis();
        } else if (t[1].equals("ACK") && t.length >= 5) {
            if (!t[4].equals("dup")) lastAck = t[3] + " " + t[4]; // the 2nd copy of a command is answered "dup": keep the real result
        }
    }
}
