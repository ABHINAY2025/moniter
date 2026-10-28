package com.employeemonitor.api.sessions;

import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.sessions.SessionDtos.IngestRequest;
import com.employeemonitor.api.sessions.SessionDtos.IngestResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session/activity event ingest, called by the agent with its device token. */
@RestController
@RequestMapping("/api/agent/session-events")
@Tag(name = "Agent (device token)")
public class AgentSessionController {

    private final SessionService service;

    public AgentSessionController(SessionService service) {
        this.service = service;
    }

    @PostMapping
    public IngestResponse ingest(@Valid @RequestBody IngestRequest request) {
        return service.ingest(CurrentUser.requireDevice(), request);
    }
}
