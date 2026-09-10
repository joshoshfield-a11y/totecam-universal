# ToteCam — Universal Camera Viewer

One app for the whole tote of cameras.

## What it connects to
| Type | How |
|---|---|
| Wi-Fi security / IP cameras | **RTSP** — auto-discovered via ONVIF WS-Discovery and a subnet scan that probes 15 common stream paths (handles Basic + Digest auth) |
| Cheap HTTP cameras | **MJPEG** (`multipart/x-mixed-replace`) streams |
| USB webcams | Android Camera2 **external camera** enumeration (works on devices whose firmware exposes UVC cameras; a built-in UVC driver for other devices lands in v1.1) |
| This phone's camera | CameraX preview (handy test feed) |

## Usage
1. Tap **SCAN** — optionally enter the login you set on the cameras. Found cameras appear in the list.
2. Or tap **+** to add a URL manually, e.g. `rtsp://192.168.1.50:554/live/ch00_0`, with username/password if needed.
3. Tap a camera to view full screen. **GRID** shows up to 4 IP cameras live at once.

## Notes
- Tap-and-hold a camera to edit it; the ✕ deletes it.
- If discovery finds a camera but reports "login needed", edit the entry and add the username/password.
- Proprietary P2P-only cameras (some Tuya/CloudEdge-style apps) don't speak open protocols and can't be supported — but most such hardware still exposes RTSP on port 554.

## Build
GitHub Actions workflow `.github/workflows/build.yml` builds a debug-signed APK on every push.
