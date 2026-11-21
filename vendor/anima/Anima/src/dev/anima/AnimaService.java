package dev.anima;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The OS-side agent runtime for Stage 0: owns the agent display, observes it, acts on it,
 * installs packages, enforces the confirmation policy, and serves the Anima API.
 */
public class AnimaService extends Service {
    static final String TAG = "Anima";
    static volatile AnimaService self;

    static final int W = 720, H = 1600, DPI = 320;
    // DisplayManager flags; the hidden ones are spelled out.
    static final int FLAG_PUBLIC = 1, FLAG_OWN_CONTENT_ONLY = 8, FLAG_SUPPORTS_TOUCH = 64,
            FLAG_DESTROY_CONTENT_ON_REMOVAL = 256, FLAG_TRUSTED = 1024, FLAG_ALWAYS_UNLOCKED = 4096,
            FLAG_OWN_FOCUS = 16384;

    /** What the shell shows. */
    interface Listener {
        void onFrame(Bitmap b);
        void onLog(Entry e);
        void onState(String state);
        void onConfirm(String question);
    }

    static final class Entry {
        final String kind, text;
        final long at = System.currentTimeMillis();
        Entry(String kind, String text) { this.kind = kind; this.text = text; }
    }

    volatile Listener listener;
    final List<Entry> log = new CopyOnWriteArrayList<>();
    final LinkedBlockingQueue<String> tasks = new LinkedBlockingQueue<>();
    volatile String state = "idle";
    volatile boolean stopRequested;

    private VirtualDisplay display;
    private ImageReader reader;
    private volatile Bitmap frame;
    private long lastFrameAt;
    private Handler frames;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile CountDownLatch confirmLatch;
    private volatile boolean confirmAnswer;
    private ApiServer server;

    // MARK: Lifecycle

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        self = this;
        foreground();
        enableTreeService();
        HandlerThread t = new HandlerThread("anima-frames");
        t.start();
        frames = new Handler(t.getLooper());
        createDisplay();
        server = new ApiServer(this, 7878);
        server.start();
        log("system", "Agent display " + displayId() + " ready (" + W + "×" + H + "). API on :7878.");
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        if (server != null) server.close();
        if (display != null) display.release();
        self = null;
        super.onDestroy();
    }

    private void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("anima", "Anima", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "anima")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Anima is running")
                .setContentText("Agent display and API are up")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
        startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    /** Turns on our own tree reader; we hold WRITE_SECURE_SETTINGS as a system component. */
    private void enableTreeService() {
        String me = getPackageName() + "/" + TreeService.class.getName();
        String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur == null || !cur.contains(me)) {
            String next = cur == null || cur.isEmpty() ? me : cur + ":" + me;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
        }
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    // MARK: Agent display

    private void createDisplay() {
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            try {
                long now = SystemClock.uptimeMillis();
                if (now - lastFrameAt < 66) return;  // about 15 fps is enough to watch
                lastFrameAt = now;
                Image.Plane p = img.getPlanes()[0];
                ByteBuffer buf = p.getBuffer();
                int rowPad = p.getRowStride() - p.getPixelStride() * W;
                Bitmap b = Bitmap.createBitmap(W + rowPad / p.getPixelStride(), H, Bitmap.Config.ARGB_8888);
                b.copyPixelsFromBuffer(buf);
                if (rowPad > 0) b = Bitmap.createBitmap(b, 0, 0, W, H);
                frame = b;
                Listener l = listener;
                if (l != null) { final Bitmap fb = b; main.post(() -> l.onFrame(fb)); }
            } finally {
                img.close();
            }
        }, frames);
        int flags = FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH | FLAG_DESTROY_CONTENT_ON_REMOVAL
                | FLAG_TRUSTED | FLAG_ALWAYS_UNLOCKED | FLAG_OWN_FOCUS;
        DisplayManager dm = getSystemService(DisplayManager.class);
        display = dm.createVirtualDisplay("anima-agent", W, H, DPI, reader.getSurface(), flags);
    }

    int displayId() { return display == null ? -1 : display.getDisplay().getDisplayId(); }
    Bitmap frame() { return frame; }

    // MARK: Observe

    JSONObject observe(boolean withImage) throws Exception {
        JSONObject o = new JSONObject();
        o.put("display", displayId()).put("width", W).put("height", H);
        JSONArray els = new JSONArray();
        TreeService t = TreeService.self;
        if (t != null) {
            for (TreeService.Element e : t.read(displayId())) els.put(e.json());
            o.put("app", t.topPackage());
        } else {
            o.put("app", "").put("tree_unavailable", true);
        }
        o.put("elements", els);
        if (withImage) {
            Bitmap b = frame;
            if (b != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 80, bo);
                o.put("jpeg", android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP));
            }
        }
        return o;
    }

    JSONArray apps() throws Exception {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        JSONArray out = new JSONArray();
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            out.put(new JSONObject().put("package", pkg).put("label", r.loadLabel(pm).toString()));
        }
        return out;
    }

    // MARK: Act

    /** Runs one action. The policy check comes first and the agent cannot skip it. */
    String act(JSONObject a) throws Exception {
        if (stopRequested) return "stopped by user";
        String type = a.optString("type");
        String why = Policy.needsConfirmation(a, this);
        if (why != null && !confirm(why)) {
            log("policy", "Denied: " + why);
            return "denied by user";
        }
        switch (type) {
            case "tap": {
                int[] p = point(a);
                if (p == null) return "error: no such element";
                tap(p[0], p[1]);
                log("act", "Tap " + describe(a) + " at (" + p[0] + ", " + p[1] + ")");
                return "ok";
            }
            case "long_press": {
                int[] p = point(a);
                if (p == null) return "error: no such element";
                press(p[0], p[1], 700);
                log("act", "Long press " + describe(a));
                return "ok";
            }
            case "type": {
                String text = a.optString("text");
                TreeService t = TreeService.self;
                boolean ok = t != null && t.setText(displayId(), a.optInt("element", -1), text);
                if (!ok) ok = typeKeys(text);
                log("act", "Type \"" + text + "\"" + (ok ? "" : " (failed)"));
                if (ok && a.optBoolean("submit")) key(KeyEvent.KEYCODE_ENTER);
                return ok ? "ok" : "error: no text field";
            }
            case "swipe": {
                String d = a.optString("direction", "up");
                int cx = W / 2, cy = H / 2, dx = 0, dy = 0;
                switch (d) {
                    case "up": dy = -H / 3; break;      // content moves up: scroll down
                    case "down": dy = H / 3; break;
                    case "left": dx = -W / 3; break;
                    case "right": dx = W / 3; break;
                }
                swipe(cx - dx / 2, cy - dy / 2, cx + dx / 2, cy + dy / 2, 300);
                log("act", "Swipe " + d);
                return "ok";
            }
            case "back": key(KeyEvent.KEYCODE_BACK); log("act", "Back"); return "ok";
            case "enter": key(KeyEvent.KEYCODE_ENTER); log("act", "Enter"); return "ok";
            case "launch": {
                String pkg = a.optString("package");
                boolean ok = launch(pkg);
                log("act", "Open " + label(pkg) + (ok ? "" : " (failed)"));
                return ok ? "ok" : "error: cannot launch " + pkg;
            }
            case "wait": SystemClock.sleep(Math.min(a.optInt("ms", 1000), 5000)); return "ok";
            default: return "error: unknown action " + type;
        }
    }

    private String describe(JSONObject a) {
        if (a.has("element")) {
            TreeService t = TreeService.self;
            TreeService.Element e = t == null ? null : t.element(a.optInt("element"));
            if (e != null) return e.text.isEmpty() ? e.role + " #" + e.index : "\"" + e.text + "\"";
        }
        return "";
    }

    /** An element's center, or explicit coordinates on the agent display. */
    int[] point(JSONObject a) {
        if (a.has("element")) {
            TreeService t = TreeService.self;
            TreeService.Element e = t == null ? null : t.element(a.optInt("element"));
            if (e == null) return null;
            Rect r = e.bounds;
            return new int[]{r.centerX(), r.centerY()};
        }
        if (a.has("x") && a.has("y")) return new int[]{a.optInt("x"), a.optInt("y")};
        return null;
    }

    String label(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) { return pkg; }
    }

    boolean launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchDisplayId(displayId());
        startActivity(i, o.toBundle());
        SystemClock.sleep(800);
        return true;
    }

    // MARK: Input injection (INJECT_EVENTS)

    void tap(int x, int y) { press(x, y, 60); }

    void press(int x, int y, long ms) {
        long down = SystemClock.uptimeMillis();
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x, y));
        SystemClock.sleep(ms);
        inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y));
    }

    void swipe(int x1, int y1, int x2, int y2, long ms) {
        long down = SystemClock.uptimeMillis();
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x1, y1));
        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            SystemClock.sleep(ms / steps);
            inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps));
        }
        inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2));
    }

    /** A raw touch from the shell (the user taking over); coordinates on the agent display. */
    void touch(int action, long downTime, int x, int y) {
        inject(motion(downTime, SystemClock.uptimeMillis(), action, x, y));
    }

    void key(int code) {
        long t = SystemClock.uptimeMillis();
        inject(keyEvent(t, KeyEvent.ACTION_DOWN, code));
        inject(keyEvent(t, KeyEvent.ACTION_UP, code));
    }

    private boolean typeKeys(String text) {
        KeyEvent[] evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (evs == null) return false;
        for (KeyEvent e : evs) inject(withDisplay(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0)));
        return true;
    }

    private MotionEvent motion(long down, long t, int action, int x, int y) {
        MotionEvent e = MotionEvent.obtain(down, t, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return withDisplay(e);
    }

    private KeyEvent keyEvent(long t, int action, int code) {
        KeyEvent e = new KeyEvent(t, t, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        return withDisplay(e);
    }

    private <T extends InputEvent> T withDisplay(T e) {
        try {
            Method m = InputEvent.class.getMethod("setDisplayId", int.class);
            m.invoke(e, displayId());
        } catch (Exception ex) { Log.w(TAG, "setDisplayId", ex); }
        return e;
    }

    private void inject(InputEvent e) {
        try {
            InputManager im = getSystemService(InputManager.class);
            Method m = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            m.invoke(im, e, 2 /* WAIT_FOR_FINISH */);
        } catch (Exception ex) { Log.w(TAG, "inject", ex); }
    }

    // MARK: Install (INSTALL_PACKAGES): generated apps become ordinary, first-class packages

    String install(byte[] apk) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try (OutputStream out = s.openWrite("app.apk", 0, apk.length)) {
            out.write(apk);
            s.fsync(out);
        }
        final String action = "dev.anima.INSTALLED." + id;
        final CountDownLatch done = new CountDownLatch(1);
        final String[] result = {"timeout"};
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
                String pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                result[0] = st == PackageInstaller.STATUS_SUCCESS ? "ok " + pkg
                        : "error " + st + ": " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                done.countDown();
            }
        };
        registerReceiver(r, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        Intent cb = new Intent(action).setPackage(getPackageName());
        s.commit(PendingIntent.getBroadcast(this, id, cb, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
        done.await(60, TimeUnit.SECONDS);
        unregisterReceiver(r);
        log("install", result[0].startsWith("ok") ? "Installed " + result[0].substring(3) : "Install failed: " + result[0]);
        return result[0];
    }

    // MARK: Tasks, log, confirmation

    void submit(String task) {
        stopRequested = false;
        tasks.offer(task);
        log("task", task);
    }

    void stop() {
        stopRequested = true;
        tasks.clear();
        log("system", "Stopped. The agent will not act again until a new task.");
        setState("idle");
    }

    void log(String kind, String text) {
        Entry e = new Entry(kind, text);
        log.add(e);
        Log.i(TAG, kind + ": " + text);
        Listener l = listener;
        if (l != null) main.post(() -> l.onLog(e));
    }

    void setState(String s) {
        state = s;
        Listener l = listener;
        if (l != null) main.post(() -> l.onState(s));
    }

    /** Asks the person on the shell; blocks the agent until they answer. */
    boolean confirm(String question) throws InterruptedException {
        confirmLatch = new CountDownLatch(1);
        confirmAnswer = false;
        String prev = state;
        setState("waiting");
        log("ask", question);
        Listener l = listener;
        if (l == null) {
            // No one to ask: the safe answer is no.
            setState(prev);
            return false;
        }
        main.post(() -> l.onConfirm(question));
        boolean answered = confirmLatch.await(120, TimeUnit.SECONDS);
        setState(prev);
        return answered && confirmAnswer;
    }

    void answer(boolean yes) {
        confirmAnswer = yes;
        CountDownLatch c = confirmLatch;
        if (c != null) c.countDown();
        log("policy", yes ? "Allowed by user" : "Denied by user");
    }

    /** The confirmation policy. It lives below the API, so an agent cannot opt out of it. */
    static final class Policy {
        private static final Pattern RISKY = Pattern.compile(
                "(?i)\\b(pay|buy|purchase|order|checkout|send|delete|remove|erase|reset|transfer|uninstall|sign out|log out)\\b"
                        + "|支付|付款|购买|下单|发送|删除|移除|清除|重置|转账|卸载|退出登录");

        static String needsConfirmation(JSONObject a, AnimaService s) {
            if (a.optBoolean("sensitive")) return a.optString("reason", "The agent marked this step as sensitive.");
            String type = a.optString("type");
            if ((type.equals("tap") || type.equals("long_press")) && a.has("element")) {
                TreeService t = TreeService.self;
                TreeService.Element e = t == null ? null : t.element(a.optInt("element"));
                if (e != null && RISKY.matcher(e.text).find())
                    return String.format(Locale.US, "Tap \"%s\" in %s?", e.text, s.label(t.topPackage()));
            }
            return null;
        }
    }

    List<Entry> history() { return new ArrayList<>(log); }
}
