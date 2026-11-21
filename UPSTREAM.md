# Upstream and what we change

Anima keeps a hard line between **our code** and **upstream code**.

- **This repository holds only our own code.** No upstream source is copied in. The `vendor/anima/` tree is laid out the way it will sit inside an AOSP/LineageOS checkout, so Stage 1 can drop it in unchanged.
- **Upstream projects we must modify live in separate fork repositories**, one per upstream project, each on its own branch on top of a pinned upstream tag. Every change there is a normal commit that can be rebased onto the next upstream release.
- **Everything else is used unmodified**, pinned to an exact version and listed below.

The goal is that anyone can answer, at a glance: which upstream, which version, and what exactly did Anima change.

## Current stage: 0 (emulator)

No upstream source is modified in Stage 0.

| Component | Upstream | Version | How Anima uses it | Modified |
|---|---|---|---|---|
| System image | AOSP emulator image `system-images;android-35;default;arm64-v8a` | Android 15 (API 35), revision 2, `userdebug`, `test-keys` | Runs the stock system; Anima is installed on top as a platform-signed app | No |
| Platform signing key | AOSP `build/make/target/product/security/platform.{pk8,x509.pem}` | `main` (public test key) | Signs the Anima app so the test-key image treats it as a platform component. Fetched by `scripts/fetch-test-keys.sh`, never committed | No |
| Build tools | Android SDK build-tools, platform `android-35` | 35.0.0 | `aapt2`, `d8`, `zipalign`, `apksigner`, `android.jar` | No |
| Emulator | Android Emulator | from the SDK | Runs the image | No |

Stage 0 relies on two platform behaviors that stock AOSP already has. They are what Stage 1 will replace with real OS changes:

| Behavior | Where in AOSP | Stage 0 use |
|---|---|---|
| Signature permissions granted to apps signed with the platform key | `PermissionManagerService` | `INJECT_EVENTS`, `ADD_TRUSTED_DISPLAY`, `INSTALL_PACKAGES`, `WRITE_SECURE_SETTINGS` |
| Trusted virtual displays with their own focus | `DisplayManagerService`, `WindowManagerService` | the agent display |

## Planned: Stage 1 (AOSP build, Cuttlefish) and Stage 2 (Pixel)

**Base: LineageOS `lineage-24.0`**. LineageOS is AOSP plus maintained device support for Pixels and many other phones, which saves us from carrying device trees ourselves. Pure AOSP stays possible for Cuttlefish-only work.

### How the tree is assembled

```
repo init -u https://github.com/LineageOS/android.git -b <lineage branch>      # upstream, unmodified
.repo/local_manifests/anima.xml                                               # ours, from anima-manifest
  ├─ adds     vendor/anima                 → github.com/enkerewpo/anima      (this repo)
  └─ replaces frameworks/base etc.         → our forks, branch anima-<lineage branch>
```

Only the projects in the table below are replaced. Every other project comes straight from LineageOS at the pinned revision.

### Projects we fork

Base: **LineageOS `lineage-24.0`**, first device **Google Pixel 8 (`shiba`, family `shusky`, SoC `zuma`/Tensor G3)**, built on a Linux x86_64 host. Every fork has a branch `anima-lineage-24.0` created at the upstream revision below; that branch is the fork's default.

The list stays as short as possible; most of Anima should live in `vendor/anima` and need no fork at all.

| Path in tree | Upstream (LineageOS) | Our fork | Base revision | Why we touch it | Status |
|---|---|---|---|---|---|
| `frameworks/base` | `LineageOS/android_frameworks_base` | [`enkerewpo/anima_frameworks_base`](https://github.com/enkerewpo/anima_frameworks_base) | `22e179769194` | `AnimaManagerService` in `system_server`; agent display and input paths; policy hooks in `ActivityTaskManager` and `PackageManager`; observation of window and task state | created 2026-11-21, no changes yet |
| `frameworks/native` | `LineageOS/android_frameworks_native` | [`enkerewpo/anima_frameworks_native`](https://github.com/enkerewpo/anima_frameworks_native) | `a96adf2c6e62` | frame and layer data from SurfaceFlinger; input dispatch tracing | created 2026-11-21, no changes yet |
| kernel manifest | `LineageOS/android_kernel_google_gs-6.1_manifest` | [`enkerewpo/anima_kernel_google_gs-6.1_manifest`](https://github.com/enkerewpo/anima_kernel_google_gs-6.1_manifest) | `88e0074f1d20` | points the kernel checkout at our kernel forks | created 2026-11-21, no changes yet |
| kernel `private/devices` | `LineageOS/android_kernel_google_gs-6.1_devices` | [`enkerewpo/anima_kernel_google_gs-6.1_devices`](https://github.com/enkerewpo/anima_kernel_google_gs-6.1_devices) | `ec1bafc7ddc9` | zuma/shusky device tree and kernel config: tracing and eBPF options | created 2026-11-21, no changes yet |
| kernel `private/google-modules` | `LineageOS/android_kernel_google_gs-6.1_google-modules` | [`enkerewpo/anima_kernel_google_gs-6.1_google-modules`](https://github.com/enkerewpo/anima_kernel_google_gs-6.1_google-modules) | `25a95e4a2981` | Pixel vendor modules (display, GPU, touch, power): rendering and input observation points | created 2026-11-21, no changes yet |
| kernel `common/gki` | AOSP `kernel/common`, tag `android14-6.1-2026-03_r25` (from android.googlesource.com, no GitHub upstream) | `enkerewpo/anima_kernel_common` (to be pushed from AOSP, not a GitHub fork) | tag above | scheduler and binder tracepoints, if vendor hooks are not enough. Changing GKI means the device runs a non-GKI kernel | not created |
| `system/sepolicy` | `LineageOS/android_system_sepolicy` | `enkerewpo/anima_system_sepolicy` | | only if `vendor/anima` sepolicy cannot express a rule | not created |
| `packages/apps/Launcher3` / `Trebuchet` | `LineageOS/android_packages_apps_Trebuchet` | none yet | | the agent-centred home may replace it from `vendor/anima` instead of forking | not planned |

The kernel is GPL-2.0: every kernel fork stays public with full source.

### Rules for forks

1. **One branch per upstream release**: `anima-<upstream branch>`, for example `anima-lineage-24.0`, started from the exact upstream revision recorded in `anima-manifest`.
2. **Every Anima commit in a fork starts with `[anima]`** and says why the change cannot live in `vendor/anima`. `git log --grep '^\[anima\]'` lists our whole delta.
3. **Rebase, don't merge.** On a new upstream release, a new branch is created and our commits are rebased onto it, so the delta stays a clean stack.
4. **Upstream bug fixes we need but do not own** go to upstream first; the fork carries them only until they land.
5. **This file is updated with every fork change**: new fork, new base version, or a change in why a project is forked.
