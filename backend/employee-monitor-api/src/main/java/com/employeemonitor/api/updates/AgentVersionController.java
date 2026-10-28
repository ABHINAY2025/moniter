package com.employeemonitor.api.updates;

import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.updates.UpdateDtos.ApprovalRequest;
import com.employeemonitor.api.updates.UpdateDtos.PublishRequest;
import com.employeemonitor.api.updates.UpdateDtos.VersionResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent-versions")
@Tag(name = "Agent versions")
@PreAuthorize("hasAuthority('AGENT_MANAGE')")
public class AgentVersionController {

    private final UpdateService service;
    private final AuditService audit;

    public AgentVersionController(UpdateService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse publish(@Valid @RequestBody PublishRequest request) {
        VersionResponse response = service.publish(CurrentUser.require().id(), request);
        audit.record("AGENT_VERSION_PUBLISHED", "AGENT_VERSION", response.id(),
                Map.of("version", response.version()));
        return response;
    }

    @GetMapping
    public List<VersionResponse> list() {
        return service.list();
    }

    @PutMapping("/{id}/approval")
    public VersionResponse setApproval(@PathVariable Long id, @Valid @RequestBody ApprovalRequest request) {
        VersionResponse response = service.setApproval(id, request.approved());
        audit.record("AGENT_VERSION_APPROVAL", "AGENT_VERSION", id,
                Map.of("version", response.version(), "approved", request.approved()));
        return response;
    }
}
