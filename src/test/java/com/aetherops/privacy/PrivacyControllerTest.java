package com.aetherops.privacy;

import com.aetherops.common.exception.ForbiddenException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.privacy.controller.PrivacyController;
import com.aetherops.privacy.model.TenantDataExportResponse;
import com.aetherops.privacy.model.TenantErasureResult;
import com.aetherops.privacy.service.TenantDataExportService;
import com.aetherops.privacy.service.TenantErasureService;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivacyControllerTest {

    @Mock private TenantDataExportService exportService;
    @Mock private TenantErasureService erasureService;

    @InjectMocks
    private PrivacyController controller;

    private final UUID tenantId = UUID.randomUUID();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void export_delegatesToServiceAndWrapsResult() {
        TenantContext.set(tenantId);
        TenantDataExportResponse export = TenantDataExportResponse.builder().tenantId(tenantId).build();
        when(exportService.export(tenantId)).thenReturn(export);

        ApiResponse<TenantDataExportResponse> response = controller.export(tenantId);

        assertThat(response.getData()).isSameAs(export);
        verify(exportService).export(tenantId);
    }

    @Test
    void export_callerFromAnotherTenant_isForbidden() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> controller.export(tenantId))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(exportService);
    }

    @Test
    void export_noCallerContext_isForbidden() {
        assertThatThrownBy(() -> controller.export(tenantId))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(exportService);
    }

    @Test
    void erase_defaultsDryRunToTrue() {
        TenantContext.set(tenantId);
        when(erasureService.erase(tenantId, true))
            .thenReturn(TenantErasureResult.builder().tenantId(tenantId).dryRun(true).build());

        controller.erase(tenantId, true);

        verify(erasureService).erase(tenantId, true);
    }

    @Test
    void erase_passesThroughExplicitDryRunFalse() {
        TenantContext.set(tenantId);
        when(erasureService.erase(tenantId, false))
            .thenReturn(TenantErasureResult.builder().tenantId(tenantId).dryRun(false).tenantDeleted(true).build());

        ApiResponse<TenantErasureResult> response = controller.erase(tenantId, false);

        assertThat(response.getData().isTenantDeleted()).isTrue();
        verify(erasureService).erase(tenantId, false);
    }

    @Test
    void erase_callerFromAnotherTenant_isForbidden() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> controller.erase(tenantId, false))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(erasureService);
    }
}
