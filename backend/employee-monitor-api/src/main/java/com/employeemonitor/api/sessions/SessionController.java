package com.employeemonitor.api.sessions;

import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.sessions.SessionDtos.SessionSummaryResponse;
import com.employeemonitor.api.sessions.SessionDtos.TimelineResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

import com.employeemonitor.api.common.ApiException;

@RestController
@Tag(name = "Employees")
public class SessionController {

    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    @GetMapping("/api/employees/{id}/timeline")
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_READ_ALL', 'EMPLOYEE_READ_ASSIGNED', 'SELF_READ')")
    public TimelineResponse timeline(@PathVariable Long id,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @RequestParam(required = false, defaultValue = "UTC") String tz) {
        return service.timeline(CurrentUser.require(), id, orToday(date, tz), zone(tz));
    }

    @GetMapping("/api/employees/{id}/session-summary")
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_READ_ALL', 'EMPLOYEE_READ_ASSIGNED', 'SELF_READ')")
    public SessionSummaryResponse summary(@PathVariable Long id,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                          @RequestParam(required = false, defaultValue = "UTC") String tz) {
        return service.summary(CurrentUser.require(), id, orToday(date, tz), zone(tz));
    }

    private static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Invalid timezone: " + tz);
        }
    }

    private static LocalDate orToday(LocalDate date, String tz) {
        return date != null ? date : LocalDate.now(zone(tz));
    }
}
