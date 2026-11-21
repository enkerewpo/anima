# Anima

**An agent-native OS research platform built on AOSP.**

Anima moves the AI agent out of the app layer and into the operating system. Instead of an assistant app that reads pixels through accessibility or drives the phone over `adb`, the agent is an OS component: it sees what the system sees (windows, tasks, input, rendering, scheduling), acts through system interfaces, and sits under a policy layer it cannot bypass.

We build on the Android Open Source Project because the app ecosystem people actually use still runs on Android, and because AOSP is open enough to change at every layer. The goal is a shared platform where we, and anyone else, can run experiments on what happens when a model becomes part of the OS: to interaction, to system design, and to security.

> **Status: pre-alpha.** Stage 0 runs on the Android Emulator: the system service, agent display, shell UI, input injection, UI-tree capture and silent install work; a cloud model completes multi-app tasks and builds its own apps. See [Roadmap](#roadmap) and the [findings log](docs/findings.md).

## Why

Today's phone agents sit outside the system they operate.

- **Research GUI agents** (AppAgent, Mobile-Agent, UI-TARS, AutoGLM and others) drive the phone from a host over `adb`, or from an accessibility service. They see screenshots and view trees, and nothing of intents, permissions, task state, frame timing or install flows.
- **Vendor agents** (Gemini UI automation, Apple App Intents, Huawei HMAF, Honor YOYO) go deeper, but they are closed, and each exposes only what the vendor chooses.
- **App makers push back.** Apps that detect injected input treat it as a script; several major Chinese apps have blocked a shipping agent phone outright. How an OS should mediate between agent, user and app is an open question with no open testbed.

Anima is that testbed: an open, modifiable OS in which the agent is a first-class system component.

## Research questions

1. **Observation.** What can an agent gain from OS-internal signals (window and task state, input dispatch, SurfaceFlinger frame and layer data, binder traffic, scheduler traces) compared with screenshots and accessibility trees? Does it make grounding faster, cheaper or more reliable?
2. **Actuation.** What is the right action interface: injected input on a background virtual display, structured calls (intents, AppFunctions), or new system calls designed for agents? When should the system pick which?
3. **Interaction.** If the agent works on its own display while the user keeps the phone, how should the user watch, interrupt, take over and approve? What does a phone look like when the agent, not a grid of app icons, is the home screen, and natural language (typed or spoken) is the main way in, with apps still there underneath?
4. **Security.** Who constrains an agent that holds system privileges? How do we contain prompt injection from screen content, confirm irreversible actions, audit everything, and respect apps that do not want to be automated?
5. **Generated software.** Can the agent write, sign, install and update its own native apps as first-class packages, and how should the OS manage their lifecycle and permissions?

## Architecture

The design is layered so that each piece can be replaced in an experiment.

```
 ┌──────────────────────────────────────────────────────────────┐
 │ Agent runtime        planner / model (cloud or on-device)    │  pluggable
 ├──────────────────────────────────────────────────────────────┤
 │ Anima API            typed tools: observe, act, install, ... │  stable contract
 ├──────────────────────────────────────────────────────────────┤
 │ Policy & audit       grants, confirmations, kill switch, log │  agent cannot bypass
 ├──────────────────────────────────────────────────────────────┤
 │ Framework            WindowManager, ActivityTaskManager,     │
 │ (system_server)      InputManager, PackageManager,           │
 │                      VirtualDisplay, ContentCapture          │
 ├──────────────────────────────────────────────────────────────┤
 │ Native services      SurfaceFlinger, inputflinger            │
 ├──────────────────────────────────────────────────────────────┤
 │ Linux kernel         sched, binder, eBPF / ftrace (observe)  │
 └──────────────────────────────────────────────────────────────┘
```

- **Anima service** is the OS component. In Stage 0 it is a platform-privileged system app; from Stage 1 it moves into `system_server` and native services.
- **Agent display.** The agent operates apps on its own virtual display, so the user's screen is not taken over. The user can mirror it, watch and take over at any time.
- **Anima API** is the contract other researchers build against: observe (screen, UI tree, window and task state, system traces), act (tap, type, launch, intents), manage (install, update, remove generated apps). Any agent, from a scripted baseline to a GUI-agent model, can drive the phone through it.
- **Policy & audit** sits below the API. Every action is logged; sensitive and irreversible actions need user confirmation; apps can be excluded.
- **Episodes** (observations, actions, system traces) are recorded for replay and evaluation.

## Repository layout

This repository holds **only Anima's own code**. Upstream projects are never copied in; what we use, at which version, and what we change is listed in [`UPSTREAM.md`](UPSTREAM.md).

```
vendor/anima/          on-device code, laid out as it will sit in an AOSP/LineageOS tree (vendor/anima)
  Anima/               the Anima system service, agent display, policy, API server and shell UI
host/                  reference agent and app generator that run on a computer
scripts/               build and run helpers
docs/findings.md       dated notes on what we learn
UPSTREAM.md            upstream components, pinned versions, our forks and the rules for them
```

From Stage 1, the OS changes that cannot live in `vendor/anima` go into separate fork repositories of the upstream projects (for example `frameworks/base`), each a clean stack of `[anima]` commits on a pinned upstream revision.

## Quick start (Stage 0)

Needs the Android SDK (emulator, platform-tools, build-tools 35, platform 35), JDK 17 and Python 3. No AOSP checkout.

```sh
scripts/stage0.sh                       # boots a stock AOSP emulator, builds and installs Anima, opens the shell
export ANIMA_API_KEY=...                 # Anthropic-compatible Messages API
python3 host/agent.py                    # then type a task on the phone and tap Run
```

How Stage 0 gets system privileges without building AOSP: stock emulator images are signed with the public AOSP platform test key, so an app signed with that key is a platform app there and holds `INJECT_EVENTS`, `ADD_TRUSTED_DISPLAY`, `INSTALL_PACKAGES` and `WRITE_SECURE_SETTINGS`. This only works on test-key builds and is never a way into a real phone.

### The Anima API

Plain HTTP on the device's localhost (`adb forward tcp:7878 tcp:7878`). See [`ApiServer.java`](vendor/anima/Anima/src/dev/anima/ApiServer.java).

| Call | What it does |
|---|---|
| `GET /observe` | Elements on the agent display (index, role, text, bounds, flags), foreground app, JPEG frame |
| `GET /apps` | Launchable apps |
| `POST /act` | `tap`, `long_press`, `type`, `swipe`, `back`, `enter`, `launch`, `wait`; passes the policy first |
| `GET /task` | Next task typed on the phone |
| `POST /log`, `POST /state` | Show the agent's reasoning and state in the shell |
| `POST /install` | Install raw APK bytes as an ordinary app, no prompt |

## Base system and stages

| Stage | Where it runs | Base | Agent lives in |
|---|---|---|---|
| 0 | Android Emulator (arm64, macOS or Linux) | stock AOSP image, API 35 | privileged system app in `/system/priv-app` |
| 1 | Cuttlefish (Linux x86_64) | LineageOS or AOSP built from source, our forks per [`UPSTREAM.md`](UPSTREAM.md) | `system_server` service plus native hooks |
| 2 | Real phones (Google Pixel 8a first) | LineageOS, our forks per [`UPSTREAM.md`](UPSTREAM.md), signed with our own keys | same as Stage 1, plus on-device models |

Stage 0 needs no AOSP build, so anyone with a laptop can try it. Stages 1 and 2 are where the OS-level changes live.

## Roadmap

- [ ] **Stage 0: emulator**
  - [x] Platform-signed Anima service: trusted agent display with its own focus, input injection, frame and UI-tree capture
  - [x] Shell UI: live agent display you can touch to take over, activity log, task input, Stop
  - [x] Silent install of a generated app as an ordinary package
  - [x] Confirmation policy below the API (keyword and agent-flagged steps)
  - [x] Reference agent with a cloud model completing multi-app tasks (Anthropic or OpenAI-style APIs)
  - [x] Agent display isolated from the owner's keyboard
  - [ ] Episode recording
  - [ ] Agent-centred home: the shell as the HOME app and the assistant (voice) entry, apps one level down
- [ ] **Stage 1: AOSP / Cuttlefish**
  - [ ] Anima service inside `system_server`
  - [ ] OS-internal observation: window/task state, SurfaceFlinger frame data, scheduler and binder traces
  - [ ] Policy layer enforced in the framework
  - [ ] Episode recording and replay; benchmark harness
- [ ] **Stage 2: real devices**
  - [ ] Pixel build signed with our own keys, relocked bootloader
  - [ ] On-device model service shared by the agent and apps
  - [ ] Field tests with real third-party apps

## Open problems

We do not know the answers to these yet, and expect some of them to shape the project.

- **App acceptance.** Third-party apps may detect and block injected input, unlocked bootloaders or modified systems. How much of the real ecosystem stays usable is an empirical question.
- **Upstream churn.** AOSP now publishes source twice a year and no longer ships Pixel device trees; keeping OS patches small and rebasable matters.
- **Model capability.** GUI agents still fail often on hard tasks; on-device models trail cloud models by a wide margin. OS signals may help, or may not.
- **Security of a privileged agent.** Screen-content prompt injection is documented and unsolved. Giving an agent system privileges raises the stakes.
- **Telephony on custom builds.** Calls and VoLTE depend on carrier configuration and may not work on every network; Stage 2 treats calls as out of scope at first.

## Building on Anima

Anima is meant to be used by others. You should be able to:

- plug in your own agent or model behind the Anima API;
- add a new observation source or action type in one place;
- swap the policy layer to test a different safety design;
- record episodes and compare agents on the same tasks.

Interfaces will change while we are pre-alpha. Open an issue before building something large on them.

## Responsible use

Anima is a research platform. Use it on devices and accounts you own, follow the terms of the apps you automate, and do not use it to evade app security or anti-fraud controls. Report security issues privately through GitHub security advisories.

## License

Apache License 2.0, matching AOSP. Kernel changes, when there are any, are released under GPL-2.0 as the Linux kernel requires.

Android is a trademark of Google LLC. Anima is an independent research project and is not affiliated with or endorsed by Google.

## Contact

wheatfox · wheatfox17@icloud.com. Questions and ideas are welcome in GitHub Issues and Discussions.

## Citation

If you use Anima in research, please cite it with the metadata in [`CITATION.cff`](CITATION.cff).
