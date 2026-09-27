package com.forgeddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.ProvenanceEvent;
import com.forgeddetect.model.ProvenanceEventType;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ProvenanceEventRepository;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
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
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 15: one-click evidentiary PDF per analysis for a platform's legal / compliance team.
 *
 * <p>Forensic panels come from the coarse signature stored at analysis time, or, when a moderator
 * re-supplies the original, from full-resolution renders verified against the stored pixel hash.
 * The original is never retained. Items at or above the explicit review threshold never embed the
 * image itself: their Grad-CAM and ELA panels always come from the coarse signature.
 */
@Service
public class ReportService {

    public enum PanelSource { SIGNATURE, ORIGINAL, NONE }

    public record Report(
            String reportId, String filename, byte[] pdf, String sha256, PanelSource panelSource, ReportSigningService.Signed signature) {}

    /** The re-supplied file is not the media that was analysed. */
    public static class MediaMismatchException extends RuntimeException {
        public MediaMismatchException(String message) {
            super(message);
        }
    }

    private record Panels(
            byte[] heatmap, byte[] fft, byte[] ela, byte[] profile, PanelSource source, String note, JsonNode signature) {}

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TRAIL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

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
    private static final Font VALUE_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Font.NORMAL, INK);
    private static final Font MONO = FontFactory.getFont(FontFactory.COURIER, 8.5f, Font.NORMAL, INK);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font EVENT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, INK);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, MUTED);

    private final AnalysisResultRepository results;
    private final ProvenanceEventRepository events;
    private final ProvenanceService provenance;
    private final SlaClockService slaClock;
    private final DecisionThresholdProperties thresholds;
    private final MlClientService mlClient;
    private final ForensicPanelRenderer renderer;
    private final ObjectMapper mapper;
    private final ReportSigningService signing;
    private final Clock clock;
    private final ZoneId localZone;

    public ReportService(
            AnalysisResultRepository results,
            ProvenanceEventRepository events,
            ProvenanceService provenance,
            SlaClockService slaClock,
            DecisionThresholdProperties thresholds,
            MlClientService mlClient,
            ForensicPanelRenderer renderer,
            ObjectMapper mapper,
            ReportSigningService signing,
            Clock clock,
            @Value("${report.local-zone:Asia/Kolkata}") String localZone) {
        this.results = results;
        this.events = events;
        this.provenance = provenance;
        this.slaClock = slaClock;
        this.signing = signing;
        this.thresholds = thresholds;
        this.mlClient = mlClient;
        this.renderer = renderer;
        this.mapper = mapper;
        this.clock = clock;
        this.localZone = ZoneId.of(localZone);
    }

    /**
     * @param original optional re-supplied media for full-resolution panels; verified, never stored
     */
    @Transactional
    public Report generate(Long id, byte[] original, String filename, String contentType, String preparedBy) {
        AnalysisResult item = results.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found: " + id));
        // Stored timestamps have microsecond precision; the signed snapshot must match what is read back
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String reportId = "RPT-" + id + "-" + ID_TIME.format(now);

        Panels panels = panels(item, original, filename, contentType);
        ReportSigningService.Signed signed = signing.sign(item, reportId, now);
        results.save(item);
        byte[] pdf = render(item, reportId, now, preparedBy, panels, signed);
        String sha = sha256(pdf);

        provenance.record(item.getProvenanceId(), item.getId(), ProvenanceEventType.REPORT_GENERATED,
                ProvenanceService.MODERATOR, "Evidentiary report " + reportId + " generated and signed (" + ReportSigningService.ALGORITHM
                        + " key " + signed.keyId() + "); PDF SHA-256 " + sha, now);
        return new Report(reportId, "evidence-report-analysis-" + id + ".pdf", pdf, sha, panels.source(), signed);
    }

    // ------------------------------------------------------------------ panels

    private Panels panels(AnalysisResult item, byte[] original, String filename, String contentType) {
        JsonNode sig = parseSignature(item.getForensicSignature());
        byte[] heatmap = null;
        byte[] fft = null;
        byte[] ela = null;
        byte[] profile = null;
        PanelSource source = PanelSource.NONE;
        String note = "No forensic signature was recorded for this analysis (it predates signature capture) "
                + "and no original was attached, so forensic panels are unavailable.";

        if (sig != null) {
            double[][] cam = matrix(sig.at("/cam/grid"));
            heatmap = ForensicPanelRenderer.isFlat(cam)
                    ? renderer.empty(360)
                    : renderer.grid(cam, ForensicPanelRenderer.Palette.JET, 360);
            fft = renderer.grid(matrix(sig.at("/fft/grid")), ForensicPanelRenderer.Palette.INFERNO, 360);
            ela = renderer.grid(matrix(sig.at("/ela/grid")), ForensicPanelRenderer.Palette.ELA, 360);
            profile = renderer.profile(vector(sig.at("/fft/profile")), 1000, 200);
            source = PanelSource.SIGNATURE;
            note = "Panels are drawn from the coarse forensic signature captured at analysis time "
                    + "(14x14 attention, 16x16 error-level and 32x32 spectrum grids). No media was retained.";
        }

        if (original != null && original.length > 0) {
            MlPanelsResponse full = mlClient.forensicPanels(original, filename, contentType);
            if (full == null) {
                throw new IllegalStateException("ML service returned no panels");
            }
            boolean matches = item.getContentHash() != null
                    ? item.getContentHash().equals(full.contentHash())
                    : item.getPhash().equals(full.phash());
            if (!matches) {
                throw new MediaMismatchException("The attached file is not the media analysed as #" + item.getId()
                        + " (its pixel hash does not match the stored one).");
            }
            boolean explicitSensitive = item.getExplicitScore() >= thresholds.getExplicitReview();
            fft = decode(full.fft());
            if (!explicitSensitive) {
                heatmap = decode(full.heatmap());
                ela = decode(full.ela());
            }
            source = PanelSource.ORIGINAL;
            note = explicitSensitive
                    ? "The original was supplied and verified against the stored pixel hash. Because the explicit-content "
                            + "score is at or above the review threshold, attention and error-level panels come only from the "
                            + "coarse signature (or are omitted) so this report never reproduces the image. The file was not retained."
                    : "Rendered from the original file supplied at report time and verified against the stored pixel hash. "
                            + "The file was not retained.";
        }
        return new Panels(heatmap, fft, ela, profile, source, note, sig);
    }

    private JsonNode parseSignature(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (IOException ex) {
            return null;
        }
    }

    private static double[][] matrix(JsonNode node) {
        double[][] out = new double[node.size()][];
        for (int r = 0; r < node.size(); r++) {
            JsonNode row = node.get(r);
            out[r] = new double[row.size()];
            for (int c = 0; c < row.size(); c++) {
                out[r][c] = row.get(c).asDouble();
            }
        }
        return out;
    }

    private static double[] vector(JsonNode node) {
        double[] out = new double[node.size()];
        for (int i = 0; i < node.size(); i++) {
            out[i] = node.get(i).asDouble();
        }
        return out;
    }

    private static byte[] decode(String b64) {
        return b64 == null || b64.isBlank() ? null : Base64.getDecoder().decode(b64);
    }

    // ------------------------------------------------------------------ PDF

    private byte[] render(
            AnalysisResult item, String reportId, Instant now, String preparedBy, Panels panels, ReportSigningService.Signed signed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 52);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer(reportId, item));
            doc.addTitle("Evidentiary analysis report " + reportId);
            doc.addSubject("Analysis #" + item.getId() + " / provenance " + item.getProvenanceId());
            doc.addAuthor("Forged Media Detection");
            doc.addCreator("Forged Media Detection evidentiary reports");
            // Machine-readable copy of the signature in the PDF Info dictionary
            doc.addHeader("FMD-Report-Id", reportId);
            doc.addHeader("FMD-Signature-Algorithm", ReportSigningService.ALGORITHM);
            doc.addHeader("FMD-Signature", signed.signature());
            doc.addHeader("FMD-Signed-Content-SHA256", signed.contentHash());
            doc.addHeader("FMD-Signing-Key-Id", signed.keyId());
            doc.addHeader("FMD-Signed-At", signed.signedAt().toString());
            doc.open();

            headerBand(doc, reportId, now, preparedBy);
            decisionBanner(doc, item.getDecision());
            identification(doc, item);
            scores(doc, item);
            compliance(doc, item);
            forensics(doc, panels);
            auditTrail(doc, item);
            integrity(doc, item, reportId);
            signatureBlock(doc, signed);
        } catch (DocumentException | IOException ex) {
            throw new IllegalStateException("Could not render report: " + ex.getMessage(), ex);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private void headerBand(Document doc, String reportId, Instant now, String preparedBy) throws DocumentException {
        PdfPTable band = new PdfPTable(new float[] {58, 42});
        band.setWidthPercentage(100);

        PdfPCell left = cell(BAND_BG, 14);
        left.addElement(new Paragraph("Evidentiary Analysis Report", H1));
        Paragraph sub = new Paragraph("Forged Media Detection  |  Trust & Safety  |  Confidential", H1_SUB);
        sub.setSpacingBefore(3);
        left.addElement(sub);
        band.addCell(left);

        PdfPCell right = cell(BAND_BG, 14);
        for (String[] kv : new String[][] {
                {"REPORT ID", reportId}, {"GENERATED", timestamp(now)}, {"PREPARED BY", preparedBy + " (moderator)"}}) {
            Paragraph p = new Paragraph();
            p.add(new Chunk(kv[0] + "   ", BAND_LABEL));
            p.add(new Chunk(kv[1], BAND_VALUE));
            p.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(p);
        }
        band.addCell(right);
        doc.add(band);
    }

    private void decisionBanner(Document doc, Decision decision) throws DocumentException {
        Color bg;
        Color fg;
        String label;
        String meaning;
        switch (decision) {
            case AUTO_FLAG -> {
                bg = new Color(0xFFE4E6);
                fg = new Color(0x9F1239);
                label = "AUTO-FLAGGED";
                meaning = "At least one score is at or above its auto-flag threshold; the content is withheld automatically.";
            }
            case REVIEW -> {
                bg = new Color(0xFEF3C7);
                fg = new Color(0x92400E);
                label = "NEEDS HUMAN REVIEW";
                meaning = "At least one score is in the review band; a moderator decides whether the content stays up.";
            }
            default -> {
                bg = new Color(0xD1FAE5);
                fg = new Color(0x065F46);
                label = "ALLOWED";
                meaning = "Both scores are below the review thresholds, or a moderator allowed the content.";
            }
        }
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(12);
        PdfPCell c = cell(bg, 11);
        Paragraph p = new Paragraph();
        p.add(new Chunk("CURRENT DECISION   ", FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, fg)));
        p.add(new Chunk(label, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Font.NORMAL, fg)));
        c.addElement(p);
        Paragraph m = new Paragraph(meaning, FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Font.NORMAL, fg));
        m.setSpacingBefore(2);
        c.addElement(m);
        t.addCell(c);
        doc.add(t);
    }

    private void identification(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "1. Case identification");
        PdfPTable t = kvTable();
        kv(t, "Analysis ID", "#" + item.getId(), false);
        kv(t, "Provenance ID", orDash(item.getProvenanceId()), true);
        kv(t, "Analysed at", timestamp(item.getCreatedAt()), false);
        kv(t, "Re-upload of", item.getSourceProvenanceId() != null
                ? item.getSourceProvenanceId() : "None (first time this content was seen)", item.getSourceProvenanceId() != null);
        kv(t, "Pixel hash (SHA-256)", orDash(item.getContentHash()), true);
        kv(t, "Perceptual hash (pHash)", orDash(item.getPhash()), true);
        doc.add(t);
    }

    private void scores(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "2. Model scores");
        PdfPTable t = new PdfPTable(new float[] {28, 14, 30, 28});
        t.setWidthPercentage(100);
        for (String h : new String[] {"Model", "Score", "Band", "Thresholds (review / auto-flag)"}) {
            t.addCell(th(h));
        }
        scoreRow(t, "Forgery (EfficientNet-B0)", item.getForgeryScore(),
                thresholds.getForgeryReview(), thresholds.getForgeryAutoFlag());
        scoreRow(t, "Explicit content (independent)", item.getExplicitScore(),
                thresholds.getExplicitReview(), thresholds.getExplicitAutoFlag());
        doc.add(t);
        PdfPTable fam = kvTable();
        kv(fam, "Generation family", familyLabel(item), false);
        doc.add(fam);
        doc.add(note("Thresholds shown are those configured when this report was generated. Scores are model "
                + "confidences in [0, 1], not probabilities of guilt; they support, and do not replace, human judgement."));
    }

    static String familyLabel(AnalysisResult item) {
        String family = item.getGenerationFamily();
        if (family == null) {
            return "Not recorded (analysed before generation-family attribution)";
        }
        String conf = item.getFamilyConfidence() != null
                ? String.format(Locale.ROOT, " (confidence %.2f)", item.getFamilyConfidence()) : "";
        return switch (family) {
            case "gan" -> "GAN-era face swap / reenactment" + conf + ". Best-effort attribution, not a verdict.";
            case "diffusion" -> "Diffusion model (e.g. inpainting / img2img)" + conf + ". Best-effort attribution, not a verdict.";
            default -> "Unknown (not judged forged, or the attribution head was not confident)";
        };
    }

    private void scoreRow(PdfPTable t, String model, double score, double review, double autoFlag) {
        String band = score >= autoFlag ? "Auto-flag band" : score >= review ? "Review band" : "Below review threshold";
        t.addCell(td(model, VALUE));
        t.addCell(td(String.format(Locale.ROOT, "%.4f", score), MONO));
        t.addCell(td(band, score >= review ? VALUE_BOLD : VALUE));
        t.addCell(td(String.format(Locale.ROOT, "%.2f / %.2f", review, autoFlag), MONO));
    }

    private void compliance(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "3. Takedown compliance");
        PdfPTable t = kvTable();
        if (item.getComplaintType() == null) {
            kv(t, "Complaint", "None filed. This item is not subject to a takedown SLA.", false);
            kv(t, "Jurisdiction", orDash(item.getJurisdiction()), false);
            kv(t, "SLA timestamps", "Not applicable", false);
            doc.add(t);
            return;
        }
        ComplaintType type = item.getComplaintType();
        Duration window = SlaClockService.window(item);
        SlaClockService.ResolvedRule current = item.getJurisdiction() != null ? slaClock.rule(item.getJurisdiction(), type) : null;
        Duration remaining = slaClock.remaining(item);
        String outcome;
        if (item.getResolvedAt() != null) {
            outcome = remaining.isNegative()
                    ? "Resolved " + ProvenanceService.human(remaining) + " after the deadline"
                    : "Resolved " + ProvenanceService.human(remaining) + " before the deadline";
        } else {
            outcome = remaining.isNegative()
                    ? "Open, " + ProvenanceService.human(remaining) + " overdue"
                    : "Open, " + ProvenanceService.human(remaining) + " remaining";
        }
        kv(t, "Complaint type", type == ComplaintType.GOVERNMENT_ORDER
                ? "Government takedown order" : "User deepfake / NCII complaint", false);
        kv(t, "Complaint reference", orDash(item.getComplaintReference()), true);
        kv(t, "Jurisdiction", orDash(item.getJurisdiction()), false);
        kv(t, "Legal basis", item.getSlaRuleLabel() == null ? "-"
                : current != null && item.getSlaRuleLabel().equals(current.label()) && current.legalReference() != null
                        ? item.getSlaRuleLabel() + ". " + current.legalReference()
                        : item.getSlaRuleLabel(), false);
        kv(t, "SLA window", window != null ? ProvenanceService.human(window) : "-", false);
        kv(t, "Received", timestamp(item.getComplaintReceivedAt()), false);
        kv(t, "Deadline", timestamp(item.getSlaDeadline()), false);
        kv(t, "SLA status", item.getSlaStatus() != null ? item.getSlaStatus().name().replace('_', ' ') : "-", false);
        kv(t, "Escalated at", item.getEscalatedAt() != null ? timestamp(item.getEscalatedAt()) : "Not escalated", false);
        kv(t, "Resolved at", item.getResolvedAt() != null ? timestamp(item.getResolvedAt()) : "Not yet resolved", false);
        kv(t, "Outcome", outcome, false);
        doc.add(t);
    }

    private void forensics(Document doc, Panels panels) throws DocumentException, IOException {
        doc.newPage();
        section(doc, "4. Forensic panels");
        JsonNode sig = panels.signature();

        PdfPTable grid = new PdfPTable(3);
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(4);
        grid.addCell(panel(panels.heatmap(), "Grad-CAM attention",
                sig == null ? null
                        : ForensicPanelRenderer.isFlat(matrix(sig.at("/cam/grid"))) ? "No salient regions"
                        : String.format(Locale.ROOT, "Peak at x %.0f%%, y %.0f%%",
                                sig.at("/cam/peak/x").asDouble() * 100, sig.at("/cam/peak/y").asDouble() * 100),
                "Regions that drove the forgery score (red = strongest)."));
        grid.addCell(panel(panels.fft(), "FFT magnitude spectrum",
                sig != null ? String.format(Locale.ROOT, "High-frequency energy %.2f%%",
                        sig.at("/fft/high_freq_ratio").asDouble() * 100) : null,
                "Grid or star patterns can indicate resampling or generator artefacts."));
        grid.addCell(panel(panels.ela(), "Error level analysis",
                sig != null ? String.format(Locale.ROOT, "Mean %.2f | p95 %.2f | max %.0f (q%d)",
                        sig.at("/ela/mean").asDouble(), sig.at("/ela/p95").asDouble(),
                        sig.at("/ela/max").asDouble(), sig.at("/ela/quality").asInt()) : null,
                "Bright blocks recompress differently and may have been pasted in."));
        doc.add(grid);

        if (panels.profile() != null) {
            Paragraph cap = new Paragraph("Radial spectrum profile (low to high frequency; shaded half counts as high frequency)", LABEL);
            cap.setSpacingBefore(10);
            doc.add(cap);
            Image chart = Image.getInstance(panels.profile());
            chart.scaleToFit(doc.getPageSize().getWidth() - 80, 110);
            chart.setSpacingBefore(3);
            doc.add(chart);
        }
        doc.add(note("Panel source: " + panels.source().name().toLowerCase(Locale.ROOT) + ". " + panels.note()));
    }

    private PdfPCell panel(byte[] png, String title, String metric, String caption) throws IOException, DocumentException {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(4);
        if (png != null) {
            Image img = Image.getInstance(png);
            img.scaleToFit(160, 160);
            img.setAlignment(Element.ALIGN_CENTER);
            c.addElement(img);
        } else {
            PdfPTable placeholder = new PdfPTable(1);
            placeholder.setWidthPercentage(100);
            PdfPCell box = cell(SHADE, 10);
            box.setFixedHeight(150);
            box.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph p = new Paragraph("Not available", SMALL);
            p.setAlignment(Element.ALIGN_CENTER);
            box.addElement(p);
            placeholder.addCell(box);
            c.addElement(placeholder);
        }
        Paragraph t = new Paragraph(title, VALUE_BOLD);
        t.setSpacingBefore(5);
        c.addElement(t);
        if (metric != null) {
            c.addElement(new Paragraph(metric, MONO));
        }
        c.addElement(new Paragraph(caption, SMALL));
        return c;
    }

    private void auditTrail(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "5. Audit trail");
        List<ProvenanceEvent> trail = item.getProvenanceId() != null
                ? events.findByProvenanceIdOrderByOccurredAtAscIdAsc(item.getProvenanceId())
                : List.of();
        if (trail.isEmpty()) {
            doc.add(note("No audit events are recorded for this item."));
            return;
        }
        PdfPTable t = new PdfPTable(new float[] {21, 22, 13, 44});
        t.setWidthPercentage(100);
        for (String h : new String[] {"Time (UTC)", "Event", "Actor", "Detail"}) {
            t.addCell(th(h));
        }
        for (ProvenanceEvent e : trail) {
            t.addCell(td(TRAIL_TIME.format(e.getOccurredAt()), MONO));
            t.addCell(td(e.getType().name().replace('_', ' '), EVENT));
            t.addCell(td(e.getActor(), VALUE));
            t.addCell(td(e.getDetail() != null ? e.getDetail() : "", VALUE));
        }
        doc.add(t);
    }

    private void integrity(Document doc, AnalysisResult item, String reportId) throws DocumentException {
        String[] points = {
            "Media retention: the analysed media is not stored. The system keeps only hashes, scores, decisions, SLA "
                    + "metadata and the coarse forensic signature used for the panels above.",
            "Provenance: labelled copies of this media carry a signed manifest with provenance ID "
                    + orDash(item.getProvenanceId()) + ". Anyone can check it and the audit trail at /verify/"
                    + orDash(item.getProvenanceId()) + ".",
            "Chain of custody: the SHA-256 of this PDF is recorded in the item's audit trail as event REPORT GENERATED for "
                    + reportId + ". Recompute the file's SHA-256 and compare it to confirm the report is unaltered.",
            "Datasets: models were trained and evaluated only on consenting-actor research benchmarks "
                    + "(FaceForensics++, DFDC, Celeb-DF).",
        };
        PdfPTable block = new PdfPTable(1);
        block.setWidthPercentage(100);
        block.setSpacingBefore(16);
        block.setKeepTogether(true);
        PdfPCell title = new PdfPCell(new Phrase("6. Integrity and data handling", H2));
        title.setBorder(Rectangle.NO_BORDER);
        title.setPadding(0);
        title.setPaddingBottom(8);
        block.addCell(title);
        for (String point : points) {
            Paragraph p = new Paragraph();
            p.add(new Chunk("-  ", VALUE_BOLD));
            p.add(new Chunk(point, VALUE));
            p.setLeading(12.5f);
            PdfPCell cell = new PdfPCell();
            cell.addElement(p);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(0);
            cell.setPaddingBottom(4);
            block.addCell(cell);
        }
        doc.add(block);
    }

    private void signatureBlock(Document doc, ReportSigningService.Signed signed) throws DocumentException {
        PdfPTable block = new PdfPTable(1);
        block.setWidthPercentage(100);
        block.setSpacingBefore(14);
        block.setKeepTogether(true);
        PdfPCell title = new PdfPCell(new Phrase("7. Digital signature", H2));
        title.setBorder(Rectangle.NO_BORDER);
        title.setPadding(0);
        title.setPaddingBottom(6);
        block.addCell(title);

        PdfPTable t = kvTable();
        kv(t, "Algorithm", ReportSigningService.ALGORITHM + " over the SHA-256 of the signed fields", false);
        kv(t, "Signed at", timestamp(signed.signedAt()), false);
        kv(t, "Signing key ID", signed.keyId(), true);
        kv(t, "Signed content SHA-256", signed.contentHash(), true);
        kv(t, "Signature (base64)", signed.signature(), true);
        kv(t, "Verify", "GET /api/verify-report/" + signed.reportId() + "  (VALID / TAMPERED / NOT_FOUND)", true);
        PdfPCell holder = new PdfPCell(t);
        holder.setBorder(Rectangle.NO_BORDER);
        holder.setPadding(0);
        block.addCell(holder);

        PdfPCell scope = new PdfPCell(note("Signed fields: report ID and time, analysis and provenance IDs, decision, both scores, "
                + "generation family, hashes, jurisdiction, complaint and SLA timestamps. Any later change to them makes verification "
                + "return TAMPERED. Scope: this uses a development key generated by the server. It demonstrates the mechanism; legal "
                + "admissibility would need the key held in a KMS/HSM and an RFC 3161 timestamp from a trusted third-party authority."));
        scope.setBorder(Rectangle.NO_BORDER);
        scope.setPadding(0);
        block.addCell(scope);
        doc.add(block);
    }

    // ------------------------------------------------------------------ layout helpers

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

    private static PdfPCell th(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, TH));
        c.setBackgroundColor(SHADE);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(5);
        return c;
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

    private String timestamp(Instant instant) {
        if (instant == null) {
            return "-";
        }
        String local = DateTimeFormatter.ofPattern("HH:mm:ss zzz", Locale.ENGLISH).withZone(localZone).format(instant);
        return UTC_TIME.format(instant) + "  (" + local + ")";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class Footer extends PdfPageEventHelper {
        private final String reportId;
        private final AnalysisResult item;

        Footer(String reportId, AnalysisResult item) {
            this.reportId = reportId;
            this.item = item;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float y = document.bottom() - 22;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(reportId + "  |  Analysis #" + item.getId() + "  |  Confidential: prepared for legal / compliance review", SMALL),
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
