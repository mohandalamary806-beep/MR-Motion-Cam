// MR Motion Cam - PC Hub core. Written in conservative C# 5 so Windows PowerShell 5.1 (Add-Type) can compile it.
// No Windows-only APIs in this file: input injection and screen capture live behind interfaces (see HubWindows.cs).
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace MrHub
{
    public interface IInputSink
    {
        void Key(int scan, bool extended, bool down);
        void MouseMove(int dx, int dy);
        void MouseButton(int button, bool down); // 0 = left, 1 = right, 2 = middle
        void Wheel(int notches);
        bool LeftButtonDown();
        string ForegroundTitle();
    }

    public interface IFrameSource
    {
        byte[] Capture();
    }

    /// <summary>Records what would have been pressed. Used for tests and -DryRun.</summary>
    public class DryRunInput : IInputSink
    {
        public readonly List<string> Events = new List<string>();
        public volatile bool LeftDown;
        public volatile string Title = "";

        private void Add(string s) { lock (Events) { Events.Add(s); } }
        public string[] Snapshot() { lock (Events) { return Events.ToArray(); } }
        public void Clear() { lock (Events) { Events.Clear(); } }

        public void Key(int scan, bool extended, bool down)
        {
            Add(String.Format("key {0:x2}{1} {2}", scan, extended ? "e" : "", down ? "down" : "up"));
        }
        public void MouseMove(int dx, int dy) { Add(String.Format("move {0} {1}", dx, dy)); }
        public void MouseButton(int button, bool down) { Add(String.Format("button {0} {1}", button, down ? "down" : "up")); }
        public void Wheel(int notches) { Add("wheel " + notches); }
        public bool LeftButtonDown() { return LeftDown; }
        public string ForegroundTitle() { return Title; }
    }

    public class FileFrameSource : IFrameSource
    {
        private readonly string path;
        public FileFrameSource(string path) { this.path = path; }
        public byte[] Capture() { return File.ReadAllBytes(path); }
    }

    public struct KeyInfo
    {
        public int Scan;
        public bool Extended;
        public KeyInfo(int scan, bool extended) { Scan = scan; Extended = extended; }
    }

    /// <summary>Hardware scan codes (set 1). Scan codes work with DirectInput / Raw Input games.</summary>
    public static class KeyTable
    {
        private static readonly Dictionary<string, KeyInfo> map = Build();

        private static Dictionary<string, KeyInfo> Build()
        {
            Dictionary<string, KeyInfo> d = new Dictionary<string, KeyInfo>(StringComparer.OrdinalIgnoreCase);
            int[] letters = { 0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
                              0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C };
            for (int i = 0; i < 26; i++) d[((char)('a' + i)).ToString()] = new KeyInfo(letters[i], false);
            for (int i = 1; i <= 9; i++) d[i.ToString()] = new KeyInfo(0x01 + i, false);
            d["0"] = new KeyInfo(0x0B, false);
            d["space"] = new KeyInfo(0x39, false);
            d["tab"] = new KeyInfo(0x0F, false);
            d["enter"] = new KeyInfo(0x1C, false);
            d["esc"] = new KeyInfo(0x01, false);
            d["backspace"] = new KeyInfo(0x0E, false);
            d["shift"] = new KeyInfo(0x2A, false);
            d["ctrl"] = new KeyInfo(0x1D, false);
            d["alt"] = new KeyInfo(0x38, false);
            d["caps"] = new KeyInfo(0x3A, false);
            for (int i = 1; i <= 10; i++) d["f" + i] = new KeyInfo(0x3A + i, false);
            d["f11"] = new KeyInfo(0x57, false);
            d["f12"] = new KeyInfo(0x58, false);
            d["up"] = new KeyInfo(0x48, true);
            d["down"] = new KeyInfo(0x50, true);
            d["left"] = new KeyInfo(0x4B, true);
            d["right"] = new KeyInfo(0x4D, true);
            return d;
        }

        public static bool TryGet(string name, out KeyInfo info) { return map.TryGetValue(name, out info); }

        /// <summary>0 = left, 1 = right, 2 = middle, -1 = not a mouse button.</summary>
        public static int MouseButtonIndex(string name)
        {
            string n = name.ToLowerInvariant();
            if (n == "lmb") return 0;
            if (n == "rmb") return 1;
            if (n == "mmb") return 2;
            return -1;
        }
    }

    public class ActionDef
    {
        public string Name;
        public string Kind;    // tap | hold | wheel
        public string Target;  // key name or lmb/rmb/mmb (not used for wheel)
        public int Button = -1;
        public KeyInfo Key;
        public int Wheel;
    }

    public static class ProfileParser
    {
        /// <summary>Lines look like:  engine = tap z   |   horn = hold g   |   zoom_in = wheel 1   (# starts a comment)</summary>
        public static Dictionary<string, ActionDef> Parse(string text, List<string> errors)
        {
            Dictionary<string, ActionDef> result = new Dictionary<string, ActionDef>(StringComparer.OrdinalIgnoreCase);
            string[] lines = text.Replace("\r", "").Split('\n');
            for (int i = 0; i < lines.Length; i++)
            {
                string line = lines[i];
                int hash = line.IndexOf('#');
                if (hash >= 0) line = line.Substring(0, hash);
                line = line.Trim();
                if (line.Length == 0) continue;
                int eq = line.IndexOf('=');
                if (eq <= 0) { errors.Add(String.Format("line {0}: expected 'name = kind target'", i + 1)); continue; }
                string name = line.Substring(0, eq).Trim().ToLowerInvariant();
                string[] parts = line.Substring(eq + 1).Split(new char[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length != 2) { errors.Add(String.Format("line {0}: '{1}' needs exactly: kind target", i + 1, name)); continue; }
                ActionDef a = new ActionDef();
                a.Name = name;
                a.Kind = parts[0].ToLowerInvariant();
                a.Target = parts[1];
                if (a.Kind == "wheel")
                {
                    if (!Int32.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out a.Wheel) || a.Wheel == 0)
                    { errors.Add(String.Format("line {0}: wheel needs a non-zero number", i + 1)); continue; }
                }
                else if (a.Kind == "tap" || a.Kind == "hold")
                {
                    a.Button = KeyTable.MouseButtonIndex(parts[1]);
                    if (a.Button < 0 && !KeyTable.TryGet(parts[1], out a.Key))
                    { errors.Add(String.Format("line {0}: unknown key '{1}'", i + 1, parts[1])); continue; }
                }
                else { errors.Add(String.Format("line {0}: kind must be tap, hold or wheel", i + 1)); continue; }
                result[name] = a;
            }
            return result;
        }
    }

    /// <summary>Turns named actions into key / mouse events. Only actions listed in the profile can ever be triggered.</summary>
    public class Dispatcher
    {
        private readonly IInputSink sink;
        private readonly Dictionary<string, ActionDef> actions;
        private readonly Dictionary<string, ActionDef> held = new Dictionary<string, ActionDef>(StringComparer.OrdinalIgnoreCase);
        private readonly object gate = new object();
        public int TapMilliseconds = 40;

        public Dispatcher(IInputSink sink, Dictionary<string, ActionDef> actions)
        {
            this.sink = sink;
            this.actions = actions;
        }

        public int HeldCount { get { lock (gate) { return held.Count; } } }
        public int ActionCount { get { return actions.Count; } }

        private void Press(ActionDef a, bool down)
        {
            if (a.Button >= 0) sink.MouseButton(a.Button, down);
            else sink.Key(a.Key.Scan, a.Key.Extended, down);
        }

        /// <returns>false when the action name is not in the profile.</returns>
        public bool Down(string name)
        {
            ActionDef a;
            if (!actions.TryGetValue(name, out a)) return false;
            lock (gate)
            {
                if (a.Kind == "wheel") { sink.Wheel(a.Wheel); }
                else if (a.Kind == "tap")
                {
                    Press(a, true);
                    Thread.Sleep(TapMilliseconds);
                    Press(a, false);
                }
                else if (!held.ContainsKey(a.Name))
                {
                    Press(a, true);
                    held[a.Name] = a;
                }
            }
            return true;
        }

        public bool Up(string name)
        {
            ActionDef a;
            if (!actions.TryGetValue(name, out a)) return false;
            lock (gate)
            {
                if (a.Kind == "hold" && held.ContainsKey(a.Name))
                {
                    Press(a, false);
                    held.Remove(a.Name);
                }
            }
            return true;
        }

        public void ReleaseAll()
        {
            lock (gate)
            {
                foreach (ActionDef a in held.Values) Press(a, false);
                held.Clear();
            }
        }
    }

    /// <summary>Phone head angles -> relative mouse movement (what OpenTrack's mouse output does), with fixes:
    /// fractional pixels are carried over (smoother), and nothing is sent while the left button is held
    /// (CPM 2 steers with the mouse), while paused, or while the game window is not in front.</summary>
    public class CameraMixer
    {
        private readonly IInputSink sink;
        public double PxPerDegX = 8.0;
        public double PxPerDegY = 8.0;
        public bool InvertX;
        public bool InvertY;
        public string GameMatch = "";
        public volatile bool Paused;
        public long MovesSent;
        public long PosesSeen;
        private bool havePrev;
        private double prevYaw, prevPitch, accX, accY;
        private long titleCheckedAtMs;
        private bool titleOk = true;

        public CameraMixer(IInputSink sink) { this.sink = sink; }

        private static double Wrap(double d)
        {
            while (d > 180.0) d -= 360.0;
            while (d < -180.0) d += 360.0;
            return d;
        }

        private bool GameFocused()
        {
            if (String.IsNullOrEmpty(GameMatch)) return true;
            long now = Environment.TickCount;
            if (now - titleCheckedAtMs > 250)
            {
                titleCheckedAtMs = now;
                string t = sink.ForegroundTitle();
                titleOk = t != null && t.IndexOf(GameMatch, StringComparison.OrdinalIgnoreCase) >= 0;
            }
            return titleOk;
        }

        public void Reset() { havePrev = false; accX = 0; accY = 0; }

        public void OnPose(double yaw, double pitch)
        {
            PosesSeen++;
            bool inhibit = Paused || sink.LeftButtonDown() || !GameFocused();
            if (!havePrev) { havePrev = true; prevYaw = yaw; prevPitch = pitch; return; }
            double dYaw = Wrap(yaw - prevYaw);
            double dPitch = pitch - prevPitch;
            prevYaw = yaw;
            prevPitch = pitch;
            if (inhibit) { accX = 0; accY = 0; return; } // movement while inhibited is skipped, so nothing jumps afterwards
            if (Math.Abs(dYaw) > 90.0 || Math.Abs(dPitch) > 90.0) return; // sensor glitch
            accX += dYaw * PxPerDegX * (InvertX ? -1.0 : 1.0);
            accY += dPitch * PxPerDegY * (InvertY ? -1.0 : 1.0);
            int mx = (int)Math.Truncate(accX);
            int my = (int)Math.Truncate(accY);
            if (mx != 0 || my != 0)
            {
                accX -= mx;
                accY -= my;
                sink.MouseMove(mx, my);
                MovesSent++;
            }
        }
    }

    public class Hub
    {
        public readonly Dispatcher Dispatcher;
        public readonly CameraMixer Camera;
        public string Pin = "-";
        public string ProfileName = "profile";
        public int ControlPort = 4243;
        public int PosePort = 4242;
        public int StreamPort = 8088;
        public int StreamFps = 15;
        public bool CameraEnabled;

        private readonly ConcurrentQueue<string> logQueue = new ConcurrentQueue<string>();
        private readonly HashSet<string> seen = new HashSet<string>();
        private readonly Queue<string> seenOrder = new Queue<string>();
        private UdpClient control, pose;
        private MjpegServer stream;
        private volatile bool running;
        private long lastPhoneTicks;
        public long PosePackets;

        public Hub(Dispatcher dispatcher, CameraMixer camera)
        {
            Dispatcher = dispatcher;
            Camera = camera;
        }

        public void Log(string s) { logQueue.Enqueue(DateTime.Now.ToString("HH:mm:ss") + "  " + s); }

        public string[] DrainLog()
        {
            List<string> list = new List<string>();
            string s;
            while (logQueue.TryDequeue(out s)) list.Add(s);
            return list.ToArray();
        }

        private bool Seen(string seq)
        {
            if (seen.Contains(seq)) return true;
            seen.Add(seq);
            seenOrder.Enqueue(seq);
            while (seenOrder.Count > 256) seen.Remove(seenOrder.Dequeue());
            return false;
        }

        /// <summary>Handles one control datagram: "MRC1 pin seq CMD args". Returns the reply text or null.</summary>
        public string HandleControlText(string text)
        {
            string[] t = text.Trim().Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (t.Length < 4 || t[0] != "MRC1") return null;
            if (Pin != "-" && t[1] != Pin) return null; // wrong PIN: stay silent
            string seq = t[2];
            string cmd = t[3].ToUpperInvariant();
            Interlocked.Exchange(ref lastPhoneTicks, DateTime.UtcNow.Ticks);

            if (cmd == "PING")
            {
                return String.Format("MRC1 PONG {0} cam={1} paused={2} stream={3} profile={4} held={5}",
                    seq, CameraEnabled ? 1 : 0, Camera.Paused ? 1 : 0, stream != null ? StreamPort : 0, ProfileName, Dispatcher.HeldCount);
            }
            bool duplicate = Seen(seq);
            if (cmd == "ACT" && t.Length >= 6)
            {
                if (duplicate) return "MRC1 ACK " + seq + " " + t[4] + " dup";
                bool down = t[5] == "1";
                bool ok = down ? Dispatcher.Down(t[4]) : Dispatcher.Up(t[4]);
                if (down) Log("action " + t[4] + (ok ? "" : "  (not in profile!)"));
                return "MRC1 ACK " + seq + " " + t[4] + (ok ? " ok" : " unknown");
            }
            if (cmd == "PAUSE" && t.Length >= 5)
            {
                if (!duplicate)
                {
                    Camera.Paused = t[4] == "1";
                    Camera.Reset();
                    Log("camera " + (Camera.Paused ? "paused" : "active"));
                }
                return "MRC1 ACK " + seq + " pause ok";
            }
            if (cmd == "BYE")
            {
                if (!duplicate) { Dispatcher.ReleaseAll(); Log("phone disconnected"); }
                return "MRC1 ACK " + seq + " bye ok";
            }
            return null;
        }

        public void HandlePose(byte[] data)
        {
            if (data.Length != 48) return;
            double yaw = BitConverter.ToDouble(data, 24);
            double pitch = BitConverter.ToDouble(data, 32);
            if (Double.IsNaN(yaw) || Double.IsNaN(pitch) || Double.IsInfinity(yaw) || Double.IsInfinity(pitch)) return;
            PosePackets++;
            Camera.OnPose(yaw, pitch);
        }

        public void Start(IFrameSource frames)
        {
            running = true;
            control = new UdpClient(new IPEndPoint(IPAddress.Any, ControlPort));
            StartThread("mr-control", ControlLoop);
            if (CameraEnabled)
            {
                pose = new UdpClient(new IPEndPoint(IPAddress.Any, PosePort));
                StartThread("mr-pose", PoseLoop);
            }
            if (frames != null)
            {
                stream = new MjpegServer(frames, Log);
                stream.Fps = StreamFps;
                stream.Start(StreamPort);
            }
            StartThread("mr-tick", TickLoop);
        }

        private static void StartThread(string name, ThreadStart body)
        {
            Thread th = new Thread(body);
            th.IsBackground = true;
            th.Name = name;
            th.Start();
        }

        private void ControlLoop()
        {
            IPEndPoint ep = new IPEndPoint(IPAddress.Any, 0);
            while (running)
            {
                byte[] data;
                try { data = control.Receive(ref ep); }
                catch (SocketException) { continue; }          // Windows reports ICMP "port unreachable" this way
                catch (ObjectDisposedException) { break; }
                string reply = null;
                try { reply = HandleControlText(Encoding.ASCII.GetString(data)); }
                catch (Exception e) { Log("control error: " + e.Message); }
                if (reply != null)
                {
                    byte[] r = Encoding.ASCII.GetBytes(reply);
                    try { control.Send(r, r.Length, ep); } catch (Exception) { }
                }
            }
        }

        private void PoseLoop()
        {
            IPEndPoint ep = new IPEndPoint(IPAddress.Any, 0);
            while (running)
            {
                byte[] data;
                try { data = pose.Receive(ref ep); }
                catch (SocketException) { continue; }
                catch (ObjectDisposedException) { break; }
                HandlePose(data);
            }
        }

        private void TickLoop()
        {
            while (running)
            {
                Thread.Sleep(200);
                long last = Interlocked.Read(ref lastPhoneTicks);
                if (Dispatcher.HeldCount > 0 && last != 0 && (DateTime.UtcNow.Ticks - last) > 3L * TimeSpan.TicksPerSecond)
                {
                    Dispatcher.ReleaseAll();
                    Log("no signal from the phone for 3s - released all held keys");
                }
            }
        }

        public bool PhoneRecentlySeen()
        {
            long last = Interlocked.Read(ref lastPhoneTicks);
            return last != 0 && (DateTime.UtcNow.Ticks - last) < 5L * TimeSpan.TicksPerSecond;
        }

        public void Stop()
        {
            running = false;
            Dispatcher.ReleaseAll();
            try { if (control != null) control.Close(); } catch (Exception) { }
            try { if (pose != null) pose.Close(); } catch (Exception) { }
            if (stream != null) stream.Stop();
        }
    }

    /// <summary>Tiny MJPEG-over-HTTP server (raw TcpListener, so no administrator rights / URL ACL needed).
    /// /stream = live video, /snapshot.jpg = one frame, / = a page you can open in any browser to test.</summary>
    public class MjpegServer
    {
        private readonly IFrameSource source;
        private readonly Action<string> log;
        private readonly object gate = new object();
        private readonly object captureLock = new object();
        private TcpListener listener;
        private volatile bool running;
        private byte[] latest;
        private long frameNo;
        private int clients;
        public int Fps = 15;
        public int Port;

        public MjpegServer(IFrameSource source, Action<string> log)
        {
            this.source = source;
            this.log = log;
        }

        public int ClientCount { get { return Thread.VolatileRead(ref clients); } }

        public void Start(int port)
        {
            Port = port;
            listener = new TcpListener(IPAddress.Any, port);
            listener.Start();
            running = true;
            Thread accept = new Thread(AcceptLoop);
            accept.IsBackground = true;
            accept.Name = "mr-stream-accept";
            accept.Start();
            Thread cap = new Thread(CaptureLoop);
            cap.IsBackground = true;
            cap.Name = "mr-stream-capture";
            cap.Start();
        }

        public void Stop()
        {
            running = false;
            try { listener.Stop(); } catch (Exception) { }
            lock (gate) { Monitor.PulseAll(gate); }
        }

        private void AcceptLoop()
        {
            while (running)
            {
                TcpClient c;
                try { c = listener.AcceptTcpClient(); }
                catch (Exception) { if (!running) break; continue; }
                Thread th = new Thread(delegate() { HandleClient(c); });
                th.IsBackground = true;
                th.Name = "mr-stream-client";
                th.Start();
            }
        }

        private void CaptureLoop()
        {
            bool errorLogged = false;
            while (running)
            {
                if (ClientCount == 0) { Thread.Sleep(100); continue; }
                int started = Environment.TickCount;
                try
                {
                    byte[] jpg = CaptureOne();
                    lock (gate) { latest = jpg; frameNo++; Monitor.PulseAll(gate); }
                    errorLogged = false;
                }
                catch (Exception e)
                {
                    if (!errorLogged) { log("screen capture failed: " + e.Message); errorLogged = true; }
                    Thread.Sleep(500);
                }
                int spent = Environment.TickCount - started;
                int wait = 1000 / Math.Max(1, Fps) - spent;
                if (wait > 0) Thread.Sleep(wait);
            }
        }

        private byte[] CaptureOne() { lock (captureLock) { return source.Capture(); } }

        private byte[] WaitFrame(ref long lastNo, int timeoutMs)
        {
            lock (gate)
            {
                if (frameNo == lastNo && running) Monitor.Wait(gate, timeoutMs);
                if (frameNo == lastNo) return null;
                lastNo = frameNo;
                return latest;
            }
        }

        private static string ReadRequestPath(NetworkStream ns)
        {
            byte[] buf = new byte[8192];
            int n = 0;
            while (n < buf.Length)
            {
                int r = ns.Read(buf, n, buf.Length - n);
                if (r <= 0) break;
                n += r;
                string s = Encoding.ASCII.GetString(buf, 0, n);
                if (s.IndexOf("\r\n\r\n", StringComparison.Ordinal) >= 0 || s.IndexOf("\n\n", StringComparison.Ordinal) >= 0) break;
            }
            string head = Encoding.ASCII.GetString(buf, 0, n);
            string[] first = head.Split('\n')[0].Trim().Split(' ');
            if (first.Length < 2 || first[0] != "GET") return null;
            string path = first[1];
            int q = path.IndexOf('?');
            return q >= 0 ? path.Substring(0, q) : path;
        }

        private static void WriteAll(NetworkStream ns, string text)
        {
            byte[] b = Encoding.ASCII.GetBytes(text);
            ns.Write(b, 0, b.Length);
        }

        private void HandleClient(TcpClient c)
        {
            bool counted = false;
            try
            {
                c.NoDelay = true;
                c.SendTimeout = 4000;
                c.ReceiveTimeout = 4000;
                NetworkStream ns = c.GetStream();
                string path = ReadRequestPath(ns);
                if (path == null) return;
                if (path == "/" || path == "/index.html")
                {
                    string html = "<html><head><meta name=viewport content='width=device-width,initial-scale=1'></head>"
                        + "<body style='margin:0;background:#000'><img src='/stream' style='width:100%;height:100vh;object-fit:contain'></body></html>";
                    WriteAll(ns, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + html.Length + "\r\nConnection: close\r\n\r\n" + html);
                }
                else if (path == "/snapshot.jpg")
                {
                    byte[] jpg = CaptureOne();
                    WriteAll(ns, "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpg.Length + "\r\nConnection: close\r\n\r\n");
                    ns.Write(jpg, 0, jpg.Length);
                }
                else if (path == "/stream" || path == "/stream.mjpg")
                {
                    Interlocked.Increment(ref clients);
                    counted = true;
                    log("live view connected");
                    WriteAll(ns, "HTTP/1.1 200 OK\r\nCache-Control: no-cache\r\nConnection: close\r\n"
                        + "Content-Type: multipart/x-mixed-replace; boundary=mrframe\r\n\r\n");
                    long last = 0;
                    while (running)
                    {
                        byte[] f = WaitFrame(ref last, 2000);
                        if (f == null) continue;
                        WriteAll(ns, "--mrframe\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.Length + "\r\n\r\n");
                        ns.Write(f, 0, f.Length);
                        WriteAll(ns, "\r\n");
                    }
                }
                else
                {
                    WriteAll(ns, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                }
            }
            catch (Exception) { }
            finally
            {
                if (counted) { Interlocked.Decrement(ref clients); log("live view disconnected"); }
                try { c.Close(); } catch (Exception) { }
            }
        }
    }
}
