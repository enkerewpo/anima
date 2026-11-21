#!/usr/bin/env python3
"""Reference agent for Anima, Stage 0.

Waits for a task typed on the phone, then loops: observe the agent display through the
Anima API, ask a model for one action, act, repeat. The model never touches the phone
directly; every action goes through the API and its policy layer.

Environment:
  ANIMA_API_KEY    model API key (required); put these in the repo's git-ignored .env
  ANIMA_API_STYLE  anthropic (default) or openai (Chat Completions, e.g. DeepSeek)
  ANIMA_BASE_URL   default https://api.anthropic.com
  ANIMA_MODEL      default claude-sonnet-5-5, or deepseek-flash with the openai style
  ANIMA_URL        Anima API, default http://127.0.0.1:7878 (adb forward tcp:7878 tcp:7878)
"""
import base64
import json
import os
import re
import sys
import time
import urllib.request

import appgen


def _load_env():
    """Reads KEY=VALUE lines from the repo's .env (git-ignored), without overriding the environment."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env")
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


_load_env()

ANIMA = os.environ.get("ANIMA_URL", "http://127.0.0.1:7878")
BASE = os.environ.get("ANIMA_BASE_URL", "https://api.anthropic.com").rstrip("/")
STYLE = os.environ.get("ANIMA_API_STYLE", "anthropic")
MODEL = os.environ.get("ANIMA_MODEL", "deepseek-flash" if STYLE == "openai" else "claude-sonnet-5-5")
KEY = os.environ.get("ANIMA_API_KEY", "")
MAX_STEPS = 30

SYSTEM = """You operate an Android phone for its owner through the Anima API.
Apps run on a separate agent display (720x1600 px) that the owner can watch and touch.
Each turn you get a screenshot of that display and a numbered list of UI elements
(index, role, text, bounds [left, top, right, bottom], flags).

Reply with ONE JSON object and nothing else:
{"thought": "<one short sentence: what you see and why this step>", "action": {...}}

Actions:
  {"type": "launch", "package": "<package from the app list>"}
  {"type": "tap", "element": <index>}            prefer this
  {"type": "tap", "x": <px>, "y": <px>}          only when no element fits
  {"type": "long_press", "element": <index>}
  {"type": "type", "text": "...", "element": <index of the field, optional>, "submit": false}
  {"type": "swipe", "direction": "up"|"down"|"left"|"right"}   "up" scrolls the content down
  {"type": "back"}
  {"type": "enter"}
  {"type": "wait", "ms": 1000}
  {"type": "make_app", "name": "<short app name>", "spec": "<what the app should do and show>"}
      builds a new native app and installs it; use only when the owner asks for an app or tool that does not exist
  {"type": "done", "summary": "<what you did, for the owner>"}
  {"type": "fail", "summary": "<why you could not finish>"}

Rules:
- One action per reply. Check the new screen before the next.
- Add "sensitive": true and a "reason" to any action that spends money, sends a message,
  deletes data or changes an account. The system will ask the owner first.
- If something blocks you (a login, a permission dialog you should not accept), stop with "fail".
- Finish with "done" as soon as the task is complete."""


def api(path, body=None, raw=None, timeout=60):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(ANIMA + path, data=data, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def log(kind, text):
    try:
        api("/log", {"kind": kind, "text": text})
    except Exception:
        pass
    print(f"[{kind}] {text}", flush=True)


def chat(system, content, max_tokens):
    """One model call. content: text, or a list of {"text": ...} / {"jpeg": base64} parts.
    ANIMA_API_STYLE=anthropic (Messages API) or openai (Chat Completions, e.g. DeepSeek)."""
    parts = [{"text": content}] if isinstance(content, str) else content
    if STYLE == "openai":
        msg = [{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + p["jpeg"]}} if "jpeg" in p
               else {"type": "text", "text": p["text"]} for p in parts]
        url, headers = BASE + "/chat/completions", {"authorization": "Bearer " + KEY}
        body = {"model": MODEL, "max_tokens": max_tokens,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": msg}]}
    else:
        msg = [{"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": p["jpeg"]}} if "jpeg" in p
               else {"type": "text", "text": p["text"]} for p in parts]
        url, headers = BASE + "/v1/messages", {"x-api-key": KEY, "anthropic-version": "2023-06-01"}
        body = {"model": MODEL, "max_tokens": max_tokens, "system": system, "messages": [{"role": "user", "content": msg}]}
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"content-type": "application/json", **headers})
    with urllib.request.urlopen(req, timeout=240) as r:
        out = json.loads(r.read())
    if STYLE == "openai":
        return out["choices"][0]["message"].get("content") or ""
    return "".join(b.get("text", "") for b in out.get("content", []))


def parse(text):
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise ValueError("no JSON in reply: " + text[:200])
    return json.loads(m.group(0))


def compact(elements):
    lines = []
    for e in elements:
        flags = " ".join(k for k in ("click", "edit", "scroll", "focused") if e.get(k))
        text = f' "{e["text"]}"' if e.get("text") else ""
        ident = f" #{e['id']}" if e.get("id") else ""
        lines.append(f'{e["i"]}: {e["role"]}{text}{ident} {e["bounds"]} {flags}'.rstrip())
    return "\n".join(lines) or "(no elements readable; use the screenshot and coordinates)"


def run(task, apps):
    api("/state", {"state": "working"})
    history = []
    app_list = ", ".join(f'{a["label"]} ({a["package"]})' for a in apps)
    try:
        for step in range(1, MAX_STEPS + 1):
            if api("/status").get("stopped"):
                return
            obs = api("/observe")
            text = (f"Task: {task}\nInstalled apps: {app_list}\n"
                    f"Previous steps:\n" + ("\n".join(history[-8:]) or "(none)") +
                    f"\n\nStep {step}. Foreground app: {obs.get('app') or 'none'}\nElements:\n{compact(obs['elements'])}")
            content = ([{"jpeg": obs["jpeg"]}] if obs.get("jpeg") else []) + [{"text": text}]
            reply = parse(chat(SYSTEM, content, 4000))
            action = reply.get("action", {})
            log("think", reply.get("thought", ""))
            kind = action.get("type")
            if kind in ("done", "fail"):
                log("done" if kind == "done" else "error", action.get("summary", ""))
                return
            if kind == "make_app":
                result = appgen.make(action.get("name", "App"), action.get("spec", task), model_fn=model_raw, log=log, api=api)
            else:
                result = api("/act", action, timeout=180).get("result", "")
            history.append(f"{step}. {json.dumps(action, ensure_ascii=False)} -> {result}")
            if result.startswith("stopped"):
                return
            time.sleep(0.6)
        log("error", f"Gave up after {MAX_STEPS} steps.")
    except Exception as e:
        log("error", f"{type(e).__name__}: {e}")
    finally:
        api("/state", {"state": "idle"})


def model_raw(system, prompt, max_tokens=8000):
    """A plain text call with its own system prompt, used for writing code."""
    return chat(system, prompt, max_tokens)


def main():
    if not KEY:
        sys.exit("Set ANIMA_API_KEY (and ANIMA_BASE_URL if you use a compatible endpoint).")
    apps = api("/apps")
    log("system", f"Agent connected ({MODEL}). Waiting for a task.")
    if len(sys.argv) > 1:
        api("/submit", {"task": " ".join(sys.argv[1:])})
    while True:
        try:
            t = api("/task", timeout=30).get("task")
        except Exception:
            time.sleep(1)
            continue
        if t:
            run(t, apps)
            apps = api("/apps")


if __name__ == "__main__":
    main()
