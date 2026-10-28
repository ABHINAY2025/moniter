package com.employeemonitor.api.webaccess;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.devices.Device;
import com.employeemonitor.api.devices.DeviceRepository;
import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.security.DevicePrincipal;
import com.employeemonitor.api.webaccess.WebAccessDtos.EffectiveWebPolicyResponse;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebAccessEventBatch;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebAccessIngestResult;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebEnforcementReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Device-authenticated web-access endpoints: fetch the effective policy, report events, report enforcement health. */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent web access")
public class AgentWebController {

    private final WebPolicyService policyService;
    private final WebAccessEventService eventService;
    private final DeviceRepository devices;

    public AgentWebController(WebPolicyService policyService, WebAccessEventService eventService,
                             DeviceRepository devices) {
        this.policyService = policyService;
        this.eventService = eventService;
        this.devices = devices;
    }

    @GetMapping("/web-policy")
    @Operation(summary = "Effective web-access policy (block/allow/monitor domains) for the calling device")
    public EffectiveWebPolicyResponse webPolicy() {
        DevicePrincipal principal = CurrentUser.requireDevice();
        Device device = devices.findById(principal.deviceId())
                .orElseThrow(() -> ApiException.notFound("Device", principal.deviceId()));
        return policyService.resolve(device).response();
    }

    @PostMapping("/web-access-events/batch")
    public WebAccessIngestResult ingest(@Valid @RequestBody WebAccessEventBatch batch) {
        return eventService.ingest(CurrentUser.requireDevice(), batch.events());
    }

    @PostMapping("/web-enforcement-status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@RequestBody WebEnforcementReport report) {
        policyService.reportEnforcement(CurrentUser.requireDevice().deviceId(), report);
    }
}
