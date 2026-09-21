package com.loanops.fincompass;

import com.loanops.api.ApiNotFoundException;
import com.loanops.fincompass.twin.*;
import com.loanops.personal.canonical.PersonalFinanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.loanops.fincompass.twin.TwinModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL, migration, gateway, immutable storage and HTTP contract for the frozen persona. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("fincompass-demo")
class FinancialTwinIntegrationTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("fincompass.open-banking.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    static final String OWNER = "golden-homebuyer";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);
    @Autowired FinancialTwinStore store;
    @Autowired PersonalFinanceService personal;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc http;
    final TwinCalculator calculator = new TwinCalculator();

    @Test void goldenAssetsDebtAndAllElevenMetricsAreAvailable() {
        Snapshot snapshot = store.latest(OWNER);
        assertThat(snapshot.status()).isEqualTo("COMPLETE");
        assertThat(snapshot.result().metrics()).hasSize(11);
        assertThat(snapshot.result().metrics().values()).allSatisfy(metric -> assertThat(metric.availability()).isEqualTo(Availability.AVAILABLE));
        amount(snapshot, "TOTAL_ASSETS", "120000000");
        amount(snapshot, "TOTAL_LIABILITIES", "30000000");
        amount(snapshot, "LIQUID_ASSETS", "120000000");
        amount(snapshot, "NET_WORTH", "90000000");
        amount(snapshot, "DEBT_TO_ASSET_RATIO", "0.25");
        amount(snapshot, "LIQUID_ASSET_RATIO", "1");
    }

    @Test void observedMonthlyIncomeIsIndependentOfVerifiedAnnualIncome() {
        Snapshot snapshot = store.latest(OWNER);
        amount(snapshot, "OBSERVED_MONTHLY_INFLOW", "4800000");
        assertThat(snapshot.result().verifiedAnnualIncome()).isEqualByComparingTo("66000000");
        assertThat(snapshot.result().metrics().get("OBSERVED_MONTHLY_INFLOW").value().multiply(new BigDecimal("12")))
                .isNotEqualByComparingTo(snapshot.result().verifiedAnnualIncome());
        assertThat(snapshot.input().coveredMonths()).isEqualTo(1);
    }

    @Test void classifiedDebtAndTransfersDoNotDoubleReduceFreeCashflow() {
        Snapshot snapshot = store.latest(OWNER);
        assertThat(snapshot.input().transactions()).hasSize(32);
        assertThat(snapshot.input().transactions()).anyMatch(transaction -> transaction.category() == Category.DEBT_SERVICE);
        assertThat(snapshot.input().transactions()).anyMatch(transaction -> transaction.category() == Category.TRANSFER);
        assertThat(snapshot.result().monthlyLivingExpense()).isEqualByComparingTo("2800000");
        assertThat(snapshot.result().monthlyDebtService()).isEqualByComparingTo("600000");
        assertThat(snapshot.result().monthlyFixedObligations()).isEqualByComparingTo("200000");
        amount(snapshot, "OBSERVED_MONTHLY_OUTFLOW", "3400000");
        amount(snapshot, "MONTHLY_FREE_CASHFLOW", "1200000");
    }

    @Test void liquidityAndDebtBurdenUseTheirDocumentedDenominators() {
        Snapshot snapshot = store.latest(OWNER);
        assertThat(snapshot.result().monthlyEssentialExpense()).isEqualByComparingTo("2200000");
        amount(snapshot, "EMERGENCY_FUND_MONTHS", "54.54545455");
        amount(snapshot, "DEBT_SERVICE_BURDEN", "0.10909091");
        assertThat(snapshot.result().limitations()).anyMatch(value -> value.contains("not regulatory DSR"));
    }

    @Test void exactInputReferencesReachCanonicalEvidenceAndExplicitUserFacts() {
        Snapshot snapshot = store.latest(OWNER);
        assertThat(snapshot.input().sourceRefs()).extracting(SourceRef::sourceType)
                .contains("OPEN_BANKING_TEST", "PUBLIC_MYDATA_MOCK", "USER_INPUT");
        assertThat(snapshot.input().sourceRefs()).allSatisfy(ref -> {
            assertThat(ref.sourceMode()).isEqualTo("DEMO_REPLAY");
            assertThat(ref.contentHash()).hasSize(64);
            assertThat(ref.recordId()).isNotNull();
            assertThat(ref.asOf()).isNotNull();
            if (!Set.of("USER_INPUT", "DERIVED").contains(ref.sourceType())) {
                assertThat(ref.provenanceId()).isNotNull();
                assertThat(ref.validationRunId()).isNotNull();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM loanops.personal_source_evidence WHERE id=?", Integer.class, ref.provenanceId())).isEqualTo(1);
            }
        });
        assertThat(snapshot.input().sourceRefs().stream().filter(ref -> "CASHFLOW".equals(ref.purpose()))).hasSize(32);
        assertThat(snapshot.input().facts()).allSatisfy(fact -> {
            assertThat(fact.sourceType()).isEqualTo("USER_INPUT");
            assertThat(fact.sourceMode()).isEqualTo("DEMO_REPLAY");
            assertThat(fact.evidenceId()).isNotBlank();
        });
    }

    @Test void identicalFrozenInputsReuseSnapshotWithoutWritingAnotherHistoryEntry() {
        Snapshot snapshot = store.latest(OWNER);
        int before = count("financial_snapshot");
        Snapshot repeated = store.save(OWNER, snapshot.input(), calculator.calculate(snapshot.input()), calculator.fingerprint(snapshot.input()), CLOCK);
        assertThat(repeated).isEqualTo(snapshot);
        assertThat(count("financial_snapshot")).isEqualTo(before);
    }

    @Test void collectionOrderingDoesNotChangeFingerprintOrStoredSnapshot() {
        Snapshot snapshot = store.latest(OWNER);
        Input original = snapshot.input();
        var refs = new ArrayList<>(original.sourceRefs()); Collections.reverse(refs);
        var facts = new ArrayList<>(original.facts()); Collections.reverse(facts);
        var transactions = new ArrayList<>(original.transactions()); Collections.reverse(transactions);
        Input reordered = new Input(original.financialUserId(), original.asOf(), refs, facts, original.liquidBalance(), original.verifiedAnnualIncome(),
                transactions, original.coveredMonths(), original.balanceUsable(), original.transactionsUsable(), original.incomeUsable(), original.warnings());
        assertThat(calculator.fingerprint(reordered)).isEqualTo(snapshot.inputFingerprint());
        assertThat(store.save(OWNER, reordered, calculator.calculate(reordered), calculator.fingerprint(reordered), CLOCK).financialSnapshotId()).isEqualTo(snapshot.financialSnapshotId());
    }

    @Test void laterIncompleteSnapshotIsAuditedWithoutReplacingUsableCurrent() throws Exception {
        Snapshot good = store.latest(OWNER);
        Input original = good.input();
        Input incomplete = new Input(original.financialUserId(), original.asOf().plusSeconds(1), original.sourceRefs(), original.facts(), original.liquidBalance(),
                original.verifiedAnnualIncome(), original.transactions(), original.coveredMonths(), false, false, original.incomeUsable(), List.of("PARTIAL_TRANSACTION_HISTORY", "STALE_BALANCE"));
        Snapshot failed = store.save(OWNER, incomplete, calculator.calculate(incomplete), calculator.fingerprint(incomplete), Clock.offset(CLOCK, java.time.Duration.ofHours(1)));
        assertThat(failed.status()).isEqualTo("INCOMPLETE");
        assertThat(failed.result().metrics().get("LIQUID_ASSETS").value()).isNull();
        assertThat(failed.result().metrics().get("MONTHLY_FREE_CASHFLOW").availability()).isEqualTo(Availability.INSUFFICIENT_DATA);
        assertThat(store.get(OWNER, failed.financialSnapshotId())).isEqualTo(failed);
        assertThat(store.latest(OWNER).financialSnapshotId()).isEqualTo(good.financialSnapshotId());
        assertThat(store.get(OWNER, good.financialSnapshotId())).isEqualTo(good);
        http.perform(get("/api/v1/financial-snapshots/latest")).andExpect(status().isOk()).andExpect(jsonPath("$.currentAssessment.newerIncomplete").value(true)).andExpect(jsonPath("$.currentAssessment.latestAttemptStatus").value("INCOMPLETE"));
    }

    @Test void databaseRejectsChangingOrDeletingFrozenSnapshotAndMetrics() {
        Snapshot snapshot = store.latest(OWNER);
        assertThatThrownBy(() -> jdbc.update("UPDATE loanops.financial_snapshot SET status='INCOMPLETE' WHERE id=?", snapshot.financialSnapshotId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM loanops.financial_metric WHERE snapshot_id=? AND name='NET_WORTH'", snapshot.financialSnapshotId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.get(OWNER, snapshot.financialSnapshotId())).isEqualTo(snapshot);
    }

    @Test void snapshotFinancialValuesRemainEncryptedAtRest() {
        Snapshot snapshot = store.latest(OWNER);
        String encrypted = jdbc.queryForObject("SELECT payload_ciphertext FROM loanops.financial_snapshot WHERE id=?", String.class, snapshot.financialSnapshotId());
        assertThat(encrypted).doesNotContain("120000000", "66000000", "30000000", "verifiedAnnualIncome", "monthlyLivingExpense");
        jdbc.queryForList("SELECT payload_ciphertext FROM loanops.financial_metric WHERE snapshot_id=?", String.class, snapshot.financialSnapshotId())
                .forEach(payload -> assertThat(payload).doesNotContain("120000000", "90000000", "availability"));
        assertThat(store.get(OWNER, snapshot.financialSnapshotId())).isEqualTo(snapshot);
    }

    @Test void differentOwnerCannotReadOrSaveAnotherUsersSnapshot() {
        Snapshot snapshot = store.latest(OWNER);
        personal.createDemoUser("isolated-twin-test");
        assertThatThrownBy(() -> store.get("isolated-twin-test", snapshot.financialSnapshotId())).isInstanceOf(ApiNotFoundException.class);
        assertThat(store.latestOptional("isolated-twin-test")).isEmpty();
        assertThatThrownBy(() -> store.save("isolated-twin-test", snapshot.input(), snapshot.result(), snapshot.inputFingerprint(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void immutableFactConflictRollsBackWholeNewSnapshot() {
        Snapshot snapshot = store.latest(OWNER);
        Input original = snapshot.input();
        var facts = new ArrayList<>(original.facts());
        Fact existing = facts.stream().filter(fact -> fact.kind().equals("EXISTING_LOAN_BALANCE")).findFirst().orElseThrow();
        facts.set(facts.indexOf(existing), new Fact(existing.id(), existing.kind(), existing.value().add(BigDecimal.ONE), existing.sourceType(), existing.sourceMode(), existing.asOf(), existing.currency(), existing.evidenceId()));
        Input changed = new Input(original.financialUserId(), original.asOf(), original.sourceRefs(), facts, original.liquidBalance(), original.verifiedAnnualIncome(), original.transactions(), original.coveredMonths(), original.balanceUsable(), original.transactionsUsable(), original.incomeUsable(), original.warnings());
        int snapshots = count("financial_snapshot"), metrics = count("financial_metric"), references = count("financial_snapshot_source_ref");
        assertThatThrownBy(() -> store.save(OWNER, changed, calculator.calculate(changed), calculator.fingerprint(changed), CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("financial_snapshot")).isEqualTo(snapshots);
        assertThat(count("financial_metric")).isEqualTo(metrics);
        assertThat(count("financial_snapshot_source_ref")).isEqualTo(references);
    }

    @Test void concurrentReplayReturnsTheSameImmutableSnapshot() throws Exception {
        Snapshot snapshot = store.latest(OWNER);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> store.save(OWNER, snapshot.input(), snapshot.result(), snapshot.inputFingerprint(), CLOCK));
            var second = pool.submit(() -> store.save(OWNER, snapshot.input(), snapshot.result(), snapshot.inputFingerprint(), CLOCK));
            assertThat(first.get(10, TimeUnit.SECONDS).financialSnapshotId()).isEqualTo(second.get(10, TimeUnit.SECONDS).financialSnapshotId());
        }
    }

    @Test void threeAnalyticsEndpointsExposeFrozenMetadataAvailabilityAndSourceBoundaries() throws Exception {
        Snapshot snapshot = store.latest(OWNER);
        for (String path : List.of("/api/v1/financial-snapshots/latest", "/api/v1/analytics/cashflow", "/api/v1/analytics/debt")) {
            http.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.financialSnapshotId").value(snapshot.financialSnapshotId().toString()))
                    .andExpect(jsonPath("$.calculationRuleVersion").value(TwinCalculator.RULE_VERSION))
                    .andExpect(jsonPath("$.inputFingerprint").value(snapshot.inputFingerprint()))
                    .andExpect(jsonPath("$.result.metrics.MONTHLY_FREE_CASHFLOW.value").value(1200000))
                    .andExpect(jsonPath("$.result.metrics.MONTHLY_FREE_CASHFLOW.availability").value("AVAILABLE"))
                    .andExpect(jsonPath("$.input.sourceRefs").isArray())
                    .andExpect(jsonPath("$.result.warnings").isArray())
                    .andExpect(jsonPath("$.result.limitations").isArray());
        }
    }

    @Test void unknownSnapshotReturnsSafeNotFoundEnvelope() throws Exception {
        http.perform(get("/api/v1/financial-snapshots/" + UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Financial snapshot not found"));
    }

    private static void amount(Snapshot snapshot, String metric, String expected) {
        assertThat(snapshot.result().metrics().get(metric).value()).as(metric).isEqualByComparingTo(expected);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM loanops." + table, Integer.class); }
}
