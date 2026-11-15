package com.atlasna.catalog.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Creates the audit_logs indexes at startup (idempotent): a TTL index that deletes entries after the retention
 * period, and the indexes behind the admin filters. A missing MongoDB must not stop the shop from starting.
 */
@Slf4j
@Component
class AuditIndexes {

    private final MongoTemplate mongo;
    private final Duration retention;

    AuditIndexes(MongoTemplate mongo, @org.springframework.beans.factory.annotation.Value("${atlasna.audit.retention:365d}") Duration retention) {
        this.mongo = mongo;
        this.retention = retention;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ensure() {
        try {
            var ops = mongo.indexOps(AuditEntry.class);
            ops.createIndex(new Index().on("at", Sort.Direction.ASC).named("ttl_at").expire(retention));
            ops.createIndex(new Index().on("entityType", Sort.Direction.ASC).on("entityId", Sort.Direction.ASC)
                    .on("at", Sort.Direction.DESC).named("by_entity"));
            ops.createIndex(new Index().on("actorId", Sort.Direction.ASC).on("at", Sort.Direction.DESC).named("by_actor"));
            ops.createIndex(new Index().on("action", Sort.Direction.ASC).on("at", Sort.Direction.DESC).named("by_action"));
        } catch (RuntimeException e) {
            log.warn("Audit log indexes not created (MongoDB unavailable?): {}", e.getMessage());
        }
    }

    long retentionSeconds() {
        return retention.toSeconds();
    }
}
