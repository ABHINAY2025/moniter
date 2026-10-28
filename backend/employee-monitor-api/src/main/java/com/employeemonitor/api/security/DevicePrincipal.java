package com.employeemonitor.api.security;

/** Principal for a monitoring agent authenticated with its device token. */
public record DevicePrincipal(Long deviceId, String deviceCode, Long employeeId, Long tokenId) {
}
