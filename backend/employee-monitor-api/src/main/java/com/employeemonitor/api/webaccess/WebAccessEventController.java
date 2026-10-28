package com.employeemonitor.api.webaccess;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.employees.EmployeeAccessService;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.users.Permissions;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebAccessEventResponse;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebAccessSummaryResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/web-access-events")
@Tag(name = "Web access events")
@PreAuthorize("hasAuthority('WEB_EVENT_READ')")
public class WebAccessEventController {

    private final WebAccessEventService service;
    private final EmployeeAccessService access;

    public WebAccessEventController(WebAccessEventService service, EmployeeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public PageResponse<WebAccessEventResponse> list(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AuthenticatedUser user = CurrentUser.require();
        if (!user.has(Permissions.EMPLOYEE_READ_ALL)) {
            if (employeeId == null) {
                throw ApiException.badRequest("Specify an employee you are authorized to view");
            }
            access.requireRead(user, employeeId);
        }
        return service.query(deviceId, employeeId, eventType, result, domain, from, to,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200),
                        Sort.by(Sort.Direction.DESC, "occurredAt")));
    }

    @GetMapping("/summary")
    public WebAccessSummaryResponse summary(@RequestParam(defaultValue = "7") int days) {
        if (!CurrentUser.require().has(Permissions.EMPLOYEE_READ_ALL)) {
            throw ApiException.badRequest("Summary is available to administrators with full employee visibility");
        }
        Instant since = Instant.now().minusSeconds(Math.clamp((long) days, 1, 365) * 86400L);
        return service.summary(since);
    }

    @GetMapping("/{id}")
    public WebAccessEventResponse get(@PathVariable Long id) {
        return service.getById(id);
    }
}
