package com.mr.motioncam;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.net.wifi.WifiManager;
import android.net.Network;
import android.net.Uri;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MR Motion Cam - sends phone orientation to OpenTrack using its 6-double UDP format.
 * Packet: x, y, z, yaw, pitch, roll; little-endian IEEE-754 doubles.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final String PREFS = "mr_motion_cam";
    private static final int DEFAULT_PORT = 4242;
    private static final int PACKET_BYTES = 48;
    private static final int DEFAULT_STREAM_PORT = 8088;
    /** 100 Hz sensor sampling; every sample is sent immediately (no resampling = no stair-stepping). */
    private static final int SAMPLE_PERIOD_US = 10_000;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private ConnectivityManager connectivityManager;
    private Network wifiNetwork;
    private boolean sensorRegistered = false;
    private boolean usingGameVector = false;
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private WifiManager.WifiLock wifiLock;
    private TextView modeText;

    // PC Hub: buttons (UDP, port = pose port + 1) and live view (HTTP MJPEG)
    private ControlClient control;
    private TextView hubText;
    private EditText pinInput;
    private Switch cameraSwitch;
    private long lastPauseSyncMs;
    private ImageView liveImage;
    private TextView liveText;
    private Button liveButton;
    private Button recordButton;
    private MjpegClient liveClient;
    private volatile String liveStatus = "";
    private volatile long liveFrames;
    private final AtomicBoolean framePending = new AtomicBoolean(false);
    private final Bitmap[] liveBuffers = new Bitmap[2]; // only touched by the stream thread
    private int liveBufferIndex;
    private volatile boolean recording;
    private ExecutorService recorderExec;               // UI thread owns the reference
    private MjpegAviWriter recorderWriter;              // the fields below: recorder thread only
    private ParcelFileDescriptor recorderPfd;
    private FileOutputStream recorderOut;
    private RandomAccessFile recorderRaf;
    private Uri recorderUri;
    private String recorderName = "";
    private String recorderLocation = "";
    private volatile long recordedFrames;
    private volatile long recordedBytes;
    private volatile String recordResult = "";

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

    private final PosePipeline pipeline = new PosePipeline();
    private final ByteBuffer packetBuffer = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN);

    private volatile boolean tracking = false;

    private volatile DatagramSocket socket;
    private volatile DatagramPacket outPacket;
    private volatile InetAddress destination;
    private volatile int destinationPort = DEFAULT_PORT;
    private final AtomicBoolean sendErrorShown = new AtomicBoolean(false);
    private volatile long packetsSent = 0;
    private volatile long sendAttempts = 0;
    private volatile long sensorEvents = 0;
    private volatile String lastSendError = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdater = new Runnable() {
        @Override public void run() {
            yawText.setText(String.format(Locale.US, "Yaw  %+.1f°", pipeline.yaw));
            pitchText.setText(String.format(Locale.US, "Pitch  %+.1f°", pipeline.pitch));
            rollText.setText(String.format(Locale.US, "Roll  %+.1f°", pipeline.roll));
            modeText.setText((usingGameVector ? "المستشعر: Game Rotation (جيروسكوب بدون بوصلة)" : "المستشعر: Rotation Vector (بالبوصلة)")
                    + (tracking ? (pipeline.isFlatMode() ? "\nوضع الموبايل: مفرود" : "\nوضع الموبايل: واقف") + " (تلقائي)" : ""));
            String detail = lastSendError.isEmpty() ? "" : "\nآخر خطأ: " + lastSendError;
            packetText.setText("Packets sent: " + packetsSent + " / Attempts: " + sendAttempts
                    + " / Sensor: " + sensorEvents + detail);
            updateHubStatus();
            updateLiveStatus();
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
        // Game rotation vector = gyroscope + accelerometer only (no magnetometer): no magnetic noise or jumps.
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        usingGameVector = rotationSensor != null;
        if (rotationSensor == null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        // Sensor callbacks run on their own thread so UI work can never delay a sample.
        sensorThread = new HandlerThread("mr-sensor", Process.THREAD_PRIORITY_DISPLAY);
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
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

        TextView pinLabel = smallLabel("رمز الأمان (اختياري) — لازم يطابق -Pin في الـ Hub", muted);
        LinearLayout.LayoutParams pinLabelLp = params(-1, -2);
        pinLabelLp.topMargin = dp(12);
        connectionCard.addView(pinLabel, pinLabelLp);
        pinInput = new EditText(this);
        pinInput.setSingleLine(true);
        pinInput.setTextColor(text);
        pinInput.setHintTextColor(Color.rgb(102, 119, 133));
        pinInput.setHint("سيبه فاضي لو الـ Hub من غير PIN");
        pinInput.setTextSize(15);
        pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        styleInput(pinInput, bg);
        LinearLayout.LayoutParams pinLp = params(-1, dp(48));
        pinLp.topMargin = dp(7);
        connectionCard.addView(pinInput, pinLp);

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

        modeText = new TextView(this);
        modeText.setTextColor(muted);
        modeText.setTextSize(11);
        modeText.setPadding(0, dp(4), 0, 0);
        movementCard.addView(modeText);

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

        buildControlCard(page, panel, text, muted, accent);
        buildLiveCard(page, panel, text, muted, accent);

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
                pipeline.sensitivity = 0.1 * (progress + 1);
                sensitivityValue.setText(String.format(Locale.US, "%.1fx", pipeline.sensitivity));
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
                pipeline.minCutoffHz = PosePipeline.cutoffForSmoothing(progress);
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
        invertYawSwitch.setOnCheckedChangeListener((buttonView, checked) -> pipeline.invertYaw = checked);
        invertPitchSwitch.setOnCheckedChangeListener((buttonView, checked) -> pipeline.invertPitch = checked);

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

        packetsSent = 0;
        sendAttempts = 0;
        sensorEvents = 0;
        lastSendError = "";
        sendErrorShown.set(false);
        outPacket = new DatagramPacket(packetBuffer.array(), PACKET_BYTES, destination, destinationPort);
        try {
            socket.setTrafficClass(0xB8); // ask the network for low-delay treatment (best effort)
        } catch (Exception ignored) { }
        pipeline.restart(); // first sample after start becomes the neutral pose
        acquireWifiLock();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tracking = true;
        registerSensor();
        startControl();
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

    private void registerSensor() {
        if (sensorRegistered || rotationSensor == null || sensorHandler == null) return;
        sensorRegistered = sensorManager.registerListener(this, rotationSensor, SAMPLE_PERIOD_US, sensorHandler);
        if (!sensorRegistered) setStatus("تعذر تشغيل المستشعر", true);
    }

    private void unregisterSensor() {
        if (sensorRegistered) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
    }

    /** Stops Wi-Fi power saving from batching packets (the usual cause of periodic stutter). */
    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                int mode = Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "MRMotionCam");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) { }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) { }
    }

    private void stopTracking(String message) {
        tracking = false;
        stopControl();
        unregisterSensor();
        stopSenderOnly();
        releaseWifiLock();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus("●  " + message, false);
        savePreferences();
    }

    private void stopSenderOnly() {
        outPacket = null;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
        wifiNetwork = null;
    }

    private void recenter() {
        if (!tracking) {
            setStatus("ابدأ التتبع الأول، وبعدين اضغط إعادة ضبط المنتصف وإنت ماسك الموبايل في الوضع المريح", true);
            return;
        }
        pipeline.requestCenter();
        setStatus("تم ضبط المنتصف", false);
    }

    private void setStatus(String message, boolean error) {
        statusText.setText(message);
        statusText.setTextColor(error ? Color.rgb(255, 173, 95) : Color.rgb(53, 197, 180));
    }

    /** Runs on the sensor thread: one sensor sample in, one UDP packet out, nothing in between. */
    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR && type != Sensor.TYPE_ROTATION_VECTOR) return;
        sensorEvents++;
        DatagramSocket out = socket;
        DatagramPacket packet = outPacket;
        if (!tracking || out == null || packet == null) return;
        if (!pipeline.process(event.values, event.timestamp)) return;

        sendAttempts++;
        ((java.nio.Buffer) packetBuffer).clear(); // cast: ByteBuffer.clear() has a different signature on Android < 13
        packetBuffer.putDouble(0.0); // X translation in cm (rotation-only tracker)
        packetBuffer.putDouble(0.0); // Y
        packetBuffer.putDouble(0.0); // Z
        packetBuffer.putDouble(pipeline.yaw);
        packetBuffer.putDouble(pipeline.pitch);
        packetBuffer.putDouble(pipeline.roll);
        try {
            out.send(packet);
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


    // ======================================================================================================
    // PC Hub: control buttons
    // ======================================================================================================

    private void startControl() {
        stopControl();
        try {
            DatagramSocket controlSocket = new DatagramSocket();
            if (wifiNetwork != null) wifiNetwork.bindSocket(controlSocket);
            control = new ControlClient(controlSocket, destination, destinationPort + 1, pinInput.getText().toString());
            control.start();
            lastPauseSyncMs = 0;
        } catch (Exception e) {
            control = null;
        }
    }

    private void stopControl() {
        ControlClient c = control;
        control = null;
        if (c != null) c.stop();
    }

    private void controlAct(String action, boolean down) {
        ControlClient c = control;
        if (c == null) {
            if (down) setStatus("ابدأ التتبع الأول عشان أزرار التحكم تشتغل", true);
            return;
        }
        c.act(action, down);
    }

    private void updateHubStatus() {
        if (hubText == null) return;
        ControlClient c = control;
        if (c == null) {
            hubText.setText("الـ Hub: دوس \"ابدأ التتبع\" الأول عشان الأزرار تشتغل");
            hubText.setTextColor(Color.rgb(151, 167, 180));
            return;
        }
        if (c.isConnected()) {
            String cam = !c.hubCamera ? "من OpenTrack" : (c.hubPaused ? "من الـ Hub (موقوفة)" : "من الـ Hub (شغالة)");
            String ack = c.lastAck.isEmpty() ? "" : "\nآخر أمر: " + c.lastAck
                    + (c.lastAck.endsWith("unknown") ? "  ⚠ مش موجود في ملف البروفايل" : "");
            hubText.setText("✔ الـ Hub متصل (" + c.profile + ") — الكاميرا " + cam + ack);
            hubText.setTextColor(Color.rgb(53, 197, 180));
            // keep the Hub's pause state equal to the switch (also after the Hub was restarted)
            boolean wantPaused = !cameraSwitch.isChecked();
            long now = SystemClock.elapsedRealtime();
            if (c.hubCamera && c.hubPaused != wantPaused && now - lastPauseSyncMs > 1500) {
                lastPauseSyncMs = now;
                c.setPaused(wantPaused);
            }
        } else {
            hubText.setText("الـ Hub مش متصل. شغّل HUB_BUTTONS_ONLY.bat أو HUB_WITH_CAMERA.bat على الكمبيوتر."
                    + " مش لازم لو بتستخدم OpenTrack بس.");
            hubText.setTextColor(Color.rgb(255, 173, 95));
        }
    }

    private void buildControlCard(LinearLayout page, int panel, int text, int muted, int accent) {
        addSectionTitle(page, "التحكم في اللعبة", text);
        LinearLayout box = card(panel);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(16));
        page.addView(box, params(-1, -2));

        hubText = new TextView(this);
        hubText.setTextColor(muted);
        hubText.setTextSize(12);
        box.addView(hubText);

        cameraSwitch = makeSwitch("تتبع الكاميرا شغال (بيوقفه مؤقتًا في الـ Hub)", text);
        cameraSwitch.setChecked(true);
        LinearLayout.LayoutParams swLp = params(-1, -2);
        swLp.topMargin = dp(8);
        box.addView(cameraSwitch, swLp);
        cameraSwitch.setOnCheckedChangeListener((b, checked) -> {
            ControlClient c = control;
            if (c != null && c.hubCamera) c.setPaused(!checked);
        });

        // {label, action name in windows/profile_cpm2.ini}. "اضغط" = the key stays pressed while you hold the button.
        String[][] items = {
                {"🔑  المحرك", "engine"}, {"📯  هورن (اضغط)", "horn"},
                {"🅿  فرامل اليد", "handbrake"}, {"📷  كاميرا", "camera"},
                {"🎥  كاميرا حرة (اضغط)", "freecam"}, {"🚪  أبواب (اضغط)", "doors"},
                {"☰  قائمة Tab (اضغط)", "radial"}, {"🚁  درون (اضغط)", "drone"},
                {"▲  عجلة", "wheel_up"}, {"▼  عجلة", "wheel_down"},
                {"🔍＋  زوم", "zoom_in"}, {"🔍－  زوم", "zoom_out"},
                {"🖱  كليك", "click"},
        };
        for (int i = 0; i < items.length; i += 2) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineLp = params(-1, dp(50));
            lineLp.topMargin = dp(8);
            box.addView(line, lineLp);
            for (int k = 0; k < 2; k++) {
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -1, 1);
                if (k == 1) bl.leftMargin = dp(8);
                if (i + k < items.length) line.addView(actionButton(items[i + k][0], items[i + k][1], text, accent), bl);
                else line.addView(new View(this), bl);
            }
        }
    }

    private Button actionButton(String label, final String action, int text, final int accent) {
        final Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(text);
        b.setTextSize(13);
        final GradientDrawable idle = round(Color.rgb(42, 59, 71), 13);
        final GradientDrawable pressed = round(accent, 13);
        b.setBackground(idle);
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    b.setBackground(pressed);
                    b.setTextColor(Color.rgb(5, 24, 25));
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    controlAct(action, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    b.setBackground(idle);
                    b.setTextColor(Color.WHITE);
                    controlAct(action, false);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    // ======================================================================================================
    // PC Hub: live view and recording
    // ======================================================================================================

    private void buildLiveCard(LinearLayout page, int panel, int text, int muted, int accent) {
        addSectionTitle(page, "بث اللعبة (Live View)", text);
        LinearLayout box = card(panel);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(16));
        page.addView(box, params(-1, -2));

        liveImage = new ImageView(this);
        liveImage.setBackgroundColor(Color.BLACK);
        liveImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(liveImage, params(-1, dp(210)));

        liveText = new TextView(this);
        liveText.setTextColor(muted);
        liveText.setTextSize(12);
        liveText.setPadding(0, dp(8), 0, 0);
        box.addView(liveText);

        LinearLayout line = row();
        LinearLayout.LayoutParams lineLp = params(-1, dp(48));
        lineLp.topMargin = dp(10);
        box.addView(line, lineLp);

        liveButton = new Button(this);
        liveButton.setText("بث اللعبة ▶");
        liveButton.setAllCaps(false);
        liveButton.setTextColor(Color.rgb(5, 24, 25));
        liveButton.setTextSize(14);
        liveButton.setBackground(round(accent, 13));
        line.addView(liveButton, new LinearLayout.LayoutParams(0, -1, 1));

        recordButton = new Button(this);
        recordButton.setText("تسجيل ●");
        recordButton.setAllCaps(false);
        recordButton.setTextColor(text);
        recordButton.setTextSize(14);
        recordButton.setBackground(round(Color.rgb(54, 67, 80), 13));
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(0, -1, 1);
        recLp.leftMargin = dp(9);
        line.addView(recordButton, recLp);

        liveButton.setOnClickListener(v -> {
            if (liveClient != null) stopLive();
            else startLive();
        });
        recordButton.setOnClickListener(v -> {
            if (recording) stopRecording();
            else startRecording();
        });
    }

    private final MjpegClient.Listener liveListener = new MjpegClient.Listener() {
        @Override public void onFrame(byte[] jpeg) {
            liveFrames++;
            if (recording) feedRecorder(jpeg);
            if (!framePending.compareAndSet(false, true)) return; // the screen is still busy: skip showing, never skip recording
            final Bitmap bmp = decodeFrame(jpeg);
            if (bmp == null) {
                framePending.set(false);
                return;
            }
            mainHandler.post(() -> {
                if (liveClient != null) liveImage.setImageBitmap(bmp);
                framePending.set(false);
            });
        }

        @Override public void onStatus(String status) {
            liveStatus = status;
        }
    };

    /** Decodes into one of two reusable bitmaps (never into the one currently on screen) to avoid GC churn. */
    private Bitmap decodeFrame(byte[] jpeg) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            o.inBitmap = liveBuffers[liveBufferIndex];
            Bitmap b;
            try {
                b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            } catch (IllegalArgumentException sizeChanged) {
                o.inBitmap = null;
                b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            }
            if (b != null) {
                liveBuffers[liveBufferIndex] = b;
                liveBufferIndex ^= 1;
            }
            return b;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private void startLive() {
        String host = cleanIp(ipInput.getText().toString());
        if (parseIpv4(host) == null) {
            ipInput.setError("اكتب IP الكمبيوتر الأول");
            return;
        }
        ControlClient c = control;
        int port = (c != null && c.streamPort > 0) ? c.streamPort : DEFAULT_STREAM_PORT;
        final Network net = findLanNetwork();
        URL url;
        try {
            url = new URL("http://" + host + ":" + port + "/stream");
        } catch (Exception e) {
            return;
        }
        liveFrames = 0;
        liveStatus = "connecting";
        framePending.set(false);
        liveClient = new MjpegClient(url, u -> net != null ? net.openConnection(u) : u.openConnection(), liveListener);
        liveClient.start();
        liveButton.setText("إيقاف البث ■");
    }

    private void stopLive() {
        if (liveButton == null) return;
        stopRecording();
        MjpegClient c = liveClient;
        liveClient = null;
        if (c != null) c.stop();
        liveButton.setText("بث اللعبة ▶");
        liveImage.setImageDrawable(null);
        framePending.set(false);
    }

    private void updateLiveStatus() {
        if (liveText == null) return;
        if (!recording && recorderExec != null) stopRecording(); // the recorder thread gave up (error): finish cleanly
        StringBuilder sb = new StringBuilder();
        if (liveClient == null) {
            sb.append("البث واقف. شغّل الـ Hub على الكمبيوتر وبعدين دوس \"بث اللعبة\". ")
              .append("ولو الشاشة سودا: اللعبة لازم تكون Windowed أو Borderless.");
        } else {
            sb.append("البث: ").append(liveStatus).append(" — ").append(liveFrames).append(" فريم");
        }
        if (recording) {
            sb.append("\n● بيسجّل: ").append(recordedFrames).append(" فريم، ")
              .append(String.format(Locale.US, "%.1f MB", recordedBytes / 1048576.0));
        } else if (!recordResult.isEmpty()) {
            sb.append("\n").append(recordResult);
        }
        liveText.setText(sb.toString());
    }

    private void startRecording() {
        if (liveClient == null) {
            recordResult = "شغّل البث الأول وبعدين سجّل";
            return;
        }
        recorderName = "MR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".avi";
        recorderExec = Executors.newSingleThreadExecutor();
        recordedFrames = 0;
        recordedBytes = 0;
        recordResult = "";
        recording = true;
        recordButton.setText("إيقاف التسجيل ■");
    }

    private void stopRecording() {
        recording = false;
        if (recordButton != null) recordButton.setText("تسجيل ●");
        final ExecutorService ex = recorderExec;
        recorderExec = null;
        if (ex == null) return;
        try {
            ex.execute(this::finishRecording); // runs after every queued frame has been written
        } catch (RejectedExecutionException ignored) { }
        ex.shutdown();
    }

    private void feedRecorder(final byte[] jpeg) {
        final ExecutorService ex = recorderExec;
        if (ex == null) return;
        final long now = System.nanoTime();
        try {
            ex.execute(() -> writeFrame(jpeg, now));
        } catch (RejectedExecutionException ignored) { }
    }

    /** Recorder thread. */
    private void writeFrame(byte[] jpeg, long nowNanos) {
        try {
            if (recorderWriter == null) {
                int[] size = JpegInfo.dimensions(jpeg);
                if (size == null) return;
                recorderWriter = new MjpegAviWriter(openRecordingChannel(recorderName), size[0], size[1]);
            }
            recorderWriter.addFrame(jpeg, nowNanos);
            recordedFrames = recorderWriter.frameCount();
            recordedBytes = recorderWriter.size();
        } catch (Exception e) {
            recordResult = "فشل التسجيل: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " — " + e.getMessage());
            recording = false;
        }
    }

    /** Recorder thread. */
    private void finishRecording() {
        String message;
        try {
            int frames = recorderWriter == null ? 0 : recorderWriter.frameCount();
            if (recorderWriter != null) recorderWriter.close();
            closeRecordingTarget();
            message = frames == 0 ? "مفيش فريمات اتسجلت"
                    : "اتحفظ: " + recorderLocation + " (" + frames + " فريم، "
                      + String.format(Locale.US, "%.1f MB", recordedBytes / 1048576.0) + ")";
        } catch (Exception e) {
            message = "فشل إنهاء الملف: " + e.getClass().getSimpleName();
        }
        recorderWriter = null;
        if (recordResult.isEmpty()) recordResult = message;
    }

    /** Recorder thread. Android 10+: into Movies/MR Motion Cam through MediaStore (no permission needed). */
    private FileChannel openRecordingChannel(String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/x-msvideo");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MR Motion Cam");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            recorderUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (recorderUri == null) throw new IOException("MediaStore insert failed");
            recorderPfd = getContentResolver().openFileDescriptor(recorderUri, "rw");
            if (recorderPfd == null) throw new IOException("cannot open the new file");
            recorderOut = new FileOutputStream(recorderPfd.getFileDescriptor());
            recorderLocation = "Movies/MR Motion Cam/" + name;
            return recorderOut.getChannel();
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = getFilesDir();
        File file = new File(dir, name);
        recorderRaf = new RandomAccessFile(file, "rw");
        recorderRaf.setLength(0);
        recorderLocation = file.getAbsolutePath();
        return recorderRaf.getChannel();
    }

    /** Recorder thread. */
    private void closeRecordingTarget() throws IOException {
        if (recorderOut != null) { recorderOut.close(); recorderOut = null; }
        if (recorderPfd != null) { recorderPfd.close(); recorderPfd = null; }
        if (recorderRaf != null) { recorderRaf.close(); recorderRaf = null; }
        if (recorderUri != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(recorderUri, done, null, null);
            recorderUri = null;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNetworkInfo();
        if (tracking) registerSensor();
    }

    @Override
    protected void onPause() {
        stopLive();
        unregisterSensor();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopLive();
        stopTracking("تم إيقاف التتبع");
        mainHandler.removeCallbacks(uiUpdater);
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
        }
        super.onDestroy();
    }

    private void loadPreferences() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        ipInput.setText(p.getString("ip", ""));
        portInput.setText(p.getString("port", String.valueOf(DEFAULT_PORT)));
        pinInput.setText(p.getString("pin", ""));
        int sensProgress = p.getInt("sensitivityProgress", 9);
        int smoothProgress = p.getInt("smoothingProgress", 25);
        boolean iy = p.getBoolean("invertYaw", false);
        boolean ip = p.getBoolean("invertPitch", false);
        sensitivityBar.setProgress(sensProgress);
        smoothingBar.setProgress(smoothProgress);
        invertYawSwitch.setChecked(iy);
        invertPitchSwitch.setChecked(ip);
        pipeline.sensitivity = 0.1 * (sensitivityBar.getProgress() + 1);
        pipeline.minCutoffHz = PosePipeline.cutoffForSmoothing(smoothingBar.getProgress());
        pipeline.invertYaw = iy;
        pipeline.invertPitch = ip;
        sensitivityValue.setText(String.format(Locale.US, "%.1fx", pipeline.sensitivity));
        smoothingValue.setText(smoothingBar.getProgress() + "%");
    }

    private void savePreferences() {
        if (ipInput == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("ip", ipInput.getText().toString().trim())
                .putString("port", portInput.getText().toString().trim())
                .putString("pin", pinInput.getText().toString().trim())
                .putInt("sensitivityProgress", sensitivityBar.getProgress())
                .putInt("smoothingProgress", smoothingBar.getProgress())
                .putBoolean("invertYaw", invertYawSwitch.isChecked())
                .putBoolean("invertPitch", invertPitchSwitch.isChecked())
                .apply();
    }
}
