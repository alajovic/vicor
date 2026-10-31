# vicor for Android

A native Android version of [vicor](../README.md). It needs no Termux or
ffmpeg: videos are re-encoded with the phone's own hardware H.264 encoder
through [Media3 Transformer](https://developer.android.com/media/media3/transformer).

- The list shows your videos, newest first and already selected, so the
  fastest path is: open the app, tap **Encode**.
- You can also **Share** a video to vicor from the gallery or camera app.
- **Original** keeps the resolution and re-encodes at about 0.12 bits per
  pixel per frame, capped at 70% of the source bitrate. For example, 1080p30
  comes out at about 7.5 Mbit/s.
- **Small** scales the longer side down to *Max size* (720 by default) and
  re-encodes audio as 96 kbit/s AAC.
- **Keep audio** off removes the audio track.
- Encoding runs as a foreground service with a progress notification, so it
  keeps going with the screen off. The phone vibrates when it's done.
- Output goes to `Movies/vicor/` with the original file name. Afterwards you
  can **View**, **Send** or **Remove** it.

Requires Android 10 (API 29) or newer.

## Building

    export ANDROID_HOME=~/Android/Sdk JAVA_HOME=/usr/lib/jvm/openjdk-bin-21
    ./gradlew assembleRelease
    adb install app/build/outputs/apk/release/app-release.apk

The release build is signed with the debug key so it installs directly.
