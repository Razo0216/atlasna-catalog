package com.atlasna.catalog.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * One line of the audit log (MongoDB {@code audit_logs}, shape from 02-DOMAIN-AND-DATA-MODEL §5). Append-only;
 * removed automatically after the retention period by a TTL index on {@code at}.
 *
 * <p>Never contains passwords, tokens, or phone numbers. {@code before}/{@code after} hold only the fields that changed.
 *
 * @param details small extra facts (order number, stock reason and delta, the email of a failed login…)
 */
@Document("audit_logs")
public record AuditEntry(
        @Id String id,
        Instant at,
        Long actorId,
        String actorRole,
        String ip,
        String action,
        String entityType,
        String entityId,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> details) {}
