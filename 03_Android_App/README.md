# DeskBot OS 🤖💻

An intelligent telemetry, ergonomics, and digital wellbeing hub. DeskBot OS connects a PC-based computer vision posture tracker, an ESP8266 hardware companion robot, and an Android mobile application via AWS IoT Core.

## Core Architecture
* **PC Vision Engine:** Tracks posture and calculates a real-time Focus Score using OpenCV and MediaPipe.
* **Hardware Companion (ESP8266):** 1.3" OLED Cyber-Minimalist HUD displaying real-time faces, synchronized tasks, and hardware-acknowledged hydration prompts.
* **Android App:** Manages wellbeing budgets, enforces mindful 4-7-8 breathing interventions, and acts as a zero-trust WhatsApp notification firewall.
* **Cloud Infrastructure:** Powered by AWS IoT Core (MQTT over TLS) for seamless, decentralized multi-node synchronization.

## Setup & Deployment
Ensure you have configured your AWS IoT certificates and updated the secrets.h file with your local Wi-Fi credentials before compiling the hardware firmware.
