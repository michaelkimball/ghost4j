/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */

package org.ghost4j;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GhostscriptLibrary tests.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public class GhostscriptLibraryTest {

    private final String testResourcesPath = "target/test-classes";

    private GhostscriptLibrary ghostscriptLibrary;

    // Static fields to prevent JNA 5.x WeakHashMap GC from collecting callbacks
    // before GS is done calling them.
    private static GhostscriptLibrary.stdin_fn staticStdinCallback;
    private static GhostscriptLibrary.stdout_fn staticStdoutCallback;
    private static GhostscriptLibrary.stderr_fn staticStderrCallback;

    @BeforeEach
    protected void setUp() throws Exception {
        ghostscriptLibrary = GhostscriptLibrary.instance;
    }

    @AfterEach
    protected void tearDown() throws Exception {}

    /** Test of gsapi_revision method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_revision() {

        // prepare revision structure and call revision function
        GhostscriptLibrary.gsapi_revision_s revision = new GhostscriptLibrary.gsapi_revision_s();
        ghostscriptLibrary.gsapi_revision(revision, revision.size());

        // test result
        assertTrue(revision.product.contains("Ghostscript"));
    }

    /** Test of gsapi_new_instance method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_new_instance() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        int result = ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // delete instance
        if (result == 0) {
            ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());
        }

        // test result
        assertEquals(0, result);
    }

    /** Test of gsapi_exit method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_exit() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // GS 9.x: set arg encoding before any other API calls
        ghostscriptLibrary.gsapi_set_arg_encoding(
                instanceByRef.getValue(), GhostscriptLibrary.GS_ARG_ENCODING_UTF8);

        // enter interpreter with headless quiet args
        String[] args = {"gs", "-dQUIET", "-dNODISPLAY", "-dNOPAUSE", "-dBATCH"};
        int result =
                ghostscriptLibrary.gsapi_init_with_args(
                        instanceByRef.getValue(), args.length, args);

        // exit interpreter
        if (result == 0 || result == -101) {
            result = ghostscriptLibrary.gsapi_exit(instanceByRef.getValue());

            // test result
            assertEquals(0, result);
        } else {
            fail("Failed to initialize interpreter (result=" + result + ")");
        }

        // delete instance
        ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());
    }

    /** Test of gsapi_init_with_args method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_init_with_args() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // call interpreter for PS to PDF conversion
        File file = new File(testResourcesPath, "input.ps");
        File outputFile = new File("output.pdf");

        // 'ps2pdf' is argv[0] (program name, skipped by GS)
        String[] args = new String[8];
        args[0] = "ps2pdf";
        args[1] = "-dQUIET";
        args[2] = "-dBATCH";
        args[3] = "-dNOPAUSE";
        args[4] = "-dNOSAFER";
        args[5] = "-sDEVICE=pdfwrite";
        args[6] = "-sOutputFile=" + outputFile.getAbsolutePath();
        args[7] = file.getAbsolutePath();
        int result =
                ghostscriptLibrary.gsapi_init_with_args(
                        instanceByRef.getValue(), args.length, args);

        // -101 (gs_error_Quit) is normal for -dBATCH
        if (result == -101) result = 0;

        // exit
        ghostscriptLibrary.gsapi_exit(instanceByRef.getValue());

        // delete instance
        ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());

        // test
        assertEquals(0, result);
        assertTrue(outputFile.exists(), "output.pdf should have been created");
        outputFile.delete();
    }

    /** Test of gsapi_run_string method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_run_string() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // enter interpreter — 'gs' is argv[0] (program name, skipped by GS)
        // so -dQUIET is the first effective GS arg (suppresses banner)
        String[] args = {"gs", "-dQUIET", "-dNODISPLAY", "-dNOPAUSE", "-dBATCH", "-dSAFER"};
        ghostscriptLibrary.gsapi_init_with_args(instanceByRef.getValue(), args.length, args);

        // run command — use simple arithmetic (no stdout output to corrupt Surefire)
        IntByReference exitCode = new IntByReference();
        ghostscriptLibrary.gsapi_run_string(instanceByRef.getValue(), "1 1 add pop\n", 0, exitCode);
        // test result
        assertEquals(0, exitCode.getValue());

        // exit interpreter
        ghostscriptLibrary.gsapi_exit(instanceByRef.getValue());

        // delete instance
        ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());
    }

    /** Test of gsapi_run_string_with_length method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_run_string_with_length() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // enter interpreter
        String[] args = {"gs", "-dQUIET", "-dNODISPLAY", "-dNOPAUSE", "-dBATCH", "-dSAFER"};
        ghostscriptLibrary.gsapi_init_with_args(instanceByRef.getValue(), args.length, args);

        // run command — use simple arithmetic (no stdout output to corrupt Surefire)
        IntByReference exitCode = new IntByReference();
        String str = "1 1 add pop\n";
        ghostscriptLibrary.gsapi_run_string_with_length(
                instanceByRef.getValue(), str, str.length(), 0, exitCode);
        // test result
        assertEquals(0, exitCode.getValue());

        // exit interpreter
        ghostscriptLibrary.gsapi_exit(instanceByRef.getValue());

        // delete instance
        ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());
    }

    /** Test of gsapi_run_string_continue method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_run_string_continue() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // enter interpreter
        String[] args = {"gs", "-dQUIET", "-dNODISPLAY", "-dNOPAUSE", "-dBATCH", "-dSAFER"};
        ghostscriptLibrary.gsapi_init_with_args(instanceByRef.getValue(), args.length, args);

        // run command — use simple arithmetic (no stdout output to corrupt Surefire)
        IntByReference exitCode = new IntByReference();
        ghostscriptLibrary.gsapi_run_string_begin(instanceByRef.getValue(), 0, exitCode);
        String str = "1 1 add pop\n";
        ghostscriptLibrary.gsapi_run_string_continue(
                instanceByRef.getValue(), str, str.length(), 0, exitCode);
        // test result
        assertEquals(0, exitCode.getValue());
        ghostscriptLibrary.gsapi_run_string_end(instanceByRef.getValue(), 0, exitCode);

        // exit interpreter
        ghostscriptLibrary.gsapi_exit(instanceByRef.getValue());

        // delete instance
        ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());
    }

    /** Test of gsapi_run_file method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_run_file() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // 'gs' is argv[0] (program name, skipped by GS); -dQUIET suppresses
        // banner. -sDEVICE=nullpage provides a headless no-op device so
        // input.ps can call showpage without requiring an X11 display.
        // -dNOSAFER: SAFER mode in GS 9.50 blocks file reads via PS operators;
        // without NOSAFEER, gsapi_run_file fails with /undefinedfilename.
        // Do NOT use -dBATCH: init returns 0, interpreter stays ready for run_file.
        String[] args = {"gs", "-dQUIET", "-dNOPAUSE", "-dNOSAFER", "-sDEVICE=nullpage"};
        ghostscriptLibrary.gsapi_init_with_args(instanceByRef.getValue(), args.length, args);

        // run command — use absolute path so file resolution is unambiguous
        IntByReference exitCode = new IntByReference();
        File file = new File(testResourcesPath, "input.ps").getAbsoluteFile();
        ghostscriptLibrary.gsapi_run_file(
                instanceByRef.getValue(), file.getAbsolutePath(), 0, exitCode);
        // test result
        assertEquals(0, exitCode.getValue());

        // exit interpreter
        ghostscriptLibrary.gsapi_exit(instanceByRef.getValue());

        // delete instance
        ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());
    }

    /** Test of gsapi_set_stdio method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_set_stdio() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // buffer to hold standard output
        final StringBuffer stdOutBuffer = new StringBuffer();
        // buffer to store value if stdin callback is called
        final StringBuffer stdInBuffer = new StringBuffer();

        // callbacks — held in static fields to prevent GC (JNA 5.x uses WeakHashMap)
        // stdin_fn
        staticStdinCallback =
                new GhostscriptLibrary.stdin_fn() {

                    public int callback(Pointer caller_handle, Pointer buf, int len) {

                        stdInBuffer.append("OK");
                        return 0;
                        /*
                         * String input = "devicenames ==\n"; buf.setString(0, input);
                         * return input.length();
                         */
                    }
                };
        // stdout_fn
        staticStdoutCallback =
                new GhostscriptLibrary.stdout_fn() {

                    public int callback(Pointer caller_handle, String str, int len) {
                        stdOutBuffer.append(str.substring(0, len));
                        return len;
                    }
                };
        // stderr_fn
        staticStderrCallback =
                new GhostscriptLibrary.stderr_fn() {

                    public int callback(Pointer caller_handle, String str, int len) {
                        return len;
                    }
                };

        // GS 9.x requires gsapi_set_arg_encoding before gsapi_set_stdio
        ghostscriptLibrary.gsapi_set_arg_encoding(
                instanceByRef.getValue(), GhostscriptLibrary.GS_ARG_ENCODING_UTF8);

        // io setting
        ghostscriptLibrary.gsapi_set_stdio(
                instanceByRef.getValue(),
                staticStdinCallback,
                staticStdoutCallback,
                staticStderrCallback);

        // 'gs' is argv[0] (skipped); -dQUIET suppresses banner, -dNODISPLAY
        // avoids X11 initialization. Do NOT use -f - (stdin reading) since
        // we verify stdout callback separately with run_string.
        // Do NOT use -dBATCH (avoids -101 / interpreter restart confusion).
        String[] args = {"gs", "-dQUIET", "-dNODISPLAY", "-dNOPAUSE"};
        ghostscriptLibrary.gsapi_init_with_args(instanceByRef.getValue(), args.length, args);

        // Run a simple command that definitely outputs to stdout callback
        IntByReference exitCode = new IntByReference();
        ghostscriptLibrary.gsapi_run_string(
                instanceByRef.getValue(), "(callbacks-work) =\n", 0, exitCode);

        // exit
        ghostscriptLibrary.gsapi_exit(instanceByRef.getValue());

        // delete instance
        ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());

        // assert std out was redirected (should contain "callbacks-work")
        assertTrue(
                stdOutBuffer.toString().contains("callbacks-work"),
                "stdout callback should have received output, but buffer is: '"
                        + stdOutBuffer.toString()
                        + "'");
    }

    /** Test of gsapi_set_display_callback method, of class GhostscriptLibrary. */
    @Test
    public void testGsapi_set_display_callback() {

        // create pointer to hold Ghostscript instance
        GhostscriptLibrary.gs_main_instance.ByReference instanceByRef =
                new GhostscriptLibrary.gs_main_instance.ByReference();

        // create instance
        ghostscriptLibrary.gsapi_new_instance(instanceByRef.getPointer(), null);

        // buffer holding callback results
        final StringBuffer result = new StringBuffer();

        // set display callbacks
        GhostscriptLibrary.display_callback_s displayCallback =
                new GhostscriptLibrary.display_callback_s();
        displayCallback.version_major = 2;
        displayCallback.version_minor = 0;
        displayCallback.display_open =
                new GhostscriptLibrary.display_callback_s.display_open() {

                    public int callback(Pointer handle, Pointer device) {
                        result.append("OPEN-");
                        return 0;
                    }
                };
        displayCallback.display_preclose =
                new GhostscriptLibrary.display_callback_s.display_preclose() {

                    public int callback(Pointer handle, Pointer device) {
                        result.append("PRECLOSE-");
                        return 0;
                    }
                };
        displayCallback.display_close =
                new GhostscriptLibrary.display_callback_s.display_close() {

                    public int callback(Pointer handle, Pointer device) {
                        result.append("CLOSE");
                        return 0;
                    }
                };
        displayCallback.display_presize =
                new GhostscriptLibrary.display_callback_s.display_presize() {

                    public int callback(
                            Pointer handle,
                            Pointer device,
                            int width,
                            int height,
                            int raster,
                            int format) {
                        result.append("PRESIZE-");
                        return 0;
                    }
                };
        displayCallback.display_size =
                new GhostscriptLibrary.display_callback_s.display_size() {

                    public int callback(
                            Pointer handle,
                            Pointer device,
                            int width,
                            int height,
                            int raster,
                            int format,
                            Pointer pimage) {
                        result.append("SIZE-");
                        return 0;
                    }
                };
        displayCallback.display_sync =
                new GhostscriptLibrary.display_callback_s.display_sync() {

                    public int callback(Pointer handle, Pointer device) {
                        result.append("SYNC-");
                        return 0;
                    }
                };
        displayCallback.display_page =
                new GhostscriptLibrary.display_callback_s.display_page() {

                    public int callback(Pointer handle, Pointer device, int copies, int flush) {
                        result.append("PAGE-");
                        return 0;
                    }
                };
        displayCallback.display_update =
                new GhostscriptLibrary.display_callback_s.display_update() {

                    public int callback(
                            Pointer handle, Pointer device, int x, int y, int w, int h) {
                        result.append("UPDATE-");
                        return 0;
                    }
                };

        displayCallback.display_memalloc = null;
        displayCallback.display_memfree = null;

        displayCallback.size = displayCallback.size();

        ghostscriptLibrary.gsapi_set_display_callback(instanceByRef.getValue(), displayCallback);

        // 'gs' is argv[0] (skipped by GS); -dQUIET suppresses banner
        String[] args = new String[8];
        args[0] = "gs";
        args[1] = "-dQUIET";
        args[2] = "-dNOPAUSE";
        args[3] = "-dBATCH";
        args[4] = "-dSAFER";
        args[5] = "-sDEVICE=display";
        args[6] = "-sDisplayHandle=0";
        args[7] = "-dDisplayFormat=16#a0800";
        ghostscriptLibrary.gsapi_init_with_args(instanceByRef.getValue(), args.length, args);

        // run command
        IntByReference exitCode = new IntByReference();
        String command = "showpage\n";
        ghostscriptLibrary.gsapi_run_string_with_length(
                instanceByRef.getValue(), command, command.length(), 0, exitCode);

        // exit interpreter
        ghostscriptLibrary.gsapi_exit(instanceByRef.getValue());

        // delete instance
        ghostscriptLibrary.gsapi_delete_instance(instanceByRef.getValue());

        // assert all display callbacks were called successfully
        assertEquals("OPEN-PRESIZE-UPDATE-SIZE-PAGE-UPDATE-SYNC-PRECLOSE-CLOSE", result.toString());
    }
}
