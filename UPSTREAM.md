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

**Base: LineageOS**, pinned to one release branch (Android 16 or 17, decided when Stage 1 starts). LineageOS is AOSP plus maintained device support for Pixels and many other phones, which saves us from carrying device trees ourselves. Pure AOSP stays possible for Cuttlefish-only work.

### How the tree is assembled

```
repo init -u https://github.com/LineageOS/android.git -b <lineage branch>      # upstream, unmodified
.repo/local_manifests/anima.xml                                               # ours, from anima-manifest
  ├─ adds     vendor/anima                 → github.com/enkerewpo/anima      (this repo)
  └─ replaces frameworks/base etc.         → our forks, branch anima-<lineage branch>
```

Only the projects in the table below are replaced. Every other project comes straight from LineageOS at the pinned revision.

### Projects we expect to fork

The list stays as short as possible; most of Anima should live in `vendor/anima` and need no fork at all.

| Path in tree | Upstream (LineageOS) | Our fork | Why we touch it | Status |
|---|---|---|---|---|
| `frameworks/base` | `LineageOS/android_frameworks_base` | `enkerewpo/anima_frameworks_base` | `AnimaManagerService` in `system_server`; agent display and input paths; policy hooks in `ActivityTaskManager` and `PackageManager`; observation of window and task state | not created |
| `frameworks/native` | `LineageOS/android_frameworks_native` | `enkerewpo/anima_frameworks_native` | frame and layer data from SurfaceFlinger; input dispatch tracing | not created |
| `system/sepolicy` | `LineageOS/android_system_sepolicy` | `enkerewpo/anima_system_sepolicy` | only if `vendor/anima` sepolicy cannot express a rule | not created |
| `packages/apps/Launcher3` / `Trebuchet` | `LineageOS/android_packages_apps_Trebuchet` | none yet | the agent-centred home may replace it from `vendor/anima` instead of forking | not planned |
| kernel | per device | `enkerewpo/anima_kernel_<device>` | eBPF/ftrace hooks if the stock kernel lacks them; GPL-2.0 | not planned |

### Rules for forks

1. **One branch per upstream release**: `anima-<upstream branch>`, for example `anima-lineage-23.0`, started from the exact upstream revision recorded in `anima-manifest`.
2. **Every Anima commit in a fork starts with `[anima]`** and says why the change cannot live in `vendor/anima`. `git log --grep '^\[anima\]'` lists our whole delta.
3. **Rebase, don't merge.** On a new upstream release, a new branch is created and our commits are rebased onto it, so the delta stays a clean stack.
4. **Upstream bug fixes we need but do not own** go to upstream first; the fork carries them only until they land.
5. **This file is updated with every fork change**: new fork, new base version, or a change in why a project is forked.
