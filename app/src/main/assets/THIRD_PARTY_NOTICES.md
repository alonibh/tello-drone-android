<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Third-party notices and redistribution audit

This file records the repository audit performed for the AGPL-3.0 release. The project does not
claim ownership of the components below. Copyright, trademarks, and other rights remain with their
respective owners. Dependency versions are pinned in `gradle/libs.versions.toml` and the vision-lab
requirements files; transitive dependency metadata remains authoritative for each resolved build.

## Shipped Android application

| Component | Use | License / redistribution finding |
| --- | --- | --- |
| Ultralytics YOLO11n checkpoint and exported `yolo11n_320_float32.tflite` | Production person detector | Ultralytics AGPL-3.0. Compatible with this AGPL-3.0-only project; attribution and license must be preserved. Upstream checkpoint SHA-256: `0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1`. Exported model SHA-256: `3a4b2e9604487942c92ac1d00e0990e50dff55a1879a66c40906a579dad706e9`. |
| Google AI Edge LiteRT 2.1.5 and transitives | Native on-device model execution | Apache-2.0 and compatible permissive transitive licenses. The upstream AAR carries its `LICENSE` and `THIRD_PARTY_NOTICE.txt`; preserve them when repackaging the runtime outside the normal Gradle build. |
| Android SDK, Android Gradle Plugin, AndroidX, Jetpack Compose | Android platform, build, and UI | Predominantly Apache-2.0. Compatible; preserve upstream notices when redistributing their binaries or sources. |
| Kotlin and kotlinx.coroutines | Language/runtime and concurrency | Apache-2.0. Compatible; preserve upstream notices. |
| Gradle Wrapper / Gradle distribution | Reproducible build launcher | Apache-2.0. Compatible; wrapper and distribution notices remain upstream-owned. |

The previous TensorFlow Task Vision dependency and EfficientDet/SSD model assets were removed from
the application because they are no longer used. They were Apache-2.0-compatible; their removal was
not a license-conflict workaround.

## Tests and development-only vision lab

| Component | Scope | License / redistribution finding |
| --- | --- | --- |
| JUnit 4.13.2 | JVM tests only; not packaged in the APK | Eclipse Public License 1.0. Kept outside the distributed application runtime. |
| AndroidX Test / Espresso | Instrumentation tests only | Apache-2.0. |
| Ultralytics 8.3.191 | Offline benchmark/export tooling | AGPL-3.0. Compatible with the repository license. |
| PyTorch, NumPy, OpenCV, MediaPipe, OpenVINO | Development-only offline evaluation | BSD-style and/or Apache-2.0 licenses as declared by their distributions. They are installed into an ignored virtual environment and are not bundled in the Android app. |
| TensorFlow, tf_keras, ONNX, onnx2tf, onnxruntime, onnxslim, AI Edge LiteRT Python | Development-only export tooling | Apache-2.0, MIT, and other compatible permissive licenses as declared by their distributions. They are not bundled in the Android app. |
| Open Model Zoo ReID and third-party SOT research artifacts | Optional ignored research caches | Not included in the repository or application. Fetch scripts retain their source, hash, and license gates; no ownership or redistribution grant is asserted here. |

## Repository assets and recordings

The committed benchmark annotations, derived reports, UI artwork, and recorded-frame artifacts are
licensed under AGPL-3.0-only only to the extent the repository copyright holders own the relevant
rights. The license does not waive privacy, publicity, trademark, or personality rights of people
or products depicted in recordings. No externally sourced photo, audio, font, or icon bundle was
found in the shipped application.

No audited shipped dependency or bundled asset was found to prohibit AGPL-3.0 redistribution. This
audit is a factual engineering record, not legal advice; downstream redistributors remain
responsible for retaining notices from the exact dependency artifacts they distribute.
