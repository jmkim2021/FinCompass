package com.loanops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import javax.sql.DataSource;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanOpsApplicationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"))
                    .withDatabaseName("loanops_test")
                    .withUsername("loanops_test")
                    .withPassword("loanops_test");

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void applicationStartsAndConnectsToPostgreSql() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("loanops_test");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'loanops')",
                        Boolean.class))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT success FROM loanops.flyway_schema_history WHERE version = '1'",
                        Boolean.class))
                .isTrue();
    }

    @Test
    void actuatorHealthReportsUp() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void openApiAndSwaggerUiAreAvailable() {
        ResponseEntity<JsonNode> apiDocs =
                restTemplate.getForEntity("/v3/api-docs", JsonNode.class);
        ResponseEntity<String> swaggerUi =
                restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiDocs.getBody()).isNotNull();
        assertThat(apiDocs.getBody().path("openapi").asText()).startsWith("3.");
        assertThat(swaggerUi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(swaggerUi.getBody()).contains("swagger-ui");
    }
}
