# DeskBot OS — Android App Development Report & Firmware Sync Requirements

**Purpose of this document:** This report summarizes everything built/changed on the Android companion app side during the Play Store readiness pass, and specifies exactly what the ESP8266 C++ firmware now needs to implement to stay in sync. Hand this to Gemini as full context before asking it to update the firmware code.

---

## 1. System Overview

- **ESP8266** node: SH1106 OLED display, passive buzzer, capacitive touch sensor. Connects to AWS IoT Core via MQTT (existing cert-based auth, unchanged).
- **Android app**: Kotlin + Jetpack Compose. Connects to the same AWS IoT Core endpoint over MQTT via WebSocket + SigV4, using the AWS IoT Device SDK for Android (`AWSIotMqttManager` + `CognitoCachingCredentialsProvider`), authenticated through an unauthenticated Cognito Identity Pool.
- **Python/OpenCV backend**: publishes posture + focus telemetry to `deskbot/posture/live`. Unchanged by this work, but see Section 4 for a naming-consistency risk.

---

## 2. What Changed on the Android App Side (chronological summary)

1. **Security rebuild**: Removed hardcoded AWS certificates from `res/raw`. App-side auth migrated to Cognito Identity Pool with ephemeral STS credentials (app-side only — does not affect firmware's own cert-based MQTT auth).
2. **MQTT library migration**: App moved from Eclipse Paho to the official AWS IoT Device SDK for Android. No firmware impact — this is purely an Android client library choice.
3. **Notification bridge**: App forwards allowlisted WhatsApp (and other app) notifications to `deskbot/notifications/stream` for OLED display, gated by user consent and a per-conversation allowlist.
4. **Play Store compliance rebuild**: Removed an AccessibilityService-based app-blocking mechanism (policy risk) and replaced it with a `UsageStatsManager`-based daily budget tracker with an **extending-threshold** model (see Section 4).
5. **20-20-20 eye-break reminder — REMOVED from app-side logic.** The app no longer schedules or fires this reminder locally. The interval-setting UI still exists in the app's Settings/Config screen and still publishes the configured interval value to MQTT (see Section 3) — **the firmware is now expected to own this feature entirely** (timer, OLED animation, buzzer chime).
6. **Water/hydration reminder acknowledgment — changed to hardware-owned.** When hardware is connected, the app no longer has an in-app "I drank water" button. Acknowledgment must come from the physical touch sensor. The app now expects an MQTT event from the hardware when the sensor is tapped for this purpose, and updates its own local streak/compliance data from that event. (A local in-app fallback button still exists, but *only* when the app is in standalone/no-hardware mode — it never appears when hardware is connected.)
7. **Device Hub screen**: "Toggle Contrast" retained (publishes `contrast_high`/`contrast_low` to `deskbot/device/cmd`, confirmed working via independent MQTT subscriber test). "Reboot Node" was **removed from the app UI** and replaced with an app-only "Force Reconnect" button that only resets the Android app's own MQTT session — it does **not** send any command to the firmware anymore. (Firmware can keep or drop reboot-command handling at your discretion — nothing in the app relies on it now.)
8. **Connection state**: App now only shows "CONNECTED" when live packets are actually arriving on `deskbot/posture/live`, with a debounced/hysteresis watchdog (15s window, 3 consecutive stable cycles) to avoid UI flicker from transient jitter. No firmware change needed here — purely an app-side display concern — but it does mean the app is now a more accurate mirror of whether your posture telemetry stream is actually reaching it.
9. Other app-only additions with no firmware dependency: Room-based daily summary reports, 14-day data retention/rollup, task board (syncs to `deskbot/tasks/sync` for possible future OLED rendering, not yet consumed by firmware as far as this report knows).

---

## 3. MQTT Contract — What Firmware Must Support

| Topic | Direction | Payload | Purpose | Status |
|---|---|---|---|---|
| `deskbot/posture/live` | Python/OpenCV → App | `{"posture": string, "focusScore": int}` *(see naming warning below)* | Live posture/focus telemetry | Existing, unchanged |
| `deskbot/notifications/stream` | App → Firmware | `{"source": string, "sender": string (≤16 chars), "msg": string (≤36 chars)}` | Forward allowlisted notification for OLED display | Existing |
| `deskbot/settings/dnd` | App → Firmware | `{"dnd": bool}` | Mute/unmute the physical buzzer | Existing |
| `deskbot/settings/intervals` | App → Firmware | `{"eyeIntervalMin": int, "waterIntervalMin": int, "buzzerEnabled": bool}` | User-configured reminder intervals | **Existing topic, but firmware must now actually act on it — see Section 4** |
| `deskbot/device/cmd` | App → Firmware | `{"cmd": "contrast_high"}` or `{"cmd": "contrast_low"}` | OLED contrast toggle | Existing, confirmed working |
| `deskbot/device/water_ack` and/or `deskbot/touch/water_ack` | Firmware → App | Any payload (app just needs message arrival; a simple `{"ack": true}` or timestamp is fine) | Touch-sensor water acknowledgment | **New — firmware needs to publish this on the correct touch gesture** |
| `deskbot/tasks/sync` | App → Firmware | JSON array of task objects | Task board sync | Existing topic, not yet confirmed consumed by firmware |

---

## 4. Critical Firmware Work Needed

### 4a. Own the 20-20-20 eye-break timer
The app no longer triggers this. Firmware must:
- Maintain its own countdown timer, defaulting to 20 minutes if no `deskbot/settings/intervals` message has ever been received.
- On receiving `{"eyeIntervalMin": N, ...}`, update the running timer's interval to N minutes going forward.
- When the timer elapses, run an eye-break OLED animation/prompt and buzzer chime (unless `buzzerEnabled` is false, or DND is active).

### 4b. Own the water/hydration reminder timer
Same pattern as above, using `waterIntervalMin` (default 40 minutes if never configured).
- On elapse: OLED prompt + buzzer chime (respecting `buzzerEnabled`/DND).
- **Do not auto-clear this reminder on your own** — it should stay "pending" until acknowledged via the touch sensor (see 4c), since the app tracks sent-vs-acknowledged compliance based on this.

### 4c. Touch sensor — water acknowledgment gesture
The hardware already has capacitive touch gestures for other things (per original feature list). Firmware needs a **distinct, disambiguated gesture** (e.g., a specific touch pattern, hold duration, or dedicated zone) that means "I drank water," separate from any existing touch gestures already implemented — please tell me/Gemini what gesture is currently free to use before wiring this, so we don't collide with existing functionality.
- On that gesture: publish to `deskbot/device/water_ack` (or `deskbot/touch/water_ack` — pick one canonical topic, don't publish to both), and clear the pending water-reminder state locally on the device (stop showing the OLED prompt).

### 4d. ⚠️ Verify a payload field-naming mismatch before anything else
During Android-side development, two different versions of the MQTT payload parser were written at different points — one expected `focus_score` (snake_case), the other expects `focusScore` (camelCase) from `deskbot/posture/live`. **The app currently expects `focusScore` (camelCase).** Please confirm what your Python/OpenCV backend is actually publishing right now — if it's still sending `focus_score`, either update the Python script to camelCase, or tell Gemini to make the Android parser accept both, before assuming telemetry display bugs are firmware issues. This is a real, live risk of a silent parsing failure.

---

## 5. Suggested Firmware Task List

1. Confirm/fix the `focusScore` vs `focus_score` field naming end-to-end (Python backend ↔ firmware, if firmware ever reads this topic too).
2. Implement local 20-20-20 timer, driven by `deskbot/settings/intervals`, with sensible default before first config arrives.
3. Implement local water/hydration timer, same pattern.
4. Pick and implement one canonical touch gesture for water acknowledgment; publish confirmation to the chosen topic; ensure it doesn't collide with existing touch gestures.
5. Confirm existing handling of `deskbot/device/cmd` (`contrast_high`/`contrast_low`) still works — this was verified from the app side, should already work firmware-side if unchanged.
6. Decide whether to keep or remove any old `reboot` command handling — app no longer sends it, but it's harmless to leave in place if it doesn't conflict with anything.
7. If desired later: consume `deskbot/tasks/sync` to render tasks on the OLED — not required for current app functionality, purely optional future work.

---

## 6. Open Questions for You to Decide Before Handing to Gemini

- Which touch gesture is currently unused and safe to repurpose for "water acknowledged"?
- Should the firmware publish water-ack confirmation to `deskbot/device/water_ack`, `deskbot/touch/water_ack`, or a topic name of your own choosing? (App can listen to whichever you confirm.)
- Do you want the eye-break/water-break OLED prompts to look different from each other, or reuse the same generic "reminder" animation?
