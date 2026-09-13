package com.loanops;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.loanops.normalization.SourceNormalizationService;
import com.loanops.publication.PublicationResult;
import com.loanops.publication.PublicationService;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.service.IngestionResult;
import com.loanops.source.service.SourceIngestionException;
import com.loanops.source.service.SourceIngestionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReadApiIntegrationTest {

    private static final String TEST_API_KEY = "read-api-secret-key";
    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"))
                    .withDatabaseName("loanops_read_api_test")
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
        registry.add("loanops.sources.seoul-loan-rate.base-url", WIRE_MOCK::baseUrl);
        registry.add("loanops.sources.seoul-loan-rate.service-name", () -> "dreamMoney");
        registry.add("loanops.sources.seoul-loan-rate.api-key", () -> TEST_API_KEY);
        registry.add("loanops.sources.seoul-loan-rate.page-size", () -> "5");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SourceIngestionService ingestionService;
    @Autowired private SourceNormalizationService normalizationService;
    @Autowired private PublicationService publicationService;

    private long validFssRunId;
    private long invalidFssRunId;
    private long validSeoulRunId;
    private long failedSeoulRunId;
    private long rejectedFssPublicationId;
    private long servingProductId;

    @BeforeEach
    void createOperationalHistory() throws Exception {
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

        validFssRunId = ingestAndNormalizeFss(fixture("fixtures/fss/mortgage-loans.json"));
        publicationService.publish(validFssRunId);

        String invalidFss = fixture("fixtures/fss/mortgage-loans.json")
                .replace("\"lend_rate_min\": \"3.96\"", "\"lend_rate_min\": \"-1.00\"");
        invalidFssRunId = ingestAndNormalizeFss(invalidFss);
        PublicationResult rejectedFss = publicationService.publish(invalidFssRunId);
        rejectedFssPublicationId = rejectedFss.publicationRunId();

        validSeoulRunId = ingestAndNormalizeSeoul(fixture("fixtures/seoul/policy-loan-rates.xml"));
        publicationService.publish(validSeoulRunId);

        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlPathEqualTo(seoulPath(1, 5)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml;charset=UTF-8")
                        .withBody(fixture("fixtures/seoul/upstream-error.xml"))));
        SourceIngestionException failure = assertThrows(
                SourceIngestionException.class,
                () -> ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE)
        );
        failedSeoulRunId = failure.getSourceRunId();
        servingProductId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM loanops.serving_loan_products",
                Long.class
        );
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    void overviewReturnsLifecycleAndServingCounts() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCount").value(2))
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.servingLoanProductCount").value(1))
                .andExpect(jsonPath("$.servingProductTermCount").value(2))
                .andExpect(jsonPath("$.servingSeoulObservationCount").value(1))
                .andExpect(jsonPath("$.currentQuarantineCount").value(1))
                .andExpect(jsonPath("$.totalQualityIssueCount").value(1))
                .andExpect(jsonPath("$.recentDataChangeCount").value(4));
    }

    @Test
    void sourceRunListSupportsFilteringAndDeterministicPagination() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/source-runs").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].id").value(failedSeoulRunId))
                .andExpect(jsonPath("$.content[1].id").value(validSeoulRunId));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/source-runs")
                        .param("source", "FSS_FINLIFE")
                        .param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(invalidFssRunId));
    }

    @Test
    void sourceRunDetailReturnsMetadataWithoutRawPayloadOrCredentials() throws Exception {
        String response = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/source-runs/{id}", failedSeoulRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceRun.status").value("FAILED"))
                .andExpect(jsonPath("$.sourceRun.httpStatus").value(200))
                .andExpect(jsonPath("$.sourceRun.errorType").value("UPSTREAM_ERROR"))
                .andExpect(jsonPath("$.rawSnapshots.length()").value(1))
                .andExpect(jsonPath("$.rawSnapshots[0].payloadHash").isString())
                .andExpect(jsonPath("$.rawSnapshots[0].payloadSize").isNumber())
                .andExpect(jsonPath("$.rawSnapshots[0].rawPayload").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(TEST_API_KEY).doesNotContain("apiKey");
    }

    @Test
    void publicationListAndDetailReturnQualityEvidence() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/publications").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].sourceRunId").value(validSeoulRunId));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/publications/{id}", rejectedFssPublicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publication.status").value("REJECTED_QUALITY"))
                .andExpect(jsonPath("$.publication.issueCount").value(1))
                .andExpect(jsonPath("$.qualityIssues.length()").value(1))
                .andExpect(jsonPath("$.quarantineRecords.length()").value(1))
                .andExpect(jsonPath("$.dataChanges.length()").value(0));
    }

    @Test
    void qualityIssueFiltersReturnOnlyMatchingEvidence() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/quality-issues")
                        .param("sourceRunId", String.valueOf(invalidFssRunId))
                        .param("recordType", "PRODUCT_TERM")
                        .param("ruleCode", "NEGATIVE_RATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceRunId").value(invalidFssRunId))
                .andExpect(jsonPath("$.content[0].ruleCode").value("NEGATIVE_RATE"));
    }

    @Test
    void dataChangeFiltersReturnDeterministicServingChanges() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data-changes")
                        .param("sourceRunId", String.valueOf(validFssRunId))
                        .param("recordType", "PRODUCT_TERM")
                        .param("changeType", "ADDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].changeType").value("ADDED"));
    }

    @Test
    void servingLoanProductListAndDetailUseCurrentPointers() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/serving/loan-products")
                        .param("institution", "우리")
                        .param("productName", "아파트"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceRunId").value(validFssRunId))
                .andExpect(jsonPath("$.content[0].termCount").value(2));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/serving/loan-products/{id}", servingProductId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.currentNormalizedRecordId").isNumber())
                .andExpect(jsonPath("$.product.rawSnapshotId").isNumber())
                .andExpect(jsonPath("$.terms.length()").value(2));
    }

    @Test
    void servingSeoulRateObservationsExposeExecutionStatisticsAndMapping() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/serving/loan-rate-observations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceRunId").value(validSeoulRunId))
                .andExpect(jsonPath("$.content[0].mappingStatus").value("MAPPED"))
                .andExpect(jsonPath("$.content[0].minimumLoanRate").isNumber())
                .andExpect(jsonPath("$.content[0].averageLoanAleRate").isNumber());
    }

    @Test
    void readApiReturnsConsistentNotFoundAndBadRequestResponses() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/source-runs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("SourceRun not found: 999999"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/publications/999999"))
                .andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/serving/loan-products/999999"))
                .andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/source-runs").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/publications").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size must be between 1 and 100"));
    }

    @Test
    void localDevelopmentCorsAllowsConfiguredFrontendOriginForGetOnly() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/overview").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    private long ingestAndNormalizeFss(String payload) {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(payload)));
        IngestionResult ingestion = ingestionService.ingest(DataSourceCode.FSS_FINLIFE);
        normalizationService.normalize(ingestion.sourceRunId());
        return ingestion.sourceRunId();
    }

    private long ingestAndNormalizeSeoul(String payload) {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlPathEqualTo(seoulPath(1, 5)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml;charset=UTF-8")
                        .withBody(payload)));
        IngestionResult ingestion = ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE);
        normalizationService.normalize(ingestion.sourceRunId());
        return ingestion.sourceRunId();
    }

    private String seoulPath(int start, int end) {
        return "/" + TEST_API_KEY + "/xml/dreamMoney/" + start + "/" + end;
    }

    private String fixture(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
