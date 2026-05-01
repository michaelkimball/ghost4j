/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */

package org.ghost4j.document;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.utils.PdfMerger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.io.IOUtils;

/**
 * @author ggrousset
 */
public class PDFDocument extends AbstractDocument {

    /** Serial version UID. */
    private static final long serialVersionUID = 6331191005700202153L;

    @Override
    public void load(InputStream inputStream) throws IOException {
        super.load(inputStream);

        // check that the file is a PDF
        ByteArrayInputStream bais = null;
        PdfReader reader = null;
        PdfDocument pdfDoc = null;

        try {

            bais = new ByteArrayInputStream(content);
            reader = new PdfReader(bais);
            pdfDoc = new PdfDocument(reader);

        } catch (Exception e) {
            throw new IOException("PDF document is not valid");
        } finally {
            if (pdfDoc != null) pdfDoc.close();
            else if (reader != null)
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            IOUtils.closeQuietly(bais);
        }
    }

    public int getPageCount() throws DocumentException {

        int pageCount = 0;

        if (content == null) {
            return pageCount;
        }

        ByteArrayInputStream bais = null;
        PdfDocument pdfDoc = null;

        try {

            bais = new ByteArrayInputStream(content);
            pdfDoc = new PdfDocument(new PdfReader(bais));
            pageCount = pdfDoc.getNumberOfPages();

        } catch (Exception e) {
            throw new DocumentException(e);
        } finally {
            if (pdfDoc != null) pdfDoc.close();
            IOUtils.closeQuietly(bais);
        }

        return pageCount;
    }

    public Document extract(int begin, int end) throws DocumentException {

        this.assertValidPageRange(begin, end);

        PDFDocument result = new PDFDocument();

        ByteArrayInputStream bais = null;
        ByteArrayOutputStream baos = null;

        if (content != null) {

            PdfDocument sourcePdf = null;
            PdfDocument destPdf = null;

            try {

                bais = new ByteArrayInputStream(content);
                baos = new ByteArrayOutputStream();

                sourcePdf = new PdfDocument(new PdfReader(bais));
                destPdf = new PdfDocument(new PdfWriter(baos));

                sourcePdf.copyPagesTo(begin, end, destPdf);

                destPdf.close();
                destPdf = null;
                sourcePdf.close();
                sourcePdf = null;

                result.load(new ByteArrayInputStream(baos.toByteArray()));

            } catch (Exception e) {
                throw new DocumentException(e);
            } finally {
                if (destPdf != null) destPdf.close();
                if (sourcePdf != null) sourcePdf.close();
                IOUtils.closeQuietly(bais);
                IOUtils.closeQuietly(baos);
            }
        }

        return result;
    }

    @Override
    public void append(Document document) throws DocumentException {

        super.append(document);

        ByteArrayOutputStream baos = null;
        PdfDocument mergedPdf = null;
        PdfDocument source1 = null;
        PdfDocument source2 = null;

        try {

            baos = new ByteArrayOutputStream();
            mergedPdf = new PdfDocument(new PdfWriter(baos));
            PdfMerger merger = new PdfMerger(mergedPdf);

            // merge current document
            source1 = new PdfDocument(new PdfReader(new ByteArrayInputStream(content)));
            merger.merge(source1, 1, source1.getNumberOfPages());
            source1.close();
            source1 = null;

            // merge new document
            source2 =
                    new PdfDocument(new PdfReader(new ByteArrayInputStream(document.getContent())));
            merger.merge(source2, 1, source2.getNumberOfPages());
            source2.close();
            source2 = null;

            mergedPdf.close();
            mergedPdf = null;

            // replace content with new content
            content = baos.toByteArray();

        } catch (Exception e) {
            throw new DocumentException(e);
        } finally {
            if (source2 != null) source2.close();
            if (source1 != null) source1.close();
            if (mergedPdf != null) mergedPdf.close();
            IOUtils.closeQuietly(baos);
        }
    }

    public String getType() {
        return TYPE_PDF;
    }
}
