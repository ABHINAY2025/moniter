package com.employeemonitor.api.liveview;

import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.liveview.LiveViewDtos.CreateRequest;
import com.employeemonitor.api.liveview.LiveViewDtos.SessionResponse;
import com.employeemonitor.api.liveview.LiveViewDtos.SessionSummary;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/live-view")
@Tag(name = "Live view")
@PreAuthorize("hasAuthority('LIVE_VIEW')")
public class LiveViewController {

    private final LiveViewService service;
    private final LiveViewSignalingHandler signaling;

    public LiveViewController(LiveViewService service, LiveViewSignalingHandler signaling) {
        this.service = service;
        this.signaling = signaling;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@Valid @RequestBody CreateRequest request) {
        return service.create(CurrentUser.require(), request.deviceId());
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable UUID sessionId) {
        service.stopByAdmin(CurrentUser.require(), sessionId);
        signaling.closeSession(sessionId, "STOPPED_BY_ADMIN");
    }

    @GetMapping("/sessions")
    public PageResponse<SessionSummary> history(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return service.history(Math.max(page, 0), Math.clamp(size, 1, 200));
    }
}
