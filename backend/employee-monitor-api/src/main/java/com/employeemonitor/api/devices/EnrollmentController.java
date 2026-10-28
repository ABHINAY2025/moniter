package com.employeemonitor.api.devices;

import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.devices.DeviceDtos.CreateEnrollmentTokenRequest;
import com.employeemonitor.api.devices.DeviceDtos.EnrollmentTokenCreatedResponse;
import com.employeemonitor.api.devices.DeviceDtos.EnrollmentTokenResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/enrollment-tokens")
@Tag(name = "Device enrollment")
@PreAuthorize("hasAuthority('ENROLLMENT_MANAGE')")
public class EnrollmentController {

    private final EnrollmentService service;

    public EnrollmentController(EnrollmentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentTokenCreatedResponse create(@Valid @RequestBody CreateEnrollmentTokenRequest request) {
        return service.create(request);
    }

    @GetMapping
    public PageResponse<EnrollmentTokenResponse> list(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return service.list(Math.max(page, 0), Math.clamp(size, 1, 200));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id) {
        service.revoke(id);
    }
}
