/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.ghost4j.display.ImageWriterDisplayCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GhostscriptLibrary tests.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public class GhostscriptTest {

    private final String testResourcesPath = "target/test-classes";

    @BeforeEach
    protected void setUp() throws Exception {}

    @AfterEach
    protected void tearDown() throws Exception {
        // delete loaded Ghostscript instance after each test
        Ghostscript.deleteInstance();
    }

    /** Test of getRevision method, of class Ghostscript. */
    @Test
    public void testGetRevision() {

        GhostscriptRevision revision = Ghostscript.getRevision();

        assertNotNull(revision.getProduct());
        assertNotNull(revision.getCopyright());
        assertNotNull(revision.getRevisionDate());
        assertNotNull(revision.getNumber());
    }

    /** Test of initialize method, of class Ghostscript. */
    @Test
    public void testInitialize() {

        Ghostscript gs = Ghostscript.getInstance();

        try {
            gs.initialize(null);
        } catch (GhostscriptException e) {
            fail(e.getMessage());
        }
    }

    /** Test of exit method, of class Ghostscript. */
    @Test
    public void testExit() {

        Ghostscript gs = Ghostscript.getInstance();

        // initialize
        try {
            gs.initialize(null);
        } catch (GhostscriptException e) {
            fail(e.getMessage());
        }

        // exit
        try {
            gs.exit();
        } catch (GhostscriptException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void testRunString() {

        Ghostscript gs = Ghostscript.getInstance();

        // initialize
        try {
            gs.initialize(null);
        } catch (GhostscriptException e) {
            fail(e.getMessage());
        }

        // run string
        try {
            gs.runString("devicenames ==");
        } catch (GhostscriptException e) {
            fail(e.getMessage());
        }

        // exit
        try {
            gs.exit();
        } catch (GhostscriptException e) {
            fail(e.getMessage());
        }
    }

    /** Test of runFile method, of class Ghostscript. */
    @Test
    public void testRunFile() {

        Ghostscript gs = Ghostscript.getInstance();

        final ByteArrayOutputStream errOut = new ByteArrayOutputStream();

        // initialize
        try {
            String[] args = new String[5];
            args[0] = "-dQUIET";
            args[1] = "-dNOPAUSE";
            args[2] = "-dBATCH";
            args[3] = "-dNOSAFER";
            args[4] = "-sDEVICE=nullpage"; // headless device: accepts all render ops
            // Capture stdout/stderr to prevent GS banner from corrupting
            // Surefire's stdout protocol (used for test result reporting)
            gs.setStdOut(new ByteArrayOutputStream());
            gs.setStdErr(errOut);
            gs.initialize(args);
        } catch (GhostscriptException e) {
            fail(e.getMessage());
        }

        // run file
        try {
            File file = new File(testResourcesPath, "input.ps").getAbsoluteFile();
            gs.runFile(file.getAbsolutePath());
        } catch (GhostscriptException e) {
            // print stderr to surefire report so we can see the PS error
            System.err.println("GS stderr: " + errOut.toString());
            fail(e.getMessage());
        }

        // exit
        try {
            gs.exit();
        } catch (GhostscriptException e) {
            fail(e.getMessage());
        }
    }

    /** Test Ghostscript standard input. */
    @Test
    public void testStdIn() {

        Ghostscript gs = Ghostscript.getInstance();

        InputStream is = null;

        // initialize
        try {
            File file = new File(testResourcesPath, "input.ps");
            is = new FileInputStream(file);

            gs.setStdIn(is);

            String[] args = new String[7];
            args[0] = "-dQUIET";
            args[1] = "-dNOPAUSE";
            args[2] = "-dBATCH";
            args[3] = "-dNODISPLAY";
            args[4] = "-sOutputFile=%stdout";
            args[5] = "-f";
            args[6] = "-";

            gs.initialize(args);

            is.close();

        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    /** Test Ghostscript standard output. */
    @Test
    public void testStdOut() {

        Ghostscript gs = Ghostscript.getInstance();

        InputStream is = null;
        ByteArrayOutputStream os = null;

        // initialize
        try {

            // input
            is = new ByteArrayInputStream(new String("devicenames ==\n").getBytes());
            gs.setStdIn(is);

            // output
            os = new ByteArrayOutputStream();
            gs.setStdOut(os);

            String[] args = new String[4];
            args[0] = "-dNODISPLAY";
            args[1] = "-sOutputFile=%stdout";
            args[2] = "-f";
            args[3] = "-";

            gs.initialize(args);

            assertTrue(os.toString().length() > 0);

            os.close();
            is.close();

        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    /** Test Ghostscript standard error output. */
    @Test
    public void testStdErr() {

        Ghostscript gs = Ghostscript.getInstance();

        InputStream is = null;
        ByteArrayOutputStream os = null;

        // initialize
        try {

            // input
            is = new ByteArrayInputStream(new String("stupid\n").getBytes());
            gs.setStdIn(is);

            // output
            os = new ByteArrayOutputStream();
            gs.setStdErr(os);

            String[] args = new String[4];
            args[0] = "-dNODISPLAY";
            args[1] = "-sOutputFile=%stdout";
            args[2] = "-f";
            args[3] = "-";

            gs.initialize(args);

            is.close();

        } catch (Exception e) {
            // do not notice error because we want to test error output
            if (!e.getMessage().contains("Error code is")) {
                fail(e.getMessage());
            }
        } finally {
            try {
                assertTrue(os.toString().length() > 0);
                os.close();
            } catch (IOException e2) {
                fail(e2.getMessage());
            }
        }
    }

    /** Test Ghostscript set with custom display. */
    @Test
    public void testDisplayCallback() {

        Ghostscript gs = Ghostscript.getInstance();

        try {

            // create display callback
            ImageWriterDisplayCallback displayCallback = new ImageWriterDisplayCallback();

            // set display callback
            gs.setDisplayCallback(displayCallback);

            String[] args = {
                "-dQUIET",
                "-dNOPAUSE",
                "-dBATCH",
                "-dNOSAFER",
                "-sDEVICE=display",
                "-sDisplayHandle=0",
                "-dDisplayFormat=16#804",
                "-r20"
            };

            gs.initialize(args);

            File file = new File(testResourcesPath, "input.ps").getAbsoluteFile();
            gs.runFile(file.getAbsolutePath());

            gs.exit();

            assertEquals(1, displayCallback.getImages().size());

        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    /**
     * Test that runFile succeeds under -dSAFER when the file's directory is permitted via
     * gsapi_add_control_path using a wildcard. GS path-control matching requires {@code dir/*} (not
     * a bare directory path) to permit all files directly inside a directory; the path must be
     * added before gsapi_init_with_args is called.
     */
    @Test
    public void testRunFileWithSaferAndPermitFileAll() {

        Ghostscript gs = Ghostscript.getInstance();

        final ByteArrayOutputStream errOut = new ByteArrayOutputStream();

        try {
            File file = new File(testResourcesPath, "input.ps").getAbsoluteFile();

            // Use "dir/*" to permit all files directly inside the directory.
            // A bare directory path (with or without trailing slash) does not
            // match direct children — only subdirectory paths. Must be called
            // before initialize().
            gs.addControlPath(Ghostscript.PERMIT_FILE_READING, file.getParent() + "/*");

            String[] args = {"-dQUIET", "-dNOPAUSE", "-dBATCH", "-dSAFER", "-sDEVICE=nullpage"};

            gs.setStdOut(new ByteArrayOutputStream());
            gs.setStdErr(errOut);
            gs.initialize(args);

            gs.runFile(file.getAbsolutePath());

            gs.exit();

        } catch (GhostscriptException e) {
            System.err.println("GS stderr: " + errOut.toString());
            fail(e.getMessage());
        }
    }
}
