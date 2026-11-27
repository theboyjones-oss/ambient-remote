# Ambient Remote

OLED "ambient" TV remote for Android (Kotlin + Jetpack Compose).
Package `com.example.ambientremote`, minSdk 27, targetSdk 35.

## Build
**Android Studio:** File → Open → this folder → let Gradle sync → Build → Build APK(s).
**Command line (with Android SDK installed):** `./gradlew assembleDebug`
APK: `app/build/outputs/apk/debug/app-debug.apk`
**GitHub:** push this folder to a repo; `.github/workflows/build-apk.yml` builds both APKs and attaches them as an artifact.

## Pieces
| Class | Role |
|---|---|
| `AmbientRemoteActivity` | Full-screen black UI over the lock screen, adjustable brightness (default 15%, slider in ⚙ settings), keeps screen on, hidden system bars, proximity blackout + touch swallow |
| `AmbientDreamService` | Interactive screensaver hosting the same Compose UI (Settings → Display → Screen saver → Ambient Remote) |
| `SofaModeTileService` | Quick Settings tile "TV Remote" → opens the activity |
| `UdpRemoteClient` | Sends each command as one UTF-8 UDP datagram to IP:port (default 192.168.1.100:9876) |
| `RemoteSettings` | IP, port and theme in SharedPreferences |

Burn-in protection (±8–10 px every 60 s) runs in both the activity and the screensaver.

## UDP commands
REMOTE: `UP DOWN LEFT RIGHT SELECT BACK VOL_UP VOL_DOWN HOME PLAY_PAUSE MUTE`
TRACKPAD: `MOUSE_MOVE:<dx>,<dy>` (≈60 Hz max) `MOUSE_CLICK MOUSE_RIGHT_CLICK MOUSE_CENTER MOUSE_SCROLL_UP MOUSE_SCROLL_DOWN BACK`
KEYBOARD (full-width Android-style docked keyboard; tap ⇧ once = capital, double-tap = caps lock): LIVE `KEY_CHAR:<c> KEY_BACKSPACE KEY_ENTER`; BATCH `KEY_TEXT:<string>` (SEND or ↵); plus `LEFT RIGHT BACK KEY_SEARCH`

## Test listener
`python3 -c "import socket;s=socket.socket(2,2);s.bind(('',9876));[print(s.recv(4096).decode()) for _ in iter(int,1)]"`
