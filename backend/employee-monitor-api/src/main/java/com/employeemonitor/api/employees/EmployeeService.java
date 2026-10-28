package com.employeemonitor.api.employees;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.devices.DeviceService;
import com.employeemonitor.api.employees.EmployeeDtos.CreateEmployeeRequest;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeDetailResponse;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeResponse;
import com.employeemonitor.api.employees.EmployeeDtos.UpdateEmployeeRequest;
import com.employeemonitor.api.security.AuthenticatedUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final DepartmentRepository departments;
    private final EmployeeAccessService access;
    private final DeviceService deviceService;
    private final AuditService audit;

    public EmployeeService(EmployeeRepository employees, DepartmentRepository departments,
                           EmployeeAccessService access, DeviceService deviceService, AuditService audit) {
        this.employees = employees;
        this.departments = departments;
        this.access = access;
        this.deviceService = deviceService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployeeResponse> list(AuthenticatedUser user, String q, Long departmentId,
                                               Employee.Status status, Pageable pageable) {
        Specification<Employee> spec = access.visibleEmployees(user);
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("fullName")), like),
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("employeeCode")), like)));
        }
        if (departmentId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("department").get("id"), departmentId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return PageResponse.of(employees.findAll(spec, pageable), EmployeeResponse::from);
    }

    @Transactional(readOnly = true)
    public EmployeeDetailResponse get(AuthenticatedUser user, Long id) {
        access.requireRead(user, id);
        Employee employee = load(id);
        if (!id.equals(user.employeeId())) {
            audit.record(AuditAction.EMPLOYEE_VIEWED, "EMPLOYEE", id, null);
        }
        return new EmployeeDetailResponse(EmployeeResponse.from(employee), deviceService.forEmployee(id));
    }

    @Transactional
    public EmployeeResponse create(CreateEmployeeRequest request) {
        if (employees.existsByEmployeeCodeIgnoreCase(request.employeeCode())) {
            throw ApiException.conflict("Employee code already exists");
        }
        if (employees.existsByEmailIgnoreCase(request.email())) {
            throw ApiException.conflict("Email already exists");
        }
        Employee employee = new Employee(request.employeeCode().trim(), request.fullName().trim(),
                request.email().trim().toLowerCase());
        employee.setJobTitle(request.jobTitle());
        if (request.departmentId() != null) {
            employee.setDepartment(loadDepartment(request.departmentId()));
        }
        employee = employees.save(employee);
        audit.record(AuditAction.EMPLOYEE_CREATED, "EMPLOYEE", employee.getId(),
                Map.of("employeeCode", employee.getEmployeeCode()));
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse update(Long id, UpdateEmployeeRequest request) {
        Employee employee = load(id);
        Map<String, Object> changed = new HashMap<>();
        if (request.fullName() != null) {
            employee.setFullName(request.fullName().trim());
            changed.put("fullName", true);
        }
        if (request.email() != null && !request.email().equalsIgnoreCase(employee.getEmail())) {
            if (employees.existsByEmailIgnoreCase(request.email())) {
                throw ApiException.conflict("Email already exists");
            }
            employee.setEmail(request.email().trim().toLowerCase());
            changed.put("email", true);
        }
        if (Boolean.TRUE.equals(request.clearDepartment())) {
            employee.setDepartment(null);
            changed.put("departmentId", null);
        } else if (request.departmentId() != null) {
            employee.setDepartment(loadDepartment(request.departmentId()));
            changed.put("departmentId", request.departmentId());
        }
        if (request.jobTitle() != null) {
            employee.setJobTitle(request.jobTitle());
            changed.put("jobTitle", true);
        }
        if (request.status() != null && request.status() != employee.getStatus()) {
            employee.setStatus(request.status());
            changed.put("status", request.status().name());
        }
        audit.record(AuditAction.EMPLOYEE_UPDATED, "EMPLOYEE", id, Map.of("changed", changed));
        return EmployeeResponse.from(employee);
    }

    private Employee load(Long id) {
        return employees.findById(id).orElseThrow(() -> ApiException.notFound("Employee", id));
    }

    private Department loadDepartment(Long id) {
        return departments.findById(id).orElseThrow(() -> ApiException.notFound("Department", id));
    }
}
