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
| Extension identity, MIDI auto-detection | `src/main/java/com/actus/push2/Push2ControllerExtensionDefinition.java` |
| Extension instance (init/exit/flush) | `src/main/java/com/actus/push2/Push2ControllerExtension.java` |
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

## Current state (update this section as the project grows)

Skeleton only: extension registers, MIDI in/out ports 0 are opened, a popup
notification fires on load/unload. No pads, encoders, display, or USB
(HID) handling yet — Push 2's screen and RGB pad LEDs go over USB, not MIDI,
and DrivenByMoss's `UsbMatcher`/display code is the reference for that when we
get there.

## Maintenance note

This file is meant to survive across sessions — when architecture decisions
change (new packages, new build steps, scope changes), update this file in
the same commit/session rather than letting it go stale.
