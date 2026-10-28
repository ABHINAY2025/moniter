package com.employeemonitor.api.devices;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface EnrollmentTokenRepository extends JpaRepository<EnrollmentToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from EnrollmentToken t where t.tokenHash = :hash")
    Optional<EnrollmentToken> findByHashForUpdate(String hash);

    Page<EnrollmentToken> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
