/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Daemon thread that continuously drains a worker process's stderr into a bounded ring buffer.
 *
 * <p>The last up to 100 lines are retained and can be retrieved via {@link #getLastLines()} for
 * crash diagnostics. The thread is started automatically in the constructor.
 */
public class StderrDrainer extends Thread {

    /** Maximum number of stderr lines retained in the ring buffer. */
    private static final int MAX_LINES = 100;

    private final InputStream stderr;
    private final ArrayDeque<String> ringBuffer = new ArrayDeque<>(MAX_LINES);
    private volatile boolean stopped = false;

    /**
     * Creates and starts a {@code StderrDrainer} for the given stderr stream.
     *
     * @param stderr the stderr {@code InputStream} of the child worker process
     * @param workerId human-readable identifier used to name the thread
     */
    public StderrDrainer(InputStream stderr, String workerId) {
        this.stderr = stderr;
        setDaemon(true);
        setName("ghost4j-stderr-" + workerId);
        start();
    }

    @Override
    public void run() {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
            String line;
            while (!stopped && (line = reader.readLine()) != null) {
                synchronized (ringBuffer) {
                    if (ringBuffer.size() >= MAX_LINES) {
                        ringBuffer.pollFirst();
                    }
                    ringBuffer.addLast(line);
                }
            }
        } catch (Exception e) {
            // Stream closed or worker exited — normal termination path, nothing to log.
        }
    }

    /**
     * Returns a snapshot of the retained stderr lines (most-recent up to 100 lines).
     *
     * @return immutable list of retained lines, oldest first
     */
    public List<String> getLastLines() {
        synchronized (ringBuffer) {
            return new ArrayList<>(ringBuffer);
        }
    }

    /**
     * Signals the drainer to stop reading. The thread will exit on the next read attempt or when
     * the underlying stream is closed.
     */
    public void halt() {
        stopped = true;
        interrupt();
    }
}
