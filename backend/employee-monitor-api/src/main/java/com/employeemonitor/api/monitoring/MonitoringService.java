package com.employeemonitor.api.monitoring;

import com.employeemonitor.api.alerts.AlertService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.employees.EmployeeAccessService;
import com.employeemonitor.api.monitoring.MonitoringDtos.UsbEventType;
import com.employeemonitor.api.monitoring.MonitoringDtos.AppUsageInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.IngestResult;
import com.employeemonitor.api.monitoring.MonitoringDtos.NetworkEventInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.NetworkEventResponse;
import com.employeemonitor.api.monitoring.MonitoringDtos.UsageReport;
import com.employeemonitor.api.monitoring.MonitoringDtos.UsbEventInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.UsbEventResponse;
import com.employeemonitor.api.monitoring.MonitoringDtos.WebsiteUsageInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.ZscalerEventInput;
import com.employeemonitor.api.monitoring.MonitoringDtos.ZscalerEventResponse;
import com.employeemonitor.api.monitoring.MonitoringDtos.ZscalerStatusResponse;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.security.DevicePrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
public class MonitoringService {

    private final ApplicationUsageRepository apps;
    private final WebsiteUsageRepository websites;
    private final UsbEventRepository usb;
    private final NetworkEventRepository networks;
    private final ZscalerEventRepository zscaler;
    private final EmployeeAccessService access;
    private final AlertService alerts;
    private final Clock clock;

    public MonitoringService(ApplicationUsageRepository apps, WebsiteUsageRepository websites, UsbEventRepository usb,
                             NetworkEventRepository networks, ZscalerEventRepository zscaler,
                             EmployeeAccessService access, AlertService alerts, Clock clock) {
        this.apps = apps;
        this.websites = websites;
        this.usb = usb;
        this.networks = networks;
        this.zscaler = zscaler;
        this.access = access;
        this.alerts = alerts;
        this.clock = clock;
    }

    // ---------- Ingest (agent) ----------

    @Transactional
    public IngestResult ingestApplications(DevicePrincipal device, List<AppUsageInput> events) {
        var prepared = prepare(device.deviceId(), events, AppUsageInput::clientEventId, AppUsageInput::startedAt,
                e -> !e.endedAt().isBefore(e.startedAt()), apps::findExistingClientIds);
        apps.saveAll(prepared.toPersist().stream()
                .map(e -> new ApplicationUsage(e.clientEventId(), device.deviceId(), device.employeeId(),
                        e.applicationName(), e.processName(), e.startedAt(), e.endedAt(), seconds(e.startedAt(), e.endedAt())))
                .toList());
        return prepared.result();
    }

    @Transactional
    public IngestResult ingestWebsites(DevicePrincipal device, List<WebsiteUsageInput> events) {
        var prepared = prepare(device.deviceId(), events, WebsiteUsageInput::clientEventId, WebsiteUsageInput::startedAt,
                e -> !e.endedAt().isBefore(e.startedAt()), websites::findExistingClientIds);
        websites.saveAll(prepared.toPersist().stream()
                .map(e -> new WebsiteUsage(e.clientEventId(), device.deviceId(), device.employeeId(),
                        e.domain(), e.browser(), e.startedAt(), e.endedAt(), seconds(e.startedAt(), e.endedAt())))
                .toList());
        for (WebsiteUsageInput e : prepared.toPersist()) {
            if (alerts.isFlaggedDomain(e.domain())) {
                alerts.raise(AlertService.FLAGGED_WEBSITE, "WARNING", "Visited flagged site: " + e.domain(),
                        device.deviceId(), device.employeeId(), e.domain().trim().toLowerCase());
            }
        }
        return prepared.result();
    }

    @Transactional
    public IngestResult ingestUsb(DevicePrincipal device, List<UsbEventInput> events) {
        var prepared = prepare(device.deviceId(), events, UsbEventInput::clientEventId, UsbEventInput::occurredAt,
                e -> true, usb::findExistingClientIds);
        usb.saveAll(prepared.toPersist().stream()
                .map(e -> new UsbEvent(e.clientEventId(), device.deviceId(), device.employeeId(), e.eventType().name(),
                        e.deviceClass(), e.vendorId(), e.productId(), e.serialNumber(), e.description(), e.occurredAt()))
                .toList());
        for (UsbEventInput e : prepared.toPersist()) {
            if (e.eventType() == UsbEventType.CONNECTED) {
                String what = e.description() != null && !e.description().isBlank() ? e.description()
                        : (e.deviceClass() != null && !e.deviceClass().isBlank() ? e.deviceClass() : "USB device");
                String dedup = e.serialNumber() != null && !e.serialNumber().isBlank() ? e.serialNumber() : what;
                alerts.raise(AlertService.USB_CONNECTED, "WARNING", "USB device connected: " + what,
                        device.deviceId(), device.employeeId(), dedup);
            }
        }
        return prepared.result();
    }

    @Transactional
    public IngestResult ingestNetwork(DevicePrincipal device, List<NetworkEventInput> events) {
        var prepared = prepare(device.deviceId(), events, NetworkEventInput::clientEventId, NetworkEventInput::occurredAt,
                e -> true, networks::findExistingClientIds);
        networks.saveAll(prepared.toPersist().stream()
                .map(e -> new NetworkEvent(e.clientEventId(), device.deviceId(), device.employeeId(), e.eventType().name(),
                        e.connectionType() == null ? null : e.connectionType().name(), e.networkName(), e.ipAddress(),
                        e.occurredAt()))
                .toList());
        return prepared.result();
    }

    @Transactional
    public IngestResult ingestZscaler(DevicePrincipal device, List<ZscalerEventInput> events) {
        var prepared = prepare(device.deviceId(), events, ZscalerEventInput::clientEventId, ZscalerEventInput::occurredAt,
                e -> true, zscaler::findExistingClientIds);
        zscaler.saveAll(prepared.toPersist().stream()
                .map(e -> new ZscalerEvent(e.clientEventId(), device.deviceId(), device.employeeId(),
                        e.status().name(), e.detail(), e.occurredAt()))
                .toList());
        return prepared.result();
    }

    // ---------- Queries (admin) ----------

    @Transactional(readOnly = true)
    public UsageReport applications(AuthenticatedUser user, Long employeeId, LocalDate date, ZoneId zone) {
        access.requireRead(user, employeeId);
        Window w = windowFor(date, zone);
        List<UsageSlice> items = apps.aggregate(employeeId, w.start(), w.end());
        return new UsageReport(employeeId, date.toString(), zone.getId(), totalOf(items), items);
    }

    @Transactional(readOnly = true)
    public UsageReport websites(AuthenticatedUser user, Long employeeId, LocalDate date, ZoneId zone) {
        access.requireRead(user, employeeId);
        Window w = windowFor(date, zone);
        List<UsageSlice> items = websites.aggregate(employeeId, w.start(), w.end());
        return new UsageReport(employeeId, date.toString(), zone.getId(), totalOf(items), items);
    }

    @Transactional(readOnly = true)
    public PageResponse<UsbEventResponse> usbEvents(AuthenticatedUser user, Long employeeId, LocalDate date,
                                                    ZoneId zone, int page, int size) {
        access.requireRead(user, employeeId);
        Window w = windowFor(date, zone);
        return PageResponse.of(usb.findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                employeeId, w.start(), w.end(), PageRequest.of(page, size)), UsbEventResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<NetworkEventResponse> networkEvents(AuthenticatedUser user, Long employeeId, LocalDate date,
                                                            ZoneId zone, int page, int size) {
        access.requireRead(user, employeeId);
        Window w = windowFor(date, zone);
        return PageResponse.of(networks.findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                employeeId, w.start(), w.end(), PageRequest.of(page, size)), NetworkEventResponse::from);
    }

    @Transactional(readOnly = true)
    public ZscalerStatusResponse zscalerStatus(AuthenticatedUser user, Long employeeId) {
        access.requireRead(user, employeeId);
        var latest = zscaler.findFirstByEmployeeIdOrderByOccurredAtDesc(employeeId).orElse(null);
        var history = zscaler.findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                employeeId, Instant.EPOCH, clock.instant().plus(Duration.ofMinutes(5)), PageRequest.of(0, 20));
        return new ZscalerStatusResponse(
                latest == null ? "UNKNOWN" : latest.getStatus(),
                latest == null ? null : latest.getOccurredAt(),
                history.getContent().stream().map(ZscalerEventResponse::from).toList());
    }

    // ---------- Internals ----------

    private record Prepared<I>(List<I> toPersist, IngestResult result) {
    }

    /** Rejects clock-skewed / invalid events and in-batch duplicates, then drops ids already stored. */
    private <I> Prepared<I> prepare(Long deviceId, List<I> inputs, Function<I, UUID> idOf, Function<I, Instant> timeOf,
                                    Predicate<I> valid, BiFunction<Long, Collection<UUID>, Set<UUID>> existing) {
        Instant now = clock.instant();
        Instant maxFuture = now.plus(Duration.ofMinutes(5));
        Instant minPast = now.minus(Duration.ofDays(7));

        List<I> candidates = new ArrayList<>();
        Set<UUID> batchIds = new HashSet<>();
        int rejected = 0;
        for (I input : inputs) {
            Instant t = timeOf.apply(input);
            if (!valid.test(input) || t.isAfter(maxFuture) || t.isBefore(minPast) || !batchIds.add(idOf.apply(input))) {
                rejected++;
            } else {
                candidates.add(input);
            }
        }
        if (candidates.isEmpty()) {
            return new Prepared<>(List.of(), new IngestResult(0, 0, rejected));
        }
        Set<UUID> already = existing.apply(deviceId, candidates.stream().map(idOf).toList());
        List<I> toPersist = candidates.stream().filter(c -> !already.contains(idOf.apply(c))).toList();
        return new Prepared<>(toPersist, new IngestResult(toPersist.size(), candidates.size() - toPersist.size(), rejected));
    }

    private static int seconds(Instant start, Instant end) {
        long s = Duration.between(start, end).getSeconds();
        return (int) Math.max(0, Math.min(s, Integer.MAX_VALUE));
    }

    private static long totalOf(List<UsageSlice> items) {
        return items.stream().mapToLong(i -> i.totalSeconds() == null ? 0 : i.totalSeconds()).sum();
    }

    private record Window(Instant start, Instant end) {
    }

    private Window windowFor(LocalDate date, ZoneId zone) {
        if (date == null) {
            throw ApiException.badRequest("date is required (YYYY-MM-DD)");
        }
        try {
            return new Window(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Invalid date/timezone");
        }
    }
}
