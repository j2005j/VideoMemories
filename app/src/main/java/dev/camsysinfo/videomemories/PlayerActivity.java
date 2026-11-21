package dev.camsysinfo.videomemories;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import android.os.Environment;

/**
 * The looping video player: a card file (EXTRA_PATH) decoded with the
 * camera's software H.264 decoder and stretched full-panel, replayed
 * forever (EXTRA_LOOP) with a fresh codec per pass.
 *
 * Display reality on this camera (short version; full evidence with
 * measurements in FINDINGS.md): the surface path has two gates in Sony's
 * backported SoftwareRenderer and both are closed - the property service
 * refuses to create ro.kernel.qemu from an app (setprop returns rc=0
 * anyway, getprop shows nothing) and Sony's scalara gralloc rejects
 * YV12 (-22) -> black; every shipped decoder reports color format 0x13,
 * so the converter branch never selects itself. What works is the bitmap
 * path: CPU integer BT.601 convert into a double-buffered bitmap on a
 * software Canvas, drawn nearest-neighbor. ~24 fps real-time at 480 wide.
 *
 * Player rules that were paid for with field runs: one raw NAL per
 * input buffer, SPS/PPS as CODEC_CONFIG, codec RECREATE (never flush)
 * for loop restarts, setPixels as the only bitmap fill (every other fill
 * crashed the camera), and a 24 fps synthetic pacing clock (raw Annex-B
 * carries no timestamps).
 *
 * On-screen: "Loading..." until the first frame (never again on loops),
 * "PAUSED" on centre, nothing else. MENU/BACK return to the browser.
 *
 * Diagnostics land in VMEM/LOG.TXT: perf line, px@ color self-checks and
 * the "screen WxH" line the ffmpeg recipe wants.
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static final String EXTRA_RESULT = "detail";
    static final String EXTRA_PATH = "path";   // card file to play (null = demo asset)
    static final String EXTRA_LOOP = "loop";   // browser mode: replay forever
    private static final int FPS = 24;
    private static final long WATCHDOG_MS = 65000;

    private boolean surfaceMode = false;
    private String srcPath;
    private boolean loopMode;
    private FrameLayout root;
    private SurfaceView surfaceView;
    private BitmapView bitmapView;
    private TextView overlay;       // Loading... / PAUSED only; gone while playing
    private volatile boolean loadingDone;
    private volatile boolean paused;
    private Decoder decoder;
    private Thread decoderThread;
    private volatile boolean started = false;
    private volatile boolean ended = false;
    private boolean flashed = false;
    private final Handler handler = new Handler();

    /** hide "Loading..." for good once the first frame reached the screen
     *  (loop restarts keep it hidden - no controls on loops, per spec) */
    private final Runnable loadingTick = new Runnable() {
        @Override
        public void run() {
            if (ended) return;
            if (!loadingDone && decoder != null && decoder.frames() > 0) {
                loadingDone = true;
                refreshOverlay();
                return;
            }
            handler.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        srcPath = getIntent().getStringExtra(EXTRA_PATH);
        loopMode = getIntent().getBooleanExtra(EXTRA_LOOP, false);

        // decide the display path BEFORE anything touches a surface: the
        // SoftwareRenderer ctor (during configure) reads the property then
        String prop = setQemuProp();
        surfaceMode = prop.contains("[ro.kernel.qemu]: [1]");
        MemLog.sdLog("qemu prop: " + prop + (surfaceMode
                ? " -> surface mode" : " -> bitmap mode"));
        // panel pixels: the ffmpeg canvas to encode to for warp-free
        // stretch-to-fill (same Display API the surface UI already uses)
        MemLog.sdLog("screen " + getWindowManager().getDefaultDisplay().getWidth()
                + "x" + getWindowManager().getDefaultDisplay().getHeight());

        if (surfaceMode) {
            buildSurfaceUi();
        } else {
            bitmapView = new BitmapView(this);
            overlay = newOverlay("Loading...");
            FrameLayout fl = new FrameLayout(this);
            fl.setBackgroundColor(Color.BLACK);
            fl.addView(bitmapView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            fl.addView(overlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(fl);
        }

        decoder = new Decoder(new Decoder.Done() {
            @Override
            public void finished(boolean ok, String detail) {
                finishWith(ok, detail);
            }
        }, surfaceMode, bitmapView, srcPath, loopMode);
        if (!surfaceMode) {
            // no surface to wait for: start immediately
            started = true;
            decoderThread = new Thread(decoder, "videomemories-player");
            decoderThread.setDaemon(true);
            decoderThread.start();
        }

        handler.postDelayed(loadingTick, 150);
        if (!loopMode) {
            // watchdog only for single-pass runs: looping plays until MENU
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finishWith(false, "watchdog " + WATCHDOG_MS / 1000
                            + "s frames=" + decoder.frames() + "/" + decoder.total());
                }
            }, WATCHDOG_MS);
        }
    }

    /** surface mode: letterboxed SurfaceView, sized ONCE before the surface
     *  exists (a layout change after configure abandons the surface) */
    private void buildSurfaceUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        int sw = getWindowManager().getDefaultDisplay().getWidth();
        int sh = getWindowManager().getDefaultDisplay().getHeight();
        float ar = 480f / 368f;
        int w = sw;
        int h = (int) (w / ar);
        if (h > sh) {
            h = sh;
            w = (int) (h * ar);
        }
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                w, h, Gravity.CENTER));
        overlay = newOverlay("Loading...");
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    // ------------------------------------------------ surface lifecycle

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!flashed) {
            // visibility control: paint RED first; then recreate the surface
            // so the codec gets a pristine one (canvas locks the CPU api)
            flashed = true;
            try {
                Canvas c = holder.lockCanvas();
                if (c != null) {
                    c.drawColor(Color.RED);
                    holder.unlockCanvasAndPost(c);
                }
                android.graphics.Rect f = holder.getSurfaceFrame();
                MemLog.sdLog("surf red flash " + f.width() + "x"
                        + f.height() + " view=" + surfaceView.getWidth() + "x"
                        + surfaceView.getHeight());
            } catch (Throwable t) {
                MemLog.sdLog("surf flash FAIL " + t);
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    surfaceView.setVisibility(View.GONE);
                }
            }, 1200);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    surfaceView.setVisibility(View.VISIBLE);
                }
            }, 1400);
            return;
        }
        decoder.setSurface(holder.getSurface());
        maybeStart();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        decoder.setSurface(null);
    }

    private void maybeStart() {
        if (started || !decoder.hasSurface()) return;
        started = true;
        decoderThread = new Thread(decoder, "videomemories-player");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }

    // ------------------------------------------------ result / lifecycle

    private void finishWith(final boolean ok, final String detail) {
        if (ended) return;
        ended = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent();
                i.putExtra(EXTRA_RESULT, detail);
                setResult(ok ? RESULT_OK : RESULT_FIRST_USER, i);
                finish();
            }
        });
    }

    // ------------------------------------------------ v28 browser UX

    private TextView newOverlay(String text) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(0xAA000000);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(18);
        t.setText(text);
        return t;
    }

    /** No on-screen controls while playing: the overlay exists only for
     *  "Loading..." before the first frame and "PAUSED" while paused. */
    private void refreshOverlay() {
        if (overlay == null) return;
        final String txt = paused ? "PAUSED" : (loadingDone ? "" : "Loading...");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                overlay.setText(txt);
                overlay.setVisibility(txt.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void togglePause() {
        if (decoder == null || ended) return;
        paused = !paused;
        decoder.setPaused(paused);
        refreshOverlay();
    }

    private void exitUser(String why) {
        if (ended) return;
        ended = true;
        Intent i = new Intent();
        i.putExtra(EXTRA_RESULT, why);
        // results are only a close-signal here; the browser ignores them
        setResult(RESULT_CANCELED, i);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            exitUser("user menu"); // spec: MENU leaves playback, not the app
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exitUser("user back");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            togglePause(); // the middle button: pause / resume
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (decoder != null) decoder.stopNow();
        if (decoderThread != null) {
            try {
                decoderThread.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ------------------------------------------------ qemu property

    /** set ro.kernel.qemu=1 and verify with a FULL getprop dump (a plain
     *  `getprop name` readback showed <unset> after rc=0 - the service may
     *  refuse ro.* creation silently, so grep the listing and keep setprop's
     *  stderr for the record). Returns the evidence string. */
    static String setQemuProp() {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {
                    "setprop", "ro.kernel.qemu", "1" });
            BufferedReader er = new BufferedReader(
                    new InputStreamReader(p.getErrorStream()));
            String err = er.readLine();
            er.close();
            int rc = p.waitFor();
            Process g = Runtime.getRuntime().exec(new String[] { "getprop" });
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(g.getInputStream()));
            String found = null, line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("[ro.kernel.qemu")) {
                    found = line;
                    break;
                }
            }
            br.close();
            g.waitFor();
            return "setprop rc=" + rc
                    + " err=" + (err == null ? "-" : err)
                    + " found=" + (found == null ? "<absent>" : found);
        } catch (Throwable t) {
            return "FAIL " + t;
        }
    }

    // ------------------------------------------------ logcat

    // ------------------------------------------------ decoder thread

    private static class Decoder implements Runnable {

        interface Done {
            void finished(boolean ok, String detail);
        }

        private static final String CODEC = "OMX.google.h264.decoder";
        private static final int DEMO_W = 480;
        private static final int DEMO_H = 360;

        private final Done done;
        private final boolean useSurface;
        private final BitmapView view; // null in surface mode
        private final String srcPath;  // card file, null = stage the demo asset
        private final boolean loop;    // browser mode: replay forever

        private volatile Surface surface;

        private File srcFile;
        private NalIndex index;
        private NalReader reader;
        private int loopCount;   // completed looping passes (0 = first pass)
        private int vclTotal;

        private MediaCodec codec;
        private ByteBuffer[] outBufs;
        private MediaCodec.BufferInfo outInfo;
        private boolean eosQueued;
        private boolean ended;
        private long ptsUs;
        private volatile int frames;
        private long tStart;

        // format state
        private int outW = DEMO_W, outH = DEMO_H, stride = DEMO_W, slice = DEMO_H;
        private int colorFormat = 0x13;
        private boolean planar = true;
        private boolean canPeek;

        // frame planes + conversion (peek fills FULL planes for ysum/px@;
        // convertFrame writes full-res px[], the display subsamples to
        // pxHalf - v26 bisect: display-size change only)
        private byte[] yArr, uArr, vArr, rowTmp;
        private int[] px;
        private int[] pxHalf;
        private boolean diagLogged;
        private int rbStage; // pixel log at frame 1, then 24 (peek owns it)
        // stage timers, nanoseconds accumulated over the run (see end())
        private long tNat, tJava, tShow;
        private volatile boolean stop;
        private volatile boolean paused;
        private final Object pauseLock = new Object();

        Decoder(Done d, boolean surface, BitmapView v,
                String path, boolean looping) {
            done = d;
            useSurface = surface;
            view = v;
            srcPath = path;
            loop = looping;
        }

        void setSurface(Surface s) {
            surface = s;
        }

        boolean hasSurface() {
            return surface != null;
        }

        int frames() {
            return frames;
        }

        int total() {
            return vclTotal;
        }

        void stopNow() {
            stop = true;
            synchronized (pauseLock) {
                pauseLock.notifyAll(); // a paused decoder must wake to exit
            }
        }

        void setPaused(boolean p) {
            synchronized (pauseLock) {
                paused = p;
                if (!p) pauseLock.notifyAll();
            }
        }

        @Override
        public void run() {
            try {
                loadSource();
                while (!stop) {
                    if (!runPass()) break;
                    // natural EOS on a looping playback: next pass
                    loopCount++;
                    resetPass();
                    openReader();
                    MemLog.sdLog("player loop " + loopCount + " start");
                }
            } catch (Throwable t) {
                end(false, "err " + t.getClass().getSimpleName() + " "
                        + String.valueOf(t.getMessage()) + " at frame " + frames
                        + " | " + trace(t));
            } finally {
                closeReader();
            }
        }

        /** One codec pass: create -> feed/drain -> EOS, then release.
         *  @return true = pass ended at natural EOS and another loop pass
         *          should start */
        private boolean runPass() {
            try {
                codec = MediaCodec.createByCodecName(CODEC);
                MediaFormat fmt = MediaFormat.createVideoFormat("video/avc",
                        DEMO_W, DEMO_H);
                if (useSurface) {
                    Surface s = surface;
                    if (s == null) {
                        end(false, "no surface");
                        return false;
                    }
                    codec.configure(fmt, s, null, 0);
                } else {
                    codec.configure(fmt, null, null, 0);
                }
                codec.start();
                outInfo = new MediaCodec.BufferInfo();
                outBufs = codec.getOutputBuffers();
                tStart = SystemClock.uptimeMillis();
                if (loopCount == 0) {
                    MemLog.sdLog("player start ("
                            + (useSurface ? "surface" : "bitmap") + ") "
                            + outW + "x" + outH + " nals=" + index.count
                            + " vcl=" + vclTotal);
                }
                while (!stop) {
                    awaitResume();
                    if (stop) return false;
                    long now = SystemClock.uptimeMillis();
                    if (now - tStart > 60000) {
                        end(false, "60s deadline frames=" + frames + "/" + vclTotal);
                        return false;
                    }
                    feed();
                    int r = drain(now);
                    if (r == 2) return false;
                    if (r == 3) return true;
                    if (r == 0) {
                        // nothing ready: yield instead of the v21 busy-spin
                        // (that spin burned ~25ms/frame of "other" and stole
                        // CPU from the decoder and the UI blit)
                        Thread.sleep(1);
                        continue;
                    }
                    if (frames > 0) {
                        long target = tStart + (long) frames * 1000 / FPS;
                        long slp = target - SystemClock.uptimeMillis();
                        if (slp > 1) Thread.sleep(Math.min(slp, 200));
                    }
                }
                return false;
            } catch (Throwable t) {
                end(false, "err " + t.getClass().getSimpleName() + " "
                        + String.valueOf(t.getMessage()) + " at frame " + frames
                        + " | " + trace(t));
                return false;
            } finally {
                releaseCodec();
            }
        }

        /** middle-button pause: blocks here instead of feeding, and shifts
         *  the pacing schedule by the pause duration on resume so playback
         *  continues where it stopped instead of fast-forwarding */
        private void awaitResume() {
            synchronized (pauseLock) {
                long p0 = 0;
                while (paused && !stop) {
                    try {
                        if (p0 == 0) p0 = SystemClock.uptimeMillis();
                        pauseLock.wait(500);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (p0 > 0) tStart += SystemClock.uptimeMillis() - p0;
            }
        }

        private static String trace(Throwable t) {
            String[] ls = Log.getStackTraceString(t).split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ls.length && i < 5; i++) {
                if (i > 0) sb.append(" <- ");
                sb.append(ls[i].trim());
            }
            return sb.toString();
        }

        /** Index the source once; NALs stream from the file after that
         *  (host-proven byte-identical to an in-memory split). The browser
         *  always passes a path - a missing one fails the run cleanly. */
        private void loadSource() throws Exception {
            if (srcPath == null || srcPath.length() == 0) {
                throw new Exception("no video path");
            }
            srcFile = new File(srcPath);
            MemLog.sdLog("player src " + srcPath);
            index = NalIndex.scan(srcFile);
            vclTotal = index.vcl;
            openReader();
        }

        private void openReader() throws IOException {
            closeReader();
            reader = new NalReader(srcFile, index);
        }

        private void closeReader() {
            if (reader != null) {
                reader.close();
                reader = null;
            }
        }

        /** per-loop reset - NOT diagnostics state (px@/ysum log once per
         *  playback, not per loop) and not the perf accumulators */
        private void resetPass() {
            eosQueued = false;
            ptsUs = 0;
            frames = 0;
            ended = false;
        }

        // ------------------------------------------------ decode loop

        private void feed() throws IOException {
            while (!eosQueued) {
                int in = codec.dequeueInputBuffer(0);
                if (in < 0) return;
                byte[] nal = reader.next();
                if (nal == null) {
                    codec.queueInputBuffer(in, 0, 0, 0, 4); // EOS
                    eosQueued = true;
                    return;
                }
                ByteBuffer bb = codec.getInputBuffers()[in];
                bb.clear();
                if (nal.length == 0 || nal.length > bb.capacity()) {
                    codec.queueInputBuffer(in, 0, 0, ptsUs, 0);
                    continue;
                }
                bb.put(nal);
                int type = nal[0] & 0x1F;
                // v8-proven feed: one raw NAL per buffer (NalIndex strips
                // start codes), SPS/PPS as CODEC_CONFIG, IDR as keyframe
                int flags = (type == 7 || type == 8) ? 2 : (type == 5 ? 1 : 0);
                long pts = ptsUs;
                if (type >= 1 && type <= 5) {
                    ptsUs += 1000000L / FPS;
                }
                codec.queueInputBuffer(in, 0, nal.length, pts, flags);
            }
        }

        /** Drain output buffers.
         *  @return 0 = nothing ready, 1 = at least one frame displayed,
         *          2 = playback ended for good (done.finished called),
         *          3 = pass over at natural EOS - looping restarts */
        private int drain(long now) {
            int processed = 0;
            for (;;) {
                int out = codec.dequeueOutputBuffer(outInfo, 0);
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return processed;
                if (out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outBufs = codec.getOutputBuffers();
                    continue;
                }
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onOutFormat();
                    continue;
                }
                if (out < 0) return processed;
                boolean eosOut = (outInfo.flags & 4) != 0;
                if (outInfo.size > 0 && !eosOut) {
                    peek(out); // diagnostics: ysum + px@ on frames 1 and 24
                    if (useSurface) {
                        codec.releaseOutputBuffer(out, true); // render
                    } else {
                        present(out); // convert + show (bitmap mode)
                        codec.releaseOutputBuffer(out, false);
                    }
                    frames++;
                    processed = 1;
                    if (loopCount == 0 && (frames == 1 || frames % 24 == 0)) {
                        MemLog.sdLog("player frame " + frames + "/" + vclTotal);
                    }
                } else {
                    codec.releaseOutputBuffer(out, false);
                }
                if (eosOut) {
                    boolean all = frames >= vclTotal;
                    long ms = now - tStart;
                    boolean again = end(all, (all ? "PASS " : "FAIL ")
                            + frames + "/" + vclTotal
                            + " frames " + outW + "x" + outH + " in " + ms
                            + "ms (" + (frames * 1000L / Math.max(1, ms))
                            + " fps)");
                    return again ? 3 : 2;
                }
            }
        }

        private void onOutFormat() {
            MediaFormat f = codec.getOutputFormat();
            outW = getInt(f, "width", outW);
            outH = getInt(f, "height", outH);
            stride = getInt(f, "stride", -1);
            slice = getInt(f, "slice-height", -1);
            colorFormat = getInt(f, "color-format", colorFormat);
            // AOSP 4.1.2 numbering: 19=YUV420Planar 21=YUV420SemiPlanar
            planar = !(colorFormat == 21 || colorFormat == 27
                    || colorFormat == 0x7F000100 || colorFormat == 0x7FA30C00
                    || colorFormat == 0x7FA30C01);
            if (stride <= 0) stride = outW;
            if (slice <= 0) slice = outH;
            canPeek = planar && colorFormat == 0x13; // only our reference layout
            yArr = new byte[outW * outH];
            uArr = new byte[outW * outH / 4];
            vArr = new byte[outW * outH / 4];
            rowTmp = new byte[outW];
            px = new int[outW * outH];
            pxHalf = new int[(outW >> 1) * (outH >> 1)];
            if (loopCount == 0) {
                MemLog.sdLog("player fmt " + outW + "x" + outH + " stride=" + stride
                        + " slice=" + slice + " cf=0x" + Integer.toHexString(colorFormat)
                        + (planar ? " planar" : " semi")
                        + (canPeek ? "" : " (peek skipped)"));
            }
        }

        private static int getInt(MediaFormat f, String key, int dflt) {
            try {
                return f.getInteger(key);
            } catch (Throwable t) {
                return dflt;
            }
        }

        // ------------------------------------------------ readback (once)

        private void peek(int idx) {
            if (!canPeek) return;
            if (rbStage == 0 && frames != 0) return;
            if (rbStage == 1 && frames != 23) return;
            if (rbStage > 1) return;
            try {
                extractPlanes(idx);
                if (rbStage == 0) {
                    logYsum();
                }
                logPixels(rbStage == 0 ? 1 : 24);
                rbStage++;
            } catch (Throwable t) {
                MemLog.sdLog("player peek FAIL " + t);
                rbStage = 2;
                canPeek = false;
            }
        }

        private void logYsum() {
            if (diagLogged) return;
            diagLogged = true;
            long sum = 0;
            for (int i = 0; i < yArr.length; i++) sum += yArr[i] & 0xFF;
            MemLog.sdLog("player ysum=" + sum
                    + " b0=" + (yArr[0] & 0xFF)
                    + " convert=" + (Jni.AVAILABLE ? "jni" : "java"));
        }

        // ------------------------------------------------ bitmap display

        /** v27: convertFrameHalf - half-res written straight from the
         *  decoder's direct buffer through the JNI shape convertFrame has
         *  used in every stable build (v21-v23, v26). Reads a quarter of
         *  the Y plane plus chroma, writes a quarter of the ints. Avoids
         *  both of v25's suspects: no byte-array JNI, no second
         *  extractPlanes on frame 1 (peek keeps the diagnostics).
         *  Host-proven: half(x,y) == full(2x,2y), planar AND semi, vs the
         *  reference frame. Fallback ladder if the native call refuses:
         *  v26 path (convertFrame full + java subsample) -> pure Java.
         *  setPixels remains the ONLY bitmap fill (v22/v24 rule).
         *  Diagnostics (ysum, px@) belong to peek(), full-res, and run first. */
        private void present(int idx) {
            // layout sanity against the real frame size (packed stride:
            // v8 measured 115200 == 320*240*3/2); fall back through guesses
            long need = (long) stride * slice * 3 / 2;
            if (need != outInfo.size) {
                if ((long) outW * outH * 3 / 2 == outInfo.size) {
                    stride = outW;
                    slice = outH;
                } else {
                    stride = (outW + 15) & ~15;
                    int s2 = (int) (outInfo.size * 2L / (3L * stride));
                    if (s2 >= outH) slice = s2;
                }
                MemLog.sdLog("player layout size=" + outInfo.size
                        + " -> stride=" + stride + " slice=" + slice);
            }
            int w2 = outW >> 1, h2 = outH >> 1;
            long t0 = System.nanoTime();
            boolean nativeOk = Jni.AVAILABLE && Jni.convertFrameHalf(
                    outBufs[idx], outInfo.offset, outW, outH, stride, slice,
                    planar, pxHalf);
            long t1 = System.nanoTime();
            if (nativeOk) {
                tNat += t1 - t0;
            } else {
                // v26-proven fallback: full convert, then java subsample.
                // Failed native attempts are ~free (JNI_FALSE fast path).
                boolean full = Jni.AVAILABLE && Jni.convertFrame(
                        outBufs[idx], outInfo.offset, outW, outH, stride, slice,
                        planar, px);
                long t2 = System.nanoTime();
                if (full) {
                    tNat += t2 - t0;
                } else {
                    extractPlanes(idx);
                    convertRgb();
                    tJava += t2 - t0;
                }
                subsamplePx(w2, h2);
                tJava += System.nanoTime() - t2;
            }
            long t3 = System.nanoTime();
            view.show(pxHalf, w2, h2);
            tShow += System.nanoTime() - t3;
        }

        /** java 2x subsample px -> pxHalf (v26-proven fallback stage) */
        private void subsamplePx(int w2, int h2) {
            for (int y = 0; y < h2; y++) {
                int src = (2 * y) * outW;
                int dst = y * w2;
                for (int x = 0; x < w2; x++) {
                    pxHalf[dst + x] = px[src + 2 * x];
                }
            }
        }

        /** java fallback: full-res integer BT.601 (v14-proven formula) */
        private void convertRgb() {
            int w = outW, h = outH, cw = w >> 1;
            for (int y = 0; y < h; y++) {
                int yb = y * w;
                int cb = (y >> 1) * cw;
                for (int x = 0; x < w; x++) {
                    int Y = yArr[yb + x] & 0xFF;
                    int ci = cb + (x >> 1);
                    int d = (uArr[ci] & 0xFF) - 128;
                    int e = (vArr[ci] & 0xFF) - 128;
                    int r = Y + ((91881 * e) >> 16);
                    int g = Y - ((22554 * d + 46802 * e) >> 16);
                    int b = Y + ((116130 * d) >> 16);
                    if (r < 0) r = 0; else if (r > 255) r = 255;
                    if (g < 0) g = 0; else if (g > 255) g = 255;
                    if (b < 0) b = 0; else if (b > 255) b = 255;
                    px[yb + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }

        private void extractPlanes(int idx) {
            ByteBuffer ob = outBufs[idx];
            ob.clear();
            int cw = outW / 2, ch = outH / 2;
            int ys = stride * slice;
            int cs = (stride / 2) * (slice / 2);
            for (int r = 0; r < outH; r++) {
                ob.position(outInfo.offset + r * stride);
                ob.get(rowTmp, 0, outW);
                System.arraycopy(rowTmp, 0, yArr, r * outW, outW);
            }
            if (planar) {
                int uBase = outInfo.offset + ys;
                int vBase = uBase + cs;
                for (int r = 0; r < ch; r++) {
                    ob.position(uBase + r * (stride / 2));
                    ob.get(rowTmp, 0, cw);
                    System.arraycopy(rowTmp, 0, uArr, r * cw, cw);
                    ob.position(vBase + r * (stride / 2));
                    ob.get(rowTmp, 0, cw);
                    System.arraycopy(rowTmp, 0, vArr, r * cw, cw);
                }
            } else {
                int uvBase = outInfo.offset + ys;
                for (int r = 0; r < ch; r++) {
                    ob.position(uvBase + r * stride);
                    ob.get(rowTmp, 0, outW);
                    for (int c = 0; c < cw; c++) {
                        uArr[r * cw + c] = rowTmp[2 * c];
                        vArr[r * cw + c] = rowTmp[2 * c + 1];
                    }
                }
            }
        }

        /** RGB at three fixed video points, computed from the planes peek
         *  just extracted (valid in BOTH display modes and always the
         *  current frame; v19 read px[] before convert had run -> zeros).
         *  Integer reference frame 0: ebec0b / 101010 / c10eee */
        private void logPixels(int at) {
            int[][] pts = { { 240, 184 }, { 40, 40 }, { 400, 300 } };
            StringBuilder sb = new StringBuilder("player px@" + at);
            for (int i = 0; i < pts.length; i++) {
                int x = pts[i][0], y = pts[i][1];
                int Y = yArr[y * outW + x] & 0xFF;
                int ci = (y >> 1) * (outW >> 1) + (x >> 1);
                int d = (uArr[ci] & 0xFF) - 128;
                int e = (vArr[ci] & 0xFF) - 128;
                int r = Y + ((91881 * e) >> 16);
                int g = Y - ((22554 * d + 46802 * e) >> 16);
                int b = Y + ((116130 * d) >> 16);
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                sb.append(String.format(Locale.US, " (%d,%d)=%02x%02x%02x",
                        x, y, r, g, b));
            }
            MemLog.sdLog(sb.toString());
        }

        // ------------------------------------------------ finish

        /** Close one pass. @return true = pass ended at natural EOS on a
         *  looping playback and another pass should start (done.finished
         *  is then deferred until playback really ends). */
        private boolean end(boolean ok, String detail) {
            if (ended) return false;
            ended = true;
            boolean cont = loop && ok && !stop;
            if (!cont) stop = true;
            MemLog.sdLog("player end: " + detail);
            if (loopCount == 0 && tStart > 0) {
                // where the per-frame time went (ns buckets -> ms);
                // "other" = feed/poll/peeks + decoder wait + overhead.
                // first pass only; loops log one "player end" line each
                long wallMs = (SystemClock.uptimeMillis() - tStart);
                long natMs = tNat / 1000000, javaMs = tJava / 1000000,
                        showMs = tShow / 1000000;
                MemLog.sdLog("player perf: native=" + natMs
                        + "ms java=" + javaMs + "ms show=" + showMs
                        + "ms other=" + (wallMs - natMs - javaMs - showMs)
                        + "ms wall=" + wallMs + "ms");
            }
            if (!cont) done.finished(ok, detail);
            return cont;
        }

        private void releaseCodec() {
            if (codec == null) return;
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;
        }
    }

    // ------------------------------------------------ bitmap display

    /** double-buffered STRETCHED bitmap view on a software Canvas: fills
     *  the whole panel, aspect ignored by request - encode to the logged
     *  "screen WxH" canvas and the stretch is warp-free (it letterboxed
     *  before). GL textures and the surface/YV12 route both fail on this
     *  driver. Nearest-neighbor: bilinear filtering was measurably too
     *  slow. */
    private static class BitmapView extends View {

        private final Object lock = new Object();
        private Bitmap front; // drawn by the UI thread
        private Bitmap back;  // written by the decoder thread
        private final Paint paint = new Paint(); // no FILTER_BITMAP

        BitmapView(Context context) {
            super(context);
        }

        void show(int[] pixels, int w, int h) {
            synchronized (lock) {
                if (back == null || back.getWidth() != w || back.getHeight() != h) {
                    back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                back.setPixels(pixels, 0, w, 0, 0, w, h);
                Bitmap t = front;
                front = back;
                back = t;
            }
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            Bitmap b;
            synchronized (lock) {
                b = front;
            }
            if (b == null) return;
            // v29: STRETCH to the panel - aspect ignored on purpose
            // ("let's stretch"). dst == view bounds: no borders, and the
            // canvas clip is exactly the panel so nothing can paint
            // outside. Encode to the "screen WxH" log line's canvas and
            // the stretch fills the screen with no warp at all.
            Rect dst = new Rect(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(b, null, dst, paint);
        }
    }
}
