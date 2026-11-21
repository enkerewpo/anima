"""Generated apps as first-class packages.

The model writes one Java Activity, the host builds a real APK (aapt2, javac, d8), signs it
with this machine's generator key, and the device installs it as an ordinary app through the
system installer. Updating an app is the same path with the same package and key.
"""
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
BT = SDK / "build-tools" / os.environ.get("BUILD_TOOLS", "35.0.0")
JAR = SDK / "platforms/android-35/android.jar"
KEYSTORE = ROOT / "keys/generated-apps.keystore"
JAVA_HOME = os.environ.get("ANIMA_JAVA_HOME", "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home")  # JDK 17+

CODER = """You write small, complete Android apps as a single Java 8 source file.
Rules:
- Exactly one public class MainActivity extends android.app.Activity, in the package given.
- Build the whole UI in code (no XML, no resources, no R class). Dark background, readable text sizes, padding for system bars.
- Only the Android SDK (API 35); no AndroidX, no Kotlin, no third-party libraries, no lambdas needing desugaring beyond Java 8.
- No network, no permissions. Keep state in SharedPreferences if it should persist.
Reply with only the Java source in one ```java block."""

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}"
    android:versionCode="{version}" android:versionName="{version}">
  <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35" />
  <application android:label="{label}" android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
    <activity android:name=".MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
"""


def slug(name):
    s = re.sub(r"[^a-z0-9]+", "", name.lower()) or "app"
    return s if s[0].isalpha() else "a" + s


def _env():
    e = dict(os.environ)
    e["JAVA_HOME"] = JAVA_HOME
    e["PATH"] = f"{JAVA_HOME}/bin:" + e.get("PATH", "")
    return e


def _key():
    if KEYSTORE.exists():
        return
    KEYSTORE.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([f"{JAVA_HOME}/bin/keytool", "-genkeypair", "-keystore", str(KEYSTORE), "-storepass", "anima-gen",
                    "-keypass", "anima-gen", "-alias", "gen", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                    "-dname", "CN=Anima generated apps"], check=True, capture_output=True)


def build(pkg, label, source, version):
    out = ROOT / "build/generated" / pkg
    shutil.rmtree(out, ignore_errors=True)
    src = out / "src" / Path(*pkg.split("."))
    src.mkdir(parents=True)
    (src / "MainActivity.java").write_text(source)
    (out / "AndroidManifest.xml").write_text(MANIFEST.format(pkg=pkg, label=label.replace('"', ""), version=version))
    (out / "classes").mkdir()
    (out / "dex").mkdir()
    env = _env()

    def run(cmd):
        p = subprocess.run([str(c) for c in cmd], capture_output=True, text=True, env=env)
        if p.returncode:
            raise RuntimeError((p.stderr or p.stdout)[-3000:])

    run([BT / "aapt2", "link", "-I", JAR, "--manifest", out / "AndroidManifest.xml", "-o", out / "base.apk"])
    run([f"{JAVA_HOME}/bin/javac", "-nowarn", "-Xlint:-options", "--release", "8", "-classpath", JAR,
         "-encoding", "UTF-8", "-d", out / "classes", src / "MainActivity.java"])
    classes = [str(p) for p in (out / "classes").rglob("*.class")]
    run([BT / "d8", "--min-api", "30", "--lib", JAR, "--output", out / "dex", *classes])
    shutil.copy(out / "base.apk", out / "unsigned.apk")
    run(["zip", "-qj", out / "unsigned.apk", out / "dex/classes.dex"])
    run([BT / "zipalign", "-f", "4", out / "unsigned.apk", out / "aligned.apk"])
    _key()
    run([BT / "apksigner", "sign", "--ks", KEYSTORE, "--ks-pass", "pass:anima-gen", "--ks-key-alias", "gen",
         "--out", out / "app.apk", out / "aligned.apk"])
    return (out / "app.apk").read_bytes()


def make(name, spec, model_fn, log, api, attempts=3):
    pkg = "dev.anima.gen." + slug(name)
    version = 1
    vfile = ROOT / "build/generated" / (pkg + ".version")
    if vfile.exists():
        version = int(vfile.read_text()) + 1
    log("install", f"Writing {name} ({pkg})")
    prompt = f"Package: {pkg}\nApp name: {name}\nWhat it does: {spec}"
    error = None
    for i in range(attempts):
        p = prompt if error is None else prompt + f"\n\nThe previous source failed to compile:\n{error}\nFix it."
        reply = model_fn(CODER, p)
        m = re.search(r"```(?:java)?\s*(.*?)```", reply, re.S)
        source = m.group(1) if m else reply
        try:
            apk = build(pkg, name, source, version)
            break
        except RuntimeError as e:
            error = str(e)
            log("think", f"Build failed (attempt {i + 1}), fixing")
    else:
        log("error", f"Could not build {name}")
        return "error: build failed"
    vfile.write_text(str(version))
    log("install", f"Built {name} v{version}, {len(apk) // 1024} KB. Installing")
    result = api("/install", raw=apk, timeout=120).get("result", "")
    if not result.startswith("ok"):
        return "error: " + result
    api("/act", {"type": "launch", "package": pkg})
    return f"installed and opened {pkg}"
