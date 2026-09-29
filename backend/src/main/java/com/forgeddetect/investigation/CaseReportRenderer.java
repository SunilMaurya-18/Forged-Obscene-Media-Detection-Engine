package com.forgeddetect.investigation;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 28: the human-readable case report inside an evidence package. It restates what
 * {@code case/report.json} holds; the JSON, not this PDF, is what the verifier checks field by field
 * (both are covered by the package signature).
 */
final class CaseReportRenderer {

    private static final Color INK = new Color(0x111827);
    private static final Color MUTED = new Color(0x6B7280);
    private static final Color LINE = new Color(0xE5E7EB);
    private static final Color BRAND = new Color(0x4F46E5);
    private static final Color BAND_BG = new Color(0x0B1020);
    private static final Color SHADE = new Color(0xF3F4F6);

    private static final Font H1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Font.NORMAL, Color.WHITE);
    private static final Font H1_SUB = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.NORMAL, new Color(0xA5B4FC));
    private static final Font BAND_LABEL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Font.NORMAL, new Color(0x9CA3AF));
    private static final Font BAND_VALUE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Font.NORMAL, Color.WHITE);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11.5f, Font.NORMAL, INK);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Font.NORMAL, MUTED);
    private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.NORMAL, INK);
    private static final Font MONO = FontFactory.getFont(FontFactory.COURIER, 7.5f, Font.NORMAL, INK);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, MUTED);
    private static final Font TD = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, INK);

    private CaseReportRenderer() {}

    /** {@code report}: the investigator view of the case plus {@code package} and {@code authorisation} blocks. */
    @SuppressWarnings("unchecked")
    static byte[] render(Map<String, Object> report) {
        Map<String, Object> pkg = (Map<String, Object>) report.get("package");
        Map<String, Object> auth = (Map<String, Object>) report.get("authorisation");
        Map<String, Object> custody = (Map<String, Object>) report.get("custody");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 52);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer(s(pkg.get("packageId")), s(report.get("caseNumber"))));
            doc.addTitle("Evidence package " + s(pkg.get("packageId")) + " for " + s(report.get("caseNumber")));
            doc.addAuthor("Forged Media Detection");
            doc.addCreator("Forged Media Detection evidence packages");
            doc.open();

            band(doc, report, pkg);

            section(doc, "Authorisation");
            PdfPTable a = kvTable();
            kv(a, "Legal request", s(auth.get("requestNumber")), false);
            kv(a, "Authority", s(auth.get("authority")), false);
            kv(a, "Order reference", s(auth.get("orderReference")), false);
            kv(a, "Issued to", s(auth.get("investigator")), false);
            kv(a, "Filed by / approved by", s(auth.get("filedBy")) + " / " + s(auth.get("decidedBy")), false);
            kv(a, "Access expires", s(auth.get("expiresAt")), false);
            doc.add(a);

            section(doc, "Case");
            PdfPTable c = kvTable();
            kv(c, "Case number", s(report.get("caseNumber")), false);
            kv(c, "Title", s(report.get("title")), false);
            kv(c, "Status", s(report.get("status")), false);
            kv(c, "Opened", s(report.get("openedAt")) + " by " + s(report.get("openedBy")), false);
            doc.add(c);

            List<Map<String, Object>> evidence = (List<Map<String, Object>>) report.get("evidence");
            section(doc, "Evidence (" + evidence.size() + ")");
            PdfPTable ev = table(new float[] {9, 11, 12, 10, 34, 24}, "Analysis", "Media", "Decision", "Forgery", "Content SHA-256", "Signed report");
            for (Map<String, Object> e : evidence) {
                ev.addCell(td("#" + s(e.get("analysisId")), TD));
                ev.addCell(td(s(e.get("mediaType")), TD));
                ev.addCell(td(s(e.get("decision")), TD));
                ev.addCell(td(e.get("forgeryScore") instanceof Number n ? String.format(Locale.ROOT, "%.3f", n.doubleValue()) : "-", TD));
                ev.addCell(td(s(e.get("contentHash")), MONO));
                ev.addCell(td(s(e.get("reportId")), MONO));
            }
            doc.add(ev);

            List<Map<String, Object>> sightings = (List<Map<String, Object>>) report.get("sightings");
            section(doc, "Partner sightings (" + sightings.size() + ")");
            if (sightings.isEmpty()) {
                doc.add(note("No partner platform has reported this media."));
            } else {
                PdfPTable st = table(new float[] {14, 18, 16, 26, 26}, "Platform", "Post", "Account", "Observed", "Matched");
                for (Map<String, Object> x : sightings) {
                    st.addCell(td(s(x.get("platform")), TD));
                    st.addCell(td(s(x.get("postRef")), MONO));
                    st.addCell(td(s(x.get("account")), MONO));
                    st.addCell(td(s(x.get("observedAt")), TD));
                    st.addCell(td(s(x.get("via")) + ("PHASH".equals(x.get("via")) ? " (distance " + s(x.get("distance")) + ")" : "")
                            + " to #" + s(x.get("matchedAnalysisId")), TD));
                }
                doc.add(st);
                doc.add(note("Accounts are keyed pseudonyms (HMAC per platform). Only the platform can say who an account is, "
                        + "in answer to its own legal process."));
            }

            List<Map<String, Object>> holds = (List<Map<String, Object>>) custody.get("legalHolds");
            if (!holds.isEmpty()) {
                section(doc, "Legal holds (" + holds.size() + ")");
                PdfPTable ht = table(new float[] {18, 14, 16, 52}, "Hold", "Platform", "Status", "Posts");
                for (Map<String, Object> h : holds) {
                    ht.addCell(td(s(h.get("holdNumber")), MONO));
                    ht.addCell(td(s(h.get("platform")), TD));
                    ht.addCell(td(s(h.get("status")), TD));
                    ht.addCell(td(String.join(", ", (List<String>) h.get("postRefs")), MONO));
                }
                doc.add(ht);
            }

            section(doc, "Integrity");
            PdfPTable i = kvTable();
            kv(i, "Preservation", s(pkg.get("preservationId")), true);
            kv(i, "Preservation manifest SHA-256", s(pkg.get("preservationManifestSha256")), true);
            kv(i, "Custody log", s(pkg.get("custodyLength")) + " events, head " + s(pkg.get("custodyHead")), true);
            kv(i, "Package signing key", "Ed25519 " + s(pkg.get("signingKeyId")), true);
            doc.add(i);
            doc.add(note("PACKAGE-MANIFEST.txt lists every file in this package with its SHA-256 and is signed with the key above "
                    + "(PACKAGE-SIGNATURE.txt). Check it with only the published public key: "
                    + "python3 verify_evidence_package.py <package.zip> --keys keys.json, where keys.json is GET /api/verify-report/keys. "
                    + "The package holds hashes, scores and records, never the media: to prove a copy of the file obtained from the "
                    + "platform is the one analysed, recompute its content SHA-256 as described in ADR 0006 and compare."));
        } catch (DocumentException ex) {
            throw new IllegalStateException("Could not render the case report: " + ex.getMessage(), ex);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private static void band(Document doc, Map<String, Object> report, Map<String, Object> pkg) throws DocumentException {
        PdfPTable band = new PdfPTable(new float[] {58, 42});
        band.setWidthPercentage(100);
        PdfPCell left = cell(BAND_BG, 14);
        left.addElement(new Paragraph("Evidence Package", H1));
        Paragraph sub = new Paragraph("Forged Media Detection  |  Case report for an authorised investigator  |  Confidential", H1_SUB);
        sub.setSpacingBefore(3);
        left.addElement(sub);
        band.addCell(left);
        PdfPCell right = cell(BAND_BG, 14);
        for (String[] kv : new String[][] {
                {"PACKAGE", s(pkg.get("packageId"))}, {"CASE", s(report.get("caseNumber"))}, {"ISSUED", s(pkg.get("issuedAt"))}}) {
            Paragraph p = new Paragraph();
            p.add(new Chunk(kv[0] + "   ", BAND_LABEL));
            p.add(new Chunk(kv[1], BAND_VALUE));
            p.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(p);
        }
        band.addCell(right);
        doc.add(band);
    }

    private static void section(Document doc, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, H2);
        p.setSpacingBefore(16);
        p.setSpacingAfter(6);
        doc.add(p);
    }

    private static Paragraph note(String text) {
        Paragraph p = new Paragraph(text, SMALL);
        p.setSpacingBefore(5);
        p.setLeading(10.5f);
        return p;
    }

    private static PdfPTable kvTable() {
        PdfPTable t = new PdfPTable(new float[] {30, 70});
        t.setWidthPercentage(100);
        return t;
    }

    private static void kv(PdfPTable t, String label, String value, boolean mono) {
        PdfPCell l = new PdfPCell(new Phrase(label, LABEL));
        PdfPCell v = new PdfPCell(new Phrase(value, mono ? MONO : VALUE));
        for (PdfPCell c : new PdfPCell[] {l, v}) {
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(LINE);
            c.setPaddingTop(5);
            c.setPaddingBottom(6);
        }
        t.addCell(l);
        t.addCell(v);
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String h : headers) {
            PdfPCell c = new PdfPCell(new Phrase(h, TH));
            c.setBackgroundColor(SHADE);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(5);
            t.addCell(c);
        }
        return t;
    }

    private static PdfPCell td(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setPadding(5);
        return c;
    }

    private static PdfPCell cell(Color bg, float padding) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(bg);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(padding);
        return c;
    }

    private static String s(Object v) {
        return v == null || v.toString().isBlank() ? "-" : v.toString();
    }

    private static final class Footer extends PdfPageEventHelper {
        private final String packageId;
        private final String caseNumber;

        Footer(String packageId, String caseNumber) {
            this.packageId = packageId;
            this.caseNumber = caseNumber;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float y = document.bottom() - 22;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(packageId + "  |  " + caseNumber + "  |  Confidential: for the authorised investigator named above", SMALL),
                    document.left(), y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), SMALL), document.right(), y, 0);
            writer.getDirectContent().setColorStroke(BRAND);
            writer.getDirectContent().setLineWidth(0.6f);
            writer.getDirectContent().moveTo(document.left(), y + 11);
            writer.getDirectContent().lineTo(document.right(), y + 11);
            writer.getDirectContent().stroke();
        }
    }
}
