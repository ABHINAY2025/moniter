package com.employeemonitor.api.screenshots;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreenshotBlobRepository extends JpaRepository<ScreenshotBlob, String> {
}
