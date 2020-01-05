# vicor

Easily reduce video size right on your phone! Video files recorded by
a phone are larger than they need to be. `vicor` offers two modes:

  - "original" which keeps the original image size, but reencodes the
    video to make the file considerably smaller without noticeable
    loss in quality;
  - "small", which rescales the video and reencodes the audio to make
    the file small enough for a quick share with your friends.

It has a very simple user interface:

  - it shows the list of available video files;
  - to select the latest video, simply press ENTER twice
  - older videos can be selected by pressing ↑ or entering a filename
    (TAB completion is supported);
  - selecting the encoding mode is one key tap;
  - encoding may take a while, so you can carry the phone in your
    pocket and `vicor` will vibrate when finished;
  - it will offer an option to view, send or remove the video, or just
    let it be.

### Usage example


```
20190707_154855.mp4      VID_20190928_172605.mp4
VID_20190928_172555.mp4  VID_20190929_162338.mp4

> VID_20190929_162338.mp4

Video size: [o]original [s]mall s

ffmpeg version 4.2 Copyright (c) 2000-2019 the FFmpeg developers
  built with Android (5220042 based on r346389c) clang version 8.0.7

... lots of ffmpeg output ...

-rw-rw---- 1 root everybody 756K Oct 26 20:34 /data/data/com.termu
x/files/home/storage/shared/DCIM/vicor//VID_20190929_162338.mp4

[v]iew [s]end [r]emove [q]uit
```

Selecting `[v]iew` and `[s]end` will keep the menu open.

## Installation

Prerequisites:

  - Termux ([F-Droid](https://f-droid.org/en/packages/com.termux/) or
    [Play
    Store](https://play.google.com/store/apps/details?id=com.termux))
  - ffmpeg installed in Termux
  - Termux:API to access storage and phone functions
    ([F-Droid](https://f-droid.org/packages/com.termux.api/) or [Play
    Store](https://play.google.com/store/apps/details?id=com.termux.api))
  - Termux:Widget for a handy shortcut
    ([F-Droid](https://f-droid.org/packages/com.termux.widget/) or
    [Play
    Store](https://play.google.com/store/apps/details?id=com.termux.widget))

Please follow the instructions on [Termux
Wiki](https://wiki.termux.com/wiki/Main_Page) to set up ffmpeg, API
(permissions, storage) and Widget.

The whole of `vicor` is in the script of the same name. It has a
couple of settings at the top, which have reasonable defaults, except
for `videodir` and `outputdir` which you need to set for your
device. You can change these settings in the `vicor` script directly,
or you can create a wrapper script that exports the corresponding
environment variables.

When you have defined video and output directories (and/or other
settings), place `vicor` or the wrapper script in the `~/.shortcuts`
directory. This is where Termux-Widget will pick it up. You can then
add a shortcut to your home screen.

The settings mean the following:

  - `VICOR_VIDEO_DIR`: the directory where `vicor` finds video
    files. This would tipically be the directory under `DCIM` where
    your camera app stores recordings.
  - `VICOR_OUTPUT_DIR`: the directory where `vicor` will save the
    reencoded videos. They will have the same name as the source
    files, so *don't set VIDEO and OUTPUT settings to the same
    directory*.
  - `VICOR_MAX_SIZE`: when resizing videos, this will be the maximum
    width (or height, in portrait mode) of the video. For example, a
    1920x1080 video will be resized to 720x405.
  - `VICOR_AAC_QUALITY`: when resizing videos, this will be the
    ffmpeg's quality goal. The default value noticeably reduces file
    size, but also quality. *When keeping the original video size,
    audio is not touched*.
  - `VICOR_FILE_SUFFIX`: defaults to `mp4` and should probably be kept
    that way, unless you're using `vicor` for something else besides
    reencoding on a phone.

### An example wrapper script

If you don't want to modify the script directly, you can use a wrapper
like the one below.

```bash
#!/data/data/com.termux/files/usr/bin/bash

export VICOR_VIDEO_DIR=$HOME/storage/shared/DCIM/OpenCamera/
export VICOR_OUTPUT_DIR=$HOME/storage/shared/DCIM/vicor/
export VICOR_MAX_SIZE=640

exec $HOME/vicor/vicor
```
