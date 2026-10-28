package com.employeemonitor.api.alerts;

import com.employeemonitor.api.alerts.AlertDtos.AlertResponse;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
@Tag(name = "Alerts")
@PreAuthorize("hasAuthority('ALERT_READ')")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AlertResponse> list(@RequestParam(defaultValue = "false") boolean unacknowledged,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return service.list(unacknowledged,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PostMapping("/{id}/acknowledge")
    public AlertResponse acknowledge(@PathVariable Long id) {
        return service.acknowledge(id, CurrentUser.require().id());
    }
}
