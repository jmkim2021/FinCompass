package com.loanops;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.loanops.normalization.NormalizationException;
import com.loanops.normalization.NormalizationResult;
import com.loanops.normalization.SourceNormalizationService;
import com.loanops.normalization.domain.InstitutionAlias;
import com.loanops.normalization.domain.InstitutionMappingStatus;
import com.loanops.normalization.domain.LoanProduct;
import com.loanops.normalization.domain.LoanRateObservation;
import com.loanops.normalization.domain.ProductTerm;
import com.loanops.normalization.repository.InstitutionAliasRepository;
import com.loanops.normalization.repository.LoanProductRepository;
import com.loanops.normalization.repository.LoanRateObservationRepository;
import com.loanops.normalization.repository.ProductTermRepository;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.SourceRun;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
class PhaseThreeIntegrationTest {

    private static final String TEST_API_KEY = "test-api-key";
    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"))
                    .withDatabaseName("loanops_phase3_test")
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
    @Autowired private SourceRunRepository sourceRunRepository;
    @Autowired private RawSnapshotRepository rawSnapshotRepository;
    @Autowired private InstitutionAliasRepository institutionAliasRepository;
    @Autowired private LoanProductRepository loanProductRepository;
    @Autowired private ProductTermRepository productTermRepository;
    @Autowired private LoanRateObservationRepository observationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

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
    void fssNormalizationPreservesRelationshipsProvenanceAndIdempotency() throws Exception {
        stubFssSuccess();
        IngestionResult ingestion = ingestionService.ingest(DataSourceCode.FSS_FINLIFE);

        NormalizationResult first = normalizationService.normalize(ingestion.sourceRunId());
        NormalizationResult second = normalizationService.normalize(ingestion.sourceRunId());

        assertThat(first.productCount()).isEqualTo(1);
        assertThat(first.termCount()).isEqualTo(2);
        assertThat(second.productCount()).isZero();
        assertThat(second.termCount()).isZero();
        assertThat(loanProductRepository.count()).isEqualTo(1);
        assertThat(productTermRepository.count()).isEqualTo(2);

        InstitutionAlias alias = institutionAliasRepository
                .findByDataSourceCodeAndSourceInstitutionCodeAndSourceInstitutionName(
                        DataSourceCode.FSS_FINLIFE,
                        "0010001",
                        "우리은행"
                )
                .orElseThrow();
        LoanProduct product = loanProductRepository.findAll().getFirst();
        List<ProductTerm> terms = productTermRepository.findAll();
        assertThat(alias.getMappingStatus()).isEqualTo(InstitutionMappingStatus.MAPPED);
        assertThat(product.getInstitutionAlias().getId()).isEqualTo(alias.getId());
        assertThat(product.getRawSnapshot().getId()).isNotNull();
        assertThat(product.getDisclosureStartDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(terms).allSatisfy(term -> {
            assertThat(term.getLoanProduct().getId()).isEqualTo(product.getId());
            assertThat(term.getRawSnapshot().getId()).isEqualTo(product.getRawSnapshot().getId());
        });
        assertThat(terms).extracting(ProductTerm::getAverageRate)
                .containsExactlyInAnyOrder(null, new BigDecimal("4.9700"));
    }

    @Test
    void seoulPaginationUsesOneRunAndNormalizesAllRowsWithExplicitMappingsOnly() throws Exception {
        stubSeoulPage(1, 5, "fixtures/seoul/page-1.xml");
        stubSeoulPage(6, 10, "fixtures/seoul/page-2.xml");
        stubSeoulPage(11, 11, "fixtures/seoul/page-3.xml");

        IngestionResult ingestion = ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE);
        NormalizationResult normalized = normalizationService.normalize(ingestion.sourceRunId());

        assertThat(sourceRunRepository.count()).isEqualTo(1);
        assertThat(rawSnapshotRepository.findAllBySourceRunIdOrderByIdAsc(ingestion.sourceRunId()))
                .hasSize(3);
        assertThat(ingestion.snapshotCount()).isEqualTo(3);
        assertThat(normalized.observationCount()).isEqualTo(11);
        assertThat(observationRepository.count()).isEqualTo(11);
        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo(seoulPath(1, 5))));
        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo(seoulPath(6, 10))));
        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo(seoulPath(11, 11))));

        LoanRateObservation mapped = observation("B01");
        LoanRateObservation unmapped = observation("B02");
        assertThat(seoulAlias("B01", "국민은행").getMappingStatus())
                .isEqualTo(InstitutionMappingStatus.MAPPED);
        assertThat(mapped.getFinancialInstitution()).isNotNull();
        assertThat(mapped.getReferenceStartDate()).isEqualTo(LocalDate.of(2026, 6, 13));
        assertThat(mapped.getLoanCount()).isEqualTo(2631L);
        assertThat(mapped.getMinimumLoanRate()).isEqualByComparingTo("3.9900");
        assertThat(mapped.getAverageLoanAleRate()).isEqualByComparingTo("1.9100");
        assertThat(seoulAlias("B02", "농협은행").getMappingStatus())
                .isEqualTo(InstitutionMappingStatus.UNMAPPED);
        assertThat(unmapped.getFinancialInstitution()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema = 'loanops'
                          AND table_name = 'loan_rate_observations'
                          AND column_name = 'loan_product_id'
                        """,
                Integer.class
        )).isZero();
    }

    @Test
    void unknownSeoulInstitutionRemainsUnmappedWithoutDiscardingObservation() throws Exception {
        String payload = fixture("fixtures/seoul/policy-loan-rates.xml")
                .replace("B01", "B99")
                .replace("국민은행", "Unknown Test Bank");
        stubSeoulResponse(1, 5, payload);

        IngestionResult ingestion = ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE);
        normalizationService.normalize(ingestion.sourceRunId());

        LoanRateObservation observation = observation("B99");
        assertThat(seoulAlias("B99", "Unknown Test Bank").getMappingStatus())
                .isEqualTo(InstitutionMappingStatus.UNMAPPED);
        assertThat(observation.getFinancialInstitution()).isNull();
    }

    @Test
    void failedSourceRunCannotBeNormalized() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse().withStatus(500).withBody("upstream failure")));
        SourceIngestionException failure = assertThrows(
                SourceIngestionException.class,
                () -> ingestionService.ingest(DataSourceCode.FSS_FINLIFE)
        );

        assertThatThrownBy(() -> normalizationService.normalize(failure.getSourceRunId()))
                .isInstanceOf(NormalizationException.class)
                .hasMessage("Only a SUCCESS source run can be normalized");
        assertThat(loanProductRepository.count()).isZero();
        assertThat(productTermRepository.count()).isZero();
        assertThat(observationRepository.count()).isZero();
    }

    @Test
    void malformedRecordRollsBackEntireNormalizationInvocation() throws Exception {
        String firstPage = fixture("fixtures/seoul/page-1.xml")
                .replace("<list_total_count>11</list_total_count>", "<list_total_count>6</list_total_count>");
        stubSeoulResponse(1, 5, firstPage);
        stubSeoulPage(6, 6, "fixtures/seoul/malformed-rate-page.xml");
        IngestionResult ingestion = ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE);

        assertThatThrownBy(() -> normalizationService.normalize(ingestion.sourceRunId()))
                .isInstanceOf(NormalizationException.class)
                .hasMessage("Seoul snapshot contains invalid XML data");
        assertThat(observationRepository.count()).isZero();
    }

    @Test
    void seoulPaginationFailureRetainsReceivedPagesAndStopsFurtherRequests() throws Exception {
        stubSeoulPage(1, 5, "fixtures/seoul/page-1.xml");
        stubSeoulPage(6, 10, "fixtures/seoul/upstream-error.xml");

        SourceIngestionException failure = assertThrows(
                SourceIngestionException.class,
                () -> ingestionService.ingest(DataSourceCode.SEOUL_POLICY_LOAN_RATE)
        );

        SourceRun run = sourceRunRepository.findById(failure.getSourceRunId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(SourceRunStatus.FAILED);
        assertThat(rawSnapshotRepository.findAllBySourceRunIdOrderByIdAsc(run.getId())).hasSize(2);
        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo(seoulPath(1, 5))));
        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo(seoulPath(6, 10))));
        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo(seoulPath(11, 11))));
        assertThatThrownBy(() -> normalizationService.normalize(run.getId()))
                .isInstanceOf(NormalizationException.class);
    }

    @Test
    void phaseThreeTablesExistOnlyInLoanopsSchema() {
        List<String> expected = List.of(
                "financial_institutions",
                "institution_aliases",
                "loan_products",
                "loan_rate_observations",
                "product_terms"
        );
        List<String> actual = jdbcTemplate.queryForList(
                """
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'loanops'
                          AND table_name IN (
                            'financial_institutions',
                            'institution_aliases',
                            'loan_products',
                            'loan_rate_observations',
                            'product_terms'
                          )
                        ORDER BY table_name
                        """,
                String.class
        );
        Integer publicCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                            'financial_institutions',
                            'institution_aliases',
                            'loan_products',
                            'loan_rate_observations',
                            'product_terms'
                          )
                        """,
                Integer.class
        );

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(publicCount).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM loanops.flyway_schema_history WHERE version = '5'",
                Boolean.class
        )).isTrue();
    }

    private void stubFssSuccess() throws IOException {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/fss"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(fixture("fixtures/fss/mortgage-loans.json"))));
    }

    private void stubSeoulPage(int start, int end, String fixturePath) throws IOException {
        stubSeoulResponse(start, end, fixture(fixturePath));
    }

    private void stubSeoulResponse(int start, int end, String payload) {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(seoulPath(start, end)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml;charset=UTF-8")
                        .withBody(payload)));
    }

    private String seoulPath(int start, int end) {
        return "/" + TEST_API_KEY + "/xml/dreamMoney/" + start + "/" + end;
    }

    private LoanRateObservation observation(String sourceInstitutionCode) {
        return observationRepository.findAll().stream()
                .filter(value -> value.getSourceInstitutionCode().equals(sourceInstitutionCode))
                .findFirst()
                .orElseThrow();
    }

    private InstitutionAlias seoulAlias(String sourceInstitutionCode, String sourceInstitutionName) {
        return institutionAliasRepository
                .findByDataSourceCodeAndSourceInstitutionCodeAndSourceInstitutionName(
                        DataSourceCode.SEOUL_POLICY_LOAN_RATE,
                        sourceInstitutionCode,
                        sourceInstitutionName
                )
                .orElseThrow();
    }

    private String fixture(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
