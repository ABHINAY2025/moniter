package com.employeemonitor.api.monitoring;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.monitoring.MonitoringDtos.NetworkEventResponse;
import com.employeemonitor.api.monitoring.MonitoringDtos.UsageReport;
import com.employeemonitor.api.monitoring.MonitoringDtos.UsbEventResponse;
import com.employeemonitor.api.monitoring.MonitoringDtos.ZscalerStatusResponse;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

/** Employee-scoped monitoring queries for the admin app. Row-level access is enforced per employee. */
@RestController
@RequestMapping("/api/employees/{id}")
@Tag(name = "Employees")
@PreAuthorize("hasAnyAuthority('EMPLOYEE_READ_ALL', 'EMPLOYEE_READ_ASSIGNED', 'SELF_READ')")
public class MonitoringQueryController {

    private final MonitoringService service;

    public MonitoringQueryController(MonitoringService service) {
        this.service = service;
    }

    @GetMapping("/applications")
    public UsageReport applications(@PathVariable Long id,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    @RequestParam(defaultValue = "UTC") String tz) {
        return service.applications(CurrentUser.require(), id, orToday(date, tz), zone(tz));
    }

    @GetMapping("/websites")
    public UsageReport websites(@PathVariable Long id,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @RequestParam(defaultValue = "UTC") String tz) {
        return service.websites(CurrentUser.require(), id, orToday(date, tz), zone(tz));
    }

    @GetMapping("/usb-events")
    public PageResponse<UsbEventResponse> usb(@PathVariable Long id,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                              @RequestParam(defaultValue = "UTC") String tz,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "100") int size) {
        return service.usbEvents(CurrentUser.require(), id, orToday(date, tz), zone(tz),
                Math.max(page, 0), Math.clamp(size, 1, 500));
    }

    @GetMapping("/network-events")
    public PageResponse<NetworkEventResponse> network(@PathVariable Long id,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                      @RequestParam(defaultValue = "UTC") String tz,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "100") int size) {
        return service.networkEvents(CurrentUser.require(), id, orToday(date, tz), zone(tz),
                Math.max(page, 0), Math.clamp(size, 1, 500));
    }

    @GetMapping("/zscaler")
    public ZscalerStatusResponse zscaler(@PathVariable Long id) {
        return service.zscalerStatus(CurrentUser.require(), id);
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
