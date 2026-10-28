package com.employeemonitor.api.policies;

import com.employeemonitor.api.policies.PolicyDtos.PolicyResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/policies")
@Tag(name = "Monitoring policies")
public class PolicyController {

    private final PolicyService service;

    public PolicyController(PolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('POLICY_READ')")
    public List<PolicyResponse> list() {
        return service.listShared();
    }

    @GetMapping("/default")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    public PolicyResponse getDefault() {
        return PolicyResponse.from(service.defaultPolicy());
    }

    @PutMapping("/default")
    @PreAuthorize("hasAuthority('POLICY_WRITE')")
    public PolicyResponse updateDefault(@Valid @RequestBody PolicySettings settings) {
        return service.updateDefault(settings);
    }

    @GetMapping("/{deviceId:\\d+}")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    public PolicyResponse getForDevice(@PathVariable Long deviceId) {
        return service.effectivePolicyForDevice(deviceId);
    }

    @PutMapping("/{deviceId:\\d+}")
    @PreAuthorize("hasAuthority('POLICY_WRITE')")
    public PolicyResponse updateForDevice(@PathVariable Long deviceId, @Valid @RequestBody PolicySettings settings) {
        return service.updateForDevice(deviceId, settings);
    }

    @DeleteMapping("/{deviceId:\\d+}")
    @PreAuthorize("hasAuthority('POLICY_WRITE')")
    public PolicyResponse resetDevice(@PathVariable Long deviceId) {
        return service.resetDevice(deviceId);
    }
}
