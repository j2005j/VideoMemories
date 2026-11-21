package dev.camsysinfo.videomemories;

import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Append one line to VMEM/LOG.TXT (8.3, rotated past 64 KB). The card's
 *  FUSE layer takes short names only, hence VMEM. The log carries the
 *  perf line, the px@ color self-checks and the "screen WxH" line - the
 *  fields a bug report needs. */
public final class MemLog {

    private static final String SD_DIR = "VMEM";
    private static final String SD_LOG = "LOG.TXT";
    private static final long LOG_ROTATE = 65536;

    public static void sdLog(String line) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), SD_DIR);
            dir.mkdirs();
            File f = new File(dir, SD_LOG);
            if (f.length() > LOG_ROTATE) {
                File old = new File(dir, "LOG.OLD");
                old.delete();
                f.renameTo(old);
            }
            FileWriter w = new FileWriter(f, true);
            String stamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            w.write(stamp + " " + line + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }

    private MemLog() {
    }
}
