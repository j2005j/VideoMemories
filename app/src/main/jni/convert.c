/* YUV420 planar -> ARGB8888, integer fixed-point BT.601.
 *
 * Same coefficients and same operation order as the Java loop in
 * PlayerActivity.Decoder.convertRgb(), so output is byte-identical:
 *   r = Y + ((91881 * e) >> 16)
 *   g = Y - ((22554 * d + 46802 * e) >> 16)
 *   b = Y + ((116130 * d) >> 16)
 * with d = U-128, e = V-128, clamped to 0..255, packed 0xFFRRGGBB.
 *
 * v26: only ONE JNI entry remains - convertFrame (the v21-v23 field-
 * proven one-pass path). The retired entries (convertYuv/convertYuvHalf/
 * convertFrameToRgba, and the v24 convertFrameToBitmap that used
 * AndroidBitmap_lockPixels) are gone from this file entirely, so the
 * .so cannot offer them to any caller. Core conversion functions stay
 * because test_convert.c verifies them offline.
 *
 * Built twice:
 *  - by the NDK as libvideomemories.so for the camera (JNI entry below);
 *  - by the host gcc together with test_convert.c for the offline
 *    full-frame verification against the Python reference.
 */
#include <jni.h>
#include <stdint.h>

static inline int clamp8(int v) {
    return v < 0 ? 0 : (v > 255 ? 255 : v);
}

void convert_yuv420_argb(const uint8_t *y, const uint8_t *u, const uint8_t *v,
                         int w, int h, int32_t *out) {
    const int cw = w >> 1;
    for (int row = 0; row < h; row++) {
        const uint8_t *yp = y + row * w;
        const uint8_t *up = u + (row >> 1) * cw;
        const uint8_t *vp = v + (row >> 1) * cw;
        int32_t *op = out + row * w;
        for (int col = 0; col < w; col++) {
            int Y = yp[col];
            int d = up[col >> 1] - 128;
            int e = vp[col >> 1] - 128;
            int r = clamp8(Y + ((91881 * e) >> 16));
            int g = clamp8(Y - ((22554 * d + 46802 * e) >> 16));
            int b = clamp8(Y + ((116130 * d) >> 16));
            op[col] = (int32_t)0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
}

/* Core half-res converter, kept because test_convert.c proves the
 * H(x,y) == full(2x,2y) invariant offline. No JNI entry: the v26 player
 * does not use it (v25, which called it from Java, crashed in frame 1 -
 * see FINDINGS.md). y is the subsampled plane (w x h); u and v are the
 * FULL chroma planes - already exactly the half-res output's chroma
 * lookup table (chroma stride = output width, not width/2). */
void convert_yuv420_half(const uint8_t *y, const uint8_t *u, const uint8_t *v,
                         int w, int h, int32_t *out) {
    for (int row = 0; row < h; row++) {
        const uint8_t *yp = y + (long)row * w;
        const uint8_t *up = u + (long)row * w;
        const uint8_t *vp = v + (long)row * w;
        int32_t *op = out + (long)row * w;
        for (int col = 0; col < w; col++) {
            int Yv = yp[col];
            int d = up[col] - 128;
            int e = vp[col] - 128;
            int r = clamp8(Yv + ((91881 * e) >> 16));
            int g = clamp8(Yv - ((22554 * d + 46802 * e) >> 16));
            int b = clamp8(Yv + ((116130 * d) >> 16));
            op[col] = (int32_t)0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
}

/* One-pass extract + convert straight from a MediaCodec output buffer:
 * planar I420 or semi-planar NV12 at (offset, stride, slice) inside a
 * direct buffer, output into the ARGB int[]. Replaces the Java row-loop
 * extraction plus conversion (v20 split those; v21 merges them).
 * Returns 0 on success, -1 on bad args / bounds (caller falls back). */
int convert_frame_strided(const uint8_t *base, long cap, long offset,
                          int w, int h, int stride, int slice, int planar,
                          int32_t *out) {
    if (!base || !out || w <= 0 || h <= 0 || stride < w || slice < h
            || (stride & 1) || offset < 0)
        return -1;
    const long ys = (long)stride * slice;
    const long cs = (long)(stride / 2) * (slice / 2);
    if (offset + ys + 2 * cs > cap)
        return -1;
    const uint8_t *Y = base + offset;
    const uint8_t *U = Y + ys;
    const uint8_t *V = U + cs; // planar only
    for (int row = 0; row < h; row++) {
        const uint8_t *yp = Y + (long)row * stride;
        const uint8_t *up = U + (long)(row >> 1) * (stride >> 1);
        const uint8_t *vp = V + (long)(row >> 1) * (stride >> 1);
        const uint8_t *urow = U + (long)(row >> 1) * stride;
        int32_t *op = out + (long)row * w;
        for (int col = 0; col < w; col++) {
            int Yv = yp[col];
            int U8, V8;
            if (planar) {
                U8 = up[col >> 1];
                V8 = vp[col >> 1];
            } else {
                U8 = urow[(col >> 1) * 2];
                V8 = urow[(col >> 1) * 2 + 1];
            }
            int d = U8 - 128;
            int e = V8 - 128;
            int r = clamp8(Yv + ((91881 * e) >> 16));
            int g = clamp8(Yv - ((22554 * d + 46802 * e) >> 16));
            int b = clamp8(Yv + ((116130 * d) >> 16));
            op[col] = (int32_t)0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_camsysinfo_videomemories_Jni_convertFrame(JNIEnv *env, jclass clazz,
        jobject jbuf, jint offset, jint w, jint h, jint stride, jint slice,
        jint planar, jintArray jaOut) {
    if (!jbuf || !jaOut) return JNI_FALSE;
    jsize outLen = (*env)->GetArrayLength(env, jaOut);
    if (outLen < (jsize)w * (jsize)h) return JNI_FALSE;
    uint8_t *base = (*env)->GetDirectBufferAddress(env, jbuf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, jbuf);
    jint *out = (*env)->GetIntArrayElements(env, jaOut, NULL);
    if (!base || cap < 0 || !out) {
        if (out) (*env)->ReleaseIntArrayElements(env, jaOut, out, 0);
        return JNI_FALSE;
    }
    int rc = convert_frame_strided(base, (long)cap, (long)offset, (int)w,
            (int)h, (int)stride, (int)slice, (int)planar, (int32_t *)out);
    (*env)->ReleaseIntArrayElements(env, jaOut, out, 0);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

/* v27: one-pass extract + convert to HALF resolution straight from a
 * MediaCodec output buffer. Output pixel (x,y) == full-res pixel (2x,2y):
 * Y read at even rows/cols; planar chroma read at U/V[y*(stride>>1)+x],
 * semi chroma pair at urow[y*stride + 2x]. Reads a quarter of the Y
 * plane plus chroma, writes a quarter of the ints (176KB copy-back
 * instead of 700KB). Same conservative bounds checks as
 * convert_frame_strided (the whole frame region is validated).
 * Uses ONLY the JNI shape convertFrame has used in every stable build
 * (GetDirectBufferAddress + GetIntArrayElements) - no byte-array JNI
 * (v25's prime suspect), no second extractPlanes (v25 runner-up).
 * Returns 0 on success, -1 on bad args / odd size / bounds. */
int convert_frame_strided_half(const uint8_t *base, long cap, long offset,
                               int w, int h, int stride, int slice,
                               int planar, int32_t *out) {
    if (!base || !out || w <= 0 || h <= 0 || (w & 1) || (h & 1)
            || stride < w || slice < h || (stride & 1) || offset < 0)
        return -1;
    const long ys = (long)stride * slice;
    const long cs = (long)(stride / 2) * (slice / 2);
    if (offset + ys + 2 * cs > cap)
        return -1;
    const int w2 = w >> 1;
    const uint8_t *Y = base + offset;
    const uint8_t *U = Y + ys;
    const uint8_t *V = U + cs; // planar only
    for (int row = 0; row < (h >> 1); row++) {
        const uint8_t *yp = Y + (long)(row * 2) * stride;
        const uint8_t *up = U + (long)row * (stride >> 1);
        const uint8_t *vp = V + (long)row * (stride >> 1);
        const uint8_t *urow = U + (long)row * stride;
        int32_t *op = out + (long)row * w2;
        for (int col = 0; col < w2; col++) {
            int Yv = yp[col * 2];
            int U8, V8;
            if (planar) {
                U8 = up[col];
                V8 = vp[col];
            } else {
                U8 = urow[col * 2];
                V8 = urow[col * 2 + 1];
            }
            int d = U8 - 128;
            int e = V8 - 128;
            int r = clamp8(Yv + ((91881 * e) >> 16));
            int g = clamp8(Yv - ((22554 * d + 46802 * e) >> 16));
            int b = clamp8(Yv + ((116130 * d) >> 16));
            op[col] = (int32_t)0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_camsysinfo_videomemories_Jni_convertFrameHalf(JNIEnv *env, jclass clazz,
        jobject jbuf, jint offset, jint w, jint h, jint stride, jint slice,
        jint planar, jintArray jaOut) {
    if (!jbuf || !jaOut) return JNI_FALSE;
    jsize outLen = (*env)->GetArrayLength(env, jaOut);
    if (w <= 0 || h <= 0
            || outLen < (jsize)(w >> 1) * (jsize)(h >> 1))
        return JNI_FALSE;
    uint8_t *base = (*env)->GetDirectBufferAddress(env, jbuf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, jbuf);
    jint *out = (*env)->GetIntArrayElements(env, jaOut, NULL);
    if (!base || cap < 0 || !out) {
        if (out) (*env)->ReleaseIntArrayElements(env, jaOut, out, 0);
        return JNI_FALSE;
    }
    int rc = convert_frame_strided_half(base, (long)cap, (long)offset, (int)w,
            (int)h, (int)stride, (int)slice, (int)planar, (int32_t *)out);
    (*env)->ReleaseIntArrayElements(env, jaOut, out, 0);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

/* convert_frame_rgba (the v22 write loop) stays as a core function -
 * test_convert.c verifies it byte-for-byte. Its JNI wrapper and the v24
 * AndroidBitmap_lockPixels entry are gone: both belongs to display
 * variants that crashed the camera (v22/v24), and the v26 .so exports
 * only convertFrame. */
int convert_frame_rgba(const uint8_t *base, long cap, long offset,
                       int w, int h, int stride, int slice, int planar,
                       uint8_t *out) {
    if (!base || !out || w <= 0 || h <= 0 || (w & 1) || stride < w
            || slice < h || (stride & 1) || offset < 0)
        return -1;
    const long ys = (long)stride * slice;
    const long cs = (long)(stride / 2) * (slice / 2);
    if (offset + ys + 2 * cs > cap)
        return -1;
    const uint8_t *Y = base + offset;
    const uint8_t *U = Y + ys;
    const uint8_t *V = U + cs;
    for (int row = 0; row < h; row++) {
        const uint8_t *yp = Y + (long)row * stride;
        uint8_t *op = out + (long)row * w * 4;
        if (planar) {
            const uint8_t *up = U + (long)(row >> 1) * (stride >> 1);
            const uint8_t *vp = V + (long)(row >> 1) * (stride >> 1);
            for (int col = 0; col < w; col += 2) {
                int d = up[col >> 1] - 128;
                int e = vp[col >> 1] - 128;
                int r0 = clamp8(yp[col] + ((91881 * e) >> 16));
                int g0 = clamp8(yp[col] - ((22554 * d + 46802 * e) >> 16));
                int b0 = clamp8(yp[col] + ((116130 * d) >> 16));
                int r1 = clamp8(yp[col + 1] + ((91881 * e) >> 16));
                int g1 = clamp8(yp[col + 1] - ((22554 * d + 46802 * e) >> 16));
                int b1 = clamp8(yp[col + 1] + ((116130 * d) >> 16));
                op[0] = (uint8_t)r0; op[1] = (uint8_t)g0;
                op[2] = (uint8_t)b0; op[3] = 0xFF;
                op[4] = (uint8_t)r1; op[5] = (uint8_t)g1;
                op[6] = (uint8_t)b1; op[7] = 0xFF;
                op += 8;
            }
        } else {
            const uint8_t *urow = U + (long)(row >> 1) * stride;
            for (int col = 0; col < w; col += 2) {
                // even col: UV pair sits exactly at urow[col], urow[col+1]
                int d = urow[col] - 128;
                int e = urow[col + 1] - 128;
                int r0 = clamp8(yp[col] + ((91881 * e) >> 16));
                int g0 = clamp8(yp[col] - ((22554 * d + 46802 * e) >> 16));
                int b0 = clamp8(yp[col] + ((116130 * d) >> 16));
                int r1 = clamp8(yp[col + 1] + ((91881 * e) >> 16));
                int g1 = clamp8(yp[col + 1] - ((22554 * d + 46802 * e) >> 16));
                int b1 = clamp8(yp[col + 1] + ((116130 * d) >> 16));
                op[0] = (uint8_t)r0; op[1] = (uint8_t)g0;
                op[2] = (uint8_t)b0; op[3] = 0xFF;
                op[4] = (uint8_t)r1; op[5] = (uint8_t)g1;
                op[6] = (uint8_t)b1; op[7] = 0xFF;
                op += 8;
            }
        }
    }
    return 0;
}

