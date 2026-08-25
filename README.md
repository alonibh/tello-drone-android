<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Tello Drone Android

Native Android control station for a Ryze/DJI Tello, designed for adaptive Android windows on
phones, tablets, foldables, and resizable/split-screen displays. It provides real Wi-Fi/UDP video,
telemetry, on-device person detection, conservative manual flight controls, protected yaw-follow,
STOP/HOVER, and Emergency Motor Kill. MCP, LLM, Python embedding, and cloud control are out of
scope.

## Requirements

- Android Studio with JBR 25 and Android SDK Platform 37
- Android 9/API 28 or newer phone or tablet
- a standard Tello Wi-Fi network whose SSID begins `TELLO-`

Open the project in Android Studio, select a device, and run `app`. Network permissions are not
requested at startup—only pressing CONNECT TELLO starts the permission and system Wi-Fi selection
flow.

From a terminal with JDK 17 and the Android SDK configured:

```text
./gradlew test
./gradlew assembleDebug
```

Read [the architecture and safety contracts](docs/ARCHITECTURE.md), [roadmap](docs/ROADMAP.md), and
[physical validation procedure](docs/PHYSICAL_TESTING.md) before
flying. Software tests do not validate radio behavior, aircraft firmware, tablet Wi-Fi behavior,
propellers, or real-world flight safety.

The original [Python repository](https://github.com/alonibh/tello-drone-mcp) is behavioral history
and reference only; it is not modified or embedded here.

## Person detector

Production person detection uses the official Ultralytics YOLO11n checkpoint exported at a fixed
320 × 320 input size to an FP32 LiteRT/TFLite model for fully local Android CPU inference. The app
does not contain or execute Python, PyTorch, or the Ultralytics Python runtime. Ultralytics YOLO
code and models are provided by Ultralytics and are used under the GNU Affero General Public
License v3.0. Ultralytics names and trademarks remain the property of their respective owners.

## License

Project-owned source code, tests, documentation, and assets in this repository are licensed under
the [GNU Affero General Public License v3.0](LICENSE), identified by
`SPDX-License-Identifier: AGPL-3.0-only`. This license decision does not transfer ownership of any
third-party component. Third-party dependencies, tools, and model artifacts remain copyrighted by
their respective owners and are distributed or referenced under their own compatible terms.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the dependency, asset, and model audit and
the notices that must be preserved in redistributions.
