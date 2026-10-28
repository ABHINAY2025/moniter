package com.employeemonitor.api.devices;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.devices.DeviceDtos.DeviceResponse;
import com.employeemonitor.api.employees.Employee;
import com.employeemonitor.api.employees.EmployeeRepository;
import com.employeemonitor.api.presence.ActivityStatus;
import com.employeemonitor.api.presence.Presence;
import com.employeemonitor.api.presence.PresenceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final DeviceTokenRepository deviceTokens;
    private final EmployeeRepository employees;
    private final PresenceService presence;
    private final AuditService audit;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, DeviceTokenRepository deviceTokens, EmployeeRepository employees,
                         PresenceService presence, AuditService audit, Clock clock) {
        this.devices = devices;
        this.deviceTokens = deviceTokens;
        this.employees = employees;
        this.presence = presence;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> list(String q, Device.Status status, Long employeeId, Boolean unassigned,
                                             Pageable pageable) {
        Specification<Device> spec = (root, query, cb) -> cb.conjunction();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("hostname")), like),
                    cb.like(cb.lower(root.get("deviceCode")), like)));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (employeeId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("employee").get("id"), employeeId));
        }
        if (Boolean.TRUE.equals(unassigned)) {
            spec = spec.and((root, query, cb) -> cb.isNull(root.get("employee")));
        }
        Page<Device> page = devices.findAll(spec, pageable);
        Map<Long, Presence> live = presence.getAll(page.getContent().stream().map(Device::getId).toList());
        return PageResponse.of(page, d -> DeviceResponse.from(d, live.get(d.getId())));
    }

    @Transactional(readOnly = true)
    public DeviceResponse get(Long id) {
        Device device = load(id);
        return DeviceResponse.from(device, presence.get(id).orElse(null));
    }

    /** Devices of one employee with live presence (used by employee detail). */
    @Transactional(readOnly = true)
    public List<DeviceResponse> forEmployee(Long employeeId) {
        List<Device> list = devices.findByEmployeeIdAndStatusNotOrderByRegisteredAt(employeeId, Device.Status.RETIRED);
        Map<Long, Presence> live = presence.getAll(list.stream().map(Device::getId).toList());
        return list.stream().map(d -> DeviceResponse.from(d, live.get(d.getId()))).toList();
    }

    @Transactional
    public DeviceResponse assign(Long id, Long employeeId) {
        Device device = load(id);
        Long previous = device.getEmployee() == null ? null : device.getEmployee().getId();
        Employee employee = null;
        if (employeeId != null) {
            employee = employees.findById(employeeId).orElseThrow(() -> ApiException.notFound("Employee", employeeId));
            if (employee.getStatus() != Employee.Status.ACTIVE) {
                throw ApiException.badRequest("Cannot assign a device to an inactive employee");
            }
        }
        device.setEmployee(employee);
        Map<String, Object> meta = new HashMap<>();
        meta.put("fromEmployeeId", previous);
        meta.put("toEmployeeId", employeeId);
        audit.record(AuditAction.DEVICE_ASSIGNED, "DEVICE", id, meta);
        return DeviceResponse.from(device, presence.get(id).orElse(null));
    }

    @Transactional
    public DeviceResponse setStatus(Long id, Device.Status status) {
        if (status == Device.Status.RETIRED) {
            remove(id);
            return get(id);
        }
        Device device = load(id);
        if (device.getStatus() == Device.Status.RETIRED) {
            throw ApiException.badRequest("A retired device must be re-enrolled with a new enrollment token");
        }
        Device.Status previous = device.getStatus();
        device.setStatus(status);
        if (status == Device.Status.DISABLED) {
            presence.forceOffline(id, device.getEmployee() == null ? null : device.getEmployee().getId());
        }
        audit.record(AuditAction.DEVICE_STATUS_CHANGED, "DEVICE", id,
                Map.of("from", previous.name(), "to", status.name()));
        return DeviceResponse.from(device, presence.get(id).orElse(null));
    }

    /** Retires a device: revokes its credentials. History is kept for reporting. */
    @Transactional
    public void remove(Long id) {
        Device device = load(id);
        device.setStatus(Device.Status.RETIRED);
        deviceTokens.revokeAllForDevice(id, clock.instant());
        presence.forceOffline(id, device.getEmployee() == null ? null : device.getEmployee().getId());
        audit.record(AuditAction.DEVICE_REMOVED, "DEVICE", id, Map.of("deviceCode", device.getDeviceCode()));
    }

    /** Persists the heartbeat side effects and updates realtime presence. */
    @Transactional
    public Presence heartbeat(Long deviceId, Long employeeId, ActivityStatus status, String ip, String agentVersion,
                              String currentApplication, String network, String zscalerStatus) {
        devices.recordHeartbeat(deviceId, clock.instant(), ip, agentVersion);
        return presence.recordHeartbeat(deviceId, employeeId, status, ip, currentApplication, network, zscalerStatus);
    }

    public Device load(Long id) {
        return devices.findById(id).orElseThrow(() -> ApiException.notFound("Device", id));
    }
}
