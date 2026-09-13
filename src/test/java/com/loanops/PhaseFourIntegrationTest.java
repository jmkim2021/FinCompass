package com.loanops;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.loanops.normalization.SourceNormalizationService;
import com.loanops.normalization.domain.LoanRateObservation;
import com.loanops.normalization.repository.LoanProductRepository;
import com.loanops.normalization.repository.LoanRateObservationRepository;
import com.loanops.normalization.repository.ProductTermRepository;
import com.loanops.publication.PublicationException;
import com.loanops.publication.PublicationResult;
import com.loanops.publication.PublicationService;
import com.loanops.publication.domain.DataChange;
import com.loanops.publication.domain.DataChangeType;
import com.loanops.publication.domain.DataQualityIssue;
import com.loanops.publication.domain.PublicationStatus;
import com.loanops.publication.domain.QualityRuleCode;
import com.loanops.publication.domain.ServingLoanRateObservation;
import com.loanops.publication.repository.DataChangeRepository;
import com.loanops.publication.repository.DataQualityIssueRepository;
import com.loanops.publication.repository.PublicationRunRepository;
import com.loanops.publication.repository.QuarantineRecordRepository;
import com.loanops.publication.repository.ServingLoanProductRepository;
import com.loanops.publication.repository.ServingLoanRateObservationRepository;
import com.loanops.publication.repository.ServingProductTermRepository;
import com.loanops.source.domain.DataSourceCode;
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
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
class PhaseFourIntegrationTest {

    private static final String TEST_API_KEY = "test-api-key";
    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"))
                    .withDatabaseName("loanops_phase4_test")
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

    @Autowired private SourceIngestionService ingestionService;
    @Autowired private SourceNormalizationService normalizationService;
    @Autowired private PublicationService publicationService;
    @Autowired private LoanProductRepository loanProductRepository;
    @Autowired private ProductTermRepository productTermRepository;
    @Autowired private LoanRateObservationRepository observationRepository;
    @Autowired private DataQualityIssueRepository issueRepository;
    @Autowired private QuarantineRecordRepository quarantineRepository;
    @Autowired private PublicationRunRepository publicationRunRepository;
    @Autowired private ServingLoanProductRepository servingProductRepository;
    @Autowired private ServingProductTermRepository servingTermRepository;
    @Autowired private ServingLoanRateObservationRepository servingObservationRepository;
    @Autowired private DataChangeRepository dataChangeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        WIRE_MOCK.resetAll();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS force_serving_term_failure ON loanops.serving_product_terms");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS loanops.fail_serving_term_insert()");
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
        jdbcTemplate.update("DELETE FROM loanops.institution_aliases WHERE mapping_status = 'UNMAPPED'");
        jdbcTemplate.update("""
                DELETE FROM loanops.financial_institutions
                WHERE canonical_name NOT IN ('국민은행', '신한은행', '우리은행', '부산은행')
                """);
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    void validFssPublicationPromotesProductsAndTermsWithProvenance() throws Exception {
        long sourceRunId = ingestAndNormalizeFss(validFssPayload());

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.PROMOTED);
        assertThat(result.issueCount()).isZero();
        assertThat(result.changeCount()).isEqualTo(3);
        assertThat(servingProductRepository.count()).isEqualTo(1);
        assertThat(servingTermRepository.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quality_policy_version FROM loanops.publication_runs WHERE source_run_id = ?",
                String.class,
                sourceRunId
        )).isEqualTo("2026-09-v2");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM loanops.serving_loan_products serving
                        JOIN loanops.loan_products product ON product.id = serving.current_loan_product_id
                        JOIN loanops.raw_snapshots snapshot ON snapshot.id = product.raw_snapshot_id
                        WHERE snapshot.source_run_id = ?
                          AND serving.promoted_source_run_id = ?
                        """,
                Integer.class,
                sourceRunId,
                sourceRunId
        )).isEqualTo(1);
    }

    @Test
    void validSeoulPublicationPromotesCurrentObservationPerAlias() throws Exception {
        long sourceRunId = ingestAndNormalizeSeoul(seoulPayload());

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.PROMOTED);
        assertThat(result.changeCount()).isEqualTo(1);
        assertThat(servingObservationRepository.count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM loanops.serving_loan_rate_observations serving
                        JOIN loanops.loan_rate_observations observation
                          ON observation.id = serving.current_loan_rate_observation_id
                        JOIN loanops.raw_snapshots snapshot ON snapshot.id = observation.raw_snapshot_id
                        WHERE snapshot.source_run_id = ?
                          AND serving.promoted_source_run_id = ?
                        """,
                Integer.class,
                sourceRunId,
                sourceRunId
        )).isEqualTo(1);
    }

    @Test
    void fssAverageOutsideMinimumAndMaximumIsAccepted() throws Exception {
        String payload = fssPayload()
                .replace("\"lend_rate_min\": \"5.33\"", "\"lend_rate_min\": \"4.50\"")
                .replace("\"lend_rate_avg\": \"4.97\"", "\"lend_rate_avg\": \"4.20\"")
                .replace("\"lend_rate_max\": \"6.53\"", "\"lend_rate_max\": \"5.50\"");
        long sourceRunId = ingestAndNormalizeFss(payload);

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.PROMOTED);
        assertThat(result.issueCount()).isZero();
        assertThat(result.quarantineCount()).isZero();
        assertThat(servingProductRepository.count()).isEqualTo(1);
        assertThat(servingTermRepository.count()).isEqualTo(2);
    }

    @Test
    void negativeFssRateIsQuarantinedAndRejected() throws Exception {
        String payload = fssPayload()
                .replace("\"lend_rate_min\": \"3.96\"", "\"lend_rate_min\": \"-1.00\"");
        long sourceRunId = ingestAndNormalizeFss(payload);

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED_QUALITY);
        assertThat(issues(sourceRunId)).extracting(DataQualityIssue::getRuleCode)
                .containsExactly(QualityRuleCode.NEGATIVE_RATE);
        assertThat(quarantineRepository.countBySourceRunId(sourceRunId)).isEqualTo(1);
        assertThat(servingProductRepository.count()).isZero();
        assertThat(servingTermRepository.count()).isZero();
    }

    @Test
    void fssMinimumAboveMaximumIsQuarantinedAndRejected() throws Exception {
        String payload = fssPayload()
                .replace("\"lend_rate_min\": \"5.33\"", "\"lend_rate_min\": \"5.50\"")
                .replace("\"lend_rate_max\": \"6.53\"", "\"lend_rate_max\": \"4.50\"");
        long sourceRunId = ingestAndNormalizeFss(payload);

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED_QUALITY);
        assertThat(issues(sourceRunId)).extracting(DataQualityIssue::getRuleCode)
                .containsExactly(QualityRuleCode.INVALID_RATE_ORDER);
        assertThat(quarantineRepository.countBySourceRunId(sourceRunId)).isEqualTo(1);
        assertThat(servingProductRepository.count()).isZero();
        assertThat(servingTermRepository.count()).isZero();
    }

    @Test
    void seoulAverageOutsideMinimumAndMaximumIsQuarantinedAndRejected() throws Exception {
        String payload = seoulPayload()
                .replace("<min_lo_ir>3.99</min_lo_ir>", "<min_lo_ir>4.00</min_lo_ir>")
                .replace("<avg_lo_ir>4.69</avg_lo_ir>", "<avg_lo_ir>3.50</avg_lo_ir>")
                .replace("<max_lo_ir>5.67</max_lo_ir>", "<max_lo_ir>5.00</max_lo_ir>");
        long sourceRunId = ingestAndNormalizeSeoul(payload);

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED_QUALITY);
        assertThat(issues(sourceRunId)).extracting(DataQualityIssue::getRuleCode)
                .containsExactly(QualityRuleCode.INVALID_RATE_ORDER);
        assertThat(quarantineRepository.countBySourceRunId(sourceRunId)).isEqualTo(1);
        assertThat(servingObservationRepository.count()).isZero();
    }

    @Test
    void negativeSeoulRateIsQuarantinedAndRejected() throws Exception {
        String payload = seoulPayload().replace("<min_lo_ir>3.99</min_lo_ir>", "<min_lo_ir>-1.00</min_lo_ir>");
        long sourceRunId = ingestAndNormalizeSeoul(payload);

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED_QUALITY);
        assertThat(issues(sourceRunId)).extracting(DataQualityIssue::getRuleCode)
                .contains(QualityRuleCode.NEGATIVE_RATE);
        assertThat(quarantineRepository.countBySourceRunId(sourceRunId)).isEqualTo(1);
        assertThat(servingObservationRepository.count()).isZero();
    }

    @Test
    void invalidSeoulDateRangeIsRejected() throws Exception {
        String payload = seoulPayload().replace("<base_str_dt>20260613</base_str_dt>", "<base_str_dt>20261001</base_str_dt>");
        long sourceRunId = ingestAndNormalizeSeoul(payload);

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED_QUALITY);
        assertThat(issues(sourceRunId)).extracting(DataQualityIssue::getRuleCode)
                .containsExactly(QualityRuleCode.INVALID_DATE_RANGE);
        assertThat(servingObservationRepository.count()).isZero();
    }

    @Test
    void negativeLoanCountIsRejected() throws Exception {
        String payload = seoulPayload().replace("<lo_cnt>2631</lo_cnt>", "<lo_cnt>-1</lo_cnt>");
        long sourceRunId = ingestAndNormalizeSeoul(payload);

        PublicationResult result = publicationService.publish(sourceRunId);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED_QUALITY);
        assertThat(issues(sourceRunId)).extracting(DataQualityIssue::getRuleCode)
                .containsExactly(QualityRuleCode.NEGATIVE_COUNT);
        assertThat(servingObservationRepository.count()).isZero();
    }

    @Test
    void invalidNewRecordMustNotReplaceLastValidServingRecord() throws Exception {
        long validRunId = ingestAndNormalizeSeoul(seoulPayload());
        publicationService.publish(validRunId);
        ServingLoanRateObservation initialServing = servingObservationRepository.findAll().getFirst();
        long validObservationId = initialServing.getCurrentLoanRateObservation().getId();

        String invalidPayload = seoulPayload().replace("<avg_lo_ir>4.69</avg_lo_ir>", "<avg_lo_ir>-3.00</avg_lo_ir>");
        long invalidRunId = ingestAndNormalizeSeoul(invalidPayload);
        long invalidObservationId = observationRepository
                .findAllByRawSnapshotSourceRunIdOrderByIdAsc(invalidRunId)
                .getFirst()
                .getId();
        PublicationResult rejected = publicationService.publish(invalidRunId);

        ServingLoanRateObservation currentServing = servingObservationRepository.findAll().getFirst();
        assertThat(rejected.status()).isEqualTo(PublicationStatus.REJECTED_QUALITY);
        assertThat(currentServing.getCurrentLoanRateObservation().getId()).isEqualTo(validObservationId);
        assertThat(currentServing.getPromotedSourceRun().getId()).isEqualTo(validRunId);
        assertThat(observationRepository.existsById(invalidObservationId)).isTrue();
        assertThat(issueRepository.countBySourceRunId(invalidRunId)).isPositive();
        assertThat(quarantineRepository.countBySourceRunId(invalidRunId)).isEqualTo(1);
    }

    @Test
    void publicationFailureRollsBackAllServingPointerChanges() throws Exception {
        long sourceRunId = ingestAndNormalizeFss(validFssPayload());
        jdbcTemplate.execute("""
                CREATE FUNCTION loanops.fail_serving_term_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced serving term failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER force_serving_term_failure
                BEFORE INSERT ON loanops.serving_product_terms
                FOR EACH ROW EXECUTE FUNCTION loanops.fail_serving_term_insert()
                """);

        try {
            assertThatThrownBy(() -> publicationService.publish(sourceRunId))
                    .hasMessageContaining("forced serving term failure");
            assertThat(servingProductRepository.count()).isZero();
            assertThat(servingTermRepository.count()).isZero();
            assertThat(publicationRunRepository.count()).isZero();
            assertThat(dataChangeRepository.count()).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS force_serving_term_failure ON loanops.serving_product_terms");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS loanops.fail_serving_term_insert()");
        }
    }

    @Test
    void businessIdenticalRunProducesNoChangeWithoutRewritingPointer() throws Exception {
        long firstRunId = ingestAndNormalizeSeoul(seoulPayload());
        publicationService.publish(firstRunId);
        ServingLoanRateObservation firstServing = servingObservationRepository.findAll().getFirst();
        long firstObservationId = firstServing.getCurrentLoanRateObservation().getId();
        Instant firstUpdatedAt = firstServing.getUpdatedAt();

        long secondRunId = ingestAndNormalizeSeoul(seoulPayload());
        PublicationResult result = publicationService.publish(secondRunId);

        ServingLoanRateObservation current = servingObservationRepository.findAll().getFirst();
        assertThat(result.status()).isEqualTo(PublicationStatus.NO_CHANGE);
        assertThat(result.changeCount()).isZero();
        assertThat(current.getCurrentLoanRateObservation().getId()).isEqualTo(firstObservationId);
        assertThat(current.getUpdatedAt()).isEqualTo(firstUpdatedAt);
    }

    @Test
    void changedBusinessFieldCreatesUpdatedChangeAndMovesPointer() throws Exception {
        long firstRunId = ingestAndNormalizeSeoul(seoulPayload());
        publicationService.publish(firstRunId);
        long firstObservationId = servingObservationRepository.findAll().getFirst()
                .getCurrentLoanRateObservation().getId();

        String updatedPayload = seoulPayload().replace("<avg_lo_ir>4.69</avg_lo_ir>", "<avg_lo_ir>4.70</avg_lo_ir>");
        long secondRunId = ingestAndNormalizeSeoul(updatedPayload);
        PublicationResult result = publicationService.publish(secondRunId);
        DataChange change = dataChangeRepository
                .findAllByPublicationRunIdOrderByIdAsc(result.publicationRunId())
                .getFirst();

        assertThat(result.status()).isEqualTo(PublicationStatus.PROMOTED);
        assertThat(change.getChangeType()).isEqualTo(DataChangeType.UPDATED);
        assertThat(change.getPreviousRecordId()).isEqualTo(firstObservationId);
        assertThat(change.getChangedFields()).isEqualTo("average_loan_rate");
        assertThat(servingObservationRepository.findAll().getFirst()
                .getCurrentLoanRateObservation().getId()).isEqualTo(change.getCurrentRecordId());
    }

    @Test
    void newStableIdentityCreatesAddedChange() throws Exception {
        long firstRunId = ingestAndNormalizeSeoul(seoulPayload());
        publicationService.publish(firstRunId);

        long secondRunId = ingestAndNormalizeSeoul(seoulPayloadWithAdditionalInstitution());
        PublicationResult result = publicationService.publish(secondRunId);
        List<DataChange> changes = dataChangeRepository
                .findAllByPublicationRunIdOrderByIdAsc(result.publicationRunId());

        assertThat(result.status()).isEqualTo(PublicationStatus.PROMOTED);
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().getChangeType()).isEqualTo(DataChangeType.ADDED);
        assertThat(servingObservationRepository.count()).isEqualTo(2);
    }

    @Test
    void repeatedPublicationReturnsExistingFinalResultWithoutDuplicates() throws Exception {
        long sourceRunId = ingestAndNormalizeSeoul(seoulPayload());

        PublicationResult first = publicationService.publish(sourceRunId);
        PublicationResult second = publicationService.publish(sourceRunId);

        assertThat(first.status()).isEqualTo(PublicationStatus.PROMOTED);
        assertThat(second.publicationRunId()).isEqualTo(first.publicationRunId());
        assertThat(second.alreadyFinalized()).isTrue();
        assertThat(publicationRunRepository.count()).isEqualTo(1);
        assertThat(dataChangeRepository.count()).isEqualTo(1);
        assertThat(issueRepository.count()).isZero();
        assertThat(quarantineRepository.count()).isZero();
    }

    @Test
    void failedSourceRunIsRejectedBeforeQualityProcessing() {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse().withStatus(500).withBody("upstream failure")));
        SourceIngestionException failure = assertThrows(
                SourceIngestionException.class,
                () -> ingestionService.ingest(DataSourceCode.FSS_FINLIFE)
        );

        assertThatThrownBy(() -> publicationService.publish(failure.getSourceRunId()))
                .isInstanceOf(PublicationException.class)
                .hasMessage("Only a SUCCESS source run can be published");
        assertThat(issueRepository.count()).isZero();
        assertThat(publicationRunRepository.count()).isZero();
    }

    @Test
    void nonNormalizedSourceRunIsRejected() throws Exception {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(validFssPayload())));
        long sourceRunId = ingestionService.ingest(DataSourceCode.FSS_FINLIFE).sourceRunId();

        assertThatThrownBy(() -> publicationService.publish(sourceRunId))
                .isInstanceOf(PublicationException.class)
                .hasMessage("Source run has no normalized records");
        assertThat(publicationRunRepository.count()).isZero();
    }

    @Test
    void phaseFourTablesExistOnlyInLoanopsSchema() {
        List<String> expected = List.of(
                "data_changes",
                "data_quality_issues",
                "publication_runs",
                "quarantine_records",
                "serving_loan_products",
                "serving_loan_rate_observations",
                "serving_product_terms"
        );
        List<String> actual = jdbcTemplate.queryForList(
                """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'loanops'
                          AND table_name IN (
                            'data_changes',
                            'data_quality_issues',
                            'publication_runs',
                            'quarantine_records',
                            'serving_loan_products',
                            'serving_loan_rate_observations',
                            'serving_product_terms'
                          )
                        ORDER BY table_name
                        """,
                String.class
        );
        Integer publicCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                            'data_changes',
                            'data_quality_issues',
                            'publication_runs',
                            'quarantine_records',
                            'serving_loan_products',
                            'serving_loan_rate_observations',
                            'serving_product_terms'
                          )
                        """,
                Integer.class
        );

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(publicCount).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM loanops.flyway_schema_history WHERE version = '6'",
                Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM loanops.flyway_schema_history WHERE version = '7'",
                Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'loanops'
                          AND table_name = 'publication_runs'
                          AND column_name = 'quality_policy_version'
                        """,
                String.class
        )).isEqualTo("NO");
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

    private List<DataQualityIssue> issues(long sourceRunId) {
        return issueRepository.findAllBySourceRunIdOrderByIdAsc(sourceRunId);
    }

    private String validFssPayload() throws IOException {
        return fssPayload().replace("\"lend_rate_avg\": \"4.97\"", "\"lend_rate_avg\": \"5.97\"");
    }

    private String fssPayload() throws IOException {
        return fixture("fixtures/fss/mortgage-loans.json");
    }

    private String seoulPayload() throws IOException {
        return fixture("fixtures/seoul/policy-loan-rates.xml");
    }

    private String seoulPayloadWithAdditionalInstitution() throws IOException {
        String payload = seoulPayload();
        int rowStart = payload.indexOf("  <row>");
        int rowEnd = payload.indexOf("  </row>") + "  </row>".length();
        String additionalRow = payload.substring(rowStart, rowEnd)
                .replace("B01", "B99")
                .replace("국민은행", "Unknown Test Bank");
        return payload
                .replace("<list_total_count>1</list_total_count>", "<list_total_count>2</list_total_count>")
                .replace("</searchLoOrgIrList>", additionalRow + "\n</searchLoOrgIrList>");
    }

    private String seoulPath(int start, int end) {
        return "/" + TEST_API_KEY + "/xml/dreamMoney/" + start + "/" + end;
    }

    private String fixture(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
