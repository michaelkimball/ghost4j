/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */

package org.ghost4j.document;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PDFDocument tests.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public class PDFDocumentTest {

    @BeforeEach
    protected void setUp() throws Exception {}

    @AfterEach
    protected void tearDown() throws Exception {}

    /** Test of getPageCount method, of class PDFDocument. */
    @Test
    public void testGetPageCount() throws Exception {

        // load document
        PDFDocument document = new PDFDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input.pdf"));

        // test
        assertEquals(1, document.getPageCount());
    }

    @Test
    public void testLoadWrongFormat() throws Exception {

        // load document (PS when PDF expected)
        try {
            PDFDocument document = new PDFDocument();
            document.load(this.getClass().getClassLoader().getResourceAsStream("input.ps"));
            fail("Test failed");
        } catch (IOException e) {
            assertEquals("PDF document is not valid", e.getMessage());
        }
    }

    @Test
    public void testExtractPages() throws Exception {

        // load document (2 pages)
        PDFDocument document = new PDFDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input-2pages.pdf"));

        // extract first page
        Document extracted = document.extract(1, 1);

        // test
        assertEquals(1, extracted.getPageCount());
    }

    @Test
    public void testAppendPages() throws Exception {

        // load document (1 page)
        PDFDocument document = new PDFDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input.pdf"));

        // load second document (2 pages)
        PDFDocument document2 = new PDFDocument();
        document2.load(this.getClass().getClassLoader().getResourceAsStream("input-2pages.pdf"));

        // append
        document.append(document2);

        // test
        assertEquals(3, document.getPageCount());
    }

    @Test
    public void testAppendPagesWrongFormat() throws Exception {

        // load document (2 pages)
        PDFDocument document = new PDFDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input-2pages.pdf"));

        // load second document but of different type (1 page)
        PSDocument document2 = new PSDocument();
        document2.load(this.getClass().getClassLoader().getResourceAsStream("input.ps"));

        // append
        try {
            document.append(document2);
            fail("Test failed");
        } catch (DocumentException e) {
            assertEquals("Cannot append document of different types", e.getMessage());
        }
    }

    @Test
    public void testLoadFromFile() throws Exception {

        File tempFile = File.createTempFile("ghost4j-test", ".pdf");
        tempFile.deleteOnExit();
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("input.pdf")) {
            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        PDFDocument document = new PDFDocument();
        document.load(tempFile);

        assertEquals(1, document.getPageCount());
    }

    @Test
    public void testGetContent() throws Exception {

        PDFDocument document = new PDFDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input.pdf"));

        assertNotNull(document.getContent());
        assertTrue(document.getContent().length > 0);
        assertEquals(document.getSize(), document.getContent().length);
    }
}
