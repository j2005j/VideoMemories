package dev.camsysinfo.videomemories;

/**
 * Native YUV->RGB converter (app/src/main/jni/convert.c). The player uses
 * convertFrame / convertFrameHalf: one-pass extract+convert straight from
 * the decoder's direct buffer, byte-identical to the Java loop (verified
 * offline against a reference frame, see hosttest/). If the .so is
 * missing, AVAILABLE stays false and the player falls back to the Java
 * loop - slow but working. Fills other than Bitmap.setPixels crashed this
 * camera; see FINDINGS.md.
 */
public final class Jni {

    public static final boolean AVAILABLE;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("videomemories");
            ok = true;
        } catch (Throwable ignored) {
        }
        AVAILABLE = ok;
    }

    /** one-pass extract+convert straight from a MediaCodec direct buffer
     *  into a full-res int[] (w*h ARGB). false = not direct / bounds. */
    public static native boolean convertFrame(java.nio.ByteBuffer buf, int offset,
            int w, int h, int stride, int slice, boolean planar, int[] out);

    /** same shape, HALF-res output: out(x,y) == full pixel (2x,2y),
     *  host-proven for planar and semi-planar against the reference frame. */
    public static native boolean convertFrameHalf(java.nio.ByteBuffer buf, int offset,
            int w, int h, int stride, int slice, boolean planar, int[] out);

    private Jni() {
    }
}
