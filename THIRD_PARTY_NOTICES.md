# Third-party notices

Meshie is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)).
It includes or depends on the following third-party software.

## Bundled in this repository

| Component | Where | License |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) (tag b12191) | `third_party/llama.cpp/` | MIT, see `third_party/llama.cpp/LICENSE` |
| [Meshtastic protobufs](https://github.com/meshtastic/protobufs) (commit in `proto/src/main/proto/PROTOBUFS_COMMIT`) | `proto/src/main/proto/meshtastic/` | GPL-3.0 |
| [nanopb](https://github.com/nanopb/nanopb) options file | `proto/src/main/proto/nanopb.proto` | zlib |

## Libraries pulled in by Gradle

| Library | License |
|---|---|
| AndroidX (Core, Activity, Lifecycle, Navigation, Room, DataStore, Test) | Apache 2.0 |
| Jetpack Compose (UI, Material 3, icons) | Apache 2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache 2.0 |
| Protocol Buffers Java/Kotlin lite runtime | BSD 3-Clause |
| JUnit 4 (tests only) | EPL 1.0 |
| org.json (tests only) | Public domain (JSON License for older versions) |

## AI models

No model weights ship with the app or this repository. Users download them at runtime from
Hugging Face, and each model has its own license that the user accepts by downloading it:

- Llama 3.2 and its fine-tunes (Uncensored, Abliterated, Dolphin, Hermes): [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/)
- Qwen 3 / 3.5: Apache 2.0
- Gemma 3 / 4: [Gemma Terms of Use](https://ai.google.dev/gemma/terms)

## Trademarks

Meshtastic® is a registered trademark of Meshtastic LLC. Meshtastic software components are released
under various licenses, see [GitHub](https://github.com/meshtastic) for details. No warranty is
provided; use at your own risk. Meshie is not affiliated with or endorsed by the Meshtastic project.
