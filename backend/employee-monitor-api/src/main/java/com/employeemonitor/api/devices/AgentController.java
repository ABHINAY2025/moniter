package com.employeemonitor.api.devices;

import com.employeemonitor.api.common.RequestContext;
import com.employeemonitor.api.devices.DeviceDtos.AgentIdentityResponse;
import com.employeemonitor.api.devices.DeviceDtos.HeartbeatRequest;
import com.employeemonitor.api.devices.DeviceDtos.HeartbeatResponse;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeSummary;
import com.employeemonitor.api.policies.MonitoringPolicy;
import com.employeemonitor.api.policies.PolicyDtos;
import com.employeemonitor.api.policies.PolicyDtos.PolicyResponse;
import com.employeemonitor.api.policies.PolicyService;
import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.security.DevicePrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/** Endpoints called by the Windows agent, authenticated with the device token. */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent (device token)")
public class AgentController {

    private final DeviceService deviceService;
    private final PolicyService policyService;
    private final Clock clock;

    public AgentController(DeviceService deviceService, PolicyService policyService, Clock clock) {
        this.deviceService = deviceService;
        this.policyService = policyService;
        this.clock = clock;
    }

    @PostMapping("/heartbeat")
    @Transactional
    public HeartbeatResponse heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        DevicePrincipal device = CurrentUser.requireDevice();
        String ip = StringUtils.hasText(request.ipAddress()) ? request.ipAddress() : RequestContext.clientIp();
        deviceService.heartbeat(device.deviceId(), device.employeeId(), request.status(), ip, request.agentVersion(),
                request.currentApplication(), request.network(), request.zscalerStatus());
        MonitoringPolicy policy = policyService.effectivePolicy(deviceService.load(device.deviceId()));
        return new HeartbeatResponse(clock.instant(), PolicyDtos.revision(policy),
                policy.getSettings().heartbeatIntervalSeconds());
    }

    @GetMapping("/policy")
    @Transactional(readOnly = true)
    public PolicyResponse policy() {
        DevicePrincipal device = CurrentUser.requireDevice();
        return PolicyResponse.from(policyService.effectivePolicy(deviceService.load(device.deviceId())));
    }

    /** Identity shown in the agent's tray "Company Monitoring" panel. */
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public AgentIdentityResponse me() {
        Device device = deviceService.load(CurrentUser.requireDevice().deviceId());
        return new AgentIdentityResponse(device.getId(), device.getDeviceCode(), device.getHostname(),
                EmployeeSummary.from(device.getEmployee()),
                PolicyDtos.revision(policyService.effectivePolicy(device)));
    }
}
