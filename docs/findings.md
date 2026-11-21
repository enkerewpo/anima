# Findings log

Short, dated notes on what we learn while building Anima. Each entry says what we saw, why it happens, and what we did.

## 2026-11-21 · Stage 0, emulator (AOSP API 35)

**First end-to-end runs.** Reference agent with a multimodal cloud model (DeepSeek `deepseek-flash`, Chat Completions API), screenshot plus UI-element list per step:

| Task | Steps | Result |
|---|---|---|
| Add a contact Ada Lovelace, phone 5551234 | 9 | done |
| Set an alarm for 7:30 AM | 9 | done |
| Delete the contact Ada Lovelace | 5 | stopped at the policy prompt; owner said no, the agent reported it and did not retry |
| Make me a tip calculator app | 1 make_app + 5 checks | app written, failed to compile once, fixed, installed silently, opened and tested by the agent |
| Add a contact Grace Hopper, phone 5559876 | 9 | done; no keyboard on the owner's screen (see below) |

**The agent's keyboard leaked onto the owner's screen.** Focusing a text field on the agent display raised the soft keyboard on display 0, covering the owner's own UI. Input-method placement follows the display's IME policy, and a new virtual display falls back to the default display. Fix: the Anima service sets `DISPLAY_IME_POLICY_HIDE` on the agent display through `IWindowManager.setDisplayImePolicy`, and the agent types through accessibility `ACTION_SET_TEXT`. Takeaway: a second, agent-owned display is not isolated by default; system UI that is per-device (IME, and likely toasts, notifications and permission dialogs) needs explicit routing.

**Permission dialogs are invisible to the tree reader.** The runtime-permission dialog shown on the agent display had an accessibility root of `null`, while ordinary app windows were readable. The agent still handled it from the screenshot. To check: whether this is the permission controller process predating our accessibility service, or a deliberate restriction.

**The policy layer worked as a backstop.** The model marked the delete step sensitive on its own, and the keyword rule would also have caught the "Delete" button. The prompt appeared in the shell while the agent waited, and a denial came back to the agent as a normal action result.
