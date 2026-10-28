package com.employeemonitor.api.webaccess;

import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.webaccess.WebAccessDtos.AssignRequest;
import com.employeemonitor.api.webaccess.WebAccessDtos.AssignmentResponse;
import com.employeemonitor.api.webaccess.WebAccessDtos.CreateWebPolicyRequest;
import com.employeemonitor.api.webaccess.WebAccessDtos.UpdateWebPolicyRequest;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebPolicyResponse;
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

import java.util.List;

@RestController
@RequestMapping("/api/web-policies")
@Tag(name = "Web access policies")
public class WebPolicyController {

    private final WebPolicyService service;

    public WebPolicyController(WebPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('WEB_POLICY_READ')")
    public PageResponse<WebPolicyResponse> list(@RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return service.list(q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200),
                Sort.by(Sort.Direction.DESC, "priority").and(Sort.by("name"))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('WEB_POLICY_READ')")
    public WebPolicyResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('WEB_POLICY_MANAGE')")
    public WebPolicyResponse create(@Valid @RequestBody CreateWebPolicyRequest request) {
        return service.create(request, CurrentUser.require().id());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('WEB_POLICY_MANAGE')")
    public WebPolicyResponse update(@PathVariable Long id, @Valid @RequestBody UpdateWebPolicyRequest request) {
        return service.update(id, request, CurrentUser.require().id());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('WEB_POLICY_MANAGE')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/{id}/assignments")
    @PreAuthorize("hasAuthority('WEB_POLICY_READ')")
    public List<AssignmentResponse> assignments(@PathVariable Long id) {
        return service.listAssignments(id);
    }

    @PostMapping("/{id}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('WEB_POLICY_MANAGE')")
    public AssignmentResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return service.assign(id, request.targetType(), request.targetId());
    }

    @DeleteMapping("/{id}/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('WEB_POLICY_MANAGE')")
    public void unassign(@PathVariable Long id, @PathVariable Long assignmentId) {
        service.unassign(id, assignmentId);
    }
}
