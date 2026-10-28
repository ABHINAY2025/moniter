package com.employeemonitor.api.updates;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UpdateBlobRepository extends JpaRepository<UpdateBlob, String> {
}
