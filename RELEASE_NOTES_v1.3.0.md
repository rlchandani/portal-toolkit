# Portal Toolkit v1.3.0

Phase 2 of the v1.2–v1.4 roadmap: power tools for debugging and inspecting a
connected Portal. A new **Tools** tab gathers a remote shell, a live logcat
viewer, and screen capture, with History and Settings tucked in alongside them
so the bottom navigation stays at four destinations.

## New features

### Remote shell
- Run ad-hoc shell commands on the Portal and see the output inline in a
  monospace, selectable transcript.
- Quick-command chips for common inspections (`pm list packages -3`, `getprop`,
  `wm size`, `df -h`).
- Command history persists across sessions; recall previous commands with the
  up/down controls.
- Destructive commands (`rm`, `reboot`, `pm uninstall/clear`, `settings put`,
  and similar) trigger a confirmation dialog before running.

### Live logcat
- Streams the Portal's logcat in real time with per-level colouring
  (error, warning, info, debug).
- Filter by log level and by tag/message text, or flip to errors-only.
- A crash banner surfaces `FATAL EXCEPTION` and jumps straight to the offending
  line.
- Export the current buffer via the system share sheet.

### Screen capture & live peek
- Capture the Portal screen on demand, or enable a ~1.5s "live peek" loop to
  watch it update.
- Save any captured frame to Pictures/PortalToolkit in the device gallery.
- Live peek warns about battery drain after five continuous minutes and skips
  frames while a previous transfer is still in flight.

## Under the hood
- New **Tools** tab replaces the old "More" tab; History and Settings moved into
  it, keeping four bottom-nav destinations.
- Streaming built on the direct ADB channel: `logcat` reads lines off an
  `AdbStream`, screen capture uses `screencap -p` plus an ADB pull.
- Logcat lines held in an immutable ring buffer so Compose recomposes cleanly on
  each new line.
- Shell command history stored in DataStore (newest-first, de-duplicated,
  capped at 100 entries).

## Reliability
- New unit tests cover the destructive-command classifier, shell history store
  (dedup, ordering, cap), the logcat line parser, and the log ring buffer.

## Compatibility
- Portal devices remain capped at Android 10 (API 29).
