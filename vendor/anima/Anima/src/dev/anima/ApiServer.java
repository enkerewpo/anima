package dev.anima;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The Anima API over plain HTTP on localhost (reach it with `adb forward tcp:7878 tcp:7878`).
 * Any agent, from a script to a GUI-agent model, drives the phone through these calls.
 *
 *   GET  /observe[?image=0]   elements on the agent display, foreground app, and a JPEG frame
 *   GET  /apps                launchable apps
 *   POST /act                 {"type": tap|long_press|type|swipe|back|enter|launch|wait, ...}
 *   GET  /task                waits up to 20 s for the next task typed on the phone
 *   POST /submit              {"task": "..."}: queue a task as if typed on the phone
 *   POST /log                 {"kind": "think"|"done"|..., "text": "..."}
 *   POST /state               {"state": "working"|"idle"}
 *   POST /install             raw APK bytes: install as an ordinary app, no prompt
 *   GET  /status              {"state", "stopped"}
 */
final class ApiServer extends Thread {
    private final AnimaService s;
    private final int port;
    private ServerSocket socket;

    ApiServer(AnimaService s, int port) {
        super("anima-api");
        this.s = s;
        this.port = port;
        setDaemon(true);
    }

    void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    @Override public void run() {
        try {
            socket = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
            while (!socket.isClosed()) {
                final Socket c = socket.accept();
                new Thread(() -> serve(c), "anima-req").start();
            }
        } catch (IOException e) {
            Log.w(AnimaService.TAG, "api", e);
        }
    }

    private void serve(Socket c) {
        try (Socket sock = c) {
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            String method = parts[0], target = parts.length > 1 ? parts[1] : "/";
            int len = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int k = h.indexOf(':');
                if (k > 0 && h.substring(0, k).trim().equalsIgnoreCase("content-length"))
                    len = Integer.parseInt(h.substring(k + 1).trim());
            }
            byte[] body = new byte[len];
            int got = 0;
            while (got < len) {
                int n = in.read(body, got, len - got);
                if (n < 0) break;
                got += n;
            }
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
            Object out;
            try {
                out = route(method, path, query, body);
            } catch (Exception e) {
                out = new JSONObject().put("error", e.toString());
            }
            byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream o = sock.getOutputStream();
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.write(bytes);
            o.flush();
        } catch (Exception e) {
            Log.w(AnimaService.TAG, "request", e);
        }
    }

    private Object route(String method, String path, String query, byte[] body) throws Exception {
        JSONObject in = body.length > 0 && !path.equals("/install")
                ? new JSONObject(new String(body, StandardCharsets.UTF_8)) : new JSONObject();
        switch (path) {
            case "/observe": return s.observe(!query.contains("image=0"));
            case "/apps": return s.apps();
            case "/act": return new JSONObject().put("result", s.act(in));
            case "/task": {
                String t = s.tasks.poll(20, TimeUnit.SECONDS);
                return t == null ? new JSONObject() : new JSONObject().put("task", t);
            }
            case "/submit": s.submit(in.getString("task")); return new JSONObject().put("ok", true);
            case "/log": s.log(in.optString("kind", "think"), in.optString("text")); return new JSONObject().put("ok", true);
            case "/state": s.setState(in.optString("state", "idle")); return new JSONObject().put("ok", true);
            case "/install": return new JSONObject().put("result", s.install(body));
            case "/status": return new JSONObject().put("state", s.state).put("stopped", s.stopRequested);
            default: return new JSONObject().put("error", "no such endpoint " + method + " " + path);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
        }
        if (ch < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }
}
