package com.employeemonitor.api.updates;

import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.updates.UpdateDtos.UpdateAvailableResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Update check and download for the agent (device token). Only approved versions are served. */
@RestController
@RequestMapping("/api/agent/update")
@Tag(name = "Agent (device token)")
public class AgentUpdateController {

    private final UpdateService service;

    public AgentUpdateController(UpdateService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<UpdateAvailableResponse> check(@RequestParam(defaultValue = "0.0.0") String current) {
        CurrentUser.requireDevice();
        return service.latestUpdate(current)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{version}/package")
    public ResponseEntity<byte[]> download(@PathVariable String version) {
        CurrentUser.requireDevice();
        byte[] bytes = service.packageBytes(version);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                .body(bytes);
    }
}
