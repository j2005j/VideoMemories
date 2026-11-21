# Sony A6300 (ILCE-6300) Android 4.1.2: media stack findings

Field-measured notes from building the looping H.264 player in this
repository. Every claim was reproduced on the device. Everything marked
CRASH took the camera down (full restart, not just an app close) or the
app down; each entry lists what to use instead. Numbers come from
on-device perf lines, not estimates.

## The platform in one screen

- Android 4.1.2 (API 16), ARM32, Sony "scalar" vendor ROM. The platform
  media stack is partially booby-trapped: several standard Android
  queries that every info/playing app makes will crash this camera.
- `/system/etc/media_codecs.xml` does not exist (`MediaCodecList` logs
  "unable to open media codecs configuration xml file").
- The one decode route proven to work forever:
  `MediaCodec.createByCodecName("OMX.google.h264.decoder")` fed one raw
  NAL per input buffer.
- Dead on arrival: `MediaPlayer`, `createDecoderByType`, surface/
  TextureView output, GL textures, and all audio output.
- The SD card goes through Sony's FUSE layer with DOS 8.3 names only.
- The logcat clock runs about 9 minutes behind the app clock: timestamps
  inside logcat lines do not line up with your own log timestamps.

## CRASHES: what each one costs, and what to use instead

1. **MediaCodecList enumeration reboots the camera.** Walking the static
   API (`MediaCodecList.getCodecCount()` / `getCodecInfoAt(i)`) caused a
   kernel reboot in one run and an app kill in another. Do not enumerate
   codecs, not even to "check what is supported". Inventory alternative:
   the device ships no `media_codecs.xml` anyway; list
   `/system/lib/libstagefright*.so` files if you need names.
2. **`MediaCodec.createDecoderByType("video/avc")` kills the app.** It
   failed every attempt. The distinction that matters:
   `createDecoderByType` consults the broken codec list internally, while
   `createByCodecName("OMX.google.h264.decoder")` bypasses it and has
   run indefinitely (configure, start, hundreds of decode passes). Always
   create by explicit component name.
3. **`MediaPlayer.prepare()` always fails with status 0x1.** Tried with
   path and fd sources, app-private and world-readable files, video and
   audio-only content: same IOException every time. There is no working
   MediaPlayer route; go straight to `MediaCodec`.
4. **`Bitmap.copyPixelsFromBuffer` fill: CRASH** (kernel reboot in one
   run, app kill in the next) within the first video frames. Use
   `Bitmap.setPixels` - the only fill that never crashed, stable across
   every run at both 480x368 and 240x184.
5. **NDK `AndroidBitmap_lockPixels` direct pixel write: CRASH** (kernel
   reboot + app kill, same first-frames window). Same rule. The
   jnigraphics include and link were removed from the build so this code
   cannot creep back in.
6. **Surface/TextureView video output: black screen, never a frame.**
   Two independent gates in Sony's backported SoftwareRenderer: it picks
   the YV12-direct branch unless `ro.kernel.qemu` is set, and apps cannot
   create `ro.*` properties (`setprop` returns rc=0 with no error, the
   value stays absent); Sony's scalara gralloc rejects YV12 with -22.
   Independently, every shipped decoder reports color format 0x13, so
   the renderer's converter branch never selects itself. Fall back to
   the bitmap path (below).
7. **GL textures: never visible, and they leak.** EGL pbuffer rendering
   itself works (hundreds of frames), but textures composited into the
   output never appeared and each upload leaked GPU memory until the
   camera restarted. Do not build UI on GL textures here.
8. **A byte-array JNI convert entry + a second buffer extract on frame 1:
   one app kill, never isolated.** A convert function taking
   `jbyteArray`s (an entry never run on this camera before) combined
   with `ByteBuffer` extraction running twice on the first frame died in
   the first frames while its host-side ASan/UBSan tests were clean.
   Conservative rule: keep native entry points in the shape that is
   already proven (`GetDirectBufferAddress` + `GetIntArrayElements`)
   and extract each output buffer once per frame.
9. **`setprop("ro.kernel.qemu", ...)`: a lie.** Returns rc=0 with an
   empty error; `getprop` never shows the value. Apps cannot create
   `ro.*` properties on this ROM.

## Audio: none, by hardware

The ROM ships the stub `audio.primary.default.so` HAL. `AudioTrack`
wrote and drained 22050 frames at volume 11/15 with normal stream state
and nothing was ever audible: there is no Android audio output path on
this camera, the speaker belongs to Sony's native layer. Build for
silent video.

## Decoding: what works, with numbers

- One raw NAL per input buffer (start codes stripped before
  `queueInputBuffer`), SPS/PPS sent with `BUFFER_FLAG_CODEC_CONFIG`,
  IDR frames flagged `BUFFER_FLAG_KEY_FRAME`.
- Raw Annex-B streams carry no timestamps: advance a synthetic pts by
  `1000000/24` microseconds per VCL NAL and pace the loop at 24 fps.
  Without that, a 30 fps source would play in slow motion.
- `feed()` silently queues an empty buffer for NALs larger than the
  input buffer, so keep I-frames bounded (GOP of 48 at these bitrates).
- The software decoder's ceiling is about 24 fps at 480x368 (surface
  mode measured the same number with no app work). With the half-res
  converter the app spends ~10.4 ms of each 41.7 ms frame budget, so
  playback holds 24 fps real-time (a 6.000 s clip played in 5969 ms and
  5970 ms, two runs) and is decoder-bound from there: ~31 ms per frame
  is the decoder/timeline floor.
- Measured converter cost per frame: full-res one-pass 26.3 ms, half-res
  one-pass 6.2 ms. Measured `setPixels` fill: 17.0 ms at 480x368, 4.7 ms
  at 240x184.
- `configure()` is called with a 480x360 hint before the stream's SPS is
  known; the soft decoder is expected to reconfigure from the SPS
  (watch the output format change for non-480x360 files).

## Display: what works

- Color format 0x13 (planar I420) is reported by ALL shipped decoders
  (AVC at runtime, MPEG4/VPX in their initPorts) - treat it as the one
  reference layout.
- CPU integer BT.601 conversion, byte-identical to a reference frame
  offline, then `Bitmap.setPixels`:

      d = U - 128, e = V - 128, clamp each result to 0..255
      r = Y + ((91881  * e) >> 16)
      g = Y - ((22554  * d + 46802 * e) >> 16)
      b = Y + ((116130 * d) >> 16)
      pixel = 0xFF000000 | (r << 16) | (g << 8) | b

- Double-buffered display: the decoder thread writes the back bitmap and
  swaps under the lock; the UI thread reads the front bitmap outside the
  lock and blits on a software Canvas, nearest-neighbor (`FILTER_BITMAP`
  off - bilinear was measurably too slow).
- This app stretches the picture to the whole panel (aspect ignored by
  request). Encode to the `screen WxH` line the player logs at start and
  the stretch is a full-screen fill with the content undistorted.
- Color self-check idea worth keeping: sample three fixed pixels from the
  extracted planes on frames 1 and 24 and log them; any decode or
  conversion regression shows up there before a human sees it.

## Gray is not black: video range

Video is limited-range: black is Y 16, white is Y 235. The converter
above is full-range math, so limited black renders as RGB 16 - dark
gray. This is not compression. A test pattern's nominal black measured
RGB 16,16,16 and its white 235 through the whole pipeline. Fix it at
encode time (the ffmpeg recipe below expands the range); the on-device
converter is left alone because it is the field-proven one.

## Storage and file names

Sony's FUSE layer serves DOS 8.3 names only: long names fail with
ENAMETOOLONG (a 10-letter folder name failed; 5-6 letter folders work).
Keep file and folder names 8.3 - this app logs to `VMEM/LOG.TXT`
(append-only, rotated past 64 KB).

## Content preparation: ffmpeg

The player eats RAW Annex-B H.264 streams (`*.264`): `MediaPlayer` is
dead, no container demuxer was ever proven, and audio does not exist.
Validated end-to-end on ffmpeg 6.1: Constrained Baseline, even canvas,
exactly 24 frames per source second, black = Y 0 after encode.

    LUT="lutyuv=y='clip((val-16)*255/219,0,255)':u='clip((val-128)*255/224+128,0,255)':v='clip((val-128)*255/224+128,0,255)'"
    ffmpeg -i INPUT -vf "scale=640:480:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=640:480:(ow-iw)/2:(oh-ih)/2,$LUT,setsar=1,fps=24" \
      -c:v libx264 -profile:v baseline -pix_fmt yuv420p \
      -crf 17 -maxrate 4000k -bufsize 4000k \
      -x264-params "aq-mode=3:aq-strength=1.1:chroma-qp-offset=-2" \
      -g 48 -an -f h264 MYVID001.264

Why each part:

- The LUT after `pad` is the range fix: Y 16-235 -> 0-255, chroma
  16-240 -> 0-255, so true black arrives as Y 0 (measured on the filter
  output and after the x264 round-trip; the pad bars expand too).
- Use `lutyuv`, not `scale`'s `in_range`/`out_range`: on ffmpeg 6.1
  those scale options were measured to be a silent no-op (an explicit
  tv->full conversion left Y 16 unchanged).
- `crf 17` plus `-x264-params "aq-mode=3:aq-strength=1.1:chroma-qp-offset=-2"`
  fix "dark yellow blotches": chroma quantization of noise in flat dark
  blocks. Auto-variance AQ with dark bias spends bits exactly there, the
  chroma offset gives color a finer quantizer. These are x264 params and
  must go through `-x264-params`; ffmpeg's top level rejects them
  ("Unrecognized option"). Optional for noisy sources: prepend
  `hqdn3d=3:4:6:8` to the filter chain.
- `fps=24`: mandatory - raw streams carry no timestamps and the player
  advances 24 synthetic pts/s.
- `force_original_aspect_ratio` + `pad`: fit the frame into the target
  canvas without stretching the content. `force_divisible_by=2` keeps
  the scaled frame even for yuv420p; older ffmpeg without it: insert
  `,scale=trunc(iw/2)*2:trunc(ih/2)*2` between scale and pad. Truly
  anamorphic sources (rare): prepend `scale=iw*sar:ih`.
- `profile:v baseline` is the proven decode path (and disables
  B-frames); `g 48` bounds I-frame size so keyframes fit one codec
  input buffer (`feed()` drops oversized NALs silently); `maxrate`/
  `bufsize` keep runaway CRF spikes away from the same limit.
- `pix_fmt yuv420p` = planar I420 = color format 0x13.
- `-an`: no audio path exists. `-f h264`: raw elementary stream, exactly
  what the NAL index parses. Output name must be 8.3.

## The player architecture this repo ships

- File browser (directories and `*.264`/`*.h264`, selection remembered)
  -> player activity with `path` + `loop` extras.
- The source is scanned once into an (offset, length) NAL index - never
  loaded whole (a full-file `byte[]` list OOMs on real videos) - and
  NALs stream sequentially from the file. The index boundary rules are
  host-proven byte-identical against a reference Annex-B splitter
  (hosttest/), including a second full read pass for loop restarts.
- Looping = fresh codec `create/configure/start/release` per pass. The
  restart shape is proven; `codec.flush()` is not - do not use it here.
  A pass that ends short (frames < expected) does NOT loop; it returns
  to the browser instead of spinning on a broken file.
- Pause: the middle button blocks the decode loop before feed/drain and
  shifts the pacing schedule by the pause duration (no fast-forward on
  resume). On-screen: "Loading..." until the first frame, "PAUSED" while
  paused, nothing at all during playback. MENU/BACK leave playback;
  MENU in the browser exits the app.
- Watchdog: none while looping (MENU exits); a per-pass 60 s deadline
  still catches a wedged pass and drops back to the browser.

## Stability ledger (measured on this camera)

PROVEN - full passes, never a crash:

- `Bitmap.setPixels` fill, any tested size (480x368, 240x184) - THE
  safe fill, the only one allowed.
- `convertFrame` / `convertFrameHalf` one-pass direct-buffer JNI -
  26.3 / 6.2 ms per frame.
- Double-buffered bitmap swap + software Canvas blit (letterbox or
  stretch), any bitmap size.
- `ByteBuffer` extraction once per frame; Java integer convert
  (~100 ms/frame - slow but proven, the last-resort fallback).
- Pure-Java int[] subsample; yield in the decode loop; per-NAL feed to
  `OMX.google.h264.decoder`.

DEAD - crashes or fails, never again:

- `MediaCodecList` enumeration (kernel reboot / app kill).
- `MediaCodec.createDecoderByType` (app kill).
- `MediaPlayer.prepare` (status 0x1, always).
- `Bitmap.copyPixelsFromBuffer` fill; `AndroidBitmap_lockPixels` write
  (both: reboot + app kill).
- Surface/TextureView video output (black); GL textures (invisible +
  GPU leak); `setprop ro.*` (silently ignored).
- Byte-array JNI convert entries not yet proven on the device, and any
  second buffer extract on frame 1 (one unisolated app kill).
- Audio output (stub HAL, silent by construction).

## Recommendations for apps on this camera

- Copy this architecture: `createByCodecName`, per-NAL feed, integer
  BT.601 convert, `setPixels` into a double-buffered bitmap. The
  half-res converter is the 24 fps real-time path; the full-res one is
  the sharper 14-15 fps path.
- Never touch `MediaCodecList`, `createDecoderByType`, `MediaPlayer`,
  GL textures, or surface mode.
- No audio paths at all.
- SD card file and folder names must be 8.3.
- Keep native entries in the proven direct-buffer shape; host tests
  (ASan clean) are necessary but not sufficient - a host-clean byte-array
  entry still died on the device. Field passes are the promotion.
