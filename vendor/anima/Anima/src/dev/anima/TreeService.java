package dev.anima;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Reads the UI tree of the agent display, and sets text in its fields. */
public class TreeService extends AccessibilityService {
    static volatile TreeService self;

    /** One element the agent can refer to by index. */
    static final class Element {
        final int index;
        final String role, text, id;
        final Rect bounds;
        final boolean clickable, editable, scrollable, focused;
        final AccessibilityNodeInfo node;

        Element(int index, AccessibilityNodeInfo n) {
            this.index = index;
            this.node = n;
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            this.role = cls.substring(cls.lastIndexOf('.') + 1);
            CharSequence t = n.getText() != null && n.getText().length() > 0 ? n.getText() : n.getContentDescription();
            String s = t == null ? "" : t.toString().replace('\n', ' ');
            this.text = s.length() > 80 ? s.substring(0, 80) + "…" : s;
            String vid = n.getViewIdResourceName();
            this.id = vid == null ? "" : vid.substring(vid.indexOf('/') + 1);
            this.bounds = new Rect();
            n.getBoundsInScreen(bounds);
            this.clickable = n.isClickable() || n.isLongClickable();
            this.editable = n.isEditable();
            this.scrollable = n.isScrollable();
            this.focused = n.isFocused();
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("i", index).put("role", role).put("text", text).put("id", id)
                        .put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom));
                if (clickable) o.put("click", true);
                if (editable) o.put("edit", true);
                if (scrollable) o.put("scroll", true);
                if (focused) o.put("focused", true);
            } catch (Exception ignored) { }
            return o;
        }
    }

    private volatile List<Element> last = new ArrayList<>();
    private volatile String topPackage = "";

    @Override protected void onServiceConnected() { self = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { self = null; super.onDestroy(); }

    /** The elements on one display worth showing to an agent: visible, and either actionable or carrying text. */
    synchronized List<Element> read(int displayId) {
        List<Element> out = new ArrayList<>();
        SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> windows = all.get(displayId);
        String top = "";
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                if (top.isEmpty() && root.getPackageName() != null) top = root.getPackageName().toString();
                walk(root, out);
            }
        }
        topPackage = top;
        last = out;
        return out;
    }

    String topPackage() { return topPackage; }

    private void walk(AccessibilityNodeInfo n, List<Element> out) {
        if (n == null || !n.isVisibleToUser()) return;
        boolean hasText = (n.getText() != null && n.getText().length() > 0)
                || (n.getContentDescription() != null && n.getContentDescription().length() > 0);
        if (n.isClickable() || n.isEditable() || n.isScrollable() || n.isCheckable() || hasText) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 2 && r.height() > 2) out.add(new Element(out.size(), n));
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out);
    }

    Element element(int i) {
        List<Element> l = last;
        return i >= 0 && i < l.size() ? l.get(i) : null;
    }

    /** Puts text into the focused (or given) field on the display. */
    boolean setText(int displayId, int index, String text) {
        AccessibilityNodeInfo target = null;
        if (index >= 0) {
            Element e = element(index);
            if (e != null) target = e.node;
        }
        if (target == null) {
            for (Element e : read(displayId)) if (e.editable && e.focused) { target = e.node; break; }
        }
        if (target == null) {
            for (Element e : last) if (e.editable) { target = e.node; break; }
        }
        if (target == null) return false;
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }
}
