package com.employeemonitor.api.webaccess;

import com.employeemonitor.api.webaccess.WebAccessDtos.WebEnforcementStatusResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
@Tag(name = "Web access policies")
public class WebEnforcementController {

    private final WebPolicyService service;

    public WebEnforcementController(WebPolicyService service) {
        this.service = service;
    }

    @GetMapping("/{deviceId}/web-enforcement-status")
    @PreAuthorize("hasAuthority('WEB_POLICY_READ')")
    public WebEnforcementStatusResponse status(@PathVariable Long deviceId) {
        return service.enforcementStatus(deviceId);
    }
}
