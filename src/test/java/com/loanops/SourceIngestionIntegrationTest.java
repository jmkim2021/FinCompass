package com.loanops;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.RawSnapshot;
import com.loanops.source.domain.SourceRun;
import com.loanops.source.domain.SourceRunErrorType;
import com.loanops.source.domain.SourceRunStatus;
import com.loanops.source.repository.RawSnapshotRepository;
import com.loanops.source.repository.SourceRunRepository;
import com.loanops.source.service.IngestionResult;
import com.loanops.source.service.SourceIngestionException;
import com.loanops.source.service.SourceIngestionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
class SourceIngestionIntegrationTest {

    private static final String TEST_API_KEY = "test-api-key";
    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"))
                    .withDatabaseName("loanops_phase2_test")
                    .withUsername("loanops_test")
                    .withPassword("loanops_test");

    @DynamicPropertySource
    static void configureDependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("loanops.sources.fss-finlife.base-url", WIRE_MOCK::baseUrl);
        registry.add("loanops.sources.fss-finlife.path", () -> "/fss");
        registry.add("loanops.sources.fss-finlife.api-key", () -> TEST_API_KEY);
        registry.add("loanops.sources.fss-finlife.read-timeout", () -> "500ms");
        registry.add("loanops.sources.seoul-loan-rate.base-url", WIRE_MOCK::baseUrl);
        registry.add("loanops.sources.seoul-loan-rate.service-name", () -> "dreamMoney");
        registry.add("loanops.sources.seoul-loan-rate.api-key", () -> TEST_API_KEY);
        registry.add("loanops.sources.seoul-loan-rate.read-timeout", () -> "2s");
    }

    @Autowired
    private SourceIngestionService ingestionService;

    @Autowired
    private SourceRunRepository sourceRunRepository;

    @Autowired
    private RawSnapshotRepository rawSnapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        WIRE_MOCK.resetAll();
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    loanops.data_changes,
                    loanops.serving_loan_rate_observations,
                    loanops.serving_product_terms,
                    loanops.serving_loan_products,
                    loanops.publication_runs,
                    loanops.quarantine_records,
                    loanops.data_quality_issues,
                    loanops.loan_rate_observations,
                    loanops.product_terms,
                    loanops.loan_products,
                    loanops.raw_snapshots,
                    loanops.source_runs
                RESTART IDENTITY
                """);
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    void fssSuccessPreservesExactJsonAndHash() throws Exception {
        String payload = fixture("fixtures/fss/mortgage-loans.json");
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .withQueryParam("auth", equalTo(TEST_API_KEY))
                .withQueryParam("topFinGrpNo", equalTo("020000"))
                .withQueryParam("pageNo", equalTo("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(payload)));

        IngestionResult result = ingestionService.ingest(DataSourceCode.FSS_FINLIFE);

        SourceRun run = sourceRunRepository.findById(result.sourceRunId()).orElseThrow();
        RawSnapshot snapshot = rawSnapshotRepository.findBySourceRunId(run.getId()).orElseThrow();
        assertSuccessfulRun(run, 200);
        assertThat(snapshot.getRawPayload()).isEqualTo(payload);
        assertThat(snapshot.getPayloadHash()).isEqualTo(sha256(payload));
        assertThat(result.payloadHash()).isEqualTo(snapshot.getPayloadHash());
        assertThat(snapshot.getContentType()).isEqualTo("application/json;charset=UTF-8");
    }

    @Test
    void seoulSuccessPreservesExactXmlAndHash() throws Exception {
        String payload = fixture("fixtures/seoul/policy-loan-rates.xml");
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/" + TEST_API_KEY + "/xml/dreamMoney/1/5"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml;charset=UTF-8")
                        .withBody(payload)));

        IngestionResult result = ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE);

        SourceRun run = sourceRunRepository.findById(result.sourceRunId()).orElseThrow();
        RawSnapshot snapshot = rawSnapshotRepository.findBySourceRunId(run.getId()).orElseThrow();
        assertSuccessfulRun(run, 200);
        assertThat(snapshot.getRawPayload()).isEqualTo(payload);
        assertThat(snapshot.getPayloadHash()).isEqualTo(sha256(payload));
        assertThat(snapshot.getContentType()).isEqualTo("application/xml;charset=UTF-8");
    }

    @Test
    void seoulHttp200UpstreamErrorPreservesRawSnapshotAndPersistsFailedRun() throws Exception {
        String payload = fixture("fixtures/seoul/upstream-error.xml");
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/" + TEST_API_KEY + "/xml/dreamMoney/1/5"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml;charset=UTF-8")
                        .withBody(payload)));

        SourceIngestionException thrown = assertThrows(
                SourceIngestionException.class,
                () -> ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE)
        );

        SourceRun run = sourceRunRepository.findById(thrown.getSourceRunId()).orElseThrow();
        RawSnapshot snapshot = rawSnapshotRepository.findBySourceRunId(run.getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(SourceRunStatus.FAILED);
        assertThat(run.getHttpStatus()).isEqualTo(200);
        assertThat(run.getErrorType()).isEqualTo(SourceRunErrorType.UPSTREAM_ERROR);
        assertThat(run.getErrorMessage()).isEqualTo("Upstream source returned result code ERROR-500");
        assertThat(thrown.getErrorType()).isEqualTo(SourceRunErrorType.UPSTREAM_ERROR);
        assertThat(thrown).hasMessage("Upstream source returned result code ERROR-500");
        assertThat(snapshot.getRawPayload()).isEqualTo(payload);
        assertThat(snapshot.getPayloadHash()).isEqualTo(sha256(payload));
    }

    @Test
    void fssHttp200UpstreamErrorPreservesRawSnapshotAndPersistsFailedRun() throws Exception {
        String payload = fixture("fixtures/fss/upstream-error.json");
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(payload)));

        SourceIngestionException thrown = assertThrows(
                SourceIngestionException.class,
                () -> ingestionService.ingest(DataSourceCode.FSS_FINLIFE)
        );

        SourceRun run = sourceRunRepository.findById(thrown.getSourceRunId()).orElseThrow();
        RawSnapshot snapshot = rawSnapshotRepository.findBySourceRunId(run.getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(SourceRunStatus.FAILED);
        assertThat(run.getHttpStatus()).isEqualTo(200);
        assertThat(run.getErrorType()).isEqualTo(SourceRunErrorType.UPSTREAM_ERROR);
        assertThat(run.getErrorMessage()).isEqualTo("Upstream source returned result code E101");
        assertThat(snapshot.getRawPayload()).isEqualTo(payload);
        assertThat(snapshot.getPayloadHash()).isEqualTo(sha256(payload));
    }

    @Test
    void http500PersistsFailedRunWithoutRawSnapshot() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse().withStatus(500).withBody("upstream failure")));

        assertThatThrownBy(() -> ingestionService.ingest(DataSourceCode.FSS_FINLIFE))
                .isInstanceOf(SourceIngestionException.class)
                .hasMessage("External API returned HTTP status 500");

        SourceRun run = onlyRun();
        assertThat(run.getStatus()).isEqualTo(SourceRunStatus.FAILED);
        assertThat(run.getErrorType()).isEqualTo(SourceRunErrorType.HTTP_ERROR);
        assertThat(run.getErrorMessage()).isEqualTo("External API returned HTTP status 500");
        assertThat(run.getHttpStatus()).isEqualTo(500);
        assertThat(run.getEndedAt()).isNotNull();
        assertThat(rawSnapshotRepository.findBySourceRunId(run.getId())).isEmpty();
    }

    @Test
    void timeoutPersistsFailedRun() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay((int) Duration.ofSeconds(1).toMillis())
                        .withBody("{}")));

        assertThatThrownBy(() -> ingestionService.ingest(DataSourceCode.FSS_FINLIFE))
                .isInstanceOf(SourceIngestionException.class)
                .hasMessage("External API request timed out");

        SourceRun run = onlyRun();
        assertThat(run.getStatus()).isEqualTo(SourceRunStatus.FAILED);
        assertThat(run.getErrorType()).isEqualTo(SourceRunErrorType.TIMEOUT);
        assertThat(run.getHttpStatus()).isNull();
        assertThat(run.getEndedAt()).isNotNull();
        assertThat(rawSnapshotRepository.findBySourceRunId(run.getId())).isEmpty();
    }

    @Test
    void failedRunRemainsQueryableAfterExceptionLeavesServiceBoundary() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse().withStatus(503)));

        SourceIngestionException thrown = assertThrows(
                SourceIngestionException.class,
                () -> ingestionService.ingest(DataSourceCode.FSS_FINLIFE)
        );

        assertThat(thrown).isNotNull();
        SourceRun persisted = sourceRunRepository.findById(thrown.getSourceRunId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SourceRunStatus.FAILED);
        assertThat(persisted.getErrorType()).isEqualTo(SourceRunErrorType.HTTP_ERROR);
    }

    @Test
    void runningRunIsQueryableWhileExternalCallIsInProgress() throws Exception {
        String payload = fixture("fixtures/seoul/policy-loan-rates.xml");
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/" + TEST_API_KEY + "/xml/dreamMoney/1/5"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withFixedDelay(500)
                        .withBody(payload)));

        CompletableFuture<IngestionResult> ingestion = CompletableFuture.supplyAsync(
                () -> ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE)
        );

        boolean runningWasVisible = waitForRunningRun();
        IngestionResult result = ingestion.get(3, TimeUnit.SECONDS);

        assertThat(runningWasVisible).isTrue();
        assertThat(sourceRunRepository.findById(result.sourceRunId()).orElseThrow().getStatus())
                .isEqualTo(SourceRunStatus.SUCCESS);
    }

    @Test
    void phaseTwoTablesExistOnlyInLoanopsSchema() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'loanops'
                          AND table_name IN ('data_sources', 'source_runs', 'raw_snapshots')
                        ORDER BY table_name
                        """,
                String.class
        );
        Integer publicTableCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('data_sources', 'source_runs', 'raw_snapshots')
                        """,
                Integer.class
        );

        assertThat(tables).containsExactly("data_sources", "raw_snapshots", "source_runs");
        assertThat(publicTableCount).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM loanops.flyway_schema_history WHERE version = '3'",
                Boolean.class
        )).isTrue();
    }

    private SourceRun onlyRun() {
        List<SourceRun> runs = sourceRunRepository.findAll();
        assertThat(runs).hasSize(1);
        return runs.getFirst();
    }

    private boolean waitForRunningRun() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM loanops.source_runs WHERE status = 'RUNNING'",
                    Integer.class
            );
            if (count != null && count > 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private void assertSuccessfulRun(SourceRun run, int expectedHttpStatus) {
        assertThat(run.getStatus()).isEqualTo(SourceRunStatus.SUCCESS);
        assertThat(run.getAttemptCount()).isEqualTo(1);
        assertThat(run.getHttpStatus()).isEqualTo(expectedHttpStatus);
        assertThat(run.getEndedAt()).isNotNull();
        assertThat(run.getErrorType()).isNull();
        assertThat(run.getErrorMessage()).isNull();
    }

    private String fixture(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private String sha256(String payload) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
