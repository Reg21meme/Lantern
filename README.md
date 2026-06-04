# Lantern

**Lantern** is an assistive mobile system that gives visually impaired users real-time spatial awareness of their surroundings. An Android app streams the phone's camera feed to a Python vision backend, which detects objects and estimates their distance, then sends back concise audio cues describing what is nearby and where.

---

## How it works

```
┌─────────────────┐     WebRTC (video)      ┌──────────────────────────┐
│   Android App   │ ──────────────────────► │      Vision Server       │
│    (Kotlin)     │                         │        (Python)          │
│                 │                         │                          │
│  • Camera feed  │                         │  • YOLOv8  → objects     │
│  • Audio cues   │ ◄────────────────────── │  • MiDaS   → depth (3D)  │
└─────────────────┘    detections + depth   └──────────────────────────┘
```

1. The Android app captures the camera feed and streams it to the backend over **WebRTC**.
2. The server runs **YOLOv8** for object detection and **MiDaS** for monocular depth estimation, combining the two to locate objects in 3D space.
3. Detections (object label + direction + rough distance) are returned to the app.
4. The app's voice guidance layer turns that data into short spoken cues — e.g. *"chair, slightly left, close."*

---

## Project structure

```
.
├── android/                 # Android app (Kotlin)
│   ├── app/
│   ├── gradle/
│   └── build.gradle.kts
│
├── vision/
│   └── Scraped_Server/      # Python vision backend
│       ├── vision.py             # Main detection + depth pipeline
│       ├── config.py             # Configuration / settings
│       ├── setup_ssl.py          # Generates SSL certs (WebRTC needs HTTPS)
│       ├── setup_webrtc.py       # WebRTC connection setup
│       ├── start_https_server.py # Launch the secure server
│       ├── start_server.sh       # Launch script (Linux/macOS)
│       ├── start_server.bat      # Launch script (Windows)
│       ├── webrtc_client.html    # Browser test client
│       ├── test_*.py             # Tests (client, imports, webrtc)
│       ├── requirements.txt
│       └── yolov8m.pt            # YOLOv8 model weights
│
└── README.md
```

---

## Getting started

### Backend (vision server)

Requires **Python 3.9+** and a machine with a GPU recommended for real-time performance.

```bash
cd vision/Scraped_Server

# Install dependencies
pip install -r requirements.txt

# Generate SSL certificates (WebRTC requires a secure context)
python setup_ssl.py

# Start the server
python start_https_server.py
# or use the helper scripts:
#   ./start_server.sh     (Linux/macOS)
#   start_server.bat      (Windows)
```

You can verify the server is working before connecting the phone by opening `webrtc_client.html` in a browser, which streams from your webcam to the backend.

### Android app

1. Open the `android/` folder in **Android Studio**.
2. Point the app at your backend's address in the app config.
3. Build and run on a device (a physical device is recommended for camera + audio).

---

## Tech stack

| Layer    | Tech                                      |
|----------|-------------------------------------------|
| Frontend | Android, Kotlin                           |
| Backend  | Python, PyTorch                           |
| Vision   | YOLOv8 (detection), MiDaS (depth)         |
| Transport| WebRTC over HTTPS                         |
| Output   | Text-to-speech voice guidance             |

---

## Highlights

- Real-time 3D object detection by fusing YOLOv8 detections with MiDaS depth estimates.
- PyTorch optimizations tuned the pipeline for low-end Android devices, targeting sub-100 ms latency.
- A voice guidance system that converts spatial detection data into directional audio cues.
