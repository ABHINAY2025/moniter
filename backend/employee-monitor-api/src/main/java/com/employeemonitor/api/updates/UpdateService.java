package com.employeemonitor.api.updates;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.config.AppProperties;
import com.employeemonitor.api.updates.UpdateDtos.PublishRequest;
import com.employeemonitor.api.updates.UpdateDtos.UpdateAvailableResponse;
import com.employeemonitor.api.updates.UpdateDtos.VersionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class UpdateService {

    private final AgentVersionRepository versions;
    private final UpdatePackageStorage storage;
    private final AppProperties.Updates props;

    public UpdateService(AgentVersionRepository versions, UpdatePackageStorage storage, AppProperties.Updates props) {
        this.versions = versions;
        this.storage = storage;
        this.props = props;
    }

    @Transactional
    public VersionResponse publish(Long userId, PublishRequest request) {
        if (!SemanticVersion.isValid(request.version())) {
            throw ApiException.badRequest("version must be major.minor.patch");
        }
        if (versions.existsByVersion(request.version().trim())) {
            throw ApiException.conflict("Version already exists");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.packageBase64());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("packageBase64 is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > props.maxBytes()) {
            throw ApiException.badRequest("Package size must be between 1 and " + props.maxBytes() + " bytes");
        }
        String sha256 = sha256Hex(bytes);
        String key = "agent-" + request.version().trim() + ".pkg";
        storage.put(key, bytes);
        AgentVersion version = new AgentVersion(request.version().trim(), key, sha256, request.signature(),
                bytes.length, "application/octet-stream", userId, request.notes());
        version.setMandatory(request.mandatory());
        versions.save(version);
        return VersionResponse.from(version);
    }

    @Transactional
    public VersionResponse setApproval(Long id, boolean approved) {
        AgentVersion version = versions.findById(id).orElseThrow(() -> ApiException.notFound("Agent version", id));
        version.setApproved(approved);
        return VersionResponse.from(version);
    }

    @Transactional(readOnly = true)
    public List<VersionResponse> list() {
        return versions.findAllByOrderByReleasedAtDesc().stream().map(VersionResponse::from).toList();
    }

    /** The newest approved version strictly greater than {@code currentVersion}, if any. */
    @Transactional(readOnly = true)
    public Optional<UpdateAvailableResponse> latestUpdate(String currentVersion) {
        SemanticVersion current = SemanticVersion.isValid(currentVersion)
                ? SemanticVersion.parse(currentVersion) : new SemanticVersion(0, 0, 0);
        return versions.findByApprovedTrueOrderByReleasedAtDesc().stream()
                .filter(v -> SemanticVersion.isValid(v.getVersion()))
                .max(Comparator.comparing(v -> SemanticVersion.parse(v.getVersion())))
                .filter(v -> SemanticVersion.parse(v.getVersion()).compareTo(current) > 0)
                .map(v -> new UpdateAvailableResponse(v.getVersion(), v.getSha256(), v.getSignature(),
                        v.isMandatory(), v.getSizeBytes() == null ? 0 : v.getSizeBytes(),
                        "/api/agent/update/" + v.getVersion() + "/package"));
    }

    @Transactional(readOnly = true)
    public byte[] packageBytes(String version) {
        AgentVersion v = versions.findByVersion(version)
                .filter(AgentVersion::isApproved)
                .orElseThrow(() -> ApiException.notFound("Agent version", version));
        return storage.get(v.getDownloadKey());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
