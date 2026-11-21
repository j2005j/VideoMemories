package dev.camsysinfo.videomemories;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Annex-B NAL index over a FILE, streaming - replaces PlayerActivity's
 * in-memory List&lt;byte[]&gt;, which would OOM on any video bigger than the
 * heap (the demo is 60 KB; real videos are tens of MB).
 *
 * scan() walks the file once, byte at a time, storing only (offset, length)
 * per NAL: O(number of NALs) memory. NalReader then streams the NALs back
 * sequentially in exactly the order feed() needs (NALs are consumed in file
 * order, so no seeking - just skip the start codes between payloads).
 *
 * Boundary rules are EXACTLY the reference splitter's - hosttest proves
 * byte-for-byte equality against it on any Annex-B file (the camera-
 * tested run: 151 NALs, 144 VCL on the development stream). A start
 * code is 00 00 01 or 00 00 00 01 found
 * left-to-right (3-byte wins at its position, greedy), a NAL spans from
 * just after its start code to the first byte of the next start code
 * (trailing zeros before the next code stay with the previous NAL), and
 * empty NALs are dropped. hosttest/NalIndexTest proves byte-for-byte
 * equality against a reference copy of that algorithm on
 * assets/demo.264 (151 NALs, 144 VCL).
 */
public final class NalIndex {

    public long[] off;  // payload offset of each NAL inside the file
    public int[] len;   // payload length (>= 1; empty NALs are dropped)
    public int count;
    public int vcl;     // NALs with type 1..5 (the frames)

    private NalIndex() {
        off = new long[256];
        len = new int[256];
    }

    private void add(long o, int l, int firstByte) {
        if (count == off.length) {
            off = Arrays.copyOf(off, count * 2);
            len = Arrays.copyOf(len, count * 2);
        }
        off[count] = o;
        len[count] = l;
        count++;
        int t = firstByte & 0x1F;
        if (t >= 1 && t <= 5) vcl++;
    }

    /** Single sequential pass; memory = one entry per NAL. */
    public static NalIndex scan(File f) throws IOException {
        NalIndex ix = new NalIndex();
        InputStream in = new BufferedInputStream(new FileInputStream(f), 64 * 1024);
        try {
            long pos = 0;        // absolute offset of the byte about to be read
            long payload = -1;   // payload start of the current NAL, -1 = none
            int first = 0;       // first payload byte of the current NAL
            boolean needFirst = false;
            int zeros = 0;       // consecutive 0x00 immediately before pos
            int b;
            while ((b = in.read()) != -1) {
                if (payload == pos && needFirst) {
                    first = b;
                    needFirst = false;
                }
                if (b == 1 && zeros >= 2) {
                    // start code completes here: 3-byte when exactly two
                    // zeros precede the 01, else 4-byte at pos-3 (greedy
                    // left-to-right = the reference splitter's scan,
                    // verbatim)
                    long from = payload;
                    long to = (zeros == 2) ? pos - 2 : pos - 3;
                    if (from >= 0 && to > from) {
                        ix.add(from, (int) (to - from), first);
                    }
                    payload = pos + 1;
                    needFirst = true;
                    zeros = 0;
                } else if (b == 0) {
                    zeros++;
                } else {
                    zeros = 0;
                }
                pos++;
            }
            if (payload >= 0 && pos > payload) {
                ix.add(payload, (int) (pos - payload), first);
            }
        } finally {
            in.close();
        }
        return ix;
    }
}

/** Sequential NAL reader over the indexed file (package: PlayerActivity).
 *  One instance per playback pass; per loop pass PlayerActivity opens a
 *  fresh one, so a loop re-reads the stream from the start. */
final class NalReader {

    private final InputStream in;
    private final NalIndex ix;
    private int pos;
    private long consumed; // absolute offset the stream will deliver next

    NalReader(File f, NalIndex ix) throws IOException {
        this.in = new BufferedInputStream(new FileInputStream(f), 64 * 1024);
        this.ix = ix;
    }

    /** next NAL payload in stream order; null at the end of the index */
    byte[] next() throws IOException {
        if (pos >= ix.count) return null;
        long target = ix.off[pos];
        long need = target - consumed;
        while (need > 0) {
            long s = in.skip(need);
            if (s > 0) {
                need -= s;
                consumed += s;
                continue;
            }
            if (in.read() < 0) return null; // file shorter than the index
            need--;
            consumed++;
        }
        byte[] nal = new byte[ix.len[pos]];
        int got = 0;
        while (got < nal.length) {
            int n = in.read(nal, got, nal.length - got);
            if (n < 0) throw new IOException("EOF inside NAL " + pos);
            got += n;
        }
        consumed += nal.length;
        pos++;
        return nal;
    }

    void close() {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
