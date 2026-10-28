package com.employeemonitor.api.monitoring;

import com.employeemonitor.api.monitoring.MonitoringDtos.AppUsageInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.Batch;
import com.employeemonitor.api.monitoring.MonitoringDtos.IngestResult;
import com.employeemonitor.api.monitoring.MonitoringDtos.NetworkEventInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.UsbEventInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.WebsiteUsageInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.ZscalerEventInput;
import com.employeemonitor.api.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Monitoring-event ingest, called by the agent with its device token. All batches are idempotent. */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent (device token)")
public class AgentMonitoringController {

    private final MonitoringService service;

    public AgentMonitoringController(MonitoringService service) {
        this.service = service;
    }

    @PostMapping("/application-usage")
    public IngestResult applications(@Valid @RequestBody Batch<AppUsageInput> batch) {
        return service.ingestApplications(CurrentUser.requireDevice(), batch.events());
    }

    @PostMapping("/website-usage")
    public IngestResult websites(@Valid @RequestBody Batch<WebsiteUsageInput> batch) {
        return service.ingestWebsites(CurrentUser.requireDevice(), batch.events());
    }

    @PostMapping("/usb-events")
    public IngestResult usb(@Valid @RequestBody Batch<UsbEventInput> batch) {
        return service.ingestUsb(CurrentUser.requireDevice(), batch.events());
    }

    @PostMapping("/network-events")
    public IngestResult network(@Valid @RequestBody Batch<NetworkEventInput> batch) {
        return service.ingestNetwork(CurrentUser.requireDevice(), batch.events());
    }

    @PostMapping("/zscaler-events")
    public IngestResult zscaler(@Valid @RequestBody Batch<ZscalerEventInput> batch) {
        return service.ingestZscaler(CurrentUser.requireDevice(), batch.events());
    }
}
