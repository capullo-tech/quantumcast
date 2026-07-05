# Quantumcast

Discover radio stations from every corner of the world and let them play through your entire home - all rooms in sync, all at once.

## What it does

Hit shuffle and Quantumcast picks stations at random from a global database of 30,000+ broadcasts. It rotates through them automatically, like a DJ who never sleeps and never repeats a genre twice. When something catches your ear, Shazam-compatible track recognition tells you what's playing - artist, title, artwork - without lifting a finger.

Multiroom playback runs on [Snapcast](https://github.com/badaix/snapcast) under the hood: every room hears the same audio, sample-perfect. Each listener gets their own volume control on the same screen. If you're using a Bluetooth speaker with noticeable delay, dial in its latency offset with a dedicated knob so it lands in sync with the rest of the house. And if you're building a spatial audio setup - placing phones or speakers around a room - you can assign each client its own channel - left, right, or full stereo - making it easy to construct a stereo field from independent devices placed around a room.

## Features

- **Global radio discovery** - search 30,000+ stations by name, country, genre, or tags via the [radio-browser.info](https://www.radio-browser.info) community database
- **DJ shuffle rotation** - Quantumcast picks random stations from around the world and rotates through them on a timer, hands-free
- **Track identification** - automatic Shazam-compatible song recognition: artist, title, and album art appear while the music plays
- **Multiroom sync** - broadcast to any number of devices on your local network with sample-accurate synchronization
- **Per-room volume** - control each room's volume independently from a single screen while the music keeps playing
- **Bluetooth latency compensation** - per-client latency offset knob to bring wireless speakers into sync with wired ones
- **Spatial channel assignment** - assign Left, Right, or Stereo to each client independently, so you can build a true stereo field from ordinary phones and speakers placed around a room
- **Favorites & groups** - organize stations into custom groups with drag-to-reorder
- **Sleep timer** - fade out after a set duration
- **Dark/light theme**

## Architecture

```
Internet radio stream
    → VLC (audio decode)
    → Snapserver (multiroom broadcast)
    → Snapclient (synchronized playback on each device)
```

The app runs a full Snapcast server and client stack natively on Android using [lib-snapcast-android](https://github.com/capullo-tech/lib-snapcast-android). Any Snapcast-compatible client on your network can join.

## Requirements

- Android 8.0 (API 26) or later
- Local Wi-Fi network for multiroom playback
- Other devices for multiroom: any Snapcast client (Android, Raspberry Pi, desktop, etc.)

## Getting started

1. Install Quantumcast on your primary device (the broadcaster)
2. Play any station - it immediately starts broadcasting on your local network
3. On other devices, open the **Qcast** tab and tap your broadcaster to connect and listen in sync
4. Use the speakers icon (⊙) on the Now Playing screen to control per-room volume and latency

## Building from source

Requires Android Studio and the Android NDK (for the native Snapcast libraries).

```bash
git clone https://github.com/capullo-tech/quantumcast
cd quantumcast/QuantumCast
./gradlew assembleProdDebug
```

APK will be at `app/build/outputs/apk/prod/debug/app-prod-debug.apk`.

## Credits

Quantumcast is built on the shoulders of:

- **[RadioCapullo](https://github.com/capullo-tech/RadioCapullo)** - the Snapcast Android integration, multiroom architecture, and stream control protocol that made this possible
- **[lib-snapcast-android](https://github.com/capullo-tech/lib-snapcast-android)** - native Snapcast server/client libraries packaged for Android
- **[Snapcast](https://github.com/badaix/snapcast)** by badaix - the synchronous multiroom audio system at the heart of this project
- **[VLC for Android](https://code.videolan.org/videolan/vlc-android)** - audio decoding and network stream handling via libVLC
- **[radio-browser.info](https://www.radio-browser.info)** - the open community radio station database

## License

Copyright 2026 capullo-tech

Quantumcast is free software, released under the [GNU General Public License v3.0](LICENSE). You may use, study, modify, and distribute it under the terms of that license.

This project incorporates [lib-snapcast-android](https://github.com/capullo-tech/lib-snapcast-android) which bundles Snapcast (GPL v3). The GPL v3 license covers the combined work.
