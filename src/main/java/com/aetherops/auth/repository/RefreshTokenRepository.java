package com.aetherops.auth.repository;

import com.aetherops.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.userId = :userId")
    void deleteAllByUserId(UUID userId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.familyId = :familyId")
    void revokeFamily(UUID familyId);
}