package com.aetherops.auth.repository;

import com.aetherops.auth.model.TotpBackupCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TotpBackupCodeRepository extends JpaRepository<TotpBackupCode, UUID> {

    List<TotpBackupCode> findByUserIdAndUsedFalse(UUID userId);

    void deleteByUserId(UUID userId);
}
