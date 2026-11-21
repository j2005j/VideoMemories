package dev.camsysinfo.videomemories;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * v28 root screen: pick a video, it plays looping.
 *
 * What shows up: directories and *.264 / *.h264 files only - raw Annex-B
 * elementary streams, because that is all this platform can produce here
 * (MediaPlayer is dead, MediaExtractor untested, and the proven player
 * parses Annex-B itself). FINDINGS.md has the ffmpeg recipe that turns
 * any video into one of these (480-wide, 24 fps, baseline, silent).
 * Names must be 8.3 - the card mount refuses long file names.
 *
 * Keys (the camera wheel is a d-pad): up/down = move, centre = open,
 * MENU = exit the app (the player's MENU returns here first; from here
 * MENU quits).
 */
public class FileBrowserActivity extends Activity {

    private static final int T_NONE = -1, T_UP = 0, T_DIR = 1, T_FILE = 2;

    private ListView list;
    private TextView header;
    private File cwd;
    private final List<File> targets = new ArrayList<File>();
    private final List<Integer> kinds = new ArrayList<Integer>();
    private String lastName; // remember the row to reselect after playback

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xFF1E1E1E);

        TextView hint = new TextView(this);
        hint.setText("Wheel: move  centre: play (loops)  MENU: exit."
                + "  Videos: *.264 / *.h264, 8.3 names - see FINDINGS for ffmpeg.");
        hint.setTextColor(0xFF9E9E9E);
        hint.setPadding(12, 8, 12, 8);
        ll.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        header = new TextView(this);
        header.setTextColor(Color.WHITE);
        header.setBackgroundColor(0xFFB71C1C);
        header.setPadding(12, 10, 12, 10);
        ll.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        ll.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(ll);

        // one "launch" line per app open
        int ver = -1;
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        MemLog.sdLog("launch up=" + SystemClock.elapsedRealtime()
                + " v" + ver);

        cwd = Environment.getExternalStorageDirectory();
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(position);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void open(int position) {
        int kind = kinds.get(position);
        File f = targets.get(position);
        if ((kind == T_UP || kind == T_DIR) && f != null) {
            cwd = f;
            lastName = null;
            refresh();
        } else if (kind == T_FILE && f != null) {
            lastName = f.getName();
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_PATH, f.getAbsolutePath());
            i.putExtra(PlayerActivity.EXTRA_LOOP, true);
            startActivity(i);
        }
    }

    private void refresh() {
        header.setText(cwd.getAbsolutePath());
        final List<String> names = new ArrayList<String>();
        targets.clear();
        kinds.clear();

        File[] kids = cwd.listFiles();
        List<File> dirs = new ArrayList<File>();
        List<File> vids = new ArrayList<File>();
        if (kids != null) {
            for (File k : kids) {
                String n = k.getName();
                if (n.startsWith(".")) continue;
                if (k.isDirectory()) {
                    dirs.add(k);
                } else {
                    String l = n.toLowerCase(Locale.US);
                    if (l.endsWith(".264") || l.endsWith(".h264")) vids.add(k);
                }
            }
        }
        Comparator<File> byName = new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        };
        Collections.sort(dirs, byName);
        Collections.sort(vids, byName);

        if (cwd.getParentFile() != null) {
            names.add("« ..");
            targets.add(cwd.getParentFile());
            kinds.add(T_UP);
        }
        for (File d : dirs) {
            names.add(d.getName() + "/");
            targets.add(d);
            kinds.add(T_DIR);
        }
        for (File v : vids) {
            names.add(v.getName() + "   " + humanSize(v.length()));
            targets.add(v);
            kinds.add(T_FILE);
        }
        if (vids.isEmpty()) {
            names.add("(no videos here)");
            targets.add(null);
            kinds.add(T_NONE);
        }

        list.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, names));
        if (lastName != null) {
            for (int i = 0; i < kinds.size(); i++) {
                File t = targets.get(i);
                if (kinds.get(i) == T_FILE && t != null && t.getName().equals(lastName)) {
                    final int sel = i;
                    list.post(new Runnable() {
                        @Override
                        public void run() {
                            list.setSelection(sel);
                        }
                    });
                    break;
                }
            }
        }
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1048576L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%d KB", bytes / 1024L);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // root screen: MENU leaves the app (the player's MENU comes
            // back here instead - one press each, per the UX spec)
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
