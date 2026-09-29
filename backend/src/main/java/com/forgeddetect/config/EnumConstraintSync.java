package com.forgeddetect.config;

import com.forgeddetect.investigation.InvestigationEventType;
import com.forgeddetect.investigation.InvestigationStatus;
import com.forgeddetect.investigation.LegalHoldStatus;
import com.forgeddetect.investigation.SightingSource;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.ModerationAction;
import com.forgeddetect.model.ProvenanceEventType;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Component;

/**
 * Hibernate writes a CHECK constraint listing enum values when it first creates a table, and
 * {@code ddl-auto: update} never widens it, so a newly added enum value would be rejected on an
 * existing PostgreSQL database. Rebuild those constraints from the current enums on startup, before
 * the seeders insert rows that use the new values.
 */
@Component
@Order(-10)
public class EnumConstraintSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnumConstraintSync.class);

    private record Column(String table, String column, Class<? extends Enum<?>> type) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("provenance_events", "type", ProvenanceEventType.class),
            new Column("jurisdiction_rules", "complaint_type", ComplaintType.class),
            new Column("analysis_results", "complaint_type", ComplaintType.class),
            new Column("moderation_audit_events", "action", ModerationAction.class),
            new Column("investigations", "status", InvestigationStatus.class),
            new Column("investigation_events", "type", InvestigationEventType.class),
            new Column("sightings", "source", SightingSource.class),
            new Column("investigation_legal_holds", "status", LegalHoldStatus.class));

    private final DataSource dataSource;

    public EnumConstraintSync(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String product = JdbcUtils.extractDatabaseMetaData(dataSource, md -> md.getDatabaseProductName());
        if (!"PostgreSQL".equalsIgnoreCase(product)) {
            return;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (Column c : COLUMNS) {
            String values = Arrays.stream(c.type().getEnumConstants())
                    .map(v -> "'" + v.name() + "'")
                    .collect(Collectors.joining(", "));
            String constraint = c.table() + "_" + c.column() + "_check";
            jdbc.execute("ALTER TABLE " + c.table() + " DROP CONSTRAINT IF EXISTS " + constraint);
            jdbc.execute("ALTER TABLE " + c.table() + " ADD CONSTRAINT " + constraint
                    + " CHECK (" + c.column() + " IN (" + values + "))");
        }
        log.info("Enum CHECK constraints synced for {} column(s)", COLUMNS.size());
    }
}
