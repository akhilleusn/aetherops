package com.aetherops.slack;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slack_workspaces")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlackWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "team_id", nullable = false, length = 20)
    private String teamId;

    @Column(name = "team_name", length = 200)
    private String teamName;

    @Column(name = "bot_user_id", nullable = false, length = 20)
    private String botUserId;

    @Column(name = "encrypted_bot_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedBotToken;

    @Column(length = 500)
    private String scope;

    @CreationTimestamp
    @Column(name = "installed_at", nullable = false, updatable = false)
    private Instant installedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
