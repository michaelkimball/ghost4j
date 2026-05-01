/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */

package org.ghost4j.document;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PSDocument tests.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public class PSDocumentTest {

    @BeforeEach
    protected void setUp() throws Exception {}

    @AfterEach
    protected void tearDown() throws Exception {}

    /** Test of getPageCount method, of class PSDocument. */
    @Test
    public void testGetPageCount() throws Exception {

        // load document
        PSDocument document = new PSDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input.ps"));

        // test
        assertEquals(1, document.getPageCount());
    }

    @Test
    public void testLoadWrongFormat() throws Exception {

        // load document (PDF when PS expected)
        try {
            PSDocument document = new PSDocument();
            document.load(this.getClass().getClassLoader().getResourceAsStream("input.pdf"));
            fail("Test failed");
        } catch (IOException e) {
            assertEquals("PostScript document is not valid", e.getMessage());
        }
    }

    @Test
    public void testExtractPages() throws Exception {

        // load document (2 pages)
        PSDocument document = new PSDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input-2pages.ps"));

        // extract first page
        Document extracted = document.extract(1, 1);

        // test
        assertEquals(1, extracted.getPageCount());
    }

    @Test
    public void testAppendPages() throws Exception {

        // load document (1 page)
        PSDocument document = new PSDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input.ps"));

        // load second document (2 pages)
        PSDocument document2 = new PSDocument();
        document2.load(this.getClass().getClassLoader().getResourceAsStream("input-2pages.ps"));

        // append
        document.append(document2);

        // test
        assertEquals(3, document.getPageCount());
    }

    @Test
    public void testAppendPagesWrongFormat() throws Exception {

        // load document (2 pages)
        PSDocument document = new PSDocument();
        document.load(this.getClass().getClassLoader().getResourceAsStream("input-2pages.ps"));

        // load second document but of different type (1 page)
        PDFDocument document2 = new PDFDocument();
        document2.load(this.getClass().getClassLoader().getResourceAsStream("input.pdf"));

        // append
        try {
            document.append(document2);
            fail("Test failed");
        } catch (DocumentException e) {
            assertEquals("Cannot append document of different types", e.getMessage());
        }
    }
}
