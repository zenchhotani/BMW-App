# BMW Link

Android app for a BMW 430i (Galaxy Z Fold 7 layouts) using BMW's official **CarData** streaming API.

## Build the APK
1. Create a GitHub repo and push this folder to `main`.
2. Open the repo's **Actions** tab → "Build APK" runs automatically (or run it manually).
3. Download `app-debug.apk` from the run's artifacts, or from the repo's **Releases** page on your phone.
4. Install it (allow "install unknown apps" for your browser/Files app).

## First-time setup in the app
1. BMW site → Personal Data → My Vehicles → **CarData** → *Create CarData client*; enable API + Stream.
2. Under *CarData Stream* choose **Change data selection** and tick the data you want.
3. Paste the Client ID and your VIN in the app, tap Connect, approve in the browser.

## What it does
- Dashboard: fuel/range, odometer, lock and open-item state, roof, location, 12V battery
- Trips: approximate trip log from odometer changes (while the app is open)
- All data: every raw value the car sends (use this to see exact BMW key names)
- Layouts: cover screen = tabs; unfolded = two panes; half-folded (Flex Mode) = gauges top, details bottom

## Limits
- CarData is read-only. BMW offers no official remote lock/unlock/climate API, so there are no remote controls.
- CarData is not true live telemetry: the car pushes updates on events, not RPM/boost every second.
  For real live gauges you would add an OBD-II Bluetooth adapter.
- Descriptor names differ by model/year; the dashboard matches loosely by keyword. Adjust `Metrics.kt` if a card is empty.
- Streaming only runs while the app is open.
