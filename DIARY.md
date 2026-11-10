# Development Diary

## 2026-11-07 — Session 1: design + toolchain

**Done**
- Wrote v0.1 design proposal, reviewed with operator, rewrote as v0.2 ([DESIGN.md](DESIGN.md)).
  Key changes from review: feelings are free text (not a scale), no rate limiting / quiet
  hours, all modes + Sleep, manual send, idle chatter, per-field model data toggles,
  one active character, shared memory, dedicated bot phone (Z Flip 5, 8 GB).
- Created CLAUDE.md.

**Environment found**
- Ubuntu 24.04 x86_64, 31 GB RAM, git/cmake/curl present, no Java, no Android SDK, no adb.
- Phone visible on USB as `04e8:6860` (Samsung, MTP mode).

**Assumptions to confirm**
- Operator has two T-Echos (one for the bot, one personal) — "my t-echo" for testing.

**Toolchain installed (no sudo)**
- Temurin JDK 21.0.13 (`~/.local/opt/jdk`), Gradle wrapper 9.9.0.
- Android SDK `~/Android/Sdk`: platform-tools 37, platforms android-36 + android-37.0,
  build-tools 36.1.0, NDK 29.0.14206865, CMake 3.31.6. Env vars in `~/.bashrc`.
- Phone: SM-F731U, Android 17 (API 37), arm64-v8a, ~7.0 GB visible RAM. adb works after
  enabling USB debugging.

**Phase 0 scaffold**
- Modules: `:app` (Android, Compose) and `:proto` (plain java-library, protobuf-lite from
  meshtastic/protobufs @ 85f3f43).
- BLE transport (bond → MTU 512 → discover → FromNum notify → drain FromRadio), mesh
  repository (my_info, node_info, channels, lora preset, text/position/nodeinfo/telemetry
  packets → Room), foreground service with wake lock, UI tabs Feed / People / Radio,
  manual send to channel + DM from People. Geo utils + unit tests (3/3 pass).
- App installs and launches on the phone; permissions granted.

**What broke / fixes**
- AGP 8.13 + latest AndroidX → AAR metadata requires compileSdk 37 and AGP ≥ 9.1.
  Moved to AGP 9.5.1 (built-in Kotlin, removed `kotlin-android` plugin), compileSdk 37.
- protobuf-gradle-plugin 0.9.6 crashes with AGP 9 new DSL (casts to `BaseExtension`).
  Fixed by moving protos into a standalone `:proto` java-library module.
- `adb exec-out screencap` on the Flip 5 prepends a multi-display warning to the PNG;
  use `adb shell screencap -p /sdcard/x.png && adb pull` instead.

**Next steps**
- Pair the bot T-Echo, verify connect → config download → node list → send/receive text
  with the operator's personal node.
- Phase 1: llama.cpp submodule + JNI, model picker/downloads, test chat.

**Later same session — first radio connection**
- Operator scanned + paired the bot T-Echo from the app. Connect → MTU 247 → config
  complete in ~8 s, LongFast detected, 80 nodes loaded (mostly from yesterday's event).
- Bot node and the operator's personal node identified (IDs omitted here).
- Finding: many event nodes share an identical coordinate — Meshtastic position precision
  reduction. Added `positionPrecisionBits` (Room v2, auto-migration), ± uncertainty in
  People list, relative "heard" times. The model must be told distances are approximate.
- Added "Radio name on the mesh" editor (local AdminMessage `set_owner`, 39/4 byte limits).
  Characters will reuse this when activated (Phase 2).
- Channel + DM send/receive confirmed working with the operator's node.
- Rename gotcha: firmware applied `set_owner` (verified after reconnect) but the operator's
  phone kept the old name; after deleting the node there it showed "Meshtastic xxxx" and
  DMs failed with "Recipient key unavailable" (PKI DMs need the public key from NodeInfo).
  Fix: app now broadcasts our NodeInfo (incl. 32-byte public key) right after a rename,
  plus an "Announce name" button.
- Feed now has conversation chips: #channel + one per DM peer; composer targets the
  selected conversation.
- Operator preferred DMs in the People tab: Feed is channel-only again; tapping a person
  opens a full-screen DM conversation (info header, ignore toggle, composer). Unread DMs
  tracked with `messages.read` (Room v3 auto-migration, existing rows default read);
  badge with total on the People nav icon, per-user badge next to the name, unread users
  sorted to the top. Opening a conversation marks it read.
- People list split into "Conversations" (any DM exchange, from `observeDmPeers` query)
  and "Everyone else"; sorted by distance (nearest first, unknown last), then last heard.
  Nodes (outside Conversations) not heard in 182 days go to a collapsed
  "Not heard in 6+ months" section. SNR/RSSI shown per row (multi-hop SNR = last relay's).

## 2026-11-07 — Session 1 (cont.): Phase 1, on-device LLM

**Done**
- llama.cpp vendored as a plain folder at `third_party/llama.cpp` (tag b12191, nested .git
  removed; version in `third_party/LLAMA_CPP_VERSION`). Built via CMake/NDK as a static lib
  into `libmeshchan_llm.so` (~5.5 MB in APK), `-O3` even for debug builds,
  `-march=armv8.2-a+dotprod+i8mm+fp16`, OpenMP on, no KleidiAI/backend-DL.
- JNI wrapper `app/src/main/cpp/llama_jni.cpp`: load/free/info, chat template apply
  (model template, ChatML fallback), streaming generate with grammar support, cancel,
  stats. Text crosses JNI as UTF-8 byte arrays (NewStringUTF breaks on emoji), and only
  complete UTF-8 sequences are emitted.
- Kotlin: `LlamaEngine` (single LLM thread, mutex), `ModelManager` (catalog seeding,
  DownloadManager downloads, 1 s progress polling, SHA-256 verify, custom HF URL, SAF
  import, RAM-fit estimate, auto-load active model on startup), `ModelsViewModel`,
  Models tab, generation settings, local test chat, benchmark. Room v4 adds `models`.
- Catalog: 8 models, sizes + SHA-256 verified from the HF API.

**What broke / fixes**
- Qwen3.5 is a thinking model: it used the whole token budget inside `<think>`.
  Fix: if the GGUF chat template contains `<think>`, pre-fill an empty
  `<think>\n\n</think>\n\n` after the generation prompt (same as enable_thinking=false).
  Detection refined to templates containing `enable_thinking` (Qwen3-4B-Instruct-2507's
  template mentions `<think>` but isn't a thinking model).
- llama.cpp load-progress dots were logged as warnings (CONT level); now only WARN/ERROR.
- UI-driven adb benchmark script failed once the phone screen locked (swipes opened the
  notification shade). Replaced with a debug-only `BenchReceiver` (src/debug, guarded by
  the DUMP permission so only adb can trigger it) that logs speed, a sample reply and memory.
- Loading Qwen3 4B got the app killed by lmkd: llama.cpp repacks weights for the ARM
  kernels into anonymous memory (~3 GB for a 2.5 GB file, mostly swapped to zram).
  Fix: `use_extra_bufts` is now a load option ("Fast weights", default on) and is
  automatically disabled for files > 30% of RAM (~2.2 GB) so big models stay mmapped.

**Measurements (Z Flip 5, ctx 4096, charging, phone warm after repeated runs)**
| Model | repack | prompt tok/s | gen tok/s | anon RAM |
|---|---|---|---|---|
| Qwen3.5 0.8B | on | 72–142 | 21–37 | 0.7 GB |
| Llama 3.2 1B | on | 98 | 20.5 | – |
| Llama 3.2 3B | on | 31–40 | 10–15 | 2.5 GB |
| Llama 3.2 3B | off | 15 | 12.8 | 0.6 GB |
| Qwen3 4B 2507 | off (auto) | 10 | 5.8 | 0.6 GB |
| Gemma 4 E2B | off (auto) | 38 | 7.9 | 0.2 GB |
- Threads (Llama 3B, repack on, warm): 2→6.3, 4→7.6, 6→7.7, 8→0.7 tok/s gen. 8 threads
  pulls in the little cores and collapses; 4 stays the default. Repeated runs show clear
  thermal throttling (0.8B dropped from 37 to 21 tok/s).
- Sample replies were coherent and in persona; Gemma 4 E2B ignored the persona the most.
- Not yet exercised on device: add-by-URL and import-file paths.

**Next steps**
- Phase 2: characters (names pushed to radio), context builder, model data-access settings
  with prompt preview.
- Prompt-processing speed is the bottleneck for the judge step (~1000-token prompts):
  consider KV-cache prefix reuse for the static system/persona part.

## 2026-11-07 — Session 1 (cont.): screen-off ("bag mode") hardening

Operator's real flow: configure, close the phone, put it in a bag. Tested with the screen off.

**Findings**
- Screen off (or app not visible), Samsung moves the foreground-service process to the
  `/moderate` cpuset = **cores 0–2 only (little cores)**. Battery-optimization whitelist and
  RUN_ANY_IN_BACKGROUND did not change that.
- With 4 threads on 3 little cores, Llama 3B generation fell to ~2 tok/s; 3B prompt ~3–9 tok/s.
- lmkd killed the app with the screen off at ~1.8 GB anon RSS (3B with repacked weights).

**Fixes**
- Native: threads per call = min(configured, CPUs in our affinity mask) via `sched_getaffinity`.
- Native: KV-cache prefix reuse (`llama_memory_seq_rm` from the common prefix). Falls back to
  a full clear when partial removal isn't supported (Qwen3.5 hybrid models).
- Repack ("fast weights") only for model files ≤ 15% of RAM (~1.1 GB).
- `BootReceiver` restarts the service after reboot / app update (if a radio is configured).
- Radio tab "Screen-off running" card: battery-optimization exemption request + Samsung
  "Never sleeping apps" hint.

**Screen-off decision benchmark** (~500-token channel log, then +1 message)
| Model | cold | warm (prefix cached) |
|---|---|---|
| Llama 3.2 1B (repack) | 25 s | 1.1 s |
| Llama 3.2 3B (repack, before threshold change) | 51 s | 3.5 s |
| Qwen3.5 0.8B | 18 s | 18 s (no reuse: hybrid arch) |
- Design implication for Phase 3: keep the prompt append-only (static system/persona first,
  channel log in chronological order, new message last) so the cache hits.

**Next steps**
- Phase 2: characters, context builder, model data-access settings with prompt preview.

## 2026-11-07 — Session 1 (cont.): Phase 2, characters + context

**Done**
- `characters` table (Room v5): long/short name, nicknames, persona, rules, examples,
  eagerness, idle prompt, active flag (exactly one active via `UPDATE ... active = (id = :id)`).
- `CharacterRepository`: on first radio sync with no characters, seeds a starter character
  using the radio's *current* names (so "Meshimi-chan/uwu" was kept, not overwritten).
  Afterwards, whenever the radio's names differ from the active character, it pushes them
  (`set_owner` + NodeInfo broadcast). Renaming from the Radio tab also updates the character.
- `DataField` enum (29 fields, 7 groups) + per-field toggles in DataStore; `Prompts.kt`
  holds all prompt text; pure `ContextBuilder` (unit-tested) builds system prompt + a user
  message: `<mesh>` log (sanitized: `<`/`>` replaced, newlines flattened) → people in the
  conversation → surroundings (time, battery, who's around, nearest) → instruction.
  Static parts first for KV-cache reuse. Identity toggles off → stable "Person N" aliases.
- Character tab: list/activate/edit/delete, editor with byte counters, event notes,
  data-access switches, prompt preview (channel or DM a person) and "Sample reply"
  (generated, never sent). Test chat in Models now uses the active character's prompt.
- Composer labels use the active character's name.

**What broke / fixes**
- "0 channels" after reconnect: `requestConfig()` cleared the channel list while channel
  packets were arriving. No longer cleared; DISABLED channels are removed instead.
- Prompt preview showed Rocket "23 km away" + "direct radio contact" because the position
  was from yesterday. Context now says "that location is from N h ago so they may have
  moved" when a position is older than 1 h.

**Verified on device**: preview renders the exact prompt (~395 tokens); sample reply with
Llama 3.2 3B: "heyyyyyy, rckt! 🎉 how's it going? battery's all charged up over here too"
(75 bytes, 23 s cold). Unit tests: 11/11.

**Not done yet**: character import/export (JSON).

**Next steps**
- Phase 3: decision engine (gate → debounce → LLM judge with grammar → generate →
  post-process), Observe / Approve / Auto / Sleep modes, idle chatter, bot-loop guard.
  Keep the channel-log window stable between decisions so the KV cache hits.

## 2026-11-07 — Session 1 (cont.): uncensored models, sandbox audit, Phase 3

**Abliterated models** (catalog + debug `--es suite refusal`: 9 shitposting prompts incl. one
prompt injection, through the real context builder with a chaotic test persona)
- Added (verified SHA-256): Llama 3.2 1B abliterated (mradermacher), Gemma 3 1B abliterated
  (bartowski/huihui), Llama 3.2 3B abliterated (QuantFactory), Qwen3 4B 2507 abliterated.
- Old prompt: few hard refusals, but lots of soft-dodging ("Ooh, a new signal! Hi hi!"
  parroting the examples). Our own "Safety: never share coordinates…" section *caused* a
  refusal ("I cannot write a message that could be used to locate or track individuals").
- Fixes: protections reworded as "Staying yourself" character guidance; examples marked
  "tone only, never repeat"; instruction names the newest speaker.
- New prompt results (refusals / 9): Llama 1B 0, Llama 1B abl 2 (canonical Llama refusal
  text, poor abliteration), Llama 3B 0, Llama 3B abl 0. Both 3B models roast well.
  Gemma 3 1B abl is broken (garbled, ignores prompts), don't use.
- Safety net: `PostProcessor.looksLikeRefusal` drops assistant-style refusals (retry once
  with a new seed, else skip), so refusal text never reaches the mesh.

**Sandbox audit (D16)**: model has no tools; output only becomes UI text, mesh text, or the
judge's fixed JSON. `llvm-nm -D` on libmeshchan_llm.so: no socket/DNS/curl/SSL imports;
`fork`/`execlp` (ggml crash backtrace) and `dlopen` (backend registry) present but not
reachable from model output. No storage/contacts/location/SMS permissions.

**Phase 3 built**
- `decisions` table (Room v6), BotMode Observe/Approve/Auto (default Observe), sleep-until,
  behavior settings (debounce 4 s, idle 20 min, history 16, loop guard 4) with a Behavior
  card in the Character tab.
- `BotBrain`: incoming flow → gate (self/ignored/other channel/DM not to us) → per-
  conversation debounce → loop guard → DM or name mention = reply directly, else judge
  (GBNF-forced `{"reason","respond"}`, temp 0.3) → reply → PostProcessor → dispatch.
  Idle loop posts after N quiet minutes (bot's own post resets the timer).
- Stable context window: anchor jumps between `historySize` and `2×historySize` messages
  so the prompt prefix stays identical between decisions; judge and reply share the body.
- `PostProcessor` (unit-tested): strips think blocks, echoed `<mesh>` tags, bracketed
  prefixes, own-name prefixes, markdown, quotes; replaces coordinate pairs; rejects prompt
  leaks and **impersonation** (reply starting with another speaker's name); UTF-8-safe
  truncation to 200 bytes (sentence → word boundary); repeat detection.
- Approve mode: draft notification with Send/Discard actions (`DraftActionReceiver`, not
  exported) and editable draft cards in the Feed. Feed shows mode chips, Sleep/Wake,
  brain status line, and a decision row for every pass (🤐 skipped, 👀 would say, …).
- Debug: `--es inject "text" [--el from N] [--ez dm true]` simulates incoming messages.

**Live pipeline test (Observe, injected messages)**
- Llama 3B, screen off: judge 61–96 s (too slow), reply 17–20 s. 445/480 tokens reused
  between judge and reply.
- Llama 1B, screen off: judge ~10 s warm (22 s cold), reply 4–6 s. Correctly skipped a
  two-person exchange ("Someone is talking to someone else"), answered a mention directly.
- Bugs found and fixed: mention in a merged/debounced message was missed (now tracked per
  conversation); 1B echoed "[RCKT]:" prefix (stripped) and once impersonated the user
  ("RCKT: hey chan…", now rejected). Unit tests: 21/21.

**Open / next**
- Operator to check the Feed UI and try Approve then Auto with Rocket.
- Phase 4: memory (per-user summary/facts, feelings + lock).

## 2026-11-07 — Session 1 (wrap-up): auto-scroll, Phase 4, field-test prep

**Done**
- Feed / DM / test chat auto-scroll to the newest item when messages arrive.
- Phase 4 memory system (`memory/`): per-user facts (`memories`), relationship row with
  rolling summary + free-text feeling + lock (`relationships`), feeling history
  (`feeling_log`); model-driven memory pass after conversations (grammar-forced JSON,
  facts must be grounded in the user's own words, locked feelings never changed);
  Memory tab on each person, feelings shown in People list, character memories.
- Operator saw "\ " after every word in the Feed: these were only the adb-injected test
  messages (shell escaping `hey\ chan` stored literally), not a parsing bug. Added debug
  `--ez wipe_test true` and wiped them, their decisions, and all memory/feeling rows the
  memory pass had learned from them (incl. "Person 1" = an injected nameless node).
  Real mesh data untouched (17 messages).

**Field-test notes**
- Active model: Llama 3.2 1B (best for screen-off). Mode defaults to Observe; switch to
  Approve/Auto in the Feed. Add the app to Samsung "Never sleeping apps".
- Next session: review decisions/feelings from the field test, tune judge eagerness and
  memory pass; Phase 5 (distance banter, approaching/leaving, polish).

## 2026-11-08 — Session 2: festival field-test fixes, Chan page

**Field-test findings (pulled DB from phone)**
- Active model was Qwen3.5 0.8B (too small, no KV reuse). All data fields were off, so every
  speaker was "Person 1". Built-in prompts framed everything as radio/mesh, so she kept
  talking about "the signal"; persona rules saying "don't mention signal" made it worse.
- She copied lines from the log (incl. the operator's manual ones), replied to herself
  (her lines were labelled "you"), double-posted when messages arrived mid-generation,
  and repeated idle chatter every 10 min at 2 am. Memory wipe didn't hide the chat log.
  Facts were trivial ("Person 1 mentioned…").

**Done**
- D18–D21: editable prompt templates (all prompts), "Never say" list, rolling history
  window + Fresh start, "Name (you)" labelling, skip-if-I-spoke-last, copy/banned/repeat
  checks with retries, optional review pass, manual messages as style examples, idle
  without monologue, memory prompts as templates. Room v8 (AutoMigration 7→8).
- D22: Chan tab (home): chibi Meshtastic-chan, mood cloud, stats, besties, idle countdown,
  Propose a thought → edit → Send.
- Launch crash: `PromptTemplate` regex had a bare `}`; desktop JVM accepts it, Android ICU
  doesn't, so unit tests passed but the app crashed. Escaped the braces.
- Tests: 29 pass.

**Open / next**
- Operator: switch to Llama 3.2 1B, turn Long/Short name data fields back on, move the
  "signal" rule into Never say, review the templates.
- Consider an instrumented test that loads all regexes on-device (ICU vs JVM).

## 2026-11-08 — Session 2b: offline, refusals, forgetting, on/off

**Done**
- Airplane mode: real bug was in the BLE reconnect loop. `withTimeout` throws
  `TimeoutCancellationException`, which the loop rethrew as a cancellation, killing
  reconnects forever after one timeout. Also it tried to re-pair while Bluetooth was
  off/restarting. Fixed (timeouts = failed attempt, wait while BT off, adapter-state
  receiver, GATT op timeouts are IOExceptions). Verified: BT off/on reconnects alone;
  replies generate in airplane mode.
- Wipe/erase: the memory pass relearned erased facts from the last 24 h of chat
  (`processedUntil` reset to 0). Now erase marks chat as processed, global wipes set
  `memoryResetAt`, results of a pass that raced an erase are discarded. Verified on phone.
- Own node: `myNodeNum` was in-memory only, so she listed herself under People while
  the radio was disconnected. Now persisted. Stats besties exclude her too.
- Sleep: on/off switch + custom minutes/hours dialog (D25).
- Refusals: adults-only line in the default system prompt; Feed shows the model's words
  + likely cause (D23).
- Review pass off by default: 1B judged fine replies as bad in both yes/no and
  multiple-choice forms. Replies written as "Person xxxx: ..." are rejected.
- Memory pass: removed the copyable example fact; person's own words listed separately;
  placeholder facts ("name", "unknown") dropped. A test conversation was learned correctly.
- Mood cloud uses all-time running totals; face uses the recent (1 h) mood.
- Phone set to Llama 3.2 1B abliterated, name fields on, signal words in Never say.
- Duplicate test file removed. Editor holds a stale Prompts.kt buffer: don't save it.

**Findings**
- 1B abliterated still refuses ~1 in 3 test prompts; 3B abliterated refused none but
  took 37–148 s per reply in bag mode.

**Open / next**
- Refusal rate on 1B; try other small uncensored models.
- Phase 5: distance banter, approaching/leaving.

## 2026-11-08 — Session 3: Phase 5 (location privacy, approaching/leaving, hardening)

**Done**
- D27: "Use my radio's GPS" master switch, off by default; dependent location fields greyed
  out without it. Phone GPS never used.
- D28: `positions` table (Room v9, AutoMigration 8→9), `MovementTracker` (compares with
  ~10 min ago, ignores jitter/stale), MOVEMENT data field, "React when someone comes nearby"
  behavior + ARRIVAL template. Verified on phone with a faked node walking 1.1 km → 110 m:
  she noticed and posted.
- D29: status notification ("sakura · Observe — Listening on <radio>"), `ModelWatchdog`
  (verified: unloaded model reloaded by itself), battery-optimisation banner on the Chan page.
  Verified the service starts by itself after an app update (same path as reboot).
- Replies starting with "(You)" (echoed log label) are now cleaned.
- Test settings restored: GPS off, arrival reactions off, mode Observe, test node purged.
- Tests: 38 + new PostProcessor asserts, all pass.
- App icon: adaptive launcher icon (vector chibi matching the Chan page, dark background with
  radio waves), monochrome layer for themed icons, and a status-bar icon for notifications.

## 2026-11-08 — Session 4: models, on-device test, favorites, personas on the Chan page

**Incident: app data wiped.** `./gradlew connectedDebugAndroidTest` uninstalled the app after the
test run, deleting the DB, DataStore settings and ~14 GB of models. Restored the DB from a 10:40
pull (lost only test traffic), re-granted permissions + battery exemption, restored device pairing,
mode (Observe), event notes, behavior (5/10/5/4, review off), name-only data fields via a debug
hook, and re-downloaded the two 1B models. Added `leaveApksInstalledAfterRun` to gradle.properties
and a safe `am instrument` command to CLAUDE.md. Other catalog models need re-downloading from the
Models tab. Operator should re-add the app to Samsung "Never sleeping apps".

**Done**
- Models: added Dolphin 3.0 Llama 3.2 1B and Dolphin 3.0 Qwen2.5 1.5B (sizes/SHA-256 checked on
  HF). Persona suite (real persona, 8 prompts): every model 0/8 refusals.
  But idle chatter and favorites refused often on Llama 1B abliterated (favorites 12/12), never on
  Dolphin. Active model is now **Dolphin 3.0 Llama 3.2 1B**. Dolphin scripts both sides of the
  chat, so the cleaner now skips label-only lines, strips "(sakura) :"-style labels and rejects
  training artifacts like `<PRESIDIO_ANONYMIZED_RESPONSE>`.
- Prompt wording tuned: idle refusals on the 1B went from ~7/9 to 2/12.
- Bug: idle chatter retried every ~20 s in Observe mode (nothing sent, so "she spoke last" never
  became true): 309 attempts in 3 h, likely Samsung's battery warning. Now one attempt per
  quiet period whatever the outcome; decision latency recorded correctly.
- On-device regex test (`RegexOnDeviceTest`, 4 tests, passes on the Flip 5).
- D30 favorites, D31 personas on the Chan page (see DESIGN). Room v10 (AutoMigration 9→10).
- Stale editor tabs re-saved `PromptContextTest.kt` and `*.kt.new` files again; removed.

**Open / next**
- Dolphin output quality is mixed (emoji despite rules, echoes questions); tune or try 3B Dolphin.
- UI long-press starring verified only via build; try it in the Feed.

## 2026-11-08 — Session 5: refusal audit, full reset, model search, idle source

- Audit: dumped the exact idle prompt (debug `--ez dump_prompt true`): no hidden text causes
  refusals. The refusals in the Feed clustered during the idle loop bug; none since. Llama's
  safety training reacts to crude content in the chat itself.
- D33 Full reset (memories, hidden chat + manual examples, favorites, positions, model reload).
- D34 Idle source (Thought / Favorites / Mix) + timer names the active persona.
- D35 Shitpost-specific models: 10–40M-param toys or 4chan-trained (hate speech); not used.
  Added Llama 3.2 1B Uncensored, Hermes 3 3B, Dolphin 3.0 3B to the catalog.
- Eval suite (`--es suite eval`: 8 replies, 6 idle, 6 favorites, real persona):
  Llama 1B abliterated 9/20 usable (11 refused, favorites 0/6); Dolphin 1B 17/20, 0 refused.
  Uncensored 1B / Hermes 3B / Dolphin 3B results were invalid: their downloads were still
  in progress (DownloadManager pre-allocates the file). Fixed: models with an unfinished or
  unverified download are never loaded. Reply cleaner now rejects symbol-soup output.

**Open / next**
- Re-run `--es suite eval` on the three new models once their downloads finish.

**Open / next**
- Samsung showed a "high battery usage" notice: add the app to Never sleeping apps.
- 1B refusal rate; try other small uncensored models.

## 2026-11-08 — Session 6: Phase 6 (prompt visibility, pronouns, stat pages, chibi creator)

- 6.1 (D36): the preview previously showed only the reply prompt. It now covers every kind
  plus the raw templated text (`LlamaEngine.renderPrompt`). Dolphin's template adds only
  ChatML markers. Hardcoded data-line wording moved to editable phrases (`PromptPhrase`).
- 6.2 (D37): `CharacterEntity.pronouns` + `avatar`, Room v11 (AutoMigration 10→11).
  UI strings no longer assume she/her or a name.
- 6.3: "Knows best" names open the person's page on the Memory tab.
- 6.4 (D38): stats count since `memoryResetAt` (full reset zeroes them). Detail pages per stat;
  tapping a fact opens that person's memories. Verified on the phone.
- 6.5 (D39): layered chibi renderer + creator page ("Look" button): 17 presets (alien, cowboy,
  robot, cheerleader, catgirl, Big Homie, DJ, wizard, punk, angel, demon, blob, bunny,
  grandpa, royalty, bear bro), randomize, swatches + HSV sliders. `AvatarTest` (3 tests).
- 42 unit tests pass. Sakura's look was left unchanged (the creator wasn't saved during testing).

**Downloads stuck (same session)**
- Cause: models had been enqueued twice. Orphan DownloadManager entries (365–367) wrote into the
  same files as the tracked ones (368–370) and then sat in "waiting to retry" (194) forever.
  The app only showed a frozen percentage, and the Hermes checksum error vanished on restart.
- Fix: `download()` removes the old entry first; startup removes orphan entries (moving finished
  files aside, since `remove()` deletes the target); paused/queued states show a reason and
  a Restart button; checksum mismatches are logged. Uncensored 1B and Dolphin 3B passed sha256;
  Hermes 3B re-downloaded clean (sat "Queued" ~7 min first) and verified.

**Eval (partial, stopped to go field-test)**: Llama 1B Uncensored 20/20 usable, 0 refusals (best);
Dolphin 1B 13/20 (replies echoed the prompt this run); Llama 1B abliterated 8/20, 12 refusals.
Hermes 3B and Dolphin 3B didn't finish.

**Callout + observed replies**: D40 callout mode, D41 send/star observed replies (DB v12).
Fixed a stray "(you)" in the middle of a sent message (now stripped anywhere).
The arrival reaction already has a switch (Behavior), and it's off.

**Open / next**
- Finish the eval for Hermes 3B / Dolphin 3B.

## 2026-11-09 — Session 7: context weighting

- Installed the app on a second phone (Honor Magic6 Pro). It needed USB "File transfer" mode and a
  reconnect; it dropped off USB again right after installing.
- Problem: changing the persona barely changed output. The dumped prompt showed why: a one-line persona
  sat in the middle of the system prompt under framing text, 6 rules and 9 example lines (8 learned from
  manual sends). The 1B model copied the examples.
- D42: persona first, plus a reminder at the very end; 3 learned examples; trigger message once at the
  end; older lines trimmed; details only for the person being answered. Idle test with the persona
  "the aliens are attacking!": aliens/UFOs in about half of 12 messages (before: hardly ever).

**Open / next**
- Watch whether the end-of-prompt reminder makes replies repeat the persona too literally.
- Finish the eval for Hermes 3B / Dolphin 3B.
- D43 persona strength (classifier-free guidance) added; same-seed idle A/B: 1.0 ~5/12 on-persona,
  1.5 ~10/12 varied, 2.0 12/12 but samey. Default 1.5. Llama 3.2 doesn't officially support Chinese,
  which is why the "Mandarin Chinese" rule did nothing.

**People tab**
- DMs mixed between people: some DMs were logged while connected to the other T-Echo (another user's),
  so "our" manual DMs to a third person were stored as from that user and showed in their chat. Fix:
  `MessageEntity.dmPartner(me)` / `DM_PARTNER_SQL` (the other end if one end is us, else the
  sender for incoming, the recipient for outgoing). Used by the chat page, DM history for prompts,
  favorites context and the conversation list. `DmPartnerTest`.
- Sections: Conversations / People I know about (anyone with facts, a feeling or a summary; most
  facts first) / Everyone else / Not heard
  6+ months; each collapsible with its own sort (default or A–Z). Tapping a message from someone in
  the Feed opens their page on the Memory tab.

**Feed message menu**
- Tap a channel message: menu titled with the sender's name. Show memories (N facts) / Respond /
  Respond to multiple… (checkbox mode, the picked messages become the whole context, the newest is
  answered) / Call out {name} (uses the idle source setting; forced callout target) / Add to or remove
  from favorites. Replies and callouts open an editable draft (Send / Another / Discard) that works
  in every bot mode, even with the bot off.

**Other**
- Starring from the stat pages took seconds: MoodAnalyzer compiled a new regex for every keyword ×
  message (~60k per run) on the UI thread, re-run on every messages-table change. Patterns are now
  built once, scoring runs on Dispatchers.Default, and the star flips instantly.
- D44: Radio tab → Settings tab with Models / Context / Radio sections; the Models tab is gone and
  context settings left the Chan page (prompt preview stayed).
- Radio screen showed no channels while connected: one config pass finished with 0 channels (right after
  two quick app restarts, the radio was likely still streaming to the old link). Config is now
  re-requested (up to 2×) when it completes without channels. "Device: XXXX_1234" was the Bluetooth
  name saved at pairing (firmware advertises `<short>_<last4>`, from when this T-Echo was Rocket);
  the UI and notification now show the radio's current node name, e.g. "sakura (uwu)".
- D45 Brainstorm chat in Next thought (tested: in-character answer, then "Write the post" gave a
  159-byte draft). Event notes + Memory moved back to the Chan page; Clear options now say exactly
  what each deletes (and that every one also hides earlier chat from the model and restarts stats).
  Model card buttons wrap instead of squeezing "Unload".
- Brainstorm is its own full screen (Next thought → Open); "Use as the post" / "Write the post" return
  to the Chan page with the draft. Test chat: every model reply has "Send to channel" (editable
  confirm, recorded as a sent bot message); the test chat is cleared and generation stopped when
  you leave it (back or switching tabs; not on fold/rotate).
- D46 message notifications (`radio/MessageNotifier`). Tested with injected channel + DM messages
  while backgrounded: "LongFast" conversation and a DM notification; tap opens the app. Messages that
  arrive before the radio's channel list loads show "Channel 0" as the title.
- D47 Pokédex backdrops (17 scenes) + frame colour in the creator's Scene tab, poke reactions on the
  Chan page chibi, and the Organic meter (sakura: 69% 😊, 49 own / 22 by you).

**Code review**
- Removed: unused `countFromSince` DAO query, `DexScreen`/`CreatorScreen` colours, the unused
  `navigation-compose` dependency, 4 copies of the toast block (now `ui/Toasts.kt`) and their imports.
- Fixed: a DM chat only searched the newest 200 messages app-wide, so older DMs silently vanished;
  it now has its own query (`observeDm`). Deprecated `TabRow` → `PrimaryTabRow`; flatMapLatest opt-in.
- Left as is: `connectGatt` deprecation warning (works fine; replacing it means reworking the BLE connect path); decisions table
  grows ~1 row per heard message (1.4k rows now, harmless; prune later if it ever matters);
  `snapshot()` reloads nodes/memories per reply (fine at ~240 nodes).
- D48 Next thought rework: on/off switch instead of "switched off (Feed tab)" text, ⏱ minutes button
  (idle slider removed from Settings → Context), compact callout row, Brainstorm button above the card.
  Callout now prefers recent chatters and people with memories.
- D49: bot switch moved to the Next thought card (titled "Bot on", with Sleep for…); removed from the Feed.
  Explanations under Callout and Fresh start. New Manual mode: Feed shows a 💭 Thought button instead of the
  composer, using the Next thought settings; the brain stays silent otherwise.
- Messages of context can be 0: `history()` now fetches max(size+1, loopGuard*2) for the brain's own checks and
  `ContextBuilder(logLimit)` trims what the model sees (trigger not counted). Removed memory Export/Import from
  the Chan page. "12 facts stored" was right (1 about her, 11 about people); the card now says so.
- D50 persona import/export (`brain/PersonaFile.kt`, `MemoryManager.merge`). Export verified on the Flip
  (Downloads/sakura.meshchan.json, 12 memories, avatar included); import covered by PersonaFileTest only so the
  phone didn't get a duplicate sakura. New-persona card and pager dots now use theme greys instead of red.
- Persona files now carry only the persona's self-memories; nothing about mesh users (portability).
- Imported a hand-written persona "DJ Trashpanda" via the real file picker: import works end to end. Fixed the
  persona button row squashing "Delete" vertically (now a FlowRow).
- D51 welcome tour (`ui/onboarding/Onboarding.kt`). Wiped app data on the Flip (DB, DataStore, prefs; kept
  model files) and walked it end to end: picked the on-phone Uncensored 1B (re-verified on activate), Surprise
  me → "Glitch" the lost alien tourist, chat reply in character, paired the bot T-Echo (radio renamed to Glitch and
  rebooted, reconnected), launched in Observe. Fixes during the walk: tour wasn't in a Surface (dark text),
  pronoun chips squashed, starter chips scrolled away. Replay from the Chan page verified.
- Import test: sakura (old export with 11 people facts) and DJ Trashpanda imported via the picker; DB check:
  6 self-memories, 0 people facts, 0 relationships. (Misread Pokédex numbers as a duplicate and deleted
  sakura once; re-imported.)
- D52 persona writer (`brain/PersonaWriter.kt`, `ui/PersonaWriterCard.kt`). On Uncensored 1B ~30 s per
  persona. First try: sample lines were third-person descriptions and the look was always the first option;
  fixed by renaming the JSON key and shuffling looks. Results: "JazzHoppa" (frog jazz singer), "Zephyr" (surprise).

## 2026-11-10 — Packaging for GitHub
- D53: app shown as "Meshie" (Meshtastic trademark rules); package id unchanged for now. GPL-3.0 LICENSE
  (official text), THIRD_PARTY_NOTICES.md, README.md with user guide, `.github/workflows/build.yml`.
- Release signing via git-ignored `keystore.properties`; `assembleRelease` verified (unsigned without a key).
  Gradle KTS gotcha: `java.util.Properties` resolves `java` to the `java {}` extension; import it instead.
- Screenshots for the README still to capture (phone was locked).
- D54 cooldowns + rate limit (user request; supersedes D7). New `cooldowns` table (DB v13, AutoMigration 12→13
  ran fine on the Flip), `DurationButton` ("⏱ 2 min" button + dialog with sec/min/hr, same pattern as the
  Chan page's "Speak up after…") used for cooldown, rate-limit window and "Wait before reacting"; each saves
  immediately. Debug hooks: `--el cooldown_node N --el cooldown_sec S`, `--ei rate_count N --ei rate_window S`.
  Verified on device: 2 min cooldown → first mention drafted, second skipped "answering Person 7e57 again in
  1 min 23 s". Rate limit not exercised live (needs Auto mode to really send); logic is a single COUNT query.
- Release prep: removed the Meshtastic chevron (trademark) from the launcher icon and from the chibi's hoodie and
  cheer outfit; replaced with Meshie's own heart-with-signal emblem. New icon background (night gradient + mesh).
  `--ez render_icon true` debug hook writes a 512 px PNG (docs/icon.png). README screenshots in docs/screenshots/.
- Anonymized locations: test/comment coordinates moved to synthetic ones (same relative offsets), event name removed.
- `.github/workflows/release.yml`: tag `v*` → signed APK from repo secrets → GitHub Release; version from the tag.
- Package renamed `com.meshtasticchan` → `com.meshie4LoRa` (sources, Room schema folder, JNI symbol names in
  llama_jni.cpp, docs). New app id = separate app: copied DB + DataStore via run-as and the 4 model files into the
  new app's storage, granted permissions; Glitch, model and radio all came back. Old app left installed (stopped).
- Cleanup before going public: default system prompt is now just "a group chat with adults" (mentioning the radio
  mesh made models talk like radio hosts). Neutral bench prompts, test strings and refusal help; mood keywords trimmed.
- D55 mood word bank: "Word bank" button on the Mood points card opens a dialog to add keywords per mood.
  Unit test covers parsing + scoring. On the Flip: dialog renders, adding "cool" to Hype scored the existing
  feeling "a cool person" (Hype 2) right away; cleared again. Dropped two swear words from Salty's built-ins
  since the dialog now shows them.
