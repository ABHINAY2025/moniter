package com.employeemonitor.api.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** USB connect/remove event. Device metadata only; employee files are never inspected or copied. */
@Entity
@Table(name = "usb_events")
public class UsbEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", nullable = false)
    private UUID clientEventId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "device_class")
    private String deviceClass;

    @Column(name = "vendor_id")
    private String vendorId;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "serial_number")
    private String serialNumber;

    private String description;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected UsbEvent() {
    }

    public UsbEvent(UUID clientEventId, Long deviceId, Long employeeId, String eventType, String deviceClass,
                    String vendorId, String productId, String serialNumber, String description, Instant occurredAt) {
        this.clientEventId = clientEventId;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.eventType = eventType;
        this.deviceClass = deviceClass;
        this.vendorId = vendorId;
        this.productId = productId;
        this.serialNumber = serialNumber;
        this.description = description;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getClientEventId() {
        return clientEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getDeviceClass() {
        return deviceClass;
    }

    public String getVendorId() {
        return vendorId;
    }

    public String getProductId() {
        return productId;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getDescription() {
        return description;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
