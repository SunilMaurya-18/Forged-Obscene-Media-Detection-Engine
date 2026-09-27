package com.forgeddetect.config;

import com.forgeddetect.model.ProvenanceEventType;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Component;

/**
 * Hibernate writes a CHECK constraint listing enum values when it first creates a table, and
 * {@code ddl-auto: update} never widens it, so a newly added event type would be rejected on an
 * existing PostgreSQL database. Rebuild it from the current enum on startup.
 */
@Component
public class EnumConstraintSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnumConstraintSync.class);

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
        String values = Arrays.stream(ProvenanceEventType.values())
                .map(v -> "'" + v.name() + "'")
                .collect(Collectors.joining(", "));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE provenance_events DROP CONSTRAINT IF EXISTS provenance_events_type_check");
        jdbc.execute("ALTER TABLE provenance_events ADD CONSTRAINT provenance_events_type_check CHECK (type IN (" + values + "))");
        log.info("provenance_events.type constraint synced to {} event types", ProvenanceEventType.values().length);
    }
}
