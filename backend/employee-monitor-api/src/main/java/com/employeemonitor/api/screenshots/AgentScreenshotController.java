package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.screenshots.ScreenshotDtos.UploadRequest;
import com.employeemonitor.api.screenshots.ScreenshotDtos.UploadResponse;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Screenshot upload, called by the agent with its device token. Idempotent per (device, clientEventId). */
@RestController
@RequestMapping("/api/agent/screenshots")
@Tag(name = "Agent (device token)")
public class AgentScreenshotController {

    private final ScreenshotService service;

    public AgentScreenshotController(ScreenshotService service) {
        this.service = service;
    }

    @PostMapping
    public UploadResponse upload(@Valid @RequestBody UploadRequest request) {
        return service.ingest(CurrentUser.requireDevice(), request);
    }
}
