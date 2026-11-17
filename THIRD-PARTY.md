# Third-party notices

## Google MediaPipe Tasks and Pose Landmarker

- [MediaPipe source](https://github.com/google-ai-edge/mediapipe), Google LLC and MediaPipe contributors, [Apache License 2.0](https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE). The app includes the upstream Android runtime; the application's alignment and export code is separate.
- [Pose Landmarker (Lite) float16 model, version 1](https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task), unchanged. SHA-256: `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`. See the [official docs](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker) and [BlazePose GHUM model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf), Apache License 2.0.
- The full license is bundled alongside this notice in `licenses/Apache-2.0.txt`.

The benchmark photograph used in private QA and its derivatives are not redistributed. Use the fixture generator with a suitable test photo you have permission to use.

## Android runtime dependencies

- AndroidX Annotation, Google Flogger (API and system backend), Guava and FailureAccess, Google Android DataTransport, Firebase Encoders, and javax.inject: Apache License 2.0. See `licenses/Apache-2.0.txt`.
- [Protocol Buffers Java Lite](https://github.com/protocolbuffers/protobuf), BSD 3-Clause. See `licenses/Protobuf-BSD-3-Clause.txt`.
