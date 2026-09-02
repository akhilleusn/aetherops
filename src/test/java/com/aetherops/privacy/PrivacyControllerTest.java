package com.aetherops.privacy;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.privacy.controller.PrivacyController;
import com.aetherops.privacy.model.TenantDataExportResponse;
import com.aetherops.privacy.model.TenantErasureResult;
import com.aetherops.privacy.service.TenantDataExportService;
import com.aetherops.privacy.service.TenantErasureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivacyControllerTest {

    @Mock private TenantDataExportService exportService;
    @Mock private TenantErasureService erasureService;

    @InjectMocks
    private PrivacyController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void export_delegatesToServiceAndWrapsResult() {
        TenantDataExportResponse export = TenantDataExportResponse.builder().tenantId(tenantId).build();
        when(exportService.export(tenantId)).thenReturn(export);

        ApiResponse<TenantDataExportResponse> response = controller.export(tenantId);

        assertThat(response.getData()).isSameAs(export);
        verify(exportService).export(tenantId);
    }

    @Test
    void erase_defaultsDryRunToTrue() {
        when(erasureService.erase(tenantId, true))
            .thenReturn(TenantErasureResult.builder().tenantId(tenantId).dryRun(true).build());

        controller.erase(tenantId, true);

        verify(erasureService).erase(tenantId, true);
    }

    @Test
    void erase_passesThroughExplicitDryRunFalse() {
        when(erasureService.erase(tenantId, false))
            .thenReturn(TenantErasureResult.builder().tenantId(tenantId).dryRun(false).tenantDeleted(true).build());

        ApiResponse<TenantErasureResult> response = controller.erase(tenantId, false);

        assertThat(response.getData().isTenantDeleted()).isTrue();
        verify(erasureService).erase(tenantId, false);
    }
}
