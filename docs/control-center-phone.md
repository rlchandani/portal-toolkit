# Control Center on the phone (House tab)

The **House** tab shows the same live house as the Portals' Control Center. It always works
through a paired Portal (`/remote/controlcenter`), and that Portal keeps the Home Assistant
token: the phone never holds it. The Portal side, its routes and its policy are described in
Hearthpane's `docs/features/control-center-phone.md`.

## Screens

- **Map:** floor tabs, Live / Climate lenses, rooms glowing with their lights' colours, open
  doors and windows in amber, an open garage with **Close**, scenes, and the floor's rooms
  with a one-tap lights toggle. Tap a room for its sheet (camera on top, every device with its
  quick control), and tap a device for its full control.
- **Tesla:** battery, range, what the car is doing, charge limit, control tiles, the status
  grid and this week's home charging.
- **Alerts:** every alert the Portal raises, most serious first. **Close** and **Shut off
  water** act on the house. Dismiss, Snooze and Remind hide the alert on this phone only.
- **Security:** alarm modes, who's home, and every lock, garage, door, window, hazard, motion
  and tamper sensor, grouped, with inline **Close** / **Lock**.
- **Cameras:** snapshots through the Portal with an honest tag: **LIVE** only when the picture
  really changes, otherwise "Snapshot · 3 h ago" or "unchanged …". The full view refreshes about
  once a second and offers **Refresh**.

## Confirm first

Unlock, the garage, the water valve, disarming, security-ish scenes or scripts, and the car's
unlock and open all ask first, as on the Portals. The phone shows the system fingerprint or face
prompt (or its screen lock), otherwise a confirm button. A coded alarm gets a PIN pad, and the
garage shows its camera. The Portal checks again with a one-time ticket. Kids mode belongs to
each Portal's own screen and does not apply here.

## States

The tab covers every state: loading, no paired Portal, Portal offline (the last values stay,
marked), another Portal answering, Home Assistant unreachable, the HA token refused, HA not set
up, Hearthpane too old, and stale ("Reconnecting… values from 7:36 pm"). Controls are disabled
unless the values are live.

## Performance

It polls every 1.5 s only while the tab is visible and the app is started. Each poll carries
only what changed, and is a few hundred bytes when nothing did. Lists are lazy and keyed, the
plan is one canvas, and camera pictures are downsampled to their tile and decoded only when the
bytes change.
