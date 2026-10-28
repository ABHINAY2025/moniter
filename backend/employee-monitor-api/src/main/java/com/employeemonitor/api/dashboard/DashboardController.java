package com.employeemonitor.api.dashboard;

import com.employeemonitor.api.devices.Device;
import com.employeemonitor.api.devices.DeviceRepository;
import com.employeemonitor.api.employees.Employee;
import com.employeemonitor.api.employees.EmployeeAccessService;
import com.employeemonitor.api.employees.EmployeeRepository;
import com.employeemonitor.api.presence.ActivityStatus;
import com.employeemonitor.api.presence.Presence;
import com.employeemonitor.api.presence.PresenceService;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    private final EmployeeRepository employees;
    private final DeviceRepository devices;
    private final EmployeeAccessService access;
    private final PresenceService presence;

    public DashboardController(EmployeeRepository employees, DeviceRepository devices, EmployeeAccessService access,
                               PresenceService presence) {
        this.employees = employees;
        this.devices = devices;
        this.access = access;
        this.presence = presence;
    }

    /**
     * Headline counts. An employee counts as ONLINE if any of their devices is active, IDLE if the best device is
     * idle, LOCKED if locked, otherwise OFFLINE. Scoped to the employees visible to the caller.
     */
    public record DashboardSummary(long totalEmployees, long online, long idle, long locked, long offline,
                                   long activeDevices, long onlineDevices, long unassignedDevices,
                                   Instant generatedAt) {
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        AuthenticatedUser user = CurrentUser.require();
        List<Employee> visible = employees.findAll(access.visibleEmployees(user).and(
                (root, q, cb) -> cb.equal(root.get("status"), Employee.Status.ACTIVE)));
        Set<Long> employeeIds = visible.stream().map(Employee::getId).collect(Collectors.toSet());

        List<Device> activeDevices = access.isScoped(user)
                ? (employeeIds.isEmpty() ? List.of() : devices.findByStatusAndEmployeeIdIn(Device.Status.ACTIVE, employeeIds))
                : devices.findByStatus(Device.Status.ACTIVE);
        Map<Long, Presence> live = presence.getAll(activeDevices.stream().map(Device::getId).toList());

        Map<Long, ActivityStatus> best = new HashMap<>();
        long onlineDevices = 0;
        long unassigned = 0;
        for (Device d : activeDevices) {
            Presence p = live.get(d.getId());
            ActivityStatus s = p == null ? ActivityStatus.OFFLINE : p.status();
            if (s != ActivityStatus.OFFLINE) {
                onlineDevices++;
            }
            if (d.getEmployee() == null) {
                unassigned++;
                continue;
            }
            best.merge(d.getEmployee().getId(), s, (a, b) -> a.rank() >= b.rank() ? a : b);
        }
        long online = 0, idle = 0, locked = 0;
        for (Long id : employeeIds) {
            switch (best.getOrDefault(id, ActivityStatus.OFFLINE)) {
                case ACTIVE -> online++;
                case IDLE -> idle++;
                case LOCKED -> locked++;
                case OFFLINE -> { }
            }
        }
        long total = employeeIds.size();
        return new DashboardSummary(total, online, idle, locked, total - online - idle - locked,
                activeDevices.size(), onlineDevices, unassigned, Instant.now());
    }
}
