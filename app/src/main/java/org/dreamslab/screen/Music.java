package org.dreamslab.screen;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plays every song in the music folder, shuffled, one after another. */
final class Music {
    private final Context ctx;
    private MediaPlayer player;
    private final List<File> queue = new ArrayList<>();
    private int index = -1;
    private float volume = 0.5f;
    private boolean playing = false;

    Music(Context c) { ctx = c.getApplicationContext(); }

    private void refill() {
        queue.clear();
        File[] fs = Library.dir(ctx, "music").listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && !f.getName().startsWith(".")) queue.add(f);
        Collections.shuffle(queue);
        index = -1;
    }

    synchronized void play() {
        if (player != null && !playing) {
            player.start();
            playing = true;
            return;
        }
        if (player == null) next();
    }

    synchronized void pause() {
        if (player != null && playing) {
            player.pause();
        }
        playing = false;
    }

    synchronized void stop() {
        release();
        playing = false;
    }

    synchronized void next() {
        release();
        if (index + 1 >= queue.size()) refill();
        if (queue.isEmpty()) { playing = false; return; }
        index++;
        File f = queue.get(index);
        if (!f.exists()) { next(); return; }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setVolume(volume, volume);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { next(); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) { next(); return true; }
            });
            mp.prepare();
            mp.start();
            player = mp;
            playing = true;
        } catch (Exception e) {
            release();
            // Skip a song that will not play, but stop if none of them do.
            if (index < queue.size() - 1) next(); else playing = false;
        }
    }

    synchronized void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        if (player != null) player.setVolume(volume, volume);
    }

    synchronized String statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("playing", playing);
            o.put("volume", volume);
            String title = "";
            if (player != null && index >= 0 && index < queue.size()) {
                title = queue.get(index).getName().replaceFirst("\\.[^.]+$", "");
            }
            o.put("title", title);
            File[] fs = Library.dir(ctx, "music").listFiles();
            int count = 0;
            if (fs != null) for (File f : fs) if (f.isFile() && !f.getName().startsWith(".")) count++;
            o.put("songs", count);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void release() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }
}
