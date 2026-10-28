package com.employeemonitor.api.users;

import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.users.UserDtos.CreateUserRequest;
import com.employeemonitor.api.users.UserDtos.ManagerAssignmentRequest;
import com.employeemonitor.api.users.UserDtos.ManagerAssignmentResponse;
import com.employeemonitor.api.users.UserDtos.UpdateUserRequest;
import com.employeemonitor.api.users.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users & manager assignments")
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return service.list(q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("username")));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return service.update(id, request);
    }

    @GetMapping("/{id}/assigned-employees")
    public List<ManagerAssignmentResponse> assignments(@PathVariable Long id) {
        return service.assignmentsFor(id);
    }

    @PutMapping("/{id}/assigned-employees/{employeeId}")
    public ManagerAssignmentResponse assign(@PathVariable Long id, @PathVariable Long employeeId,
                                            @RequestBody(required = false) ManagerAssignmentRequest request) {
        return service.assign(id, employeeId, request != null && request.liveViewAllowed());
    }

    @DeleteMapping("/{id}/assigned-employees/{employeeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable Long id, @PathVariable Long employeeId) {
        service.unassign(id, employeeId);
    }
}
