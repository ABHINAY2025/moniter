package com.employeemonitor.api.devices;

import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.devices.DeviceDtos.AssignDeviceRequest;
import com.employeemonitor.api.devices.DeviceDtos.DeviceRegistrationResponse;
import com.employeemonitor.api.devices.DeviceDtos.DeviceResponse;
import com.employeemonitor.api.devices.DeviceDtos.RegisterDeviceRequest;
import com.employeemonitor.api.devices.DeviceDtos.UpdateDeviceStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
@Tag(name = "Devices")
public class DeviceController {

    private final DeviceService service;
    private final DeviceRegistrationService registrationService;

    public DeviceController(DeviceService service, DeviceRegistrationService registrationService) {
        this.service = service;
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Enroll a device using a company-issued enrollment token (called by the installer/agent)")
    public DeviceRegistrationResponse register(@Valid @RequestBody RegisterDeviceRequest request) {
        return registrationService.register(request);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEVICE_READ')")
    public PageResponse<DeviceResponse> list(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) Device.Status status,
                                             @RequestParam(required = false) Long employeeId,
                                             @RequestParam(required = false) Boolean unassigned,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return service.list(q, status, employeeId, unassigned,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("hostname", "id")));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DEVICE_READ')")
    public DeviceResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/{id}/assignment")
    @PreAuthorize("hasAuthority('DEVICE_WRITE')")
    public DeviceResponse assign(@PathVariable Long id, @RequestBody AssignDeviceRequest request) {
        return service.assign(id, request.employeeId());
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('DEVICE_WRITE')")
    public DeviceResponse setStatus(@PathVariable Long id, @Valid @RequestBody UpdateDeviceStatusRequest request) {
        return service.setStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DEVICE_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        service.remove(id);
    }
}
