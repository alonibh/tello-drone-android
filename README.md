# Tello Drone Android

Native Android tablet control-station foundation for a future Ryze/DJI Tello controller and
person-tracking workflow. This is an Android-only standalone project; MCP and LLM integration are
explicitly out of scope.

## Current status

Phase 0/1 is complete: an adaptive Material 3 Compose dashboard, mock state/controller, safety
interaction contracts, tests, and future architecture documentation. It is **mock UI only** and
does not connect to, control, or receive video from a Tello.

## Requirements and running

- Android Studio (current stable), with Android SDK Platform 37 installed
- JDK 17 (normally bundled with Android Studio)

Open this folder in Android Studio, let Gradle sync, select an Android 10/API 29+ device or
emulator, and run the `app` configuration. The mock starts disconnected; use the dashboard’s mock
state controls as available. No Wi-Fi or Tello permissions are requested.

From a terminal with JDK 17:

```text
./gradlew test
./gradlew assembleDebug
```

See [the architecture contracts](docs/ARCHITECTURE.md) and [roadmap](docs/ROADMAP.md). The original
[Python repository](https://github.com/alonibh/tello-drone-mcp) is behavioral history/reference
only and is not modified by this project.
