package com.mr.motioncam;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MR Motion Cam - sends phone orientation to OpenTrack using its 6-double UDP format.
 * Packet: x, y, z, yaw, pitch, roll; little-endian IEEE-754 doubles.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final String PREFS = "mr_motion_cam";
    private static final int DEFAULT_PORT = 4242;
    private static final int PACKET_BYTES = 48;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private ConnectivityManager connectivityManager;
    private Network wifiNetwork;
    private boolean sensorRegistered = false;

    private EditText ipInput;
    private EditText portInput;
    private SeekBar sensitivityBar;
    private SeekBar smoothingBar;
    private Switch invertYawSwitch;
    private Switch invertPitchSwitch;
    private TextView statusText;
    private TextView yawText;
    private TextView pitchText;
    private TextView rollText;
    private TextView sensitivityValue;
    private TextView smoothingValue;
    private TextView packetText;
    private TextView netText;
    private Button startButton;
    private Button stopButton;

    private volatile double rawYaw;
    private volatile double rawPitch;
    private volatile double rawRoll;
    private volatile double centerYaw;
    private volatile double centerPitch;
    private volatile double centerRoll;
    private volatile double sentYaw;
    private volatile double sentPitch;
    private volatile double sentRoll;

    private volatile double sensitivity = 1.0;
    private volatile int smoothing = 25;
    private volatile boolean invertYaw = false;
    private volatile boolean invertPitch = false;
    private volatile boolean tracking = false;

    private DatagramSocket socket;
    private InetAddress destination;
    private int destinationPort = DEFAULT_PORT;
    private ScheduledExecutorService sender;
    private final AtomicBoolean sendErrorShown = new AtomicBoolean(false);
    private volatile long packetsSent = 0;
    private volatile long sendAttempts = 0;
    private volatile long sensorEvents = 0;
    private volatile String lastSendError = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdater = new Runnable() {
        @Override public void run() {
            yawText.setText(String.format(Locale.US, "Yaw  %+.1f°", sentYaw));
            pitchText.setText(String.format(Locale.US, "Pitch  %+.1f°", sentPitch));
            rollText.setText(String.format(Locale.US, "Roll  %+.1f°", sentRoll));
            String detail = lastSendError.isEmpty() ? "" : "\nآخر خطأ: " + lastSendError;
            packetText.setText("Packets sent: " + packetsSent + " / Attempts: " + sendAttempts
                    + " / Sensor: " + sensorEvents + detail);
            mainHandler.postDelayed(this, 120);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(11, 17, 24));
        getWindow().setNavigationBarColor(Color.rgb(11, 17, 24));

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        buildInterface();
        loadPreferences();
        updateSensorAvailability();
        refreshNetworkInfo();
        mainHandler.post(uiUpdater);
    }

    private void buildInterface() {
        int bg = Color.rgb(11, 17, 24);
        int panel = Color.rgb(20, 30, 41);
        int text = Color.rgb(238, 244, 248);
        int muted = Color.rgb(151, 167, 180);
        int accent = Color.rgb(53, 197, 180);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(page);

        TextView brand = new TextView(this);
        brand.setText("MR  /  MOTION CAM");
        brand.setTextColor(accent);
        brand.setTextSize(12);
        brand.setLetterSpacing(0.14f);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(brand);

        TextView title = new TextView(this);
        title.setText("خلّي الموبايل هو الكاميرا");
        title.setTextColor(text);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(4));
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("حرّك الموبايل، وشوف منظور القيادة بيتحرك معاك في اللعبة.");
        subtitle.setTextColor(muted);
        subtitle.setTextSize(14);
        page.addView(subtitle);

        LinearLayout statusCard = card(panel);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams statusLp = params(-1, -2);
        statusLp.topMargin = dp(22);
        page.addView(statusCard, statusLp);

        statusText = new TextView(this);
        statusText.setText("●  جاهز للاتصال");
        statusText.setTextColor(accent);
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusCard.addView(statusText);

        packetText = new TextView(this);
        packetText.setText("Packets sent: 0");
        packetText.setTextColor(muted);
        packetText.setTextSize(12);
        packetText.setPadding(0, dp(5), 0, 0);
        statusCard.addView(packetText);

        netText = new TextView(this);
        netText.setTextColor(muted);
        netText.setTextSize(12);
        netText.setPadding(0, dp(5), 0, 0);
        statusCard.addView(netText);

        addSectionTitle(page, "اتصال الكمبيوتر", text);
        LinearLayout connectionCard = card(panel);
        connectionCard.setOrientation(LinearLayout.VERTICAL);
        connectionCard.setPadding(dp(16), dp(14), dp(16), dp(16));
        page.addView(connectionCard, params(-1, -2));

        TextView ipLabel = smallLabel("عنوان IP للكمبيوتر", muted);
        connectionCard.addView(ipLabel);
        ipInput = new EditText(this);
        ipInput.setSingleLine(true);
        ipInput.setTextColor(text);
        ipInput.setHintTextColor(Color.rgb(102, 119, 133));
        ipInput.setHint("مثال: 192.168.1.5");
        ipInput.setTextSize(15);
        ipInput.setInputType( android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI );
        styleInput(ipInput, bg);
        LinearLayout.LayoutParams ipLp = params(-1, dp(48));
        ipLp.topMargin = dp(7);
        connectionCard.addView(ipInput, ipLp);

        TextView portLabel = smallLabel("UDP Port", muted);
        LinearLayout.LayoutParams plp = params(-1, -2);
        plp.topMargin = dp(12);
        connectionCard.addView(portLabel, plp);
        portInput = new EditText(this);
        portInput.setSingleLine(true);
        portInput.setTextColor(text);
        portInput.setHintTextColor(Color.rgb(102, 119, 133));
        portInput.setHint("4242");
        portInput.setTextSize(15);
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        styleInput(portInput, bg);
        LinearLayout.LayoutParams portLp = params(-1, dp(48));
        portLp.topMargin = dp(7);
        connectionCard.addView(portInput, portLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp = params(-1, dp(50));
        buttonsLp.topMargin = dp(14);
        connectionCard.addView(buttons, buttonsLp);

        startButton = new Button(this);
        startButton.setText("ابدأ التتبع");
        startButton.setTextColor(Color.rgb(5, 24, 25));
        startButton.setAllCaps(false);
        startButton.setTextSize(14);
        startButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        startButton.setBackground(round(accent, 14));
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, -1, 1));

        stopButton = new Button(this);
        stopButton.setText("إيقاف");
        stopButton.setTextColor(text);
        stopButton.setAllCaps(false);
        stopButton.setTextSize(14);
        stopButton.setBackground(round(Color.rgb(54, 67, 80), 14));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, -1, 1);
        stopLp.leftMargin = dp(9);
        buttons.addView(stopButton, stopLp);

        startButton.setOnClickListener(v -> startTracking());
        stopButton.setOnClickListener(v -> stopTracking("تم إيقاف التتبع"));

        addSectionTitle(page, "حركة الموبايل", text);
        LinearLayout movementCard = card(panel);
        movementCard.setOrientation(LinearLayout.VERTICAL);
        movementCard.setPadding(dp(16), dp(14), dp(16), dp(16));
        page.addView(movementCard, params(-1, -2));

        yawText = axisText("Yaw  +0.0°", text);
        pitchText = axisText("Pitch  +0.0°", text);
        rollText = axisText("Roll  +0.0°", text);
        movementCard.addView(yawText);
        movementCard.addView(pitchText);
        movementCard.addView(rollText);

        Button centerButton = new Button(this);
        centerButton.setText("إعادة ضبط المنتصف  ⊙");
        centerButton.setAllCaps(false);
        centerButton.setTextColor(text);
        centerButton.setTextSize(14);
        centerButton.setBackground(round(Color.rgb(42, 59, 71), 13));
        LinearLayout.LayoutParams centerLp = params(-1, dp(48));
        centerLp.topMargin = dp(14);
        movementCard.addView(centerButton, centerLp);
        centerButton.setOnClickListener(v -> recenter());

        addSectionTitle(page, "تخصيص الحركة", text);
        LinearLayout settingsCard = card(panel);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setPadding(dp(16), dp(14), dp(16), dp(12));
        page.addView(settingsCard, params(-1, -2));

        LinearLayout sensRow = row();
        TextView sensLabel = smallLabel("الحساسية", text);
        sensitivityValue = smallLabel("1.0x", accent);
        sensRow.addView(sensLabel, new LinearLayout.LayoutParams(0, -2, 1));
        sensRow.addView(sensitivityValue);
        settingsCard.addView(sensRow);
        sensitivityBar = new SeekBar(this);
        sensitivityBar.setMax(29);
        sensitivityBar.setProgress(9);
        sensitivityBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        sensitivityBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        settingsCard.addView(sensitivityBar);
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sensitivity = 0.1 * (progress + 1);
                sensitivityValue.setText(String.format(Locale.US, "%.1fx", sensitivity));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        LinearLayout smoothRow = row();
        TextView smoothLabel = smallLabel("تنعيم الحركة", text);
        smoothingValue = smallLabel("25%", accent);
        smoothRow.addView(smoothLabel, new LinearLayout.LayoutParams(0, -2, 1));
        smoothRow.addView(smoothingValue);
        LinearLayout.LayoutParams smoothRowLp = params(-1, -2);
        smoothRowLp.topMargin = dp(13);
        settingsCard.addView(smoothRow, smoothRowLp);
        smoothingBar = new SeekBar(this);
        smoothingBar.setMax(90);
        smoothingBar.setProgress(25);
        smoothingBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        smoothingBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        settingsCard.addView(smoothingBar);
        smoothingBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                smoothing = progress;
                smoothingValue.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        invertYawSwitch = makeSwitch("عكس اليمين والشمال", text);
        invertPitchSwitch = makeSwitch("عكس فوق وتحت", text);
        LinearLayout.LayoutParams switchLp1 = params(-1, -2);
        switchLp1.topMargin = dp(10);
        settingsCard.addView(invertYawSwitch, switchLp1);
        settingsCard.addView(invertPitchSwitch, params(-1, -2));
        invertYawSwitch.setOnCheckedChangeListener((buttonView, checked) -> invertYaw = checked);
        invertPitchSwitch.setOnCheckedChangeListener((buttonView, checked) -> invertPitch = checked);

        TextView footer = new TextView(this);
        footer.setText("مهم: الموبايل والكمبيوتر لازم يكونوا على نفس شبكة Wi‑Fi. التطبيق يرسل زوايا الدوران فقط. الإرسال UDP بدون تأكيد استلام، فالتطبيق مش بيعرف لو الكمبيوتر استلم — راجع الأرقام في OpenTrack.");
        footer.setTextColor(muted);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(8), dp(18), dp(8), dp(4));
        page.addView(footer);

        ipInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { refreshNetworkInfo(); }
        });

        setContentView(scroll);
    }

    private void addSectionTitle(LinearLayout parent, String value, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(20), 0, dp(9));
        parent.addView(t);
    }

    private LinearLayout card(int color) {
        LinearLayout box = new LinearLayout(this);
        box.setBackground(round(color, 18));
        return box;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private TextView smallLabel(String value, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(13);
        return t;
    }

    private TextView axisText(String value, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(15);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private Switch makeSwitch(String value, int color) {
        Switch s = new Switch(this);
        s.setText(value);
        s.setTextColor(color);
        s.setTextSize(13);
        return s;
    }

    private void styleInput(EditText input, int fill) {
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(round(fill, 11, Color.rgb(45, 62, 75), 1));
        input.setSelectAllOnFocus(false);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable round(int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = round(color, radiusDp);
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateSensorAvailability() {
        boolean available = rotationSensor != null;
        startButton.setEnabled(available);
        startButton.setAlpha(available ? 1f : 0.45f);
        if (!available) {
            statusText.setText("●  الموبايل لا يحتوي على مستشعر دوران مناسب");
            statusText.setTextColor(Color.rgb(255, 173, 95));
        }
    }

    private void startTracking() {
        if (rotationSensor == null) {
            setStatus("المستشعر غير متاح على هذا الهاتف", true);
            return;
        }
        String host = cleanIp(ipInput.getText().toString());
        String portString = portInput.getText().toString().trim();
        if (host.isEmpty()) {
            ipInput.setError("اكتب IP الكمبيوتر");
            return;
        }
        byte[] ipBytes = parseIpv4(host);
        if (ipBytes == null) {
            ipInput.setError("IPv4 غير صحيح");
            setStatus("اكتب IP الكمبيوتر بالشكل 192.168.1.5 (من ipconfig على الكمبيوتر)", true);
            return;
        }
        if ((ipBytes[0] & 0xFF) == 127 || (ipBytes[0] == 0 && ipBytes[1] == 0 && ipBytes[2] == 0 && ipBytes[3] == 0)) {
            ipInput.setError("ده عنوان الموبايل نفسه");
            setStatus("127.0.0.1 / 0.0.0.0 معناه الموبايل نفسه! اكتب IP الكمبيوتر على الـ Wi-Fi (من ipconfig).", true);
            return;
        }
        if (!host.equals(ipInput.getText().toString())) ipInput.setText(host);
        int port;
        try {
            port = Integer.parseInt(portString.isEmpty() ? String.valueOf(DEFAULT_PORT) : portString);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            // getByAddress with raw bytes never does a DNS lookup (a lookup on the UI thread would crash).
            destination = InetAddress.getByAddress(ipBytes);
        } catch (Exception e) {
            portInput.setError("Port غير صحيح");
            setStatus("راجع الـ Port (الافتراضي 4242)", true);
            return;
        }

        destinationPort = port;
        savePreferences();
        stopSenderOnly();
        String route;
        try {
            socket = new DatagramSocket();
            wifiNetwork = findLanNetwork();
            if (wifiNetwork != null) {
                // Force UDP through the real Wi-Fi/Ethernet link, even with mobile data or a VPN running.
                wifiNetwork.bindSocket(socket);
                route = "عبر Wi-Fi";
            } else {
                // Phone is the hotspot, USB tethering, or no client Wi-Fi: fall back to normal routing.
                route = "عبر المسار الافتراضي (مفيش Wi-Fi متصل)";
            }
        } catch (Exception e) {
            if (socket != null) {
                socket.close();
                socket = null;
            }
            wifiNetwork = null;
            setStatus("تعذر تجهيز اتصال Wi-Fi: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + e.getMessage()), true);
            return;
        }

        tracking = true;
        packetsSent = 0;
        sendAttempts = 0;
        sensorEvents = 0;
        lastSendError = "";
        sentYaw = sentPitch = sentRoll = 0;
        sendErrorShown.set(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!sensorRegistered) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
            sensorRegistered = true;
        }
        sender = Executors.newSingleThreadScheduledExecutor();
        sender.scheduleAtFixedRate(() -> {
            try {
                sendLatestPose();
            } catch (Throwable t) {
                lastSendError = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                mainHandler.post(() -> setStatus("توقف مرسل UDP: " + lastSendError, true));
            }
        }, 0, 16, TimeUnit.MILLISECONDS);
        setStatus("●  إرسال " + route + " إلى " + destination.getHostAddress() + ":" + destinationPort, false);
        refreshNetworkInfo();
    }

    /**
     * Picks the physical Wi-Fi (or Ethernet) network. VPN networks are skipped on purpose: a VPN running
     * over Wi-Fi also reports TRANSPORT_WIFI, and binding to it would push LAN packets into the tunnel.
     */
    private Network findLanNetwork() {
        if (connectivityManager == null) return null;
        Network ethernet = null;
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network;
            if (ethernet == null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = network;
        }
        return ethernet;
    }

    private LinkAddress phoneIpv4(Network network) {
        if (network == null || connectivityManager == null) return null;
        LinkProperties lp = connectivityManager.getLinkProperties(network);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) return la;
        }
        return null;
    }

    /** Shows the phone's own IP and whether the typed PC address is on the same subnet. */
    private void refreshNetworkInfo() {
        if (netText == null || ipInput == null) return;
        int ok = Color.rgb(53, 197, 180);
        int warn = Color.rgb(255, 173, 95);
        LinkAddress phone = phoneIpv4(findLanNetwork());
        if (phone == null) {
            netText.setText("الشبكة: مفيش Wi-Fi متصل (أو الموبايل hotspot / كابل)");
            netText.setTextColor(warn);
            return;
        }
        byte[] phoneIp = phone.getAddress().getAddress();
        String msg = "IP الموبايل: " + phone.getAddress().getHostAddress() + "/" + phone.getPrefixLength();
        int color = Color.rgb(151, 167, 180);
        byte[] target = parseIpv4(cleanIp(ipInput.getText().toString()));
        if (target != null) {
            if ((target[0] & 0xFF) == 127) {
                msg += "\n⚠ 127.0.0.1 معناه الموبايل نفسه — اكتب IP الكمبيوتر";
                color = warn;
            } else if (sameSubnet(phoneIp, target, phone.getPrefixLength())) {
                msg += "\n✔ الكمبيوتر على نفس شبكة الموبايل";
                color = ok;
            } else {
                msg += "\n⚠ الكمبيوتر مش على نفس شبكة الموبايل. أول 3 أرقام لازم تتشابه (مثلاً 192.168.1.x)."
                        + " غالبًا واخد IP كرت افتراضي (VirtualBox / WSL / VPN) — خُد IP كرت الـ Wi-Fi.";
                color = warn;
            }
        }
        netText.setText(msg);
        netText.setTextColor(color);
    }

    private boolean sameSubnet(byte[] a, byte[] b, int prefix) {
        if (a.length != 4 || b.length != 4) return false;
        int full = Math.min(prefix / 8, 4);
        for (int i = 0; i < full; i++) if (a[i] != b[i]) return false;
        int rem = prefix % 8;
        if (rem > 0 && full < 4) {
            int mask = (0xFF << (8 - rem)) & 0xFF;
            if ((a[full] & mask) != (b[full] & mask)) return false;
        }
        return true;
    }

    /** Turns what the user typed into plain ASCII: Arabic-Indic digits, Arabic dots/commas, RTL marks, spaces. */
    private String cleanIp(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int digit = Character.digit(c, 10);
            if (digit >= 0) {
                sb.append((char) ('0' + digit));
            } else if (c == '.' || c == ',' || c == '\u066B' || c == '\u060C' || c == '\u06D4' || c == '\u3002') {
                sb.append('.');
            } else if (!Character.isWhitespace(c) && Character.getType(c) != Character.FORMAT) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) return null;
            for (int k = 0; k < part.length(); k++) {
                if (part.charAt(k) < '0' || part.charAt(k) > '9') return null;
            }
            int n = Integer.parseInt(part);
            if (n > 255) return null;
            out[i] = (byte) n;
        }
        return out;
    }

    private void stopTracking(String message) {
        tracking = false;
        stopSenderOnly();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (sensorRegistered) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
        setStatus("●  " + message, false);
        savePreferences();
    }

    private void stopSenderOnly() {
        if (sender != null) {
            sender.shutdownNow();
            sender = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        wifiNetwork = null;
    }

    private void sendLatestPose() {
        if (!tracking || socket == null || destination == null) return;
        sendAttempts++;
        double yaw = normalize(rawYaw - centerYaw) * sensitivity * (invertYaw ? -1.0 : 1.0);
        double pitch = normalize(rawPitch - centerPitch) * sensitivity * (invertPitch ? -1.0 : 1.0);
        double roll = normalize(rawRoll - centerRoll) * sensitivity;

        double alpha = 1.0 - (smoothing / 100.0) * 0.85;
        sentYaw += (yaw - sentYaw) * alpha;
        sentPitch += (pitch - sentPitch) * alpha;
        sentRoll += (roll - sentRoll) * alpha;

        ByteBuffer buffer = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putDouble(0.0); // X translation in cm (rotation-only tracker)
        buffer.putDouble(0.0); // Y translation in cm
        buffer.putDouble(0.0); // Z translation in cm
        buffer.putDouble(sentYaw);
        buffer.putDouble(sentPitch);
        buffer.putDouble(sentRoll);
        byte[] bytes = buffer.array();
        try {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, destination, destinationPort);
            socket.send(packet);
            packetsSent++;
            lastSendError = "";
        } catch (Exception e) {
            lastSendError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (sendErrorShown.compareAndSet(false, true)) {
                String detail = lastSendError;
                mainHandler.post(() -> setStatus("فشل إرسال UDP: " + detail, true));
            }
        }
    }

    private void recenter() {
        centerYaw = rawYaw;
        centerPitch = rawPitch;
        centerRoll = rawRoll;
        sentYaw = sentPitch = sentRoll = 0;
        setStatus(tracking ? "تم ضبط المنتصف" : "تم حفظ وضع المنتصف الحالي", false);
    }

    private double normalize(double degrees) {
        while (degrees > 180.0) degrees -= 360.0;
        while (degrees < -180.0) degrees += 360.0;
        return degrees;
    }

    private void setStatus(String message, boolean error) {
        statusText.setText(message);
        statusText.setTextColor(error ? Color.rgb(255, 173, 95) : Color.rgb(53, 197, 180));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        sensorEvents++;
        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        rawYaw = Math.toDegrees(orientation[0]);
        rawPitch = Math.toDegrees(orientation[1]);
        rawRoll = Math.toDegrees(orientation[2]);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNetworkInfo();
        if (tracking && rotationSensor != null && !sensorRegistered) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
            sensorRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        if (sensorRegistered) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopTracking("تم إيقاف التتبع");
        mainHandler.removeCallbacks(uiUpdater);
        super.onDestroy();
    }

    private void loadPreferences() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        ipInput.setText(p.getString("ip", ""));
        portInput.setText(p.getString("port", String.valueOf(DEFAULT_PORT)));
        int sensProgress = p.getInt("sensitivityProgress", 9);
        int smoothProgress = p.getInt("smoothingProgress", 25);
        boolean iy = p.getBoolean("invertYaw", false);
        boolean ip = p.getBoolean("invertPitch", false);
        sensitivityBar.setProgress(sensProgress);
        smoothingBar.setProgress(smoothProgress);
        invertYawSwitch.setChecked(iy);
        invertPitchSwitch.setChecked(ip);
        sensitivity = 0.1 * (sensitivityBar.getProgress() + 1);
        smoothing = smoothingBar.getProgress();
        invertYaw = iy;
        invertPitch = ip;
        sensitivityValue.setText(String.format(Locale.US, "%.1fx", sensitivity));
        smoothingValue.setText(smoothing + "%");
    }

    private void savePreferences() {
        if (ipInput == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("ip", ipInput.getText().toString().trim())
                .putString("port", portInput.getText().toString().trim())
                .putInt("sensitivityProgress", sensitivityBar.getProgress())
                .putInt("smoothingProgress", smoothingBar.getProgress())
                .putBoolean("invertYaw", invertYawSwitch.isChecked())
                .putBoolean("invertPitch", invertPitchSwitch.isChecked())
                .apply();
    }
}
