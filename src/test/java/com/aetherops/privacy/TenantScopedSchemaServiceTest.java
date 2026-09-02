package com.aetherops.privacy;

import com.aetherops.privacy.service.TenantScopedSchemaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class TenantScopedSchemaServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private TenantScopedSchemaService schema;

    @Test
    void quoteIdentifier_acceptsOrdinaryTableName() {
        assertThat(schema.quoteIdentifier("incident_events")).isEqualTo("\"incident_events\"");
    }

    @Test
    void quoteIdentifier_rejectsNameWithQuoteCharacter() {
        assertThatThrownBy(() -> schema.quoteIdentifier("incidents\"; DROP TABLE tenants; --"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void quoteIdentifier_rejectsNameWithSpace() {
        assertThatThrownBy(() -> schema.quoteIdentifier("not a table"))
            .isInstanceOf(IllegalStateException.class);
    }
}
