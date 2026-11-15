package com.atlasna.catalog.audit.web;

import com.atlasna.catalog.audit.AuditEntry;
import com.atlasna.catalog.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Who changed what, newest first. ADMIN only. Every filter is optional and they combine. */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final MongoTemplate mongo;

    @GetMapping
    public Page<AuditEntry> search(@RequestParam(required = false) String entityType,
                                   @RequestParam(required = false) String entityId,
                                   @RequestParam(required = false) Long actorId,
                                   @RequestParam(required = false) String action,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 200) throw ApiException.invalidField("size", "Size must be between 1 and 200");
        Query query = new Query();
        if (entityType != null) query.addCriteria(Criteria.where("entityType").is(entityType.toUpperCase(java.util.Locale.ROOT)));
        if (entityId != null) query.addCriteria(Criteria.where("entityId").is(entityId));
        if (actorId != null) query.addCriteria(Criteria.where("actorId").is(actorId));
        if (action != null) query.addCriteria(Criteria.where("action").is(action.toUpperCase(java.util.Locale.ROOT)));
        if (from != null || to != null) {
            Criteria at = Criteria.where("at");
            if (from != null) at = at.gte(from);
            if (to != null) at = at.lt(to);
            query.addCriteria(at);
        }
        long total = mongo.count(query, AuditEntry.class);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "at"));
        return new PageImpl<>(mongo.find(query.with(pageable), AuditEntry.class), pageable, total);
    }
}
