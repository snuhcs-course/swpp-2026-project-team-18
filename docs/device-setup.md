# Running on a physical device

The emulator gets you most of the way, but three things can only be checked on real
hardware. Each one can sink the product on its own, so this is not optional polish.

| What | Why an emulator cannot answer it |
| --- | --- |
| **Alarm delivery under battery optimization** | The emulator runs stock AOSP. One UI and other vendor skins defer background work aggressively, and our product fails completely if a single alarm does not fire. |
| **Reported GPS accuracy** | Departure and arrival decisions reject any fix worse than 50 m. The emulator reports a clean 5–9 m; a phone in a pocket does not. If real accuracy is consistently worse, the radii need adjusting. |
| **Location updates with the screen off** | Tracking runs in a foreground service for a whole commute. How long a vendor skin keeps it alive is not observable on an emulator. |

Everything else — registration, firing over the lock screen, reboot recovery, the
departure/arrival state machine, observation upload — is already verified on an
emulator. See `Spec/checklist.md`.

---

## The address problem

An emulator reaches the host at the fixed address `10.0.2.2`. A phone cannot use that;
it needs your machine's LAN IP, which differs per person and changes when you switch
networks. So the address must not be hardcoded.

`local.properties` (git-ignored) holds it:

```properties
devServerHost=192.168.0.12
```

Gradle bakes that into `BuildConfig.BASE_URL`. **You do not have to undo it to go back
to the emulator** — `ApiClient` detects an emulator at runtime and substitutes
`10.0.2.2`, so one build works on both. The account dialog shows the address actually
in use, which is the fastest way to confirm which one you got.

---

## 1. Open the firewall (once)

The dev server listens on 8000. Windows blocks inbound connections to it by default,
and this is the single most common reason a phone cannot reach the server.

Run in an **administrator** PowerShell:

```powershell
New-NetFirewallRule -DisplayName "JustInTime dev server 8000" `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 `
  -Profile Private
```

`-Profile Private` keeps the port closed on public networks. Do not widen it.

## 2. Point the app at your machine

```powershell
cd APP
.\scripts\use_device.ps1
```

The script finds your IPv4 addresses, prefers the Wi-Fi one, writes `devServerHost`
into `local.properties`, checks the firewall rule, and lists connected devices with
their API level. To choose a different address:

```powershell
.\scripts\use_device.ps1 -ServerHost 192.168.0.12
.\scripts\use_device.ps1 -Emulator          # back to 10.0.2.2
```

The server already accepts whichever address you pick — `config/settings/dev.py`
detects the machine's local addresses and adds them to `ALLOWED_HOSTS`.

## 3. Connect the phone

**Requirement: Android 14 (API 34) or newer.** `minSdk` is 34, so the install simply
fails on older builds. A Galaxy S23 shipped with Android 13 and must be updated first.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell getprop ro.build.version.sdk     # must be >= 34
```

### USB

Easier for the first run, because you want logs.

1. Phone: **Settings → About phone → Software information → tap Build number 7 times**
2. **Settings → Developer options → USB debugging** ON
3. Plug in, then accept *Allow USB debugging?* on the phone

### Wireless (Android 11+, no cable)

1. Phone: **Developer options → Wireless debugging** ON → *Pair device with pairing code*
2. On the PC:

```powershell
& $adb pair <ip:port shown on the phone>      # then enter the pairing code
& $adb connect <phone-ip:port>                # the port outside the pairing dialog
```

The pairing dialog and the connect listing show **different ports**. Use the one from
the main Wireless debugging screen for `connect`.

## 4. Check the path before installing

```
http://<your-ip>:8000/api/health
```

Open that in the phone's browser. `{"ok":true,"version":"..."}` means the route is
clear. Anything else is the firewall or the two devices being on different networks —
fix it here, not after the app is installed and failing for unclear reasons.

## 5. Install and run

```powershell
# terminal 1 — server. 0.0.0.0 is required; 127.0.0.1 is unreachable from the phone.
cd backend
.\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000

# terminal 2 — app
cd APP
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat installDebug
```

`JAVA_HOME` is usually not set on the machine, so export it per shell.

---

## Permissions and power settings

| Setting | Why |
| --- | --- |
| **Notifications: allow** | Not cosmetic. Android blocks a background `startActivity`, so the alarm screen is launched by the notification's full-screen intent. Deny this and the alarm never appears over the lock screen. |
| **Location: precise** | Coarse location has hundreds of metres of error, so almost no fix would pass the 50 m filter and nothing would ever be detected. "Allow all the time" is **not** needed — tracking starts from the visible alarm screen. |
| **Battery optimization** | Excluding the app makes alarms reliable, but leaving it on is itself the thing under test. Run a few nights without the exemption first; a missed alarm is a finding, not a setup mistake. |

---

## Testing a commute, when the server is at home

Departure and arrival only happen if you walk out of the house — at which point the
phone leaves your Wi-Fi and cannot reach the dev server. This still works:

1. At home, open the app and let it refresh. The alarm is now registered **on the
   device**; the server is not needed again.
2. Leave. Detections are written to a local queue on disk as they happen.
3. Come back and open the app. The queue uploads, and the server ignores anything it
   already has, so a retry cannot create duplicates.

If you would rather watch it happen live, put both the phone and the PC on a mesh VPN
(Tailscale, ZeroTier) and pass that interface's address:

```powershell
.\scripts\use_device.ps1 -ServerHost <vpn-address-of-this-pc>
```

Then the phone reaches the server from any network, and observations arrive while you
are still walking.

---

## Checking state on the device

Tap the avatar on the home screen:

```
Alarms registered: 2 · next 7:40
Server = http://192.168.0.12:8000/
Unsent trip records: 3
```

The status bar also shows an alarm-clock icon whenever an alarm is registered — the
quickest confirmation that registration reached the system rather than just the app.

For logs:

```powershell
& $adb logcat -s AlarmScheduler:V AlarmReceiver:V TripTrackingService:V TripObservationQueue:V
```

Registered alarms as the system sees them:

```powershell
& $adb shell dumpsys alarm | Select-String "swpp" -Context 0,3
```

An entry under **Next wake from idle** is what you want. That means it was registered
as an alarm clock and will survive Doze.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Login fails with "cannot reach the server" | Firewall rule missing, phone on another network, or the server bound to `127.0.0.1`. Check `/api/health` from the phone's browser first. |
| Login fails but `/api/health` works in the browser | The APK still has the old address. Re-run `use_device.ps1`, then `installDebug`. Confirm with the address in the account dialog. |
| `INSTALL_FAILED_OLDER_SDK` | Phone is below API 34. Update Android. |
| Alarm fires but no screen appears | Notification permission denied. The full-screen intent is the only path that works from the background. |
| Alarm never fires | Battery optimization deferred it. Expected on a first run — that is the finding. Exclude the app and compare. |
| Nothing detected after walking out | Location set to coarse instead of precise, or you never got 150 m from the saved home location. Check `Unsent trip records` in the account dialog. |
| `adb devices` shows `unauthorized` | Accept the *Allow USB debugging?* dialog on the phone. |
