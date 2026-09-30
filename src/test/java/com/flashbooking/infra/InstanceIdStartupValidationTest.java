package com.flashbooking.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.flashbooking.FlashBookingApplication;
import com.flashbooking.support.AbstractIntegrationTest;

/** D6: INSTANCE_ID invalido (nao cabe em reservation_history.instance_id VARCHAR(30)) derruba a subida. */
class InstanceIdStartupValidationTest {

    @ParameterizedTest
    @ValueSource(strings = { "local", "api1", "a.b_c-d", "123456789012345678901234567890" })
    void validInstanceIdsAreAccepted(String id) {
        assertThat(new AuditContext(id).instanceId()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "1234567890123456789012345678901", "api 1", "api/1", "api\"1", "ação" })
    void invalidInstanceIdsAreRejectedWithClearMessage(String id) {
        assertThatThrownBy(() -> new AuditContext(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSTANCE_ID")
                .hasMessageContaining("30");
    }

    @Test
    void applicationFailsToStartWithTooLongInstanceId() {
        var postgres = AbstractIntegrationTest.POSTGRES;

        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(FlashBookingApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("test")
                    .run("--INSTANCE_ID=" + "x".repeat(31),
                            "--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(),
                            "--spring.main.banner-mode=off")) {
                // nao deveria subir
            }
        }).hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("INSTANCE_ID");
    }
}
