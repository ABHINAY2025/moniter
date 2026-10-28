package com.employeemonitor.api.liveview;

import com.employeemonitor.api.liveview.LiveViewDtos.AgentLiveViewStatus;
import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.security.DevicePrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the agent / tray show the on-device live-view indicator. The tray polls this and displays
 * "Live screen: Active" whenever an authorized session is in progress for this device.
 */
@RestController
@RequestMapping("/api/agent/live-view")
@Tag(name = "Agent (device token)")
public class AgentLiveViewController {

    private final LiveViewService service;

    public AgentLiveViewController(LiveViewService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public AgentLiveViewStatus status() {
        DevicePrincipal device = CurrentUser.requireDevice();
        return service.activeForDevice(device.deviceId())
                .map(s -> new AgentLiveViewStatus(true, s.getSessionId()))
                .orElse(new AgentLiveViewStatus(false, null));
    }
}
