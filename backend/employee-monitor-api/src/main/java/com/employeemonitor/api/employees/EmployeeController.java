package com.employeemonitor.api.employees;

import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.employees.EmployeeDtos.CreateEmployeeRequest;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeDetailResponse;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeResponse;
import com.employeemonitor.api.employees.EmployeeDtos.UpdateEmployeeRequest;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employees")
@Tag(name = "Employees")
public class EmployeeController {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    /** Lists employees visible to the caller (all, assigned-only, or self). */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_READ_ALL', 'EMPLOYEE_READ_ASSIGNED', 'SELF_READ')")
    public PageResponse<EmployeeResponse> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Long departmentId,
                                               @RequestParam(required = false) Employee.Status status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        return service.list(CurrentUser.require(), q, departmentId, status,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("fullName", "id")));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_READ_ALL', 'EMPLOYEE_READ_ASSIGNED', 'SELF_READ')")
    public EmployeeDetailResponse get(@PathVariable Long id) {
        return service.get(CurrentUser.require(), id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('EMPLOYEE_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse create(@Valid @RequestBody CreateEmployeeRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYEE_WRITE')")
    public EmployeeResponse update(@PathVariable Long id, @Valid @RequestBody UpdateEmployeeRequest request) {
        return service.update(id, request);
    }
}
