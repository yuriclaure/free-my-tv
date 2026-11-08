package io.freemytv;

import android.media.MediaDataSource;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

/**
 * A screensaver video played while it arrives. Apple's and Amazon's files keep their index (moov) at the end, so a
 * player streaming one asks for the end, then the start, over the network before its first frame. Here the end
 * (`.tail`) and the first seconds (`.part`) of the next videos are on the TV already (`fetchHead`), so a video opens
 * from the disk at once, and the rest streams into a window in memory (RING) a little ahead of the player: nothing more
 * is written to the TV's storage, which on a 2018 BRAVIA has ~1.5 GB free and wears with every write.
 */
final class AerialSource extends MediaDataSource {
    /** The end of the file kept apart: the index (moov) of these videos is 13 to 260 KB. */
    static final int TAIL = 1 << 20;
    /** The window of what comes after the start, in memory: ~20 s of a 1080p video, ~13 s of a 4K one. */
    private static final int RING = 24 << 20;

    private final String url;
    private final long size, tailStart, head;
    private final RandomAccessFile tail, disk;
    private volatile boolean closed;

    // The window: bytes [start, end) of the file are in ring (at their position modulo RING). Made, and the stream
    // started, only once the player nears the end of the start on the TV: a video prepared ahead and then skipped
    // costs neither the memory nor the network.
    private byte[] ring;
    private long start, end, readTo, restartAt = -1;
    private boolean streaming;

    private static final HashMap<String, Object> LOCKS = new HashMap<>();

    /** One writer at a time for a video's `.part` or `.tail`. */
    static Object lockFor(File part) {
        synchronized (LOCKS) {
            Object l = LOCKS.get(part.getPath());
            if (l == null) LOCKS.put(part.getPath(), l = new Object());
            return l;
        }
    }

    /** Opens `url` (cached as `out`): its start and end from the TV, the rest streamed. */
    AerialSource(String url, File out) throws IOException {
        this.url = url;
        File part = new File(out.getPath() + ".part");
        File tailFile = new File(out.getPath() + ".tail");
        if (!tailFile.exists()) fetchTail(url, tailFile);
        tail = new RandomAccessFile(tailFile, "r");
        size = tail.readLong();
        tailStart = Math.max(0, size - (tail.length() - 8));
        head = Math.min(part.exists() ? part.length() : 0, tailStart);
        disk = head > 0 ? new RandomAccessFile(part, "r") : null;
        start = end = readTo = head;
    }

    /** The stream starts (once) when the player reads within LEAD of where the start on the TV ends. */
    private static final long LEAD = 8L << 20;

    private void streamSoon(long position) {
        if (streaming || head >= tailStart || position < head - LEAD) return;
        streaming = true;
        ring = new byte[RING];
        new Thread(this::fetch, "aerial-stream").start();
    }

    /** The end of `url` into `file`: its total size (8 bytes), then its last TAIL bytes. */
    static void fetchTail(String url, File file) throws IOException {
        synchronized (lockFor(file)) {   // the video prepared ahead and the start fetched ahead can both want it
            if (!file.exists()) fetchTailNow(url, file);
        }
    }

    private static void fetchTailNow(String url, File file) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Range", "bytes=-" + TAIL);
        try {
            if (c.getResponseCode() != 206) throw new IOException("HTTP " + c.getResponseCode() + " for the end");
            String range = c.getHeaderField("Content-Range");   // bytes a-b/size
            long size = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
            File tmp = new File(file.getPath() + ".tmp");
            try (InputStream in = c.getInputStream(); java.io.DataOutputStream o = new java.io.DataOutputStream(new FileOutputStream(tmp))) {
                o.writeLong(size);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            if (!tmp.renameTo(file)) throw new IOException("couldn't save the end");
        } finally {
            c.disconnect();
        }
    }

    /** The first `bytes` of `url` (and its end) onto the TV, ahead of its turn: it then opens from the disk. */
    static void fetchHead(String url, File out, long bytes, int session, java.util.function.IntSupplier current) throws IOException {
        File tailFile = new File(out.getPath() + ".tail"), part = new File(out.getPath() + ".part");
        if (!tailFile.exists()) fetchTail(url, tailFile);
        long size;
        try (java.io.DataInputStream t = new java.io.DataInputStream(new java.io.FileInputStream(tailFile))) {
            size = t.readLong();
        }
        bytes = Math.min(bytes, size);   // a short video: all of it
        synchronized (lockFor(part)) {
            long have = part.exists() ? part.length() : 0;
            if (have >= bytes) return;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Range", "bytes=" + have + "-" + (bytes - 1));
            try {
                int code = c.getResponseCode();
                if (code != 206) throw new IOException("HTTP " + code + " for the start");
                try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(part, true)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        o.write(buf, 0, n);
                        if (current.getAsInt() != session) return;
                    }
                }
            } finally {
                c.disconnect();
            }
        }
    }

    /** Streams what comes after the start into the window, staying at most RING ahead of the player. */
    private void fetch() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        byte[] buf = new byte[64 * 1024];
        long from = head;
        while (!closed) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("Range", "bytes=" + from + "-" + (tailStart - 1));
                if (c.getResponseCode() != 206) throw new IOException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while (!closed && (n = in.read(buf)) > 0) {
                        synchronized (this) {
                            // Room: what the player has read (less 1 MB, for a step back) can go.
                            while (!closed && restartAt < 0 && end + n - start > RING) {
                                long keepFrom = Math.max(start, readTo - (1 << 20));
                                if (keepFrom > start) start = Math.min(keepFrom, end);
                                if (end + n - start > RING) wait(200);
                            }
                            if (closed || restartAt >= 0) break;   // the player jumped: start again from there
                            int at = (int) (end % RING), first = Math.min(n, RING - at);
                            System.arraycopy(buf, 0, ring, at, first);
                            if (first < n) System.arraycopy(buf, first, ring, 0, n - first);
                            end += n;
                            notifyAll();
                        }
                    }
                }
                synchronized (this) {
                    if (restartAt < 0) return;   // all of it came (or it closed)
                    from = restartAt;
                    start = end = readTo = from;
                    restartAt = -1;
                    notifyAll();
                }
            } catch (Exception e) {
                if (closed) return;
                AppLog.w("aerial stream: " + e.getMessage());
                SystemClock.sleep(1000);
                synchronized (this) {
                    from = restartAt >= 0 ? restartAt : end;   // on from where it stopped
                    if (restartAt >= 0) {
                        start = end = readTo = from;
                        restartAt = -1;
                    }
                }
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    @Override public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed) throw new IOException("closed");
        if (position >= this.size) return -1;
        streamSoon(position);
        if (position >= tailStart) {
            int n = (int) Math.min(size, this.size - position);
            tail.seek(8 + position - tailStart);
            tail.readFully(buffer, offset, n);
            return n;
        }
        if (position < head) {
            int n = (int) Math.min(size, head - position);
            disk.seek(position);
            return disk.read(buffer, offset, n);
        }
        // A jump outside the window (back before it, or far past it): the stream starts again from there.
        if (position < start || position > end + (4 << 20)) {
            restartAt = position;
            notifyAll();
        }
        long until = SystemClock.elapsedRealtime() + 20_000;
        while (restartAt >= 0 || end <= position || position < start) {
            if (closed || SystemClock.elapsedRealtime() > until) throw new IOException("aerial: no data at " + position);
            try {
                wait(100);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
        int n = (int) Math.min(size, Math.min(end, tailStart) - position);
        int at = (int) (position % RING), first = Math.min(n, RING - at);
        System.arraycopy(ring, at, buffer, offset, first);
        if (first < n) System.arraycopy(ring, 0, buffer, offset + first, n - first);
        readTo = Math.max(readTo, position + n);
        notifyAll();
        return n;
    }

    @Override public long getSize() {
        return size;
    }

    @Override public void close() {
        closed = true;   // first: a read waiting for data gives up, and the stream stops
        synchronized (this) {
            notifyAll();
            try {
                if (disk != null) disk.close();
                tail.close();
            } catch (IOException ignored) {
            }
        }
    }
}
