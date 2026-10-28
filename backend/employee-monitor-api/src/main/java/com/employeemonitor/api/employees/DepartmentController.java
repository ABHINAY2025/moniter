package com.employeemonitor.api.employees;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.employees.EmployeeDtos.DepartmentRequest;
import com.employeemonitor.api.employees.EmployeeDtos.DepartmentResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/departments")
@Tag(name = "Departments")
public class DepartmentController {

    private final DepartmentRepository departments;
    private final AuditService audit;

    public DepartmentController(DepartmentRepository departments, AuditService audit) {
        this.departments = departments;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_READ_ALL', 'EMPLOYEE_READ_ASSIGNED', 'REPORT_READ')")
    @Transactional(readOnly = true)
    public List<DepartmentResponse> list() {
        return departments.findAll(Sort.by("name")).stream().map(DepartmentResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('DEPARTMENT_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DepartmentResponse create(@Valid @RequestBody DepartmentRequest request) {
        if (departments.existsByNameIgnoreCase(request.name().trim())) {
            throw ApiException.conflict("Department already exists");
        }
        Department department = departments.save(new Department(request.name().trim()));
        audit.record(AuditAction.DEPARTMENT_CREATED, "DEPARTMENT", department.getId(),
                Map.of("name", department.getName()));
        return DepartmentResponse.from(department);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('DEPARTMENT_WRITE')")
    @Transactional
    public DepartmentResponse rename(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        Department department = departments.findById(id).orElseThrow(() -> ApiException.notFound("Department", id));
        String previous = department.getName();
        department.setName(request.name().trim());
        audit.record(AuditAction.DEPARTMENT_UPDATED, "DEPARTMENT", id,
                Map.of("from", previous, "to", department.getName()));
        return DepartmentResponse.from(department);
    }
}
