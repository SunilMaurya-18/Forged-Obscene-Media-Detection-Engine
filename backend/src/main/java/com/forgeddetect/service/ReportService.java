package com.forgeddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Appeal;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.ModerationAuditEvent;
import com.forgeddetect.model.ProvenanceEvent;
import com.forgeddetect.model.ProvenanceEventType;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.AppealRepository;
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
 * One-click evidentiary PDF per analysis for legal, compliance, and affected people.
 *
 * <p>Layout prioritises a plain-language summary and fused score + forensic explanation, then the
 * technical record and Ed25519 signature. Forensic panels come from the coarse signature stored at
 * analysis time, or, when a moderator re-supplies the original, from full-resolution renders
 * verified against the stored pixel hash. The original is never retained. Items at or above the
 * explicit review threshold never embed the image itself: their Grad-CAM and ELA panels always come
 * from the coarse signature.
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

    /** An original was attached for media whose panels can't be re-rendered from a file (video). */
    public static class UnsupportedOriginalException extends RuntimeException {
        public UnsupportedOriginalException(String message) {
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

    private static final Color INK = new Color(0x0F1114);
    private static final Color MUTED = new Color(0x5F666E);
    private static final Color LINE = new Color(0xD8DCE1);
    private static final Color BRAND = new Color(0xC45C3A);
    private static final Color COVER = new Color(0x12151A);
    private static final Color COVER_ACCENT = new Color(0xE8E4DF);
    private static final Color SHADE = new Color(0xF6F5F2);
    private static final Color CARD = new Color(0xFFFFFF);
    private static final Color VERIFY_BG = new Color(0xE8F6EE);
    private static final Color VERIFY_FG = new Color(0x0F5C38);
    private static final Color LIMIT_BG = new Color(0xFBF3EA);
    private static final Color LIMIT_FG = new Color(0x8A3B12);
    private static final Color RED = new Color(0xC53026);
    private static final Color GREEN = new Color(0x15803D);
    private static final Color AMBER = new Color(0xA16207);

    private static final Font COVER_EYEBROW = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.NORMAL, new Color(0xB8B4AE));
    private static final Font COVER_TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 26, Font.NORMAL, Color.WHITE);
    private static final Font COVER_OUTCOME = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Font.NORMAL, Color.WHITE);
    private static final Font COVER_BODY = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, Font.NORMAL, COVER_ACCENT);
    private static final Font COVER_META = FontFactory.getFont(FontFactory.COURIER, 9, Font.NORMAL, new Color(0x9AA1A9));
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Font.NORMAL, INK);
    private static final Font H3 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, Font.NORMAL, INK);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.NORMAL, INK);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Font.NORMAL, MUTED);
    private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Font.NORMAL, INK);
    private static final Font VALUE_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, Font.NORMAL, INK);
    private static final Font MONO = FontFactory.getFont(FontFactory.COURIER, 8.5f, Font.NORMAL, INK);
    private static final Font SCORE_NUM = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 28, Font.NORMAL, INK);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final Font EVENT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Font.NORMAL, INK);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, MUTED);
    private static final Font H1 = COVER_TITLE;
    private static final Font H1_SUB = COVER_BODY;
    private static final Font BAND_LABEL = LABEL;
    private static final Font BAND_VALUE = VALUE_BOLD;
    private static final Color BAND_BG = COVER;

    private final AnalysisResultRepository results;
    private final ProvenanceEventRepository events;
    private final ProvenanceService provenance;
    private final SlaClockService slaClock;
    private final DecisionThresholdProperties thresholds;
    private final MlClientService mlClient;
    private final ForensicPanelRenderer renderer;
    private final ObjectMapper mapper;
    private final ReportSigningService signing;
    private final AppealRepository appeals;
    private final ModerationAuditService audit;
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
            AppealRepository appeals,
            ModerationAuditService audit,
            Clock clock,
            @Value("${report.local-zone:Asia/Kolkata}") String localZone) {
        this.appeals = appeals;
        this.audit = audit;
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
        return new Report(reportId, "decision-record-" + id + ".pdf", pdf, sha, panels.source(), signed);
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

        if (original != null && original.length > 0 && item.isVideo()) {
            throw new UnsupportedOriginalException("Full-resolution panels can only be rendered from an image. For video, "
                    + "the report uses the peak frame's signature captured at analysis time; generate it without a file.");
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

        // No spatial grids and no re-attached original: show stored fingerprints instead of blank cards.
        if (source == PanelSource.NONE) {
            heatmap = renderer.phashMosaic(item.getPhash(), 360);
            fft = renderer.scoreMeters(
                    item.getForgeryScore(),
                    item.getExplicitScore(),
                    thresholds.getForgeryReview(),
                    thresholds.getForgeryAutoFlag(),
                    thresholds.getExplicitReview(),
                    thresholds.getExplicitAutoFlag(),
                    360);
            ela = renderer.hashPattern(item.getContentHash(), 360);
            note = "Spatial forensic panels (Grad-CAM, FFT, ELA) were not stored for this analysis. "
                    + "The cards below are drawn only from fingerprints and scores kept instead of the media "
                    + "(perceptual hash mosaic, score meters, content-hash pattern). Attach the original at report "
                    + "time for full forensic panels when the file is still available. No media was retained.";
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
        // Wider margins + cover-first layout (v2 design — not the old dense table dump)
        Document doc = new Document(PageSize.A4, 48, 48, 48, 56);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer(reportId, item));
            doc.addTitle("Decision record " + reportId);
            doc.addSubject("Analysis #" + item.getId() + " / provenance " + item.getProvenanceId());
            doc.addAuthor("Forged Media Detection");
            doc.addCreator("Forged Media Detection decision records v2");
            doc.addHeader("FMD-Report-Id", reportId);
            doc.addHeader("FMD-Report-Layout", "decision-record-v2");
            doc.addHeader("FMD-Signature-Algorithm", ReportSigningService.ALGORITHM);
            doc.addHeader("FMD-Signature", signed.signature());
            doc.addHeader("FMD-Signed-Content-SHA256", signed.contentHash());
            doc.addHeader("FMD-Signing-Key-Id", signed.keyId());
            doc.addHeader("FMD-Signed-At", signed.signedAt().toString());
            doc.open();

            coverPage(doc, item, reportId, now, preparedBy, signed);
            doc.newPage();
            atAGlance(doc, item, signed);
            scores(doc, item);
            forensics(doc, panels, item);
            compliance(doc, item);
            moderationAndAppeal(doc, item);
            identification(doc, item);
            contentCredentials(doc, item);
            auditTrail(doc, item);
            integrity(doc, item, reportId);
            glossary(doc);
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

    /** Full first page: dark cover, huge outcome, story in plain words — looks nothing like the old header band. */
    private void coverPage(
            Document doc, AnalysisResult item, String reportId, Instant now, String preparedBy, ReportSigningService.Signed signed)
            throws DocumentException {
        PdfPTable hero = new PdfPTable(1);
        hero.setWidthPercentage(100);
        PdfPCell bg = cell(COVER, 28);
        bg.setMinimumHeight(720);

        Paragraph eyebrow = new Paragraph("FORGED MEDIA DETECTION  ·  TRUST & SAFETY", COVER_EYEBROW);
        bg.addElement(eyebrow);

        Paragraph title = new Paragraph("Decision record", COVER_TITLE);
        title.setSpacingBefore(18);
        bg.addElement(title);

        Paragraph aka = new Paragraph("Evidentiary Analysis Report  ·  confidential", COVER_EYEBROW);
        aka.setSpacingBefore(6);
        bg.addElement(aka);

        Color outcomeColor = switch (item.getDecision()) {
            case AUTO_FLAG -> new Color(0xF07167);
            case REVIEW -> new Color(0xE8C27A);
            default -> new Color(0x4ECF7A);
        };
        Paragraph outcome = new Paragraph(plainOutcomeTitle(item.getDecision()), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Font.NORMAL, outcomeColor));
        outcome.setSpacingBefore(28);
        bg.addElement(outcome);
        Paragraph code = new Paragraph("(" + technicalOutcomeCode(item.getDecision()) + ")", COVER_EYEBROW);
        code.setSpacingBefore(4);
        bg.addElement(code);

        Paragraph story = new Paragraph(summaryParagraph(item, preparedBy), COVER_BODY);
        story.setSpacingBefore(22);
        story.setLeading(15f);
        bg.addElement(story);

        Paragraph meta = new Paragraph();
        meta.setSpacingBefore(28);
        meta.add(new Chunk(reportId + "\n", COVER_META));
        meta.add(new Chunk("Generated  " + timestamp(now) + "\n", COVER_META));
        meta.add(new Chunk("Prepared by  " + preparedBy + " (moderator)\n", COVER_META));
        meta.add(new Chunk("Verify  /api/verify-report/" + signed.reportId(), COVER_META));
        bg.addElement(meta);

        Paragraph foot = new Paragraph("Page 1 of this file is a cover for people. Technical detail starts on the next page.", COVER_EYEBROW);
        foot.setSpacingBefore(36);
        bg.addElement(foot);

        hero.addCell(bg);
        doc.add(hero);
    }

    private static String plainOutcomeTitle(Decision decision) {
        return switch (decision) {
            case AUTO_FLAG -> "REMOVED AUTOMATICALLY";
            case REVIEW -> "WAITING FOR A HUMAN DECISION";
            default -> "LEFT UP (ALLOWED)";
        };
    }

    private static String technicalOutcomeCode(Decision decision) {
        return switch (decision) {
            case AUTO_FLAG -> "AUTO-FLAGGED";
            case REVIEW -> "NEEDS HUMAN REVIEW";
            default -> "ALLOWED";
        };
    }

    /** Second page opener: verify + limits cards, not a form header. */
    private void atAGlance(Document doc, AnalysisResult item, ReportSigningService.Signed signed) throws DocumentException {
        Paragraph h = new Paragraph("What this report is", H2);
        h.setSpacingAfter(8);
        doc.add(h);
        Paragraph lead = new Paragraph(
                "This is a signed decision record for analysis #" + item.getId() + ". "
                        + "It explains the outcome in plain language, shows the model scores and forensic panels that informed it, "
                        + "then lists the technical identifiers lawyers and investigators need.",
                BODY);
        lead.setLeading(14.5f);
        lead.setSpacingAfter(12);
        doc.add(lead);

        verifyBox(doc, signed);
        limitsDisclaimer(doc);

        PdfPTable roadmap = new PdfPTable(3);
        roadmap.setWidthPercentage(100);
        roadmap.setSpacingBefore(14);
        roadmap.setSpacingAfter(4);
        roadmap.addCell(roadmapCard("01", "Scores", "How strong each check was"));
        roadmap.addCell(roadmapCard("02", "Forensics", "Where the model looked"));
        roadmap.addCell(roadmapCard("03", "Record", "IDs, trail, signature"));
        doc.add(roadmap);
    }

    private static PdfPCell roadmapCard(String num, String title, String blurb) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(LINE);
        c.setBorderWidth(1f);
        c.setBackgroundColor(SHADE);
        c.setPadding(12);
        c.setPaddingRight(10);
        c.addElement(new Paragraph(num, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Font.NORMAL, BRAND)));
        Paragraph t = new Paragraph(title, H3);
        t.setSpacingBefore(6);
        c.addElement(t);
        Paragraph b = new Paragraph(blurb, SMALL);
        b.setSpacingBefore(3);
        c.addElement(b);
        return c;
    }

    private String summaryParagraph(AnalysisResult item, String preparedBy) {
        String media = item.isVideo() ? "a video (scored frame by frame; no frames were kept)" : "an image";
        String outcome = plainDecisionSentence(item.getDecision());
        String who = item.getDecidedBy() != null
                ? "A moderator (" + item.getDecidedBy() + ") recorded the final decision"
                : "Routing was automatic (no separate moderator override)";
        String sla = item.getComplaintType() == null
                ? "No formal takedown complaint was attached to this analysis."
                : "A " + (item.getComplaintType() == ComplaintType.GOVERNMENT_ORDER
                        ? "government takedown order"
                        : "user complaint about deepfake / non-consensual imagery")
                        + " started a legal deadline"
                        + (item.getJurisdiction() != null ? " under jurisdiction " + item.getJurisdiction() : "")
                        + ".";
        return "This document records what happened when " + media + " was checked by Forged Media Detection. "
                + outcome + " " + who + ". Prepared by " + preparedBy + ". " + sla
                + " The original media is not stored with this report—only fingerprints (hashes), scores, decisions and timestamps.";
    }

    private static String plainDecisionSentence(Decision decision) {
        return switch (decision) {
            case AUTO_FLAG -> "Outcome: the content was removed automatically (AUTO-FLAGGED).";
            case REVIEW -> "Outcome: the content needs a human decision (NEEDS HUMAN REVIEW).";
            default -> "Outcome: the content was left up (ALLOWED).";
        };
    }

    /** How anyone can check the report without trusting the PDF layout alone. */
    private void verifyBox(Document doc, ReportSigningService.Signed signed) throws DocumentException {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(12);
        PdfPCell c = cell(VERIFY_BG, 11);
        Paragraph title = new Paragraph("How to verify this report", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.NORMAL, VERIFY_FG));
        c.addElement(title);
        Paragraph body = new Paragraph(
                "Anyone can check that the facts in this report still match the signed record. "
                        + "Open GET /api/verify-report/" + signed.reportId()
                        + " (or the Verify page in the console). Results are VALID, TAMPERED (with changed fields named), or NOT_FOUND. "
                        + "Public keys, including retired ones, are at /api/verify-report/keys. "
                        + "You do not need a moderator account.",
                FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Font.NORMAL, VERIFY_FG));
        body.setSpacingBefore(3);
        body.setLeading(11.5f);
        c.addElement(body);
        Paragraph id = new Paragraph();
        id.setSpacingBefore(4);
        id.add(new Chunk("Report ID  ", FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, VERIFY_FG)));
        id.add(new Chunk(signed.reportId(), FontFactory.getFont(FontFactory.COURIER_BOLD, 9, Font.NORMAL, VERIFY_FG)));
        c.addElement(id);
        t.addCell(c);
        doc.add(t);
    }

    private void limitsDisclaimer(Document doc) throws DocumentException {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(8);
        PdfPCell c = cell(LIMIT_BG, 10);
        Paragraph title = new Paragraph("Important limits (read this)", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Font.NORMAL, LIMIT_FG));
        c.addElement(title);
        Paragraph body = new Paragraph(
                "Model scores are automated estimates that support human judgement—they are not a court finding of who created "
                        + "a deepfake or whether a crime occurred. Forensic panels (Grad-CAM, FFT, ELA) help explain where the "
                        + "forgery model looked and what compression patterns appear; they do not prove manipulation on their own. "
                        + "This report is process evidence: what the system recorded, decided and when.",
                FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, LIMIT_FG));
        body.setSpacingBefore(2);
        body.setLeading(11f);
        c.addElement(body);
        t.addCell(c);
        doc.add(t);
    }

    private void identification(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "05  ·  Technical identifiers");
        PdfPTable t = kvTable();
        kv(t, "Analysis ID", "#" + item.getId(), false);
        kv(t, "Provenance ID", orDash(item.getProvenanceId()), true);
        kv(t, "Media type", item.isVideo() ? "Video (scored frame by frame; no frame retained)" : "Image", false);
        kv(t, "Analysed at", timestamp(item.getCreatedAt()), false);
        kv(t, "Re-upload of", item.getSourceProvenanceId() != null
                ? item.getSourceProvenanceId() : "None (first time this content was seen)", item.getSourceProvenanceId() != null);
        kv(t, "Pixel hash (SHA-256)", orDash(item.getContentHash()), true);
        kv(t, "Perceptual hash (pHash)", orDash(item.getPhash()), true);
        doc.add(t);
    }

    private void scores(Document doc, AnalysisResult item) throws DocumentException, IOException {
        section(doc, "01  ·  Model scores");
        doc.add(note("Two independent checks, scored from 0 to 1. Higher means more concerning for that check. "
                + "Crossing the review line sends the item to a person; crossing the auto-remove line withholds it."));

        PdfPTable tiles = new PdfPTable(2);
        tiles.setWidthPercentage(100);
        tiles.setSpacingBefore(10);
        tiles.setSpacingAfter(8);
        tiles.addCell(scoreTile(
                "Signs of AI editing",
                item.getForgeryScore(),
                thresholds.getForgeryReview(),
                thresholds.getForgeryAutoFlag()));
        tiles.addCell(scoreTile(
                "Explicit content (separate model)",
                item.getExplicitScore(),
                thresholds.getExplicitReview(),
                thresholds.getExplicitAutoFlag()));
        doc.add(tiles);

        card(doc, "Likely generation style", familyLabel(item));
        if (item.isVideo()) {
            videoTimeline(doc, item);
        }
        doc.add(note("Thresholds are those configured when this report was generated. Scores support human judgement; "
                + "they are not probabilities of guilt."));
    }

    private PdfPCell scoreTile(String title, double score, double review, double autoFlag) {
        String band = score >= autoFlag ? "Auto-flag band" : score >= review ? "Review band" : "Below review threshold";
        Color accent = score >= autoFlag ? RED : score >= review ? AMBER : GREEN;
        PdfPCell c = new PdfPCell();
        c.setBorderColor(LINE);
        c.setBorderWidth(1.2f);
        c.setBackgroundColor(CARD);
        c.setPadding(14);
        c.setPaddingTop(12);
        Paragraph t = new Paragraph(title, LABEL);
        c.addElement(t);
        Paragraph num = new Paragraph(String.format(Locale.ROOT, "%.2f", score), SCORE_NUM);
        num.setSpacingBefore(6);
        c.addElement(num);
        // Keep four-decimal form in the PDF for tests / auditors
        Paragraph exact = new Paragraph(String.format(Locale.ROOT, "%.4f   ·   %s", score, band), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Font.NORMAL, accent));
        exact.setSpacingBefore(2);
        c.addElement(exact);
        Paragraph thr = new Paragraph(
                String.format(Locale.ROOT, "Review line %.2f   ·   Auto-remove %.2f", review, autoFlag), SMALL);
        thr.setSpacingBefore(8);
        c.addElement(thr);
        // Visual meter (20 segments)
        PdfPTable meter = new PdfPTable(20);
        meter.setWidthPercentage(100);
        meter.setSpacingBefore(10);
        int filled = (int) Math.round(Math.min(1.0, Math.max(0.0, score)) * 20);
        for (int i = 0; i < 20; i++) {
            PdfPCell seg = new PdfPCell();
            seg.setFixedHeight(8);
            seg.setBorder(Rectangle.NO_BORDER);
            seg.setBackgroundColor(i < filled ? accent : SHADE);
            meter.addCell(seg);
        }
        c.addElement(meter);
        return c;
    }

    private void card(Document doc, String title, String body) throws DocumentException {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(8);
        PdfPCell c = new PdfPCell();
        c.setBorderColor(LINE);
        c.setBorderWidth(1f);
        c.setBackgroundColor(SHADE);
        c.setPadding(12);
        c.addElement(new Paragraph(title, LABEL));
        Paragraph b = new Paragraph(body, BODY);
        b.setSpacingBefore(4);
        b.setLeading(13.5f);
        c.addElement(b);
        t.addCell(c);
        doc.add(t);
    }

    private void videoTimeline(Document doc, AnalysisResult item) throws DocumentException, IOException {
        JsonNode tl = parseSignature(item.getFrameTimeline());
        if (tl == null) {
            doc.add(note("Video: no per-frame timeline was recorded."));
            return;
        }
        JsonNode frames = tl.path("frames");
        double[] forgery = new double[frames.size()];
        double[] explicit = new double[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            forgery[i] = frames.get(i).path(1).asDouble();
            explicit[i] = frames.get(i).path(2).asDouble();
        }
        PdfPTable t = kvTable();
        kv(t, "Frames sampled", String.format(Locale.ROOT, "%d at %.2f fps%s%s", frames.size(), tl.path("fps").asDouble(),
                tl.has("durationSeconds") ? String.format(Locale.ROOT, " over %.1f s", tl.path("durationSeconds").asDouble()) : "",
                tl.path("truncated").asBoolean() ? " (capped; later frames not scored)" : ""), false);
        kv(t, "Routed on", "Forgery: " + aggregateLabel(tl, "forgery") + "; explicit: " + aggregateLabel(tl, "explicit"), false);
        if (tl.has("peak")) {
            kv(t, "Peak frame", String.format(Locale.ROOT, "#%d at %.2f s (source of the forensic panels)",
                    tl.path("peak").path("index").asInt(), tl.path("peak").path("t").asDouble()), false);
        }
        doc.add(t);
        if (forgery.length > 0) {
            Paragraph cap = new Paragraph("Frame-score strip (read left to right over time): coloured bars = forgery score "
                    + "(green / amber / red by band), line = explicit score, dashed lines = review and auto-remove thresholds, "
                    + "outlined bar = peak frame used for the forensic panels", LABEL);
            cap.setSpacingBefore(8);
            doc.add(cap);
            Image strip = Image.getInstance(renderer.frameStrip(forgery, explicit, thresholds.getForgeryReview(),
                    thresholds.getForgeryAutoFlag(), tl.path("peak").path("index").asInt(-1), 1000, 160));
            strip.scaleToFit(doc.getPageSize().getWidth() - 80, 90);
            strip.setSpacingBefore(3);
            doc.add(strip);
        }
    }

    private static String aggregateLabel(JsonNode tl, String score) {
        String mode = tl.path("routing").path(score).asText("");
        int k = tl.path("k").asInt(3);
        String label = "MAX".equals(mode) ? "max over frames" : "mean of top-" + k + " frames";
        JsonNode agg = tl.path("aggregate").path(score);
        if (agg.isMissingNode()) {
            return label;
        }
        return label + String.format(Locale.ROOT, " (max %.4f, top-%d mean %.4f)",
                agg.path("max").asDouble(), k, agg.path("topkMean").asDouble());
    }

    private void moderationAndAppeal(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "04  ·  Moderation, appeals and what happens next");
        PdfPTable t = kvTable();
        kv(t, "Model decision", item.getModelDecision() != null ? item.getModelDecision().name() : item.getDecision().name(), false);
        kv(t, "Decided by", item.getDecidedBy() != null
                ? item.getDecidedBy() + (item.getReviewedAt() != null ? ", " + timestamp(item.getReviewedAt()) : "")
                : "Automated routing only (no moderator decision)", false);
        kv(t, "Assignment", item.getAssignedTo() != null
                ? item.getAssignedTo() + (item.getAssignedTeam() != null ? " (team " + item.getAssignedTeam() + ")" : "")
                : item.getRoutedTeam() != null ? "Routed to team " + item.getRoutedTeam() + " (unclaimed)" : "Unassigned", false);
        kv(t, "Senior escalation", item.getSeniorEscalatedAt() != null
                ? timestamp(item.getSeniorEscalatedAt()) + " by " + orDash(item.getSeniorEscalatedBy())
                        + (item.getSeniorEscalationReason() != null ? ": " + item.getSeniorEscalationReason() : "")
                        + (item.getSeniorResolvedAt() != null
                                ? "; resolved by senior " + orDash(item.getSeniorResolvedBy()) + " at " + timestamp(item.getSeniorResolvedAt())
                                : "; open (senior decision required)")
                : "Not escalated", false);
        kv(t, "Second review", item.getPendingDecision() != null
                ? "Change to " + item.getPendingDecision() + " proposed by " + orDash(item.getPendingProposedBy()) + " at "
                        + timestamp(item.getPendingProposedAt()) + "; awaiting a second moderator"
                : "None pending", false);
        kv(t, "Uploader notice", orDash(item.getNoticeNumber()), true);

        Appeal appeal = appeals.findByAnalysisId(item.getId()).orElse(null);
        if (appeal == null) {
            kv(t, "Appeal", "None filed", false);
        } else {
            kv(t, "Appeal", appeal.getCaseNumber() + ": " + appeal.getStatus().name()
                    + (appeal.getReinstatedAt() != null ? " (content reinstated " + timestamp(appeal.getReinstatedAt()) + ")" : ""), false);
            kv(t, "Appeal filed", timestamp(appeal.getFiledAt()), false);
            kv(t, "Uploader statement", appeal.getStatement(), false);
            kv(t, "Contested decision", appeal.getContestedDecision().name() + " by " + appeal.getContestedBy(), false);
            kv(t, "Appeal deadline", timestamp(appeal.getSlaDeadline())
                    + (appeal.getSlaRuleLabel() != null ? " (" + appeal.getSlaRuleLabel() + ")" : ""), false);
            kv(t, "Appeal SLA status", appeal.getSlaStatus().name().replace('_', ' '), false);
            kv(t, "Appeal decided", appeal.getDecidedAt() != null
                    ? timestamp(appeal.getDecidedAt()) + " by " + orDash(appeal.getDecidedBy()) : "Not yet decided", false);
            kv(t, "Statement of reasons", orDash(appeal.getOutcomeReason()), false);
        }
        doc.add(t);

        List<ModerationAuditEvent> log = audit.forItem(item.getId());
        if (!log.isEmpty()) {
            Paragraph cap = new Paragraph("Moderation log (internal; names the moderator for each action)", LABEL);
            cap.setSpacingBefore(8);
            doc.add(cap);
            PdfPTable l = new PdfPTable(new float[] {21, 16, 21, 42});
            l.setWidthPercentage(100);
            for (String h : new String[] {"Time (UTC)", "Moderator", "Action", "Detail"}) {
                l.addCell(th(h));
            }
            for (ModerationAuditEvent e : log) {
                l.addCell(td(TRAIL_TIME.format(e.getOccurredAt()), MONO));
                l.addCell(td(e.getActor(), VALUE));
                l.addCell(td(e.getAction().name().replace('_', ' '), EVENT));
                l.addCell(td(e.getDetail() != null ? e.getDetail() : "", VALUE));
            }
            doc.add(l);
        }
        doc.add(note("Changes away from AUTO-FLAGGED need a second moderator; appeals are decided, and their outcome "
                + "second-reviewed, by someone other than the people who made or upheld the contested decision. "
                + "The public audit trail below records these steps without naming moderators."));
    }

    private void contentCredentials(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "06  ·  Content credentials (C2PA)");
        JsonNode c = parseSignature(item.getC2paManifest());
        PdfPTable t = kvTable();
        if (c == null) {
            kv(t, "Manifest", "None. The upload carried no C2PA Content Credentials.", false);
            doc.add(t);
            doc.add(note("Absence of credentials is not evidence of manipulation; most capture devices and platforms "
                    + "do not yet write or preserve them."));
            return;
        }
        String status = c.path("status").asText("UNKNOWN");
        kv(t, "Validation", switch (status) {
            case "TRUSTED" -> "TRUSTED: signature valid, signer on the trust list";
            case "VALID" -> "VALID: signature and content hashes check out";
            case "VALID_UNTRUSTED" -> "VALID (untrusted signer): hashes check out, signer not on the trust list";
            case "INVALID" -> "INVALID: the manifest does not match the content or its signature failed";
            default -> status;
        }, false);
        JsonNode signer = c.path("signer");
        if (!signer.isMissingNode() && !signer.isNull()) {
            kv(t, "Signer", orDash(signer.path("common_name").asText(null))
                    + (signer.hasNonNull("issuer") ? " (issued by " + signer.path("issuer").asText() + ")" : ""), false);
            kv(t, "Signed at", orDash(signer.path("signed_at").asText(null)), false);
        }
        kv(t, "Claim generator", orDash(c.path("claim_generator").asText(null)), false);
        kv(t, "AI-generated per manifest", c.path("ai_generated").asBoolean(false)
                ? "Yes (digitalSourceType declares trained algorithmic media)" : "Not declared", false);
        StringBuilder actions = new StringBuilder();
        for (JsonNode a : c.path("actions")) {
            if (actions.length() > 0) {
                actions.append("; ");
            }
            actions.append(a.path("action").asText("?").replace("c2pa.", ""));
            if (a.hasNonNull("software_agent")) {
                actions.append(" (").append(a.path("software_agent").asText()).append(')');
            }
        }
        kv(t, "Edit actions", actions.length() > 0 ? actions.toString() : "None recorded", false);
        kv(t, "Manifests in chain", String.valueOf(c.path("manifest_count").asInt(1)), false);
        if (c.hasNonNull("error")) {
            kv(t, "Read error", c.path("error").asText(), true);
        }
        if (c.path("failures").size() > 0) {
            StringBuilder f = new StringBuilder();
            for (JsonNode x : c.path("failures")) {
                f.append(f.length() > 0 ? "; " : "").append(x.path("code").asText(x.asText()));
            }
            kv(t, "Validation failures", f.toString(), true);
        }
        doc.add(t);
        doc.add(note("C2PA manifests are self-declared by whoever signed them; a valid manifest proves who signed which claims, "
                + "not that the claims are true. Our own labelled copies carry the HMAC-signed XMP label plus a C2PA manifest."));
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

    private void compliance(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "03  ·  Legal deadline (if a complaint was filed)");
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

    private void forensics(Document doc, Panels panels, AnalysisResult item) throws DocumentException, IOException {
        doc.newPage();
        boolean fingerprintOnly = panels.source() == PanelSource.NONE;
        section(doc, fingerprintOnly ? "02  ·  Stored fingerprints (no spatial forensics)" : "02  ·  Forensic panels");
        Paragraph intro = new Paragraph(
                fingerprintOnly
                        ? "This analysis predates signature capture (or had no grids stored). Raw media is never kept, "
                                + "so the cards below show only fingerprints and scores that were retained — not the image, "
                                + "and not Grad-CAM / FFT / ELA. Attach the original when downloading to obtain full panels."
                        : "Each panel below is a full-width card: the image on the left, a plain-language caption on the right. "
                                + "Together with the scores, they explain the decision. They do not by themselves prove that media was forged."
                                + (item.isVideo()
                                        ? " For video, panels come from the peak frame (highest forgery score on the timeline)."
                                        : ""),
                BODY);
        intro.setLeading(14f);
        intro.setSpacingAfter(10);
        doc.add(intro);

        if (fingerprintOnly) {
            String phash = item.getPhash() == null ? "-" : item.getPhash();
            forensicCard(doc, panels.heatmap(), "Perceptual hash mosaic",
                    phash,
                    "In plain terms: an 8×8 map of the 64-bit perceptual fingerprint stored at analysis time. "
                            + "Light and dark cells are hash bits — useful to recognise the same file again, not a picture of the media.");
            forensicCard(doc, panels.fft(), "Score meters",
                    String.format(Locale.ROOT, "Forgery %.4f  ·  Explicit %.4f",
                            item.getForgeryScore(), item.getExplicitScore()),
                    "In plain terms: the two model scores that drove routing, with amber review and red auto-remove guides. "
                            + "Top bar = forgery, bottom = explicit. Drawn from stored numbers only.");
            String hashMetric = item.getContentHash() == null || item.getContentHash().isBlank()
                    ? "No content hash on this row"
                    : (item.getContentHash().length() > 24
                            ? item.getContentHash().substring(0, 24) + "…"
                            : item.getContentHash());
            forensicCard(doc, panels.ela(), "Content-hash pattern",
                    hashMetric,
                    "In plain terms: a pattern derived from the pixel SHA-256 (or a fold of the stored id when hash is missing). "
                            + "It binds this report to one exact file without showing any pixels.");
        } else {
            JsonNode sig = panels.signature();
            forensicCard(doc, panels.heatmap(), "Grad-CAM attention",
                    sig == null ? null
                            : ForensicPanelRenderer.isFlat(matrix(sig.at("/cam/grid"))) ? "No salient regions"
                            : String.format(Locale.ROOT, "Peak at x %.0f%%, y %.0f%%",
                                    sig.at("/cam/peak/x").asDouble() * 100, sig.at("/cam/peak/y").asDouble() * 100),
                    "In plain terms: warmer colours show where the editing-detection model paid most attention. "
                            + "Red = strongest influence on the forgery score—not proof of a fake face on its own.");
            forensicCard(doc, panels.fft(), "FFT magnitude spectrum",
                    sig != null ? String.format(Locale.ROOT, "High-frequency energy %.2f%%",
                            sig.at("/fft/high_freq_ratio").asDouble() * 100) : null,
                    "In plain terms: a view of fine detail energy. Grid or star-like patterns can hint at resizing or "
                            + "generator artefacts. A quiet spectrum does not mean the image is genuine.");
            forensicCard(doc, panels.ela(), "Error level analysis",
                    sig != null ? String.format(Locale.ROOT, "Mean %.2f | p95 %.2f | max %.0f (q%d)",
                            sig.at("/ela/mean").asDouble(), sig.at("/ela/p95").asDouble(),
                            sig.at("/ela/max").asDouble(), sig.at("/ela/quality").asInt()) : null,
                    "In plain terms: bright patches recompress differently from the rest and may have been edited or pasted. "
                            + "Uneven lighting or heavy compression can also light up.");
        }

        if (panels.profile() != null) {
            PdfPTable wrap = new PdfPTable(1);
            wrap.setWidthPercentage(100);
            wrap.setSpacingBefore(8);
            PdfPCell c = new PdfPCell();
            c.setBorderColor(LINE);
            c.setBorderWidth(1f);
            c.setPadding(12);
            c.addElement(new Paragraph("Radial spectrum profile", H3));
            Paragraph cap = new Paragraph(
                    "Low to high frequency (shaded half = high frequency). Complements the FFT panel.", SMALL);
            cap.setSpacingBefore(4);
            c.addElement(cap);
            Image chart = Image.getInstance(panels.profile());
            chart.scaleToFit(doc.getPageSize().getWidth() - 120, 100);
            chart.setSpacingBefore(8);
            c.addElement(chart);
            wrap.addCell(c);
            doc.add(wrap);
        }

        card(doc, "Panel source: " + panels.source().name().toLowerCase(Locale.ROOT), panels.note());
    }

    private void forensicCard(Document doc, byte[] png, String title, String metric, String caption)
            throws IOException, DocumentException {
        PdfPTable row = new PdfPTable(new float[] {38, 62});
        row.setWidthPercentage(100);
        row.setSpacingBefore(8);

        PdfPCell media = new PdfPCell();
        media.setBorderColor(LINE);
        media.setBorderWidth(1f);
        media.setBackgroundColor(COVER);
        media.setPadding(10);
        media.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (png != null) {
            Image img = Image.getInstance(png);
            img.scaleToFit(180, 180);
            img.setAlignment(Element.ALIGN_CENTER);
            media.addElement(img);
        } else {
            Paragraph p = new Paragraph("Not available", FontFactory.getFont(FontFactory.HELVETICA, 9, Font.NORMAL, Color.WHITE));
            p.setAlignment(Element.ALIGN_CENTER);
            media.addElement(p);
        }
        row.addCell(media);

        PdfPCell text = new PdfPCell();
        text.setBorderColor(LINE);
        text.setBorderWidth(1f);
        text.setBackgroundColor(CARD);
        text.setPadding(14);
        text.addElement(new Paragraph(title, H3));
        if (metric != null) {
            Paragraph m = new Paragraph(metric, MONO);
            m.setSpacingBefore(6);
            text.addElement(m);
        }
        Paragraph cap = new Paragraph(caption, BODY);
        cap.setSpacingBefore(8);
        cap.setLeading(13.5f);
        text.addElement(cap);
        row.addCell(text);
        doc.add(row);
    }

    private void auditTrail(Document doc, AnalysisResult item) throws DocumentException {
        section(doc, "07  ·  Audit trail (public timeline)");
        doc.add(note("Append-only events tied to the provenance ID. Actor names here are roles or system labels, not a full "
                + "personnel file."));
        List<ProvenanceEvent> trail = item.getProvenanceId() != null
                ? events.findByProvenanceIdOrderByOccurredAtAscIdAsc(item.getProvenanceId())
                : List.of();
        if (trail.isEmpty()) {
            doc.add(note("No audit events are recorded for this item."));
            return;
        }
        PdfPTable t = new PdfPTable(new float[] {21, 22, 13, 44});
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
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
            item.isVideo()
                    ? "Provenance: videos are not relabelled, so no labelled copy exists; this analysis has provenance ID "
                            + orDash(item.getProvenanceId()) + " and anyone can check its audit trail at /verify/"
                            + orDash(item.getProvenanceId()) + "."
                    : "Provenance: labelled copies of this media carry a signed manifest with provenance ID "
                            + orDash(item.getProvenanceId()) + ". Anyone can check it and the audit trail at /verify/"
                            + orDash(item.getProvenanceId()) + ".",
            "Chain of custody: the SHA-256 of this PDF is recorded in the item's audit trail as event REPORT GENERATED for "
                    + reportId + ". Recompute the file's SHA-256 and compare it to confirm the file bytes are unaltered.",
            "Datasets: models were trained and evaluated only on consenting-actor research benchmarks "
                    + "(FaceForensics++, DFDC, Celeb-DF).",
        };
        PdfPTable block = new PdfPTable(1);
        block.setWidthPercentage(100);
        block.setSpacingBefore(16);
        block.setKeepTogether(true);
        PdfPCell title = new PdfPCell(new Phrase("08  ·  Integrity and data handling", H2));
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
            cell.setPaddingBottom(5);
            block.addCell(cell);
        }
        doc.add(block);
    }

    private void glossary(Document doc) throws DocumentException {
        section(doc, "09  ·  Glossary");
        PdfPTable t = kvTable();
        kv(t, "pHash", "A short perceptual fingerprint of the picture. Similar images share nearby codes; it is not the image itself.", false);
        kv(t, "Pixel hash (SHA-256)", "A fingerprint of the exact file bytes. Any change to the file changes this code.", false);
        kv(t, "Provenance ID", "A public ID for this analysis's timeline. You can look it up without signing in.", false);
        kv(t, "SLA", "Service level agreement — here, the legal deadline to handle a takedown complaint.", false);
        kv(t, "C2PA", "Content Credentials: optional signed claims embedded by a camera or tool about how media was made.", false);
        kv(t, "Ed25519", "The digital signature algorithm used so others can check this report without a secret password.", false);
        kv(t, "AUTO-FLAGGED", "System code for \"removed automatically\" when a score crosses the auto-remove line.", false);
        doc.add(t);
    }

    private void signatureBlock(Document doc, ReportSigningService.Signed signed) throws DocumentException {
        PdfPTable block = new PdfPTable(1);
        block.setWidthPercentage(100);
        block.setSpacingBefore(14);
        block.setKeepTogether(true);
        PdfPCell title = new PdfPCell(new Phrase("10  ·  Digital signature", H2));
        title.setBorder(Rectangle.NO_BORDER);
        title.setPadding(0);
        title.setPaddingBottom(6);
        block.addCell(title);

        Paragraph intro = new Paragraph(
                "This signature covers a fixed list of facts (the canonical snapshot), not the decorative layout of the PDF. "
                        + "If those facts change later, verification returns TAMPERED.",
                BODY);
        intro.setLeading(12.5f);
        intro.setSpacingAfter(6);
        PdfPCell introCell = new PdfPCell();
        introCell.addElement(intro);
        introCell.setBorder(Rectangle.NO_BORDER);
        introCell.setPadding(0);
        block.addCell(introCell);

        PdfPTable t = kvTable();
        kv(t, "Algorithm", ReportSigningService.ALGORITHM + " over the SHA-256 of the signed fields", false);
        kv(t, "Signed at", timestamp(signed.signedAt()), false);
        kv(t, "Signing key ID", signed.keyId() + "  (" + signing.provider().kind() + " key provider)", true);
        kv(t, "Signed content SHA-256", signed.contentHash(), true);
        kv(t, "Signature (base64)", signed.signature(), true);
        kv(t, "RFC 3161 timestamp", timestampLine(signed.timestamp()), false);
        kv(t, "Verify", "GET /api/verify-report/" + signed.reportId() + "  (VALID / TAMPERED / NOT_FOUND)", true);
        PdfPCell holder = new PdfPCell(t);
        holder.setBorder(Rectangle.NO_BORDER);
        holder.setPadding(0);
        block.addCell(holder);

        PdfPCell scope = new PdfPCell(note("Signed fields: report ID and time, analysis and provenance IDs, decision, both scores, "
                + "generation family, hashes, jurisdiction, complaint and SLA timestamps, and (when present) media type, frames "
                + "sampled, deciding moderator, appeal outcome and C2PA status. Keys rotate; retired public keys stay published at "
                + "/api/verify-report/keys so older reports still verify. " + (signing.provider().production()
                        ? "The signing key is held in a key-management service and never leaves it."
                        : "Scope: this deployment uses a development key stored by the server. For stronger custody use a KMS "
                                + "(Vault Transit) and a trusted RFC 3161 timestamp authority.")));
        scope.setBorder(Rectangle.NO_BORDER);
        scope.setPadding(0);
        block.addCell(scope);
        doc.add(block);
    }

    private String timestampLine(ReportSigningService.Timestamp ts) {
        if (ts == null || "DISABLED".equals(ts.status())) {
            return "Not requested (no timestamp authority configured); the signing time is this server's clock.";
        }
        if ("STAMPED".equals(ts.status())) {
            return timestamp(ts.time()) + " attested by " + ts.authority();
        }
        return "Requested but the timestamp authority was unavailable (" + ts.error() + "); issue the report again to add one.";
    }

    // ------------------------------------------------------------------ layout helpers

    private static void section(Document doc, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, H2);
        p.setSpacingBefore(14);
        p.setSpacingAfter(7);
        doc.add(p);
    }

    private static Paragraph note(String text) {
        Paragraph p = new Paragraph(text, SMALL);
        p.setSpacingBefore(6);
        p.setLeading(11f);
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
            // Cover page is full-bleed dark; skip the light footer there
            if (writer.getPageNumber() <= 1) {
                return;
            }
            float y = document.bottom() - 22;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(reportId + "  ·  Analysis #" + item.getId()
                            + "  ·  Decision record v2  ·  Verify: /api/verify-report/" + reportId, SMALL),
                    document.left(), y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), SMALL), document.right(), y, 0);
            writer.getDirectContent().setColorStroke(BRAND);
            writer.getDirectContent().setLineWidth(1.2f);
            writer.getDirectContent().moveTo(document.left(), y + 11);
            writer.getDirectContent().lineTo(document.right(), y + 11);
            writer.getDirectContent().stroke();
        }
    }
}
