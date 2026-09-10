# ToteCam — Universal Camera Viewer

One app for the whole tote of cameras.

## v1.1 scan engine
- **ONVIF WS-Discovery** with WSSE (PasswordDigest) auth — even password-protected ONVIF cams give up their stream URI
- **mDNS listening** — cameras advertising `_rtsp._tcp` / `_onvif._tcp` / `_axis-video._tcp` are found by their advertised name, no brute force needed
- **Subnet scan** across RTSP ports 554/8554/10554/5540 and HTTP ports 80/81/8000/8080
- **30 common RTSP paths** (Hikvision, Dahua, Axis, ACTi, Reolink, Foscam, TP-Link, generic…)
- **MJPEG probing** on 8 common HTTP paths
- **Factory-default login pass** — with the checkbox on, cameras that answer 401 are tried against common default pairs (admin/blank, admin/admin, ubnt/ubnt, 888888/888888, …) and cracked credentials are saved on the entry

## What it connects to
| Type | How |
|---|---|
| Wi-Fi security / IP cameras | RTSP — auto-discovered, path-probed, auth-handled (Basic + Digest) |
| Cheap HTTP cameras | MJPEG (`multipart/x-mixed-replace`) streams |
| USB webcams | Android Camera2 external camera enumeration (built-in UVC driver lands in v1.2) |
| This phone's camera | CameraX preview (test feed) |

## Usage
1. Tap **SCAN** — optionally enter the camera login; leave "try defaults" checked for factory-password cams.
2. Found cameras appear by name; "(login needed)" entries can be edited to add credentials.
3. Tap to view full screen; **GRID** shows up to 4 live at once.

## Build
`.github/workflows/build.yml` builds a debug-signed APK on every push.
