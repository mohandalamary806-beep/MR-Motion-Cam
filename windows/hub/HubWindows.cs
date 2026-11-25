// MR Motion Cam - Windows-only part of the PC Hub: real key/mouse injection and screen capture.
// Conservative C# 5 (Windows PowerShell 5.1 compiles it with Add-Type).
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace MrHub
{
    public class WinInput : IInputSink
    {
        private const uint INPUT_MOUSE = 0;
        private const uint INPUT_KEYBOARD = 1;
        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_SCANCODE = 0x0008;
        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        private const uint MOUSEEVENTF_WHEEL = 0x0800;

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Explicit)]
        private struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT { public uint type; public InputUnion u; }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
        [DllImport("user32.dll")]
        private static extern short GetAsyncKeyState(int vKey);
        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int maxCount);
        [DllImport("user32.dll")]
        public static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")]
        public static extern int GetSystemMetrics(int index);

        private static void Send(INPUT input)
        {
            INPUT[] arr = new INPUT[] { input };
            SendInput(1, arr, Marshal.SizeOf(typeof(INPUT)));
        }

        public void Key(int scan, bool extended, bool down)
        {
            INPUT i = new INPUT();
            i.type = INPUT_KEYBOARD;
            i.u.ki.wScan = (ushort)scan;
            i.u.ki.dwFlags = KEYEVENTF_SCANCODE | (down ? 0u : KEYEVENTF_KEYUP) | (extended ? KEYEVENTF_EXTENDEDKEY : 0u);
            Send(i);
        }

        public void MouseMove(int dx, int dy)
        {
            INPUT i = new INPUT();
            i.type = INPUT_MOUSE;
            i.u.mi.dx = dx;
            i.u.mi.dy = dy;
            i.u.mi.dwFlags = MOUSEEVENTF_MOVE;
            Send(i);
        }

        public void MouseButton(int button, bool down)
        {
            INPUT i = new INPUT();
            i.type = INPUT_MOUSE;
            if (button == 0) i.u.mi.dwFlags = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
            else if (button == 1) i.u.mi.dwFlags = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
            else i.u.mi.dwFlags = down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
            Send(i);
        }

        public void Wheel(int notches)
        {
            INPUT i = new INPUT();
            i.type = INPUT_MOUSE;
            i.u.mi.dwFlags = MOUSEEVENTF_WHEEL;
            i.u.mi.mouseData = unchecked((uint)(notches * 120));
            Send(i);
        }

        public bool LeftButtonDown() { return (GetAsyncKeyState(0x01) & 0x8000) != 0; }

        public string ForegroundTitle()
        {
            IntPtr h = GetForegroundWindow();
            if (h == IntPtr.Zero) return "";
            StringBuilder sb = new StringBuilder(256);
            GetWindowText(h, sb, sb.Capacity);
            return sb.ToString();
        }
    }

    /// <summary>Grabs the primary screen, scales it down and returns a JPEG. Works for windowed / borderless games;
    /// exclusive-fullscreen games may show up black with this method.</summary>
    public class ScreenFrameSource : IFrameSource
    {
        private readonly int targetWidth;
        private readonly ImageCodecInfo jpegCodec;
        private readonly EncoderParameters encoderParams;

        public ScreenFrameSource(int targetWidth, int quality)
        {
            this.targetWidth = targetWidth;
            foreach (ImageCodecInfo c in ImageCodecInfo.GetImageEncoders())
            {
                if (c.MimeType == "image/jpeg") { jpegCodec = c; break; }
            }
            encoderParams = new EncoderParameters(1);
            encoderParams.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)quality);
        }

        public byte[] Capture()
        {
            int sw = WinInput.GetSystemMetrics(0);
            int sh = WinInput.GetSystemMetrics(1);
            int tw = Math.Min(targetWidth, sw);
            int th = Math.Max(1, sh * tw / sw);
            using (Bitmap full = new Bitmap(sw, sh, PixelFormat.Format24bppRgb))
            {
                using (Graphics g = Graphics.FromImage(full))
                {
                    g.CopyFromScreen(0, 0, 0, 0, new Size(sw, sh), CopyPixelOperation.SourceCopy);
                }
                using (Bitmap small = new Bitmap(tw, th, PixelFormat.Format24bppRgb))
                {
                    using (Graphics g2 = Graphics.FromImage(small))
                    {
                        g2.InterpolationMode = InterpolationMode.Low;
                        g2.DrawImage(full, 0, 0, tw, th);
                    }
                    using (MemoryStream ms = new MemoryStream())
                    {
                        small.Save(ms, jpegCodec, encoderParams);
                        return ms.ToArray();
                    }
                }
            }
        }
    }
}
