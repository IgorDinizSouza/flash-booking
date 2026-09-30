package com.flashbooking.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Container estatico compartilhado por todas as classes de teste (singleton; o Ryuk o remove ao fim da JVM).
    @ServiceConnection
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            // varios contextos Spring em cache (pools de 20) + as duas instancias do teste multi-instancia
            .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }
}
