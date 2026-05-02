/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.ghost4j.worker.protocol.ControlFrame;
import org.ghost4j.worker.protocol.Frame;
import org.ghost4j.worker.protocol.FrameCodec;

/**
 * Wraps a single child worker JVM and its connected Unix Domain Socket channel.
 *
 * <p>Instances are created exclusively via {@link #spawn(Class, String)}. The startup sequence is:
 *
 * <ol>
 *   <li>Parent generates a UUID and derives the UDS socket path.
 *   <li>Parent starts the child JVM via {@code ProcessBuilder}, passing the socket path via the
 *       {@code ghost4j.worker.socket} system property.
 *   <li>Parent reads exactly one byte from the child's stdout (15 s timeout). The child writes
 *       {@code 0x01} after it has successfully bound its {@code ServerSocketChannel} to the socket
 *       path.
 *   <li>Parent connects as a client to that socket path.
 *   <li>The child's {@code accept()} returns, completing the handshake.
 * </ol>
 */
public class WorkerProcess {

    /** Socket path length cap (POSIX {@code sun_path} limit is 108 bytes on Linux). */
    private static final int SOCKET_PATH_MAX = 100;

    /** Milliseconds to wait for the worker ready-byte before timing out. */
    private static final long STARTUP_TIMEOUT_MS = 15_000L;

    /** Milliseconds to wait for orderly shutdown before force-killing. */
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000L;

    private final UUID id;
    private final Process process;
    private final Path socketPath;
    private SocketChannel channel;
    private final AtomicBoolean crashed = new AtomicBoolean(false);
    private final AtomicLong requestsHandled = new AtomicLong(0L);
    private final StderrDrainer stderrDrainer;

    private WorkerProcess(
            UUID id,
            Process process,
            Path socketPath,
            SocketChannel channel,
            StderrDrainer stderrDrainer) {
        this.id = id;
        this.process = process;
        this.socketPath = socketPath;
        this.channel = channel;
        this.stderrDrainer = stderrDrainer;
    }

    /**
     * Package-private constructor for unit tests. Creates a {@code WorkerProcess} backed by the
     * supplied process stub with no UDS socket channel. The {@link #send} and {@link #receive}
     * methods will throw {@link IOException} when called on instances created this way.
     *
     * <p>This constructor exists solely to enable {@link WorkerPool} unit tests that supply
     * pre-built {@code WorkerProcess} instances without forking real child JVMs.
     *
     * @param id unique worker ID
     * @param process the fake {@link Process} to wrap
     */
    WorkerProcess(UUID id, Process process) {
        this.id = id;
        this.process = process;
        this.socketPath = Path.of("/tmp", "ghost4j-test-" + id + ".sock");
        this.channel = null;
        this.stderrDrainer = new StderrDrainer(InputStream.nullInputStream(), "test-" + id);
    }

    /**
     * Spawns a new child worker JVM for the given component class.
     *
     * @param componentClass the component class to run inside the worker (e.g. {@code
     *     PDFConverter.class})
     * @param xmx the {@code -Xmx} value for the child JVM (e.g. {@code "256m"})
     * @return a fully connected {@code WorkerProcess}
     * @throws WorkerStartupException if the worker does not signal readiness within the timeout
     * @throws IOException if process creation or socket connection fails
     */
    public static WorkerProcess spawn(Class<?> componentClass, String xmx)
            throws WorkerStartupException, IOException {

        UUID id = UUID.randomUUID();

        // Build socket path — keep under POSIX sun_path limit.
        String sockName = "ghost4j-" + id + ".sock";
        String tmpDirStr = System.getProperty("java.io.tmpdir");
        Path socketPath =
                (tmpDirStr != null
                                && (tmpDirStr.length() + 1 + sockName.length()) <= SOCKET_PATH_MAX)
                        ? Path.of(tmpDirStr, sockName)
                        : Path.of("/tmp", sockName);

        // Build the child JVM command.
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Xmx" + xmx);
        command.add("-Dghost4j.worker.socket=" + socketPath);
        command.add("-Dghost4j.worker.class=" + componentClass.getName());
        command.add("org.ghost4j.worker.WorkerMain");

        ProcessBuilder pb = new ProcessBuilder(command);

        // Propagate LD_LIBRARY_PATH so libgs.so is findable in the child JVM.
        String ldPath = System.getenv("LD_LIBRARY_PATH");
        if (ldPath != null) {
            pb.environment().putIfAbsent("LD_LIBRARY_PATH", ldPath);
        }

        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);

        Process process = pb.start();

        // Start draining stderr immediately so it never blocks the child.
        StderrDrainer stderrDrainer = new StderrDrainer(process.getErrorStream(), id.toString());

        // Wait for the child to signal that its ServerSocketChannel is bound.
        int readyByte = readReadyByteWithTimeout(process, id);

        if (readyByte != 0x01) {
            process.destroyForcibly();
            throw new WorkerStartupException(
                    "Worker "
                            + id
                            + " sent unexpected ready byte: 0x"
                            + Integer.toHexString(readyByte)
                            + " (expected 0x01)");
        }

        // Child has bound its ServerSocketChannel — connect as the client.
        SocketChannel channel;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socketPath));
        } catch (IOException e) {
            process.destroyForcibly();
            throw new IOException("Failed to connect to worker " + id + " at " + socketPath, e);
        }

        return new WorkerProcess(id, process, socketPath, channel, stderrDrainer);
    }

    /**
     * Reads exactly one byte from the child's stdout, with a timeout. If the timeout fires,
     * destroys the process and throws {@link WorkerStartupException}.
     */
    private static int readReadyByteWithTimeout(Process process, UUID id)
            throws WorkerStartupException, IOException {

        // Use a timeout thread that force-kills the child if it doesn't respond.
        final Thread[] timeoutHolder = new Thread[1];
        final Thread callerThread = Thread.currentThread();
        final AtomicBoolean timedOut = new AtomicBoolean(false);

        Thread timeoutThread =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(STARTUP_TIMEOUT_MS);
                                timedOut.set(true);
                                process.destroyForcibly();
                                callerThread.interrupt();
                            } catch (InterruptedException e) {
                                // Timeout cancelled — normal path.
                            }
                        },
                        "ghost4j-startup-timeout-" + id);
        timeoutThread.setDaemon(true);
        timeoutHolder[0] = timeoutThread;
        timeoutThread.start();

        int readyByte;
        try {
            InputStream stdout = process.getInputStream();
            readyByte = stdout.read();
        } catch (IOException e) {
            timeoutThread.interrupt();
            if (timedOut.get()) {
                throw new WorkerStartupException(
                        "Worker "
                                + id
                                + " did not signal readiness within "
                                + STARTUP_TIMEOUT_MS
                                + " ms");
            }
            throw e;
        } finally {
            timeoutThread.interrupt();
        }

        if (timedOut.get()) {
            throw new WorkerStartupException(
                    "Worker "
                            + id
                            + " did not signal readiness within "
                            + STARTUP_TIMEOUT_MS
                            + " ms");
        }

        if (readyByte == -1) {
            throw new WorkerStartupException(
                    "Worker "
                            + id
                            + " stdout closed before sending ready byte (process exited early)");
        }

        return readyByte;
    }

    // -------------------------------------------------------------------------
    // Frame I/O
    // -------------------------------------------------------------------------

    /**
     * Sends a frame to the worker over the UDS channel.
     *
     * @param frame the frame to send
     * @throws IOException on I/O failure
     */
    public void send(Frame frame) throws IOException {
        if (channel == null) {
            throw new IOException(
                    "send() is not available for test-only WorkerProcess instances (no socket channel)");
        }
        OutputStream out = Channels.newOutputStream(channel);
        FrameCodec.writeFrame(out, frame);
    }

    /**
     * Receives the next frame from the worker.
     *
     * @return the received frame
     * @throws IOException on I/O failure or protocol violation
     */
    public Frame receive() throws IOException {
        if (channel == null) {
            throw new IOException(
                    "receive() is not available for test-only WorkerProcess instances (no socket channel)");
        }
        InputStream in = Channels.newInputStream(channel);
        return FrameCodec.readFrame(in);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the underlying OS process is still running.
     *
     * @return {@code true} if alive
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Attempts an orderly shutdown: sends a {@code SHUTDOWN} control frame, closes the channel, and
     * waits up to 5 seconds for the process to exit before force-killing it. Also deletes the
     * socket file.
     */
    public void shutdown() {
        try {
            send(new ControlFrame(ControlFrame.SHUTDOWN));
        } catch (IOException ignored) {
            // Worker may already be dead.
        }

        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (!process.waitFor(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }

        stderrDrainer.halt();

        // Clean up the socket file.
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the unique ID of this worker instance. */
    public UUID id() {
        return id;
    }

    /** Returns the underlying OS process. */
    public Process process() {
        return process;
    }

    /** Returns the UDS socket path used by this worker. */
    public Path socketPath() {
        return socketPath;
    }

    /**
     * CAS flag for exactly-once crash handling. Set to {@code true} by whichever thread (the {@link
     * WorkerWatcher} or the dispatch path) first detects the crash.
     */
    public AtomicBoolean crashed() {
        return crashed;
    }

    /** Total number of requests successfully handled by this worker. */
    public AtomicLong requestsHandled() {
        return requestsHandled;
    }

    /** Returns the stderr drainer for this worker. */
    public StderrDrainer stderrDrainer() {
        return stderrDrainer;
    }

    // -------------------------------------------------------------------------
    // POSIX permissions helper (best-effort; no-op on non-POSIX systems)
    // -------------------------------------------------------------------------

    /**
     * Sets {@code rw-------} permissions on the socket file. Called by {@link WorkerMain} after
     * binding the server socket. Silently ignored on non-POSIX file systems (e.g. Windows).
     *
     * @param path the socket file path
     */
    public static void setSocketPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system — skip.
        }
    }
}
