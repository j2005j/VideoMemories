/* Host-side verification for convert.c:
 *  1. tight-plane conversion (convert_yuv420_argb) vs /tmp/opencode/ref.argb
 *  2. strided one-pass conversion (convert_frame_strided), planar and semi,
 *     with a non-zero offset and 0xAA-filled padding rows to prove nothing
 *     outside the picture is read.
 *
 *   gcc -I$JAVA_HOME/include -I$JAVA_HOME/include/linux \
 *       convert.c test_convert.c -o test_convert && ./test_convert
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

void convert_yuv420_argb(const uint8_t *y, const uint8_t *u, const uint8_t *v,
                         int w, int h, int32_t *out);
void convert_yuv420_half(const uint8_t *y, const uint8_t *u, const uint8_t *v,
                         int w, int h, int32_t *out);
int convert_frame_strided(const uint8_t *base, long cap, long offset,
                          int w, int h, int stride, int slice, int planar,
                          int32_t *out);
int convert_frame_rgba(const uint8_t *base, long cap, long offset,
                       int w, int h, int stride, int slice, int planar,
                       uint8_t *out);
int convert_frame_strided_half(const uint8_t *base, long cap, long offset,
                               int w, int h, int stride, int slice,
                               int planar, int32_t *out);

static const int W = 480, H = 360, STRIDE = 480, SLICE = 368, OFF = 16;
static uint8_t *planes;   /* f0.yuv: y + u + v */
static uint8_t *ref;      /* ref.argb */

static int compare(const int32_t *out, const char *what) {
    int mismatches = 0, first = -1;
    for (int i = 0; i < W * H; i++) {
        if (out[i] != ((const int32_t *)ref)[i]) {
            if (first < 0) first = i;
            mismatches++;
        }
    }
    if (mismatches == 0) {
        printf("%s: OK (%d pixels)\n", what, W * H);
        return 0;
    }
    printf("%s: FAIL %d mismatches, first at x=%d y=%d got=%06x want=%06x\n",
           what, mismatches, first % W, first / W,
           out[first] & 0xFFFFFF, ((const int32_t *)ref)[first] & 0xFFFFFF);
    return 1;
}

/* build a decoder-style buffer: offset prefix, stride/slice rows with the
 * picture at the top, padding filled with 0xAA */
static uint8_t *build_buffer(int planar, long *cap_out) {
    const long ys = (long)STRIDE * SLICE;
    const long cap = OFF + ys + ys / 2;
    uint8_t *base = malloc(cap);
    memset(base, 0xAA, cap);
    const uint8_t *y = planes;
    const uint8_t *u = planes + W * H;
    const uint8_t *v = u + W * H / 4;
    for (int r = 0; r < H; r++)
        memcpy(base + OFF + (long)r * STRIDE, y + (long)r * W, W);
    if (planar) {
        const long uoff = OFF + ys;
        const long voff = uoff + (STRIDE / 2) * (SLICE / 2);
        for (int r = 0; r < H / 2; r++) {
            memcpy(base + uoff + (long)r * (STRIDE / 2), u + (long)r * (W / 2), W / 2);
            memcpy(base + voff + (long)r * (STRIDE / 2), v + (long)r * (W / 2), W / 2);
        }
    } else {
        const long uvoff = OFF + ys;
        for (int r = 0; r < H / 2; r++) {
            uint8_t *row = base + uvoff + (long)r * STRIDE;
            for (int c = 0; c < W / 2; c++) {
                row[2 * c] = u[(long)r * (W / 2) + c];
                row[2 * c + 1] = v[(long)r * (W / 2) + c];
            }
        }
    }
    *cap_out = cap;
    return base;
}

/* ref.argb stores 0xFFRRGGBB as little-endian int32 = bytes [B,G,R,A];
 * the RGBA path must produce [R,G,B,A] instead */
static int compare_rgba(const uint8_t *rgba, const char *what) {
    int mismatches = 0, first = -1;
    for (int i = 0; i < W * H; i++) {
        const uint8_t *r8 = ref + (long)i * 4;
        if (rgba[i * 4] != r8[2] || rgba[i * 4 + 1] != r8[1]
                || rgba[i * 4 + 2] != r8[0] || rgba[i * 4 + 3] != r8[3]) {
            if (first < 0) first = i;
            mismatches++;
        }
    }
    if (mismatches == 0) {
        printf("%s: OK (%d pixels)\n", what, W * H);
        return 0;
    }
    printf("%s: FAIL %d mismatches, first at x=%d y=%d\n",
           what, mismatches, first % W, first / W);
    return 1;
}

/* v27: half-res one-pass output must equal the reference at (2x,2y) */
static int compare_half(const int32_t *out, const char *what) {
    const int w2 = W / 2, h2 = H / 2;
    int mismatches = 0, first = -1;
    for (int y = 0; y < h2; y++) {
        for (int x = 0; x < w2; x++) {
            int i = y * w2 + x;
            if (out[i] != ((const int32_t *)ref)[(2 * y) * W + 2 * x]) {
                if (first < 0) first = i;
                mismatches++;
            }
        }
    }
    if (mismatches == 0) {
        printf("%s: OK (%d pixels == ref(2x,2y))\n", what, w2 * h2);
        return 0;
    }
    printf("%s: FAIL %d mismatches, first x=%d y=%d got=%06x want=%06x\n",
           what, mismatches, first % w2, first / w2,
           out[first] & 0xFFFFFF,
           ((const int32_t *)ref)[(2 * (first / w2)) * W
                                  + 2 * (first % w2)] & 0xFFFFFF);
    return 1;
}

int main(void) {
    const size_t ysz = (size_t)W * H, csz = ysz / 4;

    FILE *f = fopen("/tmp/opencode/f0.yuv", "rb");
    if (!f) { perror("f0.yuv"); return 1; }
    planes = malloc(ysz + 2 * csz);
    if (fread(planes, 1, ysz + 2 * csz, f) != ysz + 2 * csz) return 1;
    fclose(f);

    FILE *r = fopen("/tmp/opencode/ref.argb", "rb");
    if (!r) { perror("ref.argb"); return 1; }
    ref = malloc((size_t)W * H * 4);
    if (fread(ref, 1, (size_t)W * H * 4, r) != (size_t)W * H * 4) return 1;
    fclose(r);

    int fails = 0;

    int32_t *out = malloc((size_t)W * H * 4);
    convert_yuv420_argb(planes, planes + ysz, planes + ysz + csz, W, H, out);
    fails += compare(out, "tight planes");

    for (int planar = 1; planar >= 0; planar--) {
        long cap;
        uint8_t *buf = build_buffer(planar, &cap);
        memset(out, 0, (size_t)W * H * 4);
        int rc = convert_frame_strided(buf, cap, OFF, W, H, STRIDE, SLICE,
                                       planar, out);
        char name[48];
        snprintf(name, sizeof name, "strided offset=%d planar=%d", OFF, planar);
        if (rc != 0) {
            printf("%s: FAIL rc=%d\n", name, rc);
            fails++;
        } else {
            fails += compare(out, name);
        }
        /* same buffer through the v22 RGBA path */
        uint8_t *rgba = malloc((size_t)W * H * 4);
        memset(rgba, 0, (size_t)W * H * 4);
        rc = convert_frame_rgba(buf, cap, OFF, W, H, STRIDE, SLICE,
                                planar, rgba);
        snprintf(name, sizeof name, "rgba offset=%d planar=%d", OFF, planar);
        if (rc != 0) {
            printf("%s: FAIL rc=%d\n", name, rc);
            fails++;
        } else {
            fails += compare_rgba(rgba, name);
        }
        free(rgba);
        /* v27: half-res one-pass from the same padded buffer - must equal
         * the reference at (2x,2y) for every output pixel */
        int32_t *hout = malloc((size_t)(W / 2) * (H / 2) * 4);
        memset(hout, 0, (size_t)(W / 2) * (H / 2) * 4);
        rc = convert_frame_strided_half(buf, cap, OFF, W, H, STRIDE, SLICE,
                                        planar, hout);
        snprintf(name, sizeof name, "half offset=%d planar=%d", OFF, planar);
        if (rc != 0) {
            printf("%s: FAIL rc=%d\n", name, rc);
            fails++;
        } else {
            fails += compare_half(hout, name);
        }
        if (planar && convert_frame_strided_half(buf, cap, OFF, W - 1, H,
                STRIDE, SLICE, 1, hout) == 0) {
            printf("half odd-width reject: FAIL (accepted w=%d)\n", W - 1);
            fails++;
        } else if (planar) {
            printf("half odd-width reject: OK\n");
        }
        free(hout);
        free(buf);
    }

    const int pts[3][2] = { {240,184}, {40,40}, {400,300} };
    for (int i = 0; i < 3; i++) {
        int c = ((const int32_t *)ref)[pts[i][1] * W + pts[i][0]];
        printf("ref (%d,%d)=%06x\n", pts[i][0], pts[i][1], c & 0xFFFFFF);
    }

    /* v25 half-res invariant: H(x,y) must equal full(2x,2y) exactly.
     * y = Y subsampled (even rows/cols), u/v = FULL chroma planes. */
    {
        const int w2 = W / 2, h2 = H / 2;
        uint8_t *y2 = malloc((size_t)w2 * h2);
        for (int r = 0; r < h2; r++)
            for (int c = 0; c < w2; c++)
                y2[r * w2 + c] = planes[(2 * r) * W + 2 * c];
        const uint8_t *uFull = planes + W * H;
        const uint8_t *vFull = uFull + W * H / 4;
        int32_t *out2 = malloc((size_t)w2 * h2 * 4);
        convert_yuv420_half(y2, uFull, vFull, w2, h2, out2);
        int bad = 0, first = -1;
        for (int y = 0; y < h2; y++) {
            for (int x = 0; x < w2; x++) {
                if (out2[y * w2 + x] != ((const int32_t *)ref)[(2 * y) * W + 2 * x]) {
                    if (first < 0) first = y * w2 + x;
                    bad++;
                }
            }
        }
        if (bad == 0) {
            printf("half-res invariant: OK (%d pixels == full(2x,2y))\n", w2 * h2);
        } else {
            printf("half-res invariant: FAIL %d mismatches, first x=%d y=%d\n",
                   bad, first % w2, first / w2);
            fails++;
        }
        free(y2);
        free(out2);
    }

    printf(fails ? "FAIL: %d test(s) failed\n" : "ALL TESTS PASSED\n", fails);
    return fails ? 2 : 0;
}
