package com.employeemonitor.api.policies;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.devices.Device;
import com.employeemonitor.api.devices.DeviceRepository;
import com.employeemonitor.api.policies.PolicyDtos.PolicyResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PolicyService {

    private final MonitoringPolicyRepository policies;
    private final DeviceRepository devices;
    private final AuditService audit;

    public PolicyService(MonitoringPolicyRepository policies, DeviceRepository devices, AuditService audit) {
        this.policies = policies;
        this.devices = devices;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public MonitoringPolicy defaultPolicy() {
        return policies.findByDefaultPolicyTrue()
                .orElseThrow(() -> new IllegalStateException("No default monitoring policy configured"));
    }

    /** The policy that applies to a device: its own override/assigned policy, otherwise the default. */
    @Transactional(readOnly = true)
    public MonitoringPolicy effectivePolicy(Device device) {
        if (device.getPolicyId() != null) {
            return policies.findById(device.getPolicyId()).orElseGet(this::defaultPolicy);
        }
        return defaultPolicy();
    }

    @Transactional(readOnly = true)
    public PolicyResponse effectivePolicyForDevice(Long deviceId) {
        return PolicyResponse.from(effectivePolicy(loadDevice(deviceId)));
    }

    @Transactional(readOnly = true)
    public List<PolicyResponse> listShared() {
        return policies.findByDeviceSpecificFalseOrderByName().stream().map(PolicyResponse::from).toList();
    }

    @Transactional
    public PolicyResponse updateDefault(PolicySettings settings) {
        MonitoringPolicy policy = defaultPolicy();
        Map<String, Object> meta = changeMetadata(policy.getSettings(), settings);
        policy.setSettings(settings);
        policies.flush();
        audit.record(AuditAction.POLICY_CHANGED, "POLICY", policy.getId(), meta);
        return PolicyResponse.from(policy);
    }

    /** Creates or updates the device's own override policy. */
    @Transactional
    public PolicyResponse updateForDevice(Long deviceId, PolicySettings settings) {
        Device device = loadDevice(deviceId);
        MonitoringPolicy current = effectivePolicy(device);
        Map<String, Object> meta = changeMetadata(current.getSettings(), settings);
        meta.put("deviceId", deviceId);

        MonitoringPolicy override;
        if (current.isDeviceSpecific()) {
            override = current;
            override.setSettings(settings);
        } else {
            override = policies.save(new MonitoringPolicy("device:" + device.getDeviceCode(), true, settings));
            device.setPolicyId(override.getId());
        }
        policies.flush();
        audit.record(AuditAction.POLICY_CHANGED, "DEVICE", deviceId, meta);
        return PolicyResponse.from(override);
    }

    /** Removes a device override so the device falls back to the default policy. */
    @Transactional
    public PolicyResponse resetDevice(Long deviceId) {
        Device device = loadDevice(deviceId);
        Long previous = device.getPolicyId();
        device.setPolicyId(null);
        if (previous != null) {
            policies.findById(previous).filter(MonitoringPolicy::isDeviceSpecific).ifPresent(policies::delete);
            audit.record(AuditAction.POLICY_CHANGED, "DEVICE", deviceId, Map.of("reset", true));
        }
        return PolicyResponse.from(defaultPolicy());
    }

    private Device loadDevice(Long deviceId) {
        return devices.findById(deviceId).orElseThrow(() -> ApiException.notFound("Device", deviceId));
    }

    /** Audit metadata: before/after for every changed top-level setting. */
    private static Map<String, Object> changeMetadata(PolicySettings before, PolicySettings after) {
        Map<String, Object> changes = new HashMap<>();
        for (var component : PolicySettings.class.getRecordComponents()) {
            try {
                Object b = before == null ? null : component.getAccessor().invoke(before);
                Object a = component.getAccessor().invoke(after);
                if (!java.util.Objects.equals(a, b)) {
                    changes.put(component.getName(), Map.of("from", String.valueOf(b), "to", String.valueOf(a)));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("changes", changes);
        return meta;
    }
}
