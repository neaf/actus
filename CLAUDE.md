# Actus – Claude Code notes

## What this is

A Bitwig Studio controller extension for **Ableton Push 2**, focused solely on
**live performance** — session/clip launching, mixing, transport. Deliberately
**not** trying to cover full production workflows (device editing, deep step
sequencing, etc.) the way general-purpose frameworks do. When adding a feature,
ask whether it serves playing live; if not, it's probably out of scope.

## Relationship to DrivenByMoss

`~/dev/DrivenByMoss` is Jürgen Moßgraber's multi-controller framework
(LGPL-3.0), which includes a full Push 1/2/3 implementation. We treat it as a
primary reference and **adapt its code directly when it's already a good
solution** — no obligation to reinvent something different just to avoid the
resemblance. That's why this repo is LGPL-3.0 too (see `LICENSE`), matching
upstream instead of doing per-file license bookkeeping. Credit lives once, at
the repo root, in `CREDITS.md` — don't add per-file "adapted from Moss"
headers, the user explicitly doesn't want that noise.

DrivenByMoss's own `~/dev/DrivenByMoss/CLAUDE.md` has good architecture notes
(Views vs Modes, cursor button wiring, config option pattern, clip nav API,
LED arrow states) — read it when porting behavior from there. Its Push 2 code
lives under `src/main/java/de/mossgrabers/controller/ableton/push/`.

Important: DrivenByMoss is a huge generic framework (supports dozens of
controllers). We are not building a framework — keep Actus a small, direct
Push-2-only extension. Borrow logic, not architecture-for-generality.

## Build system

Plain **Maven**, not Gradle (DrivenByMoss's own CLAUDE.md says Gradle, but its
actual `pom.xml` is Maven — that note is stale/wrong, ignore it).

- `./setup.sh` — checks Java 21+ and Maven are installed, resolves deps. Run
  once per machine / after a long gap.
- `./build.sh` — `mvn clean package`, which also copies+renames the jar to
  `~/Documents/Bitwig Studio/Extensions/Actus.bwextension` (a Maven plugin
  execution bound to the `package` phase in `pom.xml`, not a shell step).
  Override the install location with `BITWIG_EXTENSION_DIR=...`.
- Only dependency: `com.bitwig:extension-api:21`, scope `provided` (Bitwig
  supplies it at runtime — do not bundle/shade it into the jar).
- After building, **restart Bitwig Studio** (or use its controller rescan) to
  pick up changes — it doesn't hot-reload `.bwextension` files.

Local environment: Bitwig Studio 6.1.1 is installed at
`/Applications/Bitwig Studio.app`. DrivenByMoss's own `.bwextension` is
already installed alongside ours in the same Extensions folder — useful for
A/B comparison while testing.

## Project layout

| Concern | Path |
|---|---|
| Extension identity, MIDI auto-detection, USB device matcher | `src/main/java/com/actus/push2/Push2ControllerExtensionDefinition.java` |
| Extension instance, central MIDI dispatch (init/exit/flush) | `src/main/java/com/actus/push2/Push2ControllerExtension.java` |
| Push 2 screen rendering + USB frame send | `src/main/java/com/actus/push2/Push2Display.java` |
| One row of the pad grid, dispatched by note range (see matrix-region note) | `src/main/java/com/actus/push2/PadRow.java` |
| Bottom pad row = clip launcher for the active scene | `src/main/java/com/actus/push2/Push2ClipLaunchRow.java` |
| Row above it = per-track stop (independent of active scene) | `src/main/java/com/actus/push2/Push2StopRow.java` |
| Scene Launch + Stop All Clips + Up/Down buttons (own the active-scene state) | `src/main/java/com/actus/push2/Push2SceneButtons.java` |
| Push 2's 128-color palette + nearest-color matching | `src/main/java/com/actus/push2/Push2Colors.java` |
| Writes the color palette to the device via SysEx on init | `src/main/java/com/actus/push2/Push2Palette.java` |
| Global pad LED brightness (top-left encoder) | `src/main/java/com/actus/push2/Push2Brightness.java` |
| SPI registration (required for Bitwig to find the definition) | `src/main/resources/META-INF/services/com.bitwig.extension.ExtensionDefinition` |
| Build config, install-path property | `pom.xml` |

Package root is `com.actus.push2` — keep everything under this until there's
an actual reason to split packages (e.g. `com.actus.push2.view`,
`.mode`, `.command` once those layers exist, mirroring DrivenByMoss's split
but only introduced when the code volume justifies it).

## Bitwig Extension API basics (v21, learned by inspecting the jar directly —
there's no bundled source/docs, use `javap -classpath ~/.m2/repository/com/bitwig/extension-api/21/extension-api-21.jar <class>`)

- `ControllerExtensionDefinition` (abstract): `getName/getAuthor/getVersion/getId`
  (from parent `ExtensionDefinition`), `getHardwareVendor/getHardwareModel`,
  `getNumMidiInPorts/getNumMidiOutPorts`, `listAutoDetectionMidiPortNames`,
  `getRequiredAPIVersion`, `createInstance(ControllerHost)`.
- `ControllerExtension` (abstract): `init()`, `exit()`, `flush()`; provides
  `getHost()`, `getMidiInPort(int)`, `getMidiOutPort(int)`.
- `PlatformType` enum: `WINDOWS`, `MAC`, `LINUX` (no `MAC_ARM` split like
  DrivenByMoss's own `OperatingSystem` enum uses — Bitwig's is coarser).
- `AutoDetectionMidiPortNamesList.add(String[] inputNames, String[] outputNames)`
  registers one candidate port-name pairing per call.
- `ControllerHost.println(String)` / `showPopupNotification(String)` for
  logging/user feedback during development. Console output only appears in
  Bitwig's Controller Script Console (Settings → Controllers → select Actus),
  not any file on disk.
- Push 2 MIDI port name on macOS: `"Ableton Push 2 Live Port"` (confirmed via
  DrivenByMoss's `Push2ControllerDefinition.java`). Windows/Linux names in our
  definition are best-guess from DrivenByMoss and **untested** — verify against
  real hardware before relying on them.

### Gotcha: Bitwig's Value objects are lazy — `.get()` throws unless marked interested

Any `Value<T>` (e.g. `IntegerValue`, `BooleanValue`) throws on `.get()` if
never subscribed to: *"Either call markInterested() or add at least one
observer in init..."* (`ValueProxy.checkCanGet`). An observer
(`addValueObserver(...)`) satisfies this automatically; for a value you only
ever read on-demand (no callback needed), call `.markInterested()` on it once
during `init()` instead. **Rule: any Bitwig `Value` you plan to `.get()`
synchronously — not just ones you observe — must be marked interested (or
observed) during `init()`, full stop.** (`Push2SceneButtons` marks interest
on all 128 scenes' `clipCount()` for exactly this reason — its
`findInitialScene()` reads them synchronously.)

### USB (screen, and later pad LEDs)

Bitwig's own API (v21) has native USB support — no need for DrivenByMoss's
JNA/purejavahidapi dependencies. Two parts:

1. **Declare the device**, in `ControllerExtensionDefinition.listHardwareDevices
   (HardwareDeviceMatcherList)`: build a `UsbDeviceMatcher` (expression string
   `"idVendor == 0x... && idProduct == 0x..."`) wrapping `UsbInterfaceMatcher`
   (expression `"bInterfaceNumber == 0x..."`) wrapping `UsbEndpointMatcher`
   (`UsbTransferType.BULK`/`INTERRUPT` + endpoint address byte).
2. **Use it**, in `ControllerExtension.init()`: `host.hardwareDevice(0)` (index
   = position among matchers you registered) cast to `UsbDevice`, then
   `device.iface(0).pipe(0)` (indices = position within your interface/endpoint
   matchers) cast to `UsbOutputPipe`/`UsbInputPipe`, then
   `pipe.write(MemoryBlock, timeoutMs)`. Buffers come from
   `host.allocateMemoryBlock(size)` (declared on the parent `Host` interface,
   not `ControllerHost` itself).

Push 2's screen protocol (960x160, confirmed working via `Push2Display.java`):
header = 16 bytes `FF CC AA 88` + 12 zero bytes, sent as one `pipe.write()`;
then the frame = fixed 327680 bytes (`20 * 0x4000`) as a second `pipe.write()`.
Pixels are BGR565 (not the more common RGB565 — blue in the high bits), each
row padded to a fixed stride, and the *entire* frame buffer must be XORed
4-bytes-at-a-time with `E7 F3 E7 FF` before sending ("signal shaping" — see
Ableton's own spec, linked in the file). Vendor/product ID `0x2982`/`0x1967`,
interface `0`, bulk OUT endpoint `0x01`.

Drawing uses Bitwig's own Cairo-like 2D API (`host.createBitmap(w, h,
BitmapFormat.ARGB32)`, then `bitmap.render(gc -> ...)` with `GraphicsOutput` —
`setColor`, `rectangle`/`fill`, `setFontSize`, `showText`,
`getTextExtents`/`getFontExtents` for centering). No font file bundled —
omitting `setFontFace` falls back to a default font. `bitmap.getMemoryBlock()
.createByteBuffer()` gives raw pixel bytes ready to encode, no stride
surprises.

**Gotcha: Push 2's screen blanks a few seconds after its last frame**, and
`ControllerExtension.flush()` is NOT a timer — since Bitwig 3.1 it only fires
on actual DAW state changes. Fix, both halves required:
- `flush()` calls `display.refresh()` (re-sends last rendered frame, no redraw).
- `keepDisplayAlive()`, scheduled once from `init()` via
  `host.scheduleTask(this::keepDisplayAlive, 100)`, calls `host.requestFlush()`
  then reschedules itself every 100ms until `exit()` sets `running = false`.
  `requestFlush()` just asks Bitwig to call `flush()` once, soon.

**Naming:** the human-readable name passed as `UsbDeviceMatcher`'s first
constructor arg is what shows up in Bitwig's Settings → Controllers hardware
device list. Match DrivenByMoss's convention:
`getHardwareVendor() + " " + getHardwareModel()` → `"Ableton Push 2"`.

### Pad grid + buttons (MIDI, not USB — only the screen is USB)

- **Grid note mapping**: linear, `note = 36 + column + 8 * rowFromBottom`
  (column 0-7 left→right, row 0 = bottom, row 7 = top; note 36 = bottom-left,
  99 = top-right). Channel 0. Press = Note On with velocity, release = Note
  On velocity 0 or Note Off — both normalize to "velocity 0 = release".
- **Pad color = Note On on the same note**, `data2` (0-127) is a palette
  index, not a real velocity. Off = index 0.
- **Push 2's 128-color palette lives in the device's volatile memory, not
  firmware.** A hardware power-cycle resets it to Push 2's true cold-boot
  palette, which differs from DrivenByMoss's `DEFAULT_PALETTE` table.
  `Push2Palette.write()` writes `Push2Colors.PALETTE` (table copied verbatim
  from DrivenByMoss's `PushColorManager.DEFAULT_PALETTE`) to the device via
  SysEx on every `init()`, so Actus is correct regardless of prior device
  state — don't assume "whatever's already loaded" is right. Same table
  backs `Push2Colors.nearest(r,g,b)` (Euclidean-distance nearest-match,
  approximating a clip's real Bitwig color on its pad) and a few fixed
  semantic indices (0=off, 3=white, 5=red hi, 21=green hi). SysEx write per
  entry: `F0 00 21 1D 01 01 03 <index> <r_lo> <r_hi> <g_lo> <g_hi> <b_lo>
  <b_hi> <w_lo> <w_hi> F7` (white always 0), then reload once with
  `F0 00 21 1D 01 01 05 F7`.
- **Playing clips pulse toward a fixed green (`COLOR_PLAYING_HI = 21`), not
  a hue-matched dim of the clip's own color.** Scaling a clip's RGB down and
  nearest-matching again is unstable near black (unrelated hues' dark shades
  cluster close together), so don't do that — a plain semantic pulse color
  is also just how Push 2 session views normally indicate "playing" anyway
  (confirmed against DrivenByMoss directly).
- **Pulsing/blinking pads**: a pad animates by sending a *second* Note On for
  the same note on a different channel — channel 10 (`0x9A`) = slow pulse,
  channel 14 (`0x9E`) = fast blink. **Sending that second message at all
  turns on animation, regardless of color value** — there is no "steady"
  no-op via matching colors. `Push2ClipLaunchRow.sendPulsing()` (both
  channels, only for playing/queued/recording) vs `sendSteady()` (channel-0
  only) — never send the pulse channel for a steady pad.
- **Clip launch** = `ClipLauncherSlot.launch()` (press) /
  `.launchRelease()` (release).
- **Per-track stop** (`Push2StopRow`, notes 44-51, row 1) uses `Track.stop()`
  / `Track.isStopped()` directly — these operate on whatever's currently
  playing/recording/queued on that track regardless of scene, so unlike the
  clip launch row this needs no per-scene state at all. Dim grey above any
  track that isn't stopped (deliberately subtle, not a loud alert color),
  dark otherwise.
- **Push 2 has 8 dedicated Scene Launch buttons**, separate physical controls
  from the 64-pad grid, CC 36-43 channel 0
  (`PushControlSurface.PUSH_BUTTON_SCENE1..8` in DrivenByMoss). We use two:
  `Push2SceneButtons.LAUNCH_CC = 36` (aligned with the clip launch row) and
  `STOP_ALL_CC = 37` (aligned with the stop row, one up — calls
  `SceneBank.stop()`, matching Bitwig's own "Stop All Clips" button in the
  Clip Launcher's scenes sidebar; unrelated to `activeScene`). The remaining
  6 are ignored/dark — same treatment as pad rows 2-7. CC 36 (SCENE1) is the
  one physically aligned with the bottom pad row (DrivenByMoss layout:
  SCENE1=bottom, SCENE8=top).
- **Encoders send relative deltas as two's-complement 7-bit CC values**: 1-63
  = positive steps, 65-127 = negative (127 = -1, 66 = -62). Decode with
  `value < 64 ? value : value - 128`. The encoder above Tap Tempo (CC 15,
  `PUSH_SMALL_KNOB2` in DrivenByMoss) drives global pad LED brightness via
  `Push2Brightness` — SysEx `F0 00 21 1D 01 01 06 <0-127> F7`. Below 10%
  brightness Push 2's hardware itself glitches (buttons vanish, pads show
  wrong colors — reproduced with DrivenByMoss's own script too, so it's a
  real hardware floor); clamped to [10, 100], default 10%.
- Bitwig's `MidiIn.setMidiCallback()` is single-slot (last caller wins), so
  `Push2ControllerExtension.handleMidi()` is the one place all raw MIDI
  input is dispatched from — grid notes to whichever registered `PadRow`
  owns that note range (`padRows` list), the scene CCs (36 launch, 37 stop
  all) and the Up/Down CCs (46/47) to `Push2SceneButtons`, the brightness
  encoder CC to `Push2Brightness`. Don't add a second `setMidiCallback()`
  call anywhere.

### "Active scene" is controller-local state, not read from Bitwig

Bitwig's Controller API has **no per-scene "is playing" property** — audited
every method on `Scene`, `SceneBank`, `ClipLauncherSlotOrScene`,
`ClipLauncherSlotOrSceneBank` via `javap`. Only per-*clip* state exists
(`ClipLauncherSlot.isPlaying()`/`isPlaybackQueued()`/etc.). The underline/
brighter-button Bitwig's own UI shows when a scene is launched is real,
internal Bitwig state, not exposed to controller scripts.

Confirmed empirically: Push 2's pads/display do **not** react when a scene is
launched by mouse in Bitwig's UI (true of DrivenByMoss's own Scenes mode
too) — so this is controller-local by design, not a limitation we're unaware
of. `Push2ControllerExtension.activeScene` (an `AtomicInteger`, starts at
`-1` = "none yet") changes **only** via our own hardware
(`Push2SceneButtons.onButtonPressed()`/`onNavigate()`), never inferred from
clip playback state.

Three entry points touch it, all in `Push2SceneButtons`:
- **`bootstrapWithoutLaunching()`**, called once from
  `Push2ControllerExtension.init()` — sets `activeScene` (if still `-1`) and
  repaints, but deliberately does **not** call `.launch()`. So the bottom
  row shows the right colors as soon as the project opens, before transport
  play and before any button press.
- **Scene Launch** (`LAUNCH_CC`) — launches `activeScene` exactly like
  clicking that scene's own play button in Bitwig's sidebar. Does not change
  *which* scene is active by itself (beyond bootstrapping if unset).
- **Up/Down cursor buttons** (CC 46/47, `PUSH_BUTTON_UP`/`_DOWN` in
  DrivenByMoss) — moves `activeScene` by one, clamped to `[0,
  MAX_SCENES-1]`. Navigation only, does not launch.

(`STOP_ALL_CC` is a fourth button on `Push2SceneButtons` but deliberately
does *not* touch `activeScene` — it calls `SceneBank.stop()` directly, same
as `Push2StopRow`'s per-track stop calling `Track.stop()` directly.)

Any of the three, if `activeScene` is still `-1`, bootstraps it first via
`Push2SceneButtons.findInitialScene()`: the first scene (0-127) with any
clips, or scene 0 if the whole project has none.

`activeScene` can hold any value 0-127 (`Push2ControllerExtension.MAX_SCENES`
— the track bank is created with that many scenes), all reachable via
Up/Down.

### Matrix region ownership (the "multiple modes share the grid" plan)

Longer-term plan (per user): the 8x8 grid won't be one full-screen "view"
like DrivenByMoss's — instead different pad ranges get claimed by different
concurrent features, likely bottom 4 rows for clip/scene management and top 4
for submode control. Rows 0-1 (notes 36-51) are claimed —
`Push2ClipLaunchRow` (launch) and `Push2StopRow` (stop) — rows 2-7 are left
dark on purpose.

Now that there are two row-consumers, note-range dispatch is a shared
`PadRow` interface (`startNote()` + `onPadPressed(column, velocity)`);
`Push2ControllerExtension` holds a `padRows` list and range-checks against
each in `handleMidi()`. A third row-consumer just needs to implement
`PadRow` and get added to that list — no dispatch changes required.

## Current state (update this section as the project grows)

- Extension registers, MIDI in/out ports 0 are opened, a popup notification
  fires on load/unload. `Push2Palette.write()` runs first thing in `init()`.
- Push 2's screen shows "Actus" centered, kept alive via `flush()` +
  `keepDisplayAlive()`. USB claim wrapped in try/catch so a missing Push 2
  logs via `host.errorln()` instead of breaking the rest of init.
- Bottom pad row (notes 36-43) launches/stops clips on tracks 0-7 for
  whichever scene is active. Pad color = clip's real Bitwig color,
  nearest-matched; recording overrides to solid red; playing pulses slowly
  toward fixed green; queued blinks fast toward white; stopped-with-content
  is steady; empty is dark.
- Row above it (notes 44-51) is a per-track stop row, independent of the
  active scene: dim grey above any track with something playing/recording/
  queued, dark when the track is stopped; pressing calls `Track.stop()`.
  Rows 2-7 are dark.
- Scene Launch button (CC 36) launches/replays `activeScene`; Stop All Clips
  button (CC 37, one row up, aligned with the stop row) calls
  `SceneBank.stop()` — Bitwig's own "stop everything" action, unrelated to
  `activeScene`. The other 6 Scene Launch buttons are dark. Up/Down cursor
  buttons (CC 46/47) move `activeScene` by one. On project load,
  `bootstrapWithoutLaunching()` paints the first scene with clips
  immediately, without auto-launching it.
- Top-left encoder above Tap Tempo (CC 15) controls global pad LED
  brightness 10-100%, default 10%.
- Not yet done: the other 8 display encoders, track navigation/scrolling
  (only the first 8 tracks are reachable — no bank paging yet), the
  top-4-rows submode concept, custom RGB clip colors beyond the fixed
  128-entry palette.

## Maintenance note

This file is meant to survive across sessions — when architecture decisions
change (new packages, new build steps, scope changes), update this file in
the same commit/session rather than letting it go stale.
