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
| Extension instance (init/exit/flush) | `src/main/java/com/actus/push2/Push2ControllerExtension.java` |
| Push 2 screen rendering + USB frame send | `src/main/java/com/actus/push2/Push2Display.java` |
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
  logging/user feedback during development.
- Push 2 MIDI port name on macOS: `"Ableton Push 2 Live Port"` (confirmed via
  DrivenByMoss's `Push2ControllerDefinition.java`). Windows/Linux names in our
  definition are best-guess from DrivenByMoss and **untested** — verify against
  real hardware before relying on them.

### USB (screen, and later pad LEDs)

Bitwig's own API (v21) has native USB support — no need for DrivenByMoss's
JNA/purejavahidapi dependencies. Two parts:

1. **Declare the device**, in `ControllerExtensionDefinition.listHardwareDevices
   (HardwareDeviceMatcherList)`: build a `UsbDeviceMatcher` (expression string
   `"idVendor == 0x... && idProduct == 0x..."`) wrapping `UsbInterfaceMatcher`
   (expression `"bInterfaceNumber == 0x..."`) wrapping `UsbEndpointMatcher`
   (`UsbTransferType.BULK`/`INTERRUPT` + endpoint address byte). This expression
   string format was reverse-engineered from DrivenByMoss's
   `AbstractControllerExtensionDefinition.createDeviceMatcher()` — Bitwig's own
   jar has no docs/source, only class files (`javap` everything).
2. **Use it**, in `ControllerExtension.init()`: `host.hardwareDevice(0)` (index
   = position among matchers you registered) cast to `UsbDevice`, then
   `device.iface(0).pipe(0)` (indices = position within your interface/endpoint
   matchers) cast to `UsbOutputPipe`/`UsbInputPipe`, then
   `pipe.write(MemoryBlock, timeoutMs)`. Buffers come from
   `host.allocateMemoryBlock(size)` (declared on the parent `Host` interface,
   not `ControllerHost` itself — easy to miss when grepping).

Push 2's screen protocol (960x160, confirmed working via `Push2Display.java`):
header = 16 bytes `FF CC AA 88` + 12 zero bytes, sent as one `pipe.write()`;
then the frame = fixed 327680 bytes (`20 * 0x4000`) as a second `pipe.write()`.
Pixels are BGR565 (not the more common RGB565 — blue in the high bits), each
row padded to a fixed stride, and the *entire* frame buffer must be XORed
4-bytes-at-a-time with `E7 F3 E7 FF` before sending ("signal shaping" — see
Ableton's own spec, linked in the file). Vendor/product ID `0x2982`/`0x1967`,
interface `0`, bulk OUT endpoint `0x01` — all from DrivenByMoss's
`Push2ControllerDefinition`/`PushUsbDisplay`.

Drawing itself uses Bitwig's own Cairo-like 2D API
(`host.createBitmap(w, h, BitmapFormat.ARGB32)`, then `bitmap.render(gc -> ...)`
with `GraphicsOutput` — `setColor`, `rectangle`/`fill`, `setFontSize`,
`showText`, `getTextExtents`/`getFontExtents` for centering). No font file is
bundled — omitting `setFontFace` falls back to a default font, which is enough
for the current logo text. `bitmap.getMemoryBlock().createByteBuffer()` gives
raw pixel bytes ready to encode, no stride surprises (confirmed against
DrivenByMoss's `BitmapImpl.encode()`, which does the same thing).

**Gotcha: Push 2's screen blanks a few seconds after its last frame**, and
`ControllerExtension.flush()` is NOT a timer — since Bitwig 3.1 it only fires
on actual DAW state changes (see DrivenByMoss's `ModelImpl.flushWorkaround()`
comment). Fix, both halves required:
- `flush()` calls `display.refresh()` (re-sends last rendered frame, no redraw).
- `keepDisplayAlive()`, scheduled once from `init()` via
  `host.scheduleTask(this::keepDisplayAlive, 100)`, calls `host.requestFlush()`
  then reschedules itself every 100ms until `exit()` sets `running = false`.
  `requestFlush()` just asks Bitwig to call `flush()` once, soon.

**Naming:** the human-readable name passed as `UsbDeviceMatcher`'s first
constructor arg is what shows up in Bitwig's Settings → Controllers hardware
device list. Match DrivenByMoss's convention:
`getHardwareVendor() + " " + getHardwareModel()` → `"Ableton Push 2"` (not an
arbitrary label like `"Push 2 Display"` — that was a first-pass mistake,
already fixed).

## Current state (update this section as the project grows)

- Extension registers, MIDI in/out ports 0 are opened, a popup notification
  fires on load/unload.
- Push 2's screen shows "Actus" centered, drawn once on `init()`
  (`Push2Display.showText`) and kept alive by re-sending the same frame on
  every `flush()` (`Push2Display.refresh()` — see the USB section above for
  why this is required). USB claim is wrapped in a try/catch in
  `Push2ControllerExtension.init()` so a missing/disconnected Push 2 logs via
  `host.errorln()` instead of breaking the rest of init (MIDI still works).
- Not yet done: pads (RGB LEDs, also USB — different endpoint, likely similar
  matcher pattern), encoders, buttons, any actual session/mix behavior. The
  display is currently static — no redraw-on-change wiring yet, since there's
  nothing dynamic to show.

## Maintenance note

This file is meant to survive across sessions — when architecture decisions
change (new packages, new build steps, scope changes), update this file in
the same commit/session rather than letting it go stale.
