package com.employeemonitor.api.employees;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class EmployeeDtos {

    private EmployeeDtos() {
    }

    public record DepartmentRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record DepartmentResponse(Long id, String name) {
        public static DepartmentResponse from(Department d) {
            return d == null ? null : new DepartmentResponse(d.getId(), d.getName());
        }
    }

    public record CreateEmployeeRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+",
                    message = "may contain letters, digits, '.', '_' and '-' only") String employeeCode,
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            Long departmentId,
            @Size(max = 120) String jobTitle) {
    }

    /** Partial update: null fields are left unchanged. */
    public record UpdateEmployeeRequest(
            @Size(min = 1, max = 160) String fullName,
            @Email @Size(max = 255) String email,
            Long departmentId,
            Boolean clearDepartment,
            @Size(max = 120) String jobTitle,
            Employee.Status status) {
    }

    public record EmployeeResponse(Long id, String employeeCode, String fullName, String email,
                                   DepartmentResponse department, String jobTitle, Employee.Status status,
                                   Instant createdAt, Instant updatedAt) {
        public static EmployeeResponse from(Employee e) {
            return new EmployeeResponse(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getEmail(),
                    DepartmentResponse.from(e.getDepartment()), e.getJobTitle(), e.getStatus(),
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record EmployeeSummary(Long id, String employeeCode, String fullName, String department) {
        public static EmployeeSummary from(Employee e) {
            return e == null ? null : new EmployeeSummary(e.getId(), e.getEmployeeCode(), e.getFullName(),
                    e.getDepartment() == null ? null : e.getDepartment().getName());
        }
    }

    /** Employee detail, including devices with live presence. Device type lives in the devices package. */
    public record EmployeeDetailResponse(EmployeeResponse employee,
                                         List<com.employeemonitor.api.devices.DeviceDtos.DeviceResponse> devices) {
    }
}
