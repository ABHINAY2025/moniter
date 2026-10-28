package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.screenshots.ScreenshotDtos.ScreenshotContent;
import com.employeemonitor.api.screenshots.ScreenshotDtos.ScreenshotMetadata;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Admin screenshot access. Listing returns metadata only; fetching an image goes through an authorized, audited
 * endpoint and is returned with no-store caching. Images are never exposed via a public URL.
 */
@RestController
@Tag(name = "Screenshots")
public class ScreenshotController {

    private final ScreenshotService service;

    public ScreenshotController(ScreenshotService service) {
        this.service = service;
    }

    @GetMapping("/api/employees/{id}/screenshots")
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_READ_ALL', 'EMPLOYEE_READ_ASSIGNED', 'SELF_READ')")
    public PageResponse<ScreenshotMetadata> list(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "UTC") String tz,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        LocalDate day = date != null ? date : LocalDate.now(ScreenshotService.zone(tz));
        return service.list(CurrentUser.require(), id, day, ScreenshotService.zone(tz),
                Math.max(page, 0), Math.clamp(size, 1, 500));
    }

    @GetMapping("/api/screenshots/{id}/content")
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_READ_ALL', 'EMPLOYEE_READ_ASSIGNED', 'SELF_READ')")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        ScreenshotContent content = service.view(CurrentUser.require(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(content.bytes());
    }
}
