# CLAUDE.md — Meshie (formerly Meshtastic-chan)

Guidance for AI coding agents working in this repo.

## What this is
Android app (Kotlin + Compose) that connects to a LilyGO T-Echo over BLE and runs
a local LLM (llama.cpp) to act as an AI character on a Meshtastic channel.
**[DESIGN.md](DESIGN.md) is the source of truth** — read it before changing behavior,
and update it (decisions log, section 0) when a design decision changes.

## Diary
Append an entry to [DIARY.md](DIARY.md) at the end of every work session:
date, what was done, what broke and how it was fixed, open questions, next steps.
Newest entries at the bottom. Keep entries short and factual.

## Hardware
- Bot phone: Samsung Galaxy Z Flip 5 (SM-F731*), Snapdragon 8 Gen 2, 8 GB RAM, arm64-v8a. Dedicated to the bot.
- Radio: LilyGO T-Echo (nRF52840 + SX1262 + GPS), Meshtastic firmware, BLE only.
- The operator has a second phone + T-Echo for testing as a mesh user.

## Toolchain (installed locally, no sudo)
- JDK: `~/.local/opt/jdk` → `JAVA_HOME`
- Android SDK: `~/Android/Sdk` → `ANDROID_HOME` (platform-tools, build-tools, platforms, NDK, CMake)
- Env vars are exported from `~/.bashrc`; open a new shell or `source ~/.bashrc` if `adb`/`java` aren't found.

## Common commands
```bash
./gradlew assembleDebug            # build (also compiles llama.cpp via CMake/NDK)
./gradlew installDebug             # build + install on connected phone
./gradlew testDebugUnitTest        # unit tests
# On-device regex/ICU test. NEVER use connectedDebugAndroidTest on the bot phone: it can uninstall the app
# and wipe its database, settings and downloaded models. This runs the test without uninstalling:
./gradlew installDebug installDebugAndroidTest && adb shell am instrument -w com.meshie4LoRa.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s MeshChan:*           # app logs (all app tags start with MeshChan)
# Headless model benchmark (debug builds only; works with the screen locked):
adb shell am broadcast -n com.meshie4LoRa/.debug.BenchReceiver --es model <model-id> [--es msg "text"]
adb logcat -s MeshChan.Bench
# Screenshots: exec-out screencap is corrupted by a multi-display warning on the Flip 5
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
```

## Project layout
```
app/src/main/java/com/meshie4LoRa/
  radio/    BLE transport (BleRadioTransport), foreground RadioService
  mesh/     MeshRepository (FromRadio -> Room, send text/admin), Geo utils
  llm/      LlamaNative (JNI), LlamaEngine, ModelManager (downloads/verify/load), ModelCatalog
  brain/    (Phase 3) decision engine, context builder, post-processor, idle timer
  memory/   (Phase 4) memory manager (summaries, facts, feelings)
  data/     Room entities/DAOs/AppDatabase, SettingsStore (DataStore)
  ui/       Compose screens + ViewModels
app/src/main/cpp/          JNI wrapper around llama.cpp (llama_jni.cpp, CMakeLists.txt)
app/src/debug/             debug-only BenchReceiver
proto/                     plain java-library module; Meshtastic protobufs (lite)
third_party/llama.cpp/     vendored plain folder (version in third_party/LLAMA_CPP_VERSION)
```

## Build notes
- AGP 9 with built-in Kotlin (no `kotlin-android` plugin), compileSdk 37.
- protobuf-gradle-plugin is incompatible with AGP 9's new DSL, so protos live in the `:proto` java-library module.
- Native code is always built `-DCMAKE_BUILD_TYPE=Release` (debug llama.cpp is unusably slow).
- The user handles all git operations: do not commit or push.

## Conventions
- Kotlin coroutines + Flow; no RxJava. Manual constructor DI via an `AppContainer`, no Hilt.
- Room for persistent data, DataStore for settings. Every schema change needs a migration once we've shipped to the phone with real data.
- Keep LLM prompts in one place (`brain/Prompts.kt`) so they're easy to tune.
- Mesh text is untrusted input: always delimit it in prompts; never put raw coordinates in outgoing text.
- Outgoing text must fit Meshtastic's payload limit (~200 bytes UTF-8 per message).
- No quiet hours. Rate limiting exists only as the operator-set cooldowns and hard limit from DESIGN.md D54 (D7 is superseded).
- Only one character active at a time; memories are shared (D4, D5). Keep nullable `character_id` columns for future per-character memory.
