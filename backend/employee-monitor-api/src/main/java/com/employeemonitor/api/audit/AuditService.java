package com.employeemonitor.api.audit;

import com.employeemonitor.api.common.RequestContext;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** Records an action performed by the currently authenticated user (if any). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Object targetId, Map<String, Object> metadata) {
        AuthenticatedUser user = CurrentUser.find().orElse(null);
        record(user == null ? null : user.id(), user == null ? null : user.username(),
                action, targetType, targetId, "SUCCESS", metadata);
    }

    /**
     * Records an action with an explicit actor. Runs in its own transaction so that failures
     * (e.g. a rejected login) are still recorded even though the caller's transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorUserId, String actorUsername, String action, String targetType, Object targetId,
                       String outcome, Map<String, Object> metadata) {
        AuditLog entry = new AuditLog(actorUserId, actorUsername, action, targetType,
                targetId == null ? null : String.valueOf(targetId), outcome, RequestContext.clientIp(), metadata);
        repository.save(entry);
        log.info("audit action={} actor={} target={}:{} outcome={}", action, actorUsername, targetType, targetId, outcome);
    }
}
