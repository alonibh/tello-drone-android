# Tello Drone Android

Native Android tablet control station for a Ryze/DJI Tello. Phase 2 provides real Wi-Fi/UDP
connectivity, telemetry, conservative manual flight controls, STOP/HOVER, and a protected
Emergency Motor Kill. MCP, LLM integration, video, detection, tracking, and autonomous movement
are explicitly absent.

## Requirements

- Android Studio (current stable), Android SDK Platform 37, and JDK 17
- Android 10/API 29 or newer tablet
- a standard Tello Wi-Fi network whose SSID begins `TELLO-`

Open the project in Android Studio, select a device, and run `app`. REAL is the default controller;
MOCK remains selectable while disconnected for UI development and previews. Network permissions
are not requested at startup—pressing CONNECT TELLO starts the permission and system Wi-Fi
selection flow.

From a terminal with JDK 17 and the Android SDK configured:

```text
./gradlew test
./gradlew assembleDebug
```

Read [the architecture and safety contracts](docs/ARCHITECTURE.md),
[roadmap](docs/ROADMAP.md), and [physical validation procedure](docs/PHYSICAL_TESTING.md) before
flying. Software tests do not validate radio behavior, aircraft firmware, tablet Wi-Fi behavior,
propellers, or real-world flight safety.

The original [Python repository](https://github.com/alonibh/tello-drone-mcp) is behavioral history
and reference only; it is not modified or embedded here.
