package dev.anima;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The shell: what the person sees. Left, the agent's own display, live (touch it to take over).
 * Right, what the agent is doing. Below, where you tell it what to do, and where it asks before risky steps.
 */
public class MainActivity extends Activity implements AnimaService.Listener {
    static final int BG = 0xFF101114, PANEL = 0xFF1A1C21, LINE = 0xFF2A2D34, FG = 0xFFECEDEF,
            DIM = 0xFF8B8F98, ACCENT = 0xFF7FB8A4, WARN = 0xFFE2B540, BAD = 0xFFE06C6C, BLUE = 0xFF8FB4D0;

    private Mirror mirror;
    private LinearLayout logList;
    private ScrollView logScroll;
    private TextView statePill, displayLabel;
    private EditText input;
    private LinearLayout confirmCard;
    private TextView confirmText;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        startForegroundService(new Intent(this, AnimaService.class));
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        attach();
    }

    @Override protected void onPause() {
        AnimaService s = AnimaService.self;
        if (s != null && s.listener == this) s.listener = null;
        super.onPause();
    }

    /** The service may still be starting; try again shortly. */
    private void attach() {
        AnimaService s = AnimaService.self;
        if (s == null) { mirror.postDelayed(this::attach, 200); return; }
        s.listener = this;
        logList.removeAllViews();
        for (AnimaService.Entry e : s.history()) onLog(e);
        onState(s.state);
        displayLabel.setText("Agent display · #" + s.displayId());
        Bitmap f = s.frame();
        if (f != null) onFrame(f);
    }

    // MARK: Layout

    private View build() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(dp(12) + i.left, dp(8) + i.top, dp(12) + i.right, dp(10) + i.bottom);
            return WindowInsets.CONSUMED;
        });

        // Header
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Anima", 22, FG);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        statePill = text("Idle", 13, DIM);
        statePill.setPadding(dp(10), dp(4), dp(10), dp(4));
        statePill.setBackground(round(PANEL, 99, LINE));
        head.addView(statePill);
        Button stop = button("Stop", BAD);
        stop.setOnClickListener(v -> { AnimaService s = AnimaService.self; if (s != null) s.stop(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(34));
        sp.leftMargin = dp(8);
        head.addView(stop, sp);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // Body: agent display | activity
        LinearLayout body = row();
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1);
        bp.topMargin = dp(10);

        LinearLayout left = column();
        displayLabel = text("Agent display", 12, DIM);
        left.addView(displayLabel);
        mirror = new Mirror(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, 0, 1);
        mp.topMargin = dp(6);
        left.addView(mirror, mp);
        TextView hint = text("Touch to take over", 11, DIM);
        hint.setPadding(0, dp(6), 0, 0);
        left.addView(hint);
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 0.46f));

        LinearLayout right = column();
        right.addView(text("Activity", 12, DIM));
        logScroll = new ScrollView(this);
        logScroll.setBackground(round(PANEL, 14, LINE));
        logList = column();
        logList.setPadding(dp(10), dp(8), dp(10), dp(8));
        logScroll.addView(logList);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.topMargin = dp(6);
        right.addView(logScroll, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 0.54f);
        rp.leftMargin = dp(10);
        body.addView(right, rp);
        root.addView(body, bp);

        // Confirmation, shown only while the agent waits for you
        confirmCard = column();
        confirmCard.setBackground(round(0xFF2B2616, 14, WARN));
        confirmCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        confirmCard.setVisibility(View.GONE);
        TextView ct = text("Anima needs your OK", 12, WARN);
        confirmCard.addView(ct);
        confirmText = text("", 15, FG);
        confirmText.setPadding(0, dp(4), 0, dp(8));
        confirmCard.addView(confirmText);
        LinearLayout cb = row();
        Button deny = button("Don't", FG);
        Button allow = button("Allow", BG);
        allow.setBackground(round(WARN, 10, WARN));
        deny.setOnClickListener(v -> answer(false));
        allow.setOnClickListener(v -> answer(true));
        cb.addView(deny, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(40), 1);
        ap.leftMargin = dp(8);
        cb.addView(allow, ap);
        confirmCard.addView(cb);
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(-1, -2);
        ccp.topMargin = dp(10);
        root.addView(confirmCard, ccp);

        // Example tasks
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        String[] examples = {
                "Add a contact Ada Lovelace, phone 5551234",
                "Set an alarm for 7:30",
                "Turn on dark theme in Settings",
                "Find the Android version in Settings",
        };
        for (String ex : examples) {
            TextView chip = text(ex, 13, FG);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setBackground(round(PANEL, 99, LINE));
            chip.setOnClickListener(v -> input.setText(ex));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.rightMargin = dp(6);
            chips.addView(chip, p);
        }
        chipsScroll.addView(chips);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(-1, -2);
        chp.topMargin = dp(10);
        root.addView(chipsScroll, chp);

        // Input
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Tell Anima what to do");
        input.setHintTextColor(DIM);
        input.setTextColor(FG);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(round(PANEL, 12, LINE));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { send(); return true; }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button go = button("Run", BG);
        go.setBackground(round(ACCENT, 12, ACCENT));
        go.setOnClickListener(v -> send());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(72), dp(46));
        gp.leftMargin = dp(8);
        bar.addView(go, gp);
        LinearLayout.LayoutParams barp = new LinearLayout.LayoutParams(-1, -2);
        barp.topMargin = dp(8);
        root.addView(bar, barp);
        return root;
    }

    private void send() {
        String t = input.getText().toString().trim();
        AnimaService s = AnimaService.self;
        if (t.isEmpty() || s == null) return;
        s.submit(t);
        input.setText("");
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void answer(boolean yes) {
        confirmCard.setVisibility(View.GONE);
        AnimaService s = AnimaService.self;
        if (s != null) s.answer(yes);
    }

    // MARK: Listener

    @Override public void onFrame(Bitmap b) { mirror.set(b); }

    @Override public void onLog(AnimaService.Entry e) {
        LinearLayout r = row();
        r.setPadding(0, dp(5), 0, dp(5));
        int color;
        String tag;
        switch (e.kind) {
            case "task": color = ACCENT; tag = "You"; break;
            case "think": color = DIM; tag = "Plan"; break;
            case "act": color = FG; tag = "Do"; break;
            case "ask": case "policy": color = WARN; tag = "Ask"; break;
            case "done": color = ACCENT; tag = "Done"; break;
            case "error": color = BAD; tag = "Error"; break;
            case "install": color = BLUE; tag = "App"; break;
            default: color = DIM; tag = "Sys"; break;
        }
        TextView k = text(tag, 11, color);
        k.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        r.addView(k, new LinearLayout.LayoutParams(dp(38), -2));
        TextView t = text(e.text, 13, e.kind.equals("think") || e.kind.equals("system") ? DIM : FG);
        t.setLineSpacing(0, 1.15f);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        logList.addView(r);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override public void onState(String state) {
        switch (state) {
            case "working": statePill.setText("Working"); statePill.setTextColor(ACCENT); break;
            case "waiting": statePill.setText("Needs you"); statePill.setTextColor(WARN); break;
            default: statePill.setText("Idle"); statePill.setTextColor(DIM); break;
        }
    }

    @Override public void onConfirm(String question) {
        confirmText.setText(question);
        confirmCard.setVisibility(View.VISIBLE);
    }

    // MARK: The live agent display; touches go straight to it

    static final class Mirror extends View {
        private Bitmap bmp;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private final Path clip = new Path();
        private long downTime;

        Mirror(Context c) {
            super(c);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.5f);
            frame.setColor(LINE);
        }

        void set(Bitmap b) { bmp = b; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float aw = AnimaService.W, ah = AnimaService.H;
            float s = Math.min(getWidth() / aw, getHeight() / ah);
            float w = aw * s, h = ah * s, x = (getWidth() - w) / 2, y = 0;
            dst.set(x, y, x + w, y + h);
            float r = 14 * getResources().getDisplayMetrics().density;
            clip.reset();
            clip.addRoundRect(dst, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawColor(PANEL);
            if (bmp != null) c.drawBitmap(bmp, null, dst, paint);
            c.restore();
            c.drawRoundRect(dst, r, r, frame);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            AnimaService s = AnimaService.self;
            if (s == null || dst.width() == 0) return false;
            int a = e.getActionMasked();
            if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_MOVE && a != MotionEvent.ACTION_UP) return true;
            int x = (int) ((e.getX() - dst.left) / dst.width() * AnimaService.W);
            int y = (int) ((e.getY() - dst.top) / dst.height() * AnimaService.H);
            if (a == MotionEvent.ACTION_DOWN) downTime = android.os.SystemClock.uptimeMillis();
            final long dt = downTime;
            new Thread(() -> s.touch(a, dt, x, y)).start();
            return true;
        }
    }

    // MARK: Small helpers

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(color);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setStateListAnimator(null);
        b.setBackground(round(PANEL, 10, LINE));
        return b;
    }

    private GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    @SuppressWarnings("unused") private static ViewGroup.LayoutParams wrap() { return new ViewGroup.LayoutParams(-2, -2); }
}
