package com.employeemonitor.api.employees;

import com.employeemonitor.api.devices.Device;
import com.employeemonitor.api.devices.DeviceDtos.DeviceResponse;
import com.employeemonitor.api.devices.DeviceRepository;
import com.employeemonitor.api.devices.DeviceService;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeResponse;
import com.employeemonitor.api.policies.PolicyDtos.PolicyResponse;
import com.employeemonitor.api.policies.PolicyService;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Transparency endpoint: an employee can see their own record, their devices and exactly which monitoring
 * policy applies to each device.
 */
@RestController
@RequestMapping("/api/self")
@Tag(name = "Self service")
public class SelfServiceController {

    private final EmployeeRepository employees;
    private final DeviceRepository devices;
    private final DeviceService deviceService;
    private final PolicyService policyService;

    public SelfServiceController(EmployeeRepository employees, DeviceRepository devices, DeviceService deviceService,
                                 PolicyService policyService) {
        this.employees = employees;
        this.devices = devices;
        this.deviceService = deviceService;
        this.policyService = policyService;
    }

    public record SelfDevice(DeviceResponse device, PolicyResponse policy) {
    }

    public record SelfOverview(EmployeeResponse employee, List<SelfDevice> devices) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SELF_READ')")
    @Transactional(readOnly = true)
    public SelfOverview overview() {
        AuthenticatedUser user = CurrentUser.require();
        if (user.employeeId() == null) {
            return new SelfOverview(null, List.of());
        }
        EmployeeResponse employee = employees.findById(user.employeeId()).map(EmployeeResponse::from).orElse(null);
        List<SelfDevice> list = deviceService.forEmployee(user.employeeId()).stream()
                .map(d -> {
                    Device device = devices.findById(d.id()).orElseThrow();
                    return new SelfDevice(d, PolicyResponse.from(policyService.effectivePolicy(device)));
                })
                .toList();
        return new SelfOverview(employee, list);
    }
}
