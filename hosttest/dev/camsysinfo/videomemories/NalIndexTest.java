package dev.camsysinfo.videomemories;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host test: NalIndex.scan + NalReader over a file must match the
 * reference Annex-B splitter byte-for-byte.
 *
 *   javac -d /tmp/vm app/src/main/java/dev/camsysinfo/videomemories/NalIndex.java \
 *         hosttest/dev/camsysinfo/videomemories/NalIndexTest.java
 *   java -cp /tmp/vm dev.camsysinfo.videomemories.NalIndexTest any.264
 */
public class NalIndexTest {

    /** verbatim copy of the camera-proven reference splitter */
    static List<byte[]> splitAnnexB(byte[] es) {
        List<Integer> starts = new ArrayList<Integer>();
        int i = 0;
        while (i + 3 <= es.length) {
            if (es[i] == 0 && es[i + 1] == 0
                    && (es[i + 2] == 1
                    || (es[i + 2] == 0 && i + 4 <= es.length && es[i + 3] == 1))) {
                starts.add(i);
                i += es[i + 2] == 1 ? 3 : 4;
            } else {
                i++;
            }
        }
        List<byte[]> out = new ArrayList<byte[]>();
        for (int s = 0; s < starts.size(); s++) {
            int from = starts.get(s);
            from += (es[from + 2] == 1) ? 3 : 4;
            int to = (s + 1 < starts.size()) ? starts.get(s + 1) : es.length;
            if (to > from) {
                byte[] nal = new byte[to - from];
                System.arraycopy(es, from, nal, 0, nal.length);
                out.add(nal);
            }
        }
        return out;
    }

    static byte[] readAll(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) break;
            got += n;
        }
        in.close();
        if (got != b.length) throw new IllegalStateException("short read");
        return b;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: NalIndexTest <file.264>");
            System.exit(1);
        }
        String path = args[0];
        File f = new File(path);
        byte[] es = readAll(f);

        List<byte[]> ref = splitAnnexB(es);
        int refVcl = 0;
        for (byte[] n : ref) {
            int t = n[0] & 0x1F;
            if (t >= 1 && t <= 5) refVcl++;
        }

        NalIndex ix = NalIndex.scan(f);
        int fails = 0;

        if (ref.size() != ix.count) {
            System.out.println("count: FAIL ref=" + ref.size() + " index=" + ix.count);
            fails++;
        } else {
            System.out.println("count: OK (" + ix.count + " NALs)");
        }
        if (refVcl != ix.vcl) {
            System.out.println("vcl: FAIL ref=" + refVcl + " index=" + ix.vcl);
            fails++;
        } else {
            System.out.println("vcl: OK (" + ix.vcl + " frames)");
        }

        NalReader rd = new NalReader(f, ix);
        int mismatch = -1;
        for (int i = 0; i < ref.size(); i++) {
            byte[] n = rd.next();
            if (n == null || !Arrays.equals(n, ref.get(i))) {
                mismatch = i;
                break;
            }
        }
        if (mismatch >= 0) {
            byte[] n = mismatch < ref.size() ? rd.next() : null;
            System.out.println("bytes: FAIL first mismatch at NAL " + mismatch
                    + " refLen=" + (mismatch < ref.size() ? ref.get(mismatch).length : -1)
                    + " gotLen=" + (n != null ? n.length : -1));
            fails++;
        } else {
            System.out.println("bytes: OK (every NAL byte-identical to splitAnnexB)");
        }
        if (rd.next() != null) {
            System.out.println("reader: FAIL extra NAL after the end");
            fails++;
        } else {
            System.out.println("reader: OK (exact end of stream)");
        }

        // a second full read pass must produce the same NALs (loop reopen)
        NalReader rd2 = new NalReader(f, ix);
        boolean again = true;
        for (int i = 0; i < ref.size(); i++) {
            byte[] n = rd2.next();
            if (n == null || !Arrays.equals(n, ref.get(i))) {
                again = false;
                break;
            }
        }
        System.out.println(again ? "reopen: OK (loop pass reads identical NALs)"
                : "reopen: FAIL");
        if (!again) fails++;

        System.out.println(fails == 0 ? "NalIndexTest PASSED" : "NalIndexTest FAILED (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
