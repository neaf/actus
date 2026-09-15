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
| Screen-only 3-row session clip grid (adapted from DrivenByMoss, see below) | `src/main/java/com/actus/push2/Push2SessionDisplay.java` |
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
`getTextExtents`/`getFontExtents` for centering, `save`/`rectangle`/`clip`/
`restore` to constrain drawing to one region). No font file bundled —
omitting `setFontFace` falls back to a default font. `bitmap.getMemoryBlock()
.createByteBuffer()` gives raw pixel bytes ready to encode, no stride
surprises. `Push2Display.render(Renderer)` is the generic entry point (paint
+ send in one call); `showText()` is a thin convenience wrapper over it.

### Session clip grid on the screen (adapted from DrivenByMoss)

`Push2SessionDisplay` draws a 7-row x 8-column clip grid on the screen —
**screen only**, no relationship to the pad grid, `PadRow`, or
`Push2ClipLaunchRow`'s per-scene pad state (deliberately separate caches;
see the matrix-region note for why pad rows stay independent of this).
Adapted from DrivenByMoss's `SessionMode.updateDisplay2Clips()` /
`ClipListComponent` (Push 2's screen-side clip-grid rendering, distinct from
`AbstractSessionView` which is pads-only) — same idea (fill rect + state
border + name text per cell, using `save()`/`clip()`/`restore()` to keep
text/fills inside one cell), reimplemented directly against our own
`GraphicsOutput` calls rather than DBM's `IGraphicsContext` wrapper.

- **Row mapping**: rows run top-to-bottom in ascending scene order, same
  direction as Bitwig's own Session view sidebar — `BACK` (2) rows above
  `activeScene`, then `activeScene` itself (row index `BACK`, marked with a
  white strip down both its left and right edges, plus every non-current
  row is dimmed (`Push2SessionDisplay.DIM = 0.18`, applied to fill/border/
  name colors alike) so it stands out - a full-row filled background and a
  full-row rectangle outline (grey, then white, then green) were tried and
  rejected before landing on left/right strips + dimming), then `FORWARD`
  (4) rows below it.
  `ROWS = BACK + 1 + FORWARD = 7`. A row whose scene index would
  fall outside `[0, MAX_SCENES)` (no scene that far
  back/forward yet) is left empty — not filled with the active scene
  repeated — per explicit request.
- **State cache**: `Push2SessionDisplay` keeps its own
  `[track][scene]`-indexed cache (hasContent/isPlaying/isQueued/
  isRecording/color/name) via its own `ClipLauncherSlotBank` observers,
  covering all `MAX_SCENES` scenes exactly like `Push2ClipLaunchRow` does
  for the pads — sized the same way (bank created with
  `numScenes = MAX_SCENES`, so every scene is already addressable, no
  paging/scrolling needed at the Bitwig API level). This is intentionally a
  **second**, independent set of observers rather than sharing
  `Push2ClipLaunchRow`'s cache — screen needs true RGB floats (not the
  palette-nearest-matched index pads use) plus clip names pads never needed
  at all. Revisit sharing only if a third consumer needs the same per-slot
  data (mirrors the `PadRow` extraction reasoning).
- **Redraw triggers, coalesced within a tick.** Any observer firing for a
  scene inside the currently visible window (`[activeScene - BACK,
  activeScene + FORWARD]`) schedules a repaint; changes outside that
  window are cached but don't touch the screen. **Gotcha, confirmed on
  real hardware**: two clips that Bitwig launches "at the same instant"
  (e.g. both queued to start on the next bar) still arrive as two separate
  `isPlaying` observer callbacks, not one. Calling `redraw()` directly
  from each one made simultaneous launches visibly light up one after
  another on the physical screen, not together - each callback triggered
  its own immediate full repaint + USB send. Fixed with a coalescing
  scheduler: `redrawIfVisible()` calls `scheduleRedraw()`, which sets a
  `redrawScheduled` flag and does `host.scheduleTask(..., 0)` only if one
  isn't already pending; any number of state changes arriving before that
  deferred task runs collapse into a single `redraw()` that reflects all
  of them at once. `Push2ControllerExtension`'s `onSceneChanged` callback
  (the same one that already repaints `Push2ClipLaunchRow` and
  `Push2SceneButtons` when `activeScene` changes) still calls
  `sessionDisplay.redraw()` directly, not through the scheduler - a
  user-initiated scene change is a single discrete event, not a burst.
- **Cell state, two clean states, purely event-driven (no animation
  loop - redraws only happen from `ClipLauncherSlotBank` observer
  callbacks, same as everywhere else in this file)**: recording/playing/
  queued are filled solid with the clip's color plus a border - red
  (recording) / white (playing) / grey (queued). Stopped-with-content is
  zero fill, outline only in the clip's own color, blank interior - the
  clip name (if any) also draws in the clip's color rather than black,
  since there's no fill to contrast black text against. No content =
  nothing drawn, black background shows through.
- **Playback progress bar: tried and abandoned, not just unimplemented.**
  Wanted: a playing cell darkens left-to-right as it plays, matching
  Bitwig's own clip progress indicator. Needs a real `Clip` cursor per
  track (`ClipLauncherSlotBank` has no position data) - a `CursorTrack`
  pinned to that track (`selectChannel` + `isPinned(true)`) so it doesn't
  follow Bitwig's real UI selection, plus a `PinnableCursorClip`
  (`createLauncherCursorClip`) for `getPlayStart()`/`getPlayStop()`.

  **Root cause, confirmed by tracing DrivenByMoss's own working
  implementation (`ModelImpl.java`, `CursorClipImpl.java`,
  `AbstractSessionView.onGridNote()`)**: `ClipLauncherSlot.select()`
  doesn't do anything to a cursor directly - it changes Bitwig's real UI
  track selection, and a `PinnableCursorClip` only updates because its
  parent `CursorTrack` is configured to *follow* that real selection
  (`shouldFollowSelection = true`). DBM uses exactly **one** global
  `CursorTrack` this way - it has no per-track-pinned-cursor pattern at
  all, anywhere. With `shouldFollowSelection = false` (required for 8
  independent per-track cursors that don't get hijacked by whatever the
  user clicks), `select()` - by any of six different call variations
  tried, including ones that either silently no-op or hard-throw at
  runtime despite compiling clean - can never move them. This is a real
  Bitwig Controller API constraint, not a bug in this codebase: **there is
  no way to bind a `PinnableCursorClip` to an arbitrary clip without
  hijacking Bitwig's actual UI selection.** A single-shared-cursor design
  (one clip's progress at a time, visibly stealing focus in Bitwig's UI on
  every launch) was considered and explicitly rejected - too disruptive
  for a live-performance controller. Don't re-attempt the per-track-pinned
  version without new information; if this is revisited, start from the
  single-shared-cursor tradeoff, not from scratch.
- **Extra navigation buttons**: Push 2's physical **Octave Up/Octave Down**
  buttons (`Push2SceneButtons.OCTAVE_UP_CC`/`OCTAVE_DOWN_CC` = CC 55/54,
  `PUSH_BUTTON_OCTAVE_UP`/`_DOWN` in DrivenByMoss) are repurposed as a
  second pair of scene-navigation buttons — same one-scene-at-a-time move
  as the Up/Down cursor buttons (`Push2SceneButtons.onNavigate(±1)`), just a
  physically separate pair — since Actus has no note/octave transposition
  feature to give them their usual job. (An earlier attempt bound these to
  a coarser multi-scene "page" jump; reverted per explicit request - Octave
  Up/Down should move one scene, same as Up/Down.) These are plain
  monochrome single-LED buttons (confirmed via DrivenByMoss's
  `MONOCHROME_BUTTONS` set) - the CC value sent is a raw 0-127 brightness,
  not a `Push2Colors` palette index; `Push2SceneButtons.redraw()` lights
  both steady at full brightness (127) purely so they're visible in the
  dark, same "always available" treatment as `STOP_ALL_CC`.

**Gotcha: Push 2's screen blanks a few seconds after its last frame**, and
`ControllerExtension.flush()` is NOT a timer — since Bitwig 3.1 it only fires
on actual DAW state changes. Fix, both halves required:
- `flush()` calls `display.refresh()` (re-sends last rendered frame, no redraw).
- `keepDisplayAlive()`, scheduled once from `init()` via
  `host.scheduleTask(this::keepDisplayAlive, 100)`, calls `host.requestFlush()`
  then reschedules itself every 100ms until `exit()` sets `running = false`.
  `requestFlush()` just asks Bitwig to call `flush()` once, soon.

**Performance note (compared against DrivenByMoss's `PushUsbDisplay`):**
`Push2Display.refresh()` offloads the actual blocking USB transfer (two
`pipe.write()` calls, up to 1000ms timeout each) to a single-thread
`ExecutorService`, same as DBM's `PushUsbDisplay.sendExecutor` - otherwise a
stalled write could block whatever thread called `refresh()`, which for us
is the same scheduled task driving `keepDisplayAlive()` (the screen is
purely event-driven otherwise - no animation timer). A `volatile boolean
sending` flag guards
`frame`/`frameBlock`: `refresh()` skips entirely (touches neither) if a send
is still in flight, rather than racing the background thread - dropping an
occasional frame is harmless for a display. `shutdown()` stops the executor,
called from `exit()`. DBM's own display loop is otherwise mostly
event-driven (rides Bitwig's `flush()` callback, not a fixed timer, and only
falls back to its own 100ms timer while the transport is stopped) and does a
cheap `ModelInfo`-equality dirty-check before repainting the canvas -
neither applies well to our much simpler screen, so not adopted.

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
  <b_hi> <w_lo> <w_hi> F7`, then reload once with `F0 00 21 1D 01 01 05 F7`.

  **Gotcha: plain white-LED buttons (Octave Up/Down, Up/Down, Shift, etc. -
  anything that isn't an RGB pad or an RGB-colored button like Scene
  Launch) read the palette entry's separate white field, not r/g/b** —
  confirmed against Ableton's own published `Push2-map.json`/
  `AbletonPush2MIDIDisplayInterface.asc` MIDI spec (`doc/` in
  `github.com/Ableton/push-interface`). The CC/note velocity sent to one of
  these buttons is a palette index exactly like for RGB LEDs, just resolved
  against `white` instead. `Push2Colors.PALETTE` has no white component
  (copied verbatim from DrivenByMoss's r/g/b-only `DEFAULT_PALETTE`), so
  `Push2Palette.write()` derives one — `white = max(r, g, b)` per entry —
  rather than sending a flat 0. Sending flat 0 (an earlier version of this
  file did) silently makes **every** monochrome button on the device
  permanently unlightable, since every index it could possibly be told to
  use has a zeroed white channel — this is exactly what happened when
  `Push2SceneButtons` first tried to light the Octave Up/Down buttons: input
  worked (confirmed via scene navigation), but the LEDs stayed dark no
  matter the value sent or how many times Bitwig was restarted, because the
  bug was in the palette data, not the button-handling code. DrivenByMoss
  avoids this by reading each entry's existing (factory-default) white
  value off the device before writing (`ColorPaletteEntry.requiresUpdate()`)
  rather than deriving one — Actus doesn't have that read-back machinery,
  so the derived value is an approximation, not a factory-accurate one.
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
- **Animation shape/timing is driven by MIDI real-time clock (`0xF8`, 24
  per quarter note), sent by whatever the active MIDI clock source is —
  not something Actus's code controls.** Without it, pulse/blink animation
  shape is undefined (this caused pads to pulse with a broken "instant
  color then slow decay" look). Two dead ends before landing here:
  1. Hand-rolling it (`Push2ClockSync`, deleted) - sent `0xF8`/`0xFA`/`0xFC`
     directly via `MidiOut.sendMidi()`, which threw
     `IllegalArgumentException: Parameter status (=250) must be in the
     range 0 to 239` - `sendMidi()` only accepts channel messages, never
     system real-time ones, so this could never have worked.
  2. `midiOut.setShouldSendMidiBeatClock(true)` - compiles, but **throws
     at runtime**: `"This has been deprecated since API version 2: Users
     should enable the clock from the settings."` This aborts the rest of
     `init()` if left in (worse than not calling it at all - none of
     `keepDisplayAlive`/`sessionDisplay.startAnimating` ever ran), so it
     was removed entirely, not just left as a compile-time warning.
  **Actual fix: a Bitwig user setting, not code** - Settings →
  Synchronization has a per-controller "send MIDI clock" toggle the user
  enables directly; Actus has no code path for this at all.
- **Clip launch** = `ClipLauncherSlot.launch()` (press) /
  `.launchRelease()` (release).
- **Per-track stop** (`Push2StopRow`, notes 44-51, row 1) uses `Track.stop()`
  / `Track.isStopped()` directly — these operate on whatever's currently
  playing/recording/queued on that track regardless of scene, so unlike the
  clip launch row this needs no per-scene state at all. Dim grey above any
  track that isn't stopped (deliberately subtle, not a loud alert color);
  once pressed, `Track.isQueuedForStop()` goes true while waiting for the
  next quantization boundary, so the pad blinks (fast pulse channel, same
  mechanism as a queued clip launch, but pulsing toward *off* rather than a
  brighter color - "fading out" reads better for a pending stop than a
  pending launch's toward-white flash) until the stop actually lands; dark
  once truly stopped.
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
  MAX_SCENES-1]`. Navigation only, does not launch. **Octave Up/Down**
  (CC 55/54, repurposed - see the session-grid section above) call the same
  `onNavigate(±1)` — a second, physically separate pair of buttons doing
  the identical one-scene move.

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
- Push 2's screen shows a 7-row x 8-column session clip grid
  (`Push2SessionDisplay`) — 2 rows above active scene, active scene itself
  (marked with a white left-edge strip), 4 rows below it, top-to-bottom in
  ascending scene order; rows with no scene that far back/forward yet are
  empty (just the black background, no placeholder). No progress bar -
  see the playback progress section above, a real Bitwig API constraint,
  not planned work. Kept alive via `flush()` +
  `keepDisplayAlive()`. USB claim wrapped in try/catch so a missing Push 2
  logs via `host.errorln()` instead
  of breaking the rest of
  init.
- Bottom pad row (notes 36-43) launches/stops clips on tracks 0-7 for
  whichever scene is active. Pad color = clip's real Bitwig color,
  nearest-matched; recording overrides to solid red; playing pulses slowly
  toward fixed green; queued blinks fast toward white; stopped-with-content
  is steady; empty is dark.
- Row above it (notes 44-51) is a per-track stop row, independent of the
  active scene: dim grey above any track with something playing/recording/
  queued, dark when the track is stopped, blinks fast toward off (not
  brighter) while a stop press is queued for the next quantization
  boundary; pressing calls `Track.stop()`. Rows 2-7 are dark.
- Scene Launch button (CC 36) launches/replays `activeScene`; Stop All Clips
  button (CC 37, one row up, aligned with the stop row) calls
  `SceneBank.stop()` — Bitwig's own "stop everything" action, unrelated to
  `activeScene`. The other 6 Scene Launch buttons are dark. Up/Down cursor
  buttons (CC 46/47) move `activeScene` by one; Octave Up/Down buttons
  (CC 55/54, repurposed and lit steady so they're visible in the dark) do
  the same, one scene at a time. On project load,
  `bootstrapWithoutLaunching()` paints the first scene with clips
  immediately, without auto-launching it.
- Top-left encoder above Tap Tempo (CC 15) controls global pad LED
  brightness 10-100%, default 10%.
- Pad pulse/blink animation tempo-sync is a Bitwig user setting (Settings
  → Synchronization), not code - see the pulsing/blinking pads gotcha
  above for why Actus has no code path for this.
- Not yet done: the other 8 display encoders, track navigation/scrolling
  (only the first 8 tracks are reachable — no bank paging yet), the
  top-4-rows submode concept, custom RGB clip colors beyond the fixed
  128-entry palette.

## Maintenance note

This file is meant to survive across sessions — when architecture decisions
change (new packages, new build steps, scope changes), update this file in
the same commit/session rather than letting it go stale.
