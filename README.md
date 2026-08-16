# Tello Drone Android

Native Android control station for a Ryze/DJI Tello, designed for adaptive Android windows on
phones, tablets, foldables, and resizable/split-screen displays. It provides real Wi-Fi/UDP video,
telemetry, on-device person detection, conservative manual flight controls, protected yaw-follow,
STOP/HOVER, and Emergency Motor Kill. A lightweight in-app simulator exercises the same production
tracking/controller/RC loop without a physical drone. MCP, LLM, Python embedding, and cloud control
are out of scope.

## Requirements

- Android Studio with JBR 25 and Android SDK Platform 37
- Android 9/API 28 or newer phone or tablet
- a standard Tello Wi-Fi network whose SSID begins `TELLO-`

Open the project in Android Studio, select a device, and run `app`. REAL is the default controller;
SIMULATOR is selectable while disconnected and runs fully inside the app. Network permissions are
not requested at startup or by simulator mode—only pressing CONNECT TELLO starts the permission and
system Wi-Fi selection flow.

From a terminal with JDK 17 and the Android SDK configured:

```text
./gradlew test
./gradlew assembleDebug
```

Read [the architecture and safety contracts](docs/ARCHITECTURE.md),
[simulator guide](docs/SIMULATOR.md),
[roadmap](docs/ROADMAP.md), and [physical validation procedure](docs/PHYSICAL_TESTING.md) before
flying. Software tests do not validate radio behavior, aircraft firmware, tablet Wi-Fi behavior,
propellers, or real-world flight safety.

The original [Python repository](https://github.com/alonibh/tello-drone-mcp) is behavioral history
and reference only; it is not modified or embedded here.
