# VideoMemories

A looping video player for Sony Android cameras, developed and measured
on the ILCE-6300 (A6300, Android 4.1.2): pick a file, it plays, it loops
until you leave. Built on the media-stack findings in
[FINDINGS.md](FINDINGS.md) - read that before writing any media code for
these cameras; several perfectly ordinary Android calls reboot them.

## Controls

- Wheel: move in the browser
- Centre: open a file / pause and resume playback
- MENU in the player: back to the browser
- MENU in the browser: exit the app

During playback there are no on-screen controls: "Loading..." until the
first frame, "PAUSED" while paused, nothing while playing. The picture
stretches to the full panel; the player logs `screen WxH` into
VMEM/LOG.TXT, encode to that size and the fill carries no warp.

## Videos

Raw Annex-B H.264 (`*.264`, `*.h264`, 8.3 file names). Convert anything
with the ffmpeg recipe in FINDINGS.md (it includes the range fix that
gives true black instead of gray). There is no audio: this ROM has no
audio output at all.

## Build

    ./build.sh
    # JDK 17+, Android SDK (platform 34, build-tools 34.0.0).
    # An NDK (r21 works) builds the native converter; without it the APK
    # still builds and falls back to the slow Java converter.

    adb install -r build/videomemories-v1.apk

Host tests, no device needed:

    # NAL index vs a reference Annex-B splitter (needs any .264 file)
    javac -d /tmp/vm app/src/main/java/dev/camsysinfo/videomemories/NalIndex.java \
          hosttest/dev/camsysinfo/videomemories/NalIndexTest.java
    java -cp /tmp/vm dev.camsysinfo.videomemories.NalIndexTest some.264

    # converter math vs a reference frame pair (see test_convert.c header)
    gcc -I$JAVA_HOME/include -I$JAVA_HOME/include/linux \
        app/src/main/jni/convert.c app/src/main/jni/test_convert.c -o /tmp/vt
    /tmp/vt

## Icon

No icon yet: the manifest sets no `android:icon`, so the system default
stands in until the launcher artwork is added (`android:icon` plus
`app/src/main/res/drawable-nodpi/`).
