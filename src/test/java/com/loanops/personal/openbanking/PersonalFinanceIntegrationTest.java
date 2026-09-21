package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.loanops.personal.canonical.*;
import com.loanops.personal.canonical.PersonalFinanceModels.*;
import com.loanops.personal.gateway.PersonalFinanceProvider;
import com.loanops.personal.quality.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.loanops.personal.canonical.PersonalTypes.*;
import static com.loanops.personal.canonical.PersonalFinanceException.Code.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"fincompass.open-banking.enabled=true","fincompass.personal.enabled=true"})
@ExtendWith(OutputCaptureExtension.class)
class PersonalFinanceIntegrationTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
    static final WireMockServer HTTP=new WireMockServer(wireMockConfig().dynamicPort());
    static { HTTP.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
        r.add("fincompass.open-banking.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @AfterAll static void closeHttp(){HTTP.stop();}
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;
    @Autowired ObjectMapper mapper;
    @Autowired PersonalFinanceService wiredService;
    @Autowired PersonalFinanceProvider wiredProvider;
    final String owner="canonical-synthetic-owner";
    final LocalDate from=LocalDate.of(2026,9,1),through=LocalDate.of(2026,9,2);
    MutableClock clock;
    OpenBankingJson json;SecretBox box;OpenBankingStore source;OpenBankingMapper sourceMapper;
    OpenBankingSyncService sync;OpenBankingProperties config;OpenBankingPersonalFinanceProvider gateway;
    PersonalFinanceService personal;PersonalFinanceStore store;PersonalFinanceCipher cipher;
    UUID connection;ConsentGrant consent;
    @BeforeEach void setup() {
        HTTP.resetAll();jdbc.execute("TRUNCATE loanops.open_banking_connections,loanops.financial_users CASCADE");
        clock=new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        json=new OpenBankingJson(mapper);box=new SecretBox(Base64.getEncoder().encodeToString(new byte[32]),"synthetic-test");
        source=new OpenBankingStore(jdbc,tx,box,json,clock);sourceMapper=new OpenBankingMapper(json);config=sourceConfig(OpenBankingMode.DEMO_REPLAY);
        sync=sync(new ReplayOpenBankingProvider(json,clock));
        gateway=new OpenBankingPersonalFinanceProvider(source,sourceMapper,json,box,jdbc);
        cipher=new PersonalFinanceCipher(box,mapper);store=new PersonalFinanceStore(jdbc,tx,cipher);
        personal=service(Duration.ofHours(24),"personal-dq-v1");
        connection=source.createConnection(owner,OpenBankingMode.DEMO_REPLAY,"000000000000000000000001",clock.instant().plus(Duration.ofDays(10)));
        consent=personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.BALANCE,Capability.TRANSACTIONS),clock.instant().plus(Duration.ofDays(5)));
    }
    PersonalFinanceService service(Duration threshold,String version) { return new PersonalFinanceService(List.of(gateway),store,cipher,new PersonalQualityProperties(threshold,version),clock); }
    OpenBankingProperties sourceConfig(OpenBankingMode mode) {
        return new OpenBankingProperties(true,mode,HTTP.baseUrl(),HTTP.baseUrl()+"/authorize",HTTP.baseUrl()+"/token","synthetic-client","synthetic-secret","http://localhost/callback","TEST000001",
                Base64.getEncoder().encodeToString(new byte[32]),"synthetic-test",180,3,10,Duration.ofSeconds(1),Duration.ofSeconds(2),1048576);
    }
    OpenBankingSyncService sync(OpenBankingProvider provider) { return new OpenBankingSyncService(provider,source,sourceMapper,config,clock); }
    void live() {
        config=sourceConfig(OpenBankingMode.LIVE_TEST);var client=new OpenBankingClient(config,clock,json);var tokens=new OpenBankingTokenService(source,client,config,clock);
        sync=sync(new LiveTestOpenBankingProvider(client,tokens,source,config,clock));
        connection=source.createConnection(owner,OpenBankingMode.LIVE_TEST,"000000000000000000000001",clock.instant().plus(Duration.ofDays(10)));
        source.updateToken(source.connection(connection,owner),old->new OpenBankingModels.Token("synthetic-access","synthetic-refresh",clock.instant().plus(Duration.ofDays(2)),"inquiry"));
        consent=personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.BALANCE,Capability.TRANSACTIONS),clock.instant().plus(Duration.ofDays(5)));
    }
    SourceSyncRun ingest(OpenBankingCapability capability) { return sync.sync(connection,owner,capability,from,through); }
    ValidationResult normalize(SourceSyncRun run) { return personal.normalize(owner,consent.id(),run.id()); }
    CanonicalView current(Capability capability) { return personal.current(owner,consent.id(),capability,0,100); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM loanops."+table,Integer.class); }
    String page(int count,boolean next,String cursor) {
        ObjectNode result=mapper.createObjectNode();result.put("rsp_code","A0000");result.put("page_record_cnt",count);result.put("next_page_yn",next?"Y":"N");
        if(cursor!=null)result.put("befor_inquiry_trace_info",cursor);var rows=result.putArray("res_list");
        for(int i=0;i<count;i++) {var row=rows.addObject();row.put("tran_date","20260901");row.put("tran_time","100000");row.put("inout_type","출금");row.put("tran_type","현금");row.put("tran_amt","1000.10");row.put("after_balance_amt",Integer.toString(10000-i));row.put("print_content","SYNTHETIC "+i);}
        return result.toString();
    }
    void first(String body) { HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.TRANSACTIONS_PATH)).withQueryParam("befor_inquiry_trace_info",absent()).willReturn(okJson(body))); }
    void balance(String amount,String stamp) { HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.BALANCE_PATH)).willReturn(okJson("{\"rsp_code\":\"A0000\",\"account_type\":\"1\",\"balance_amt\":\""+amount+"\",\"api_tran_dtm\":\""+stamp+"\"}"))); }

    @Test void grantIsIdempotentAtDatabaseTimestampPrecision() {
        personal.revoke(owner,consent.id());
        var expiry=clock.instant().plusSeconds(1000).plusNanos(123456789);
        var first=personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.BALANCE),expiry);
        assertThat(personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.BALANCE),expiry).id()).isEqualTo(first.id());
    }
    @Test void completeEmptyTransactionWindowIsExplicitAndPromotable() {
        live();first(page(0,false,null));var result=normalize(ingest(OpenBankingCapability.TRANSACTIONS));
        assertThat(result.promoted()).isTrue();assertThat(current(Capability.TRANSACTIONS).transactions()).isEmpty();
        assertThat(current(Capability.TRANSACTIONS).state().completeForLatestAttempt()).isTrue();
        assertThat(result.coveredFrom()).isEqualTo(from);assertThat(result.coveredThrough()).isEqualTo(through);
    }
    @Test void failedBalanceKeepsPreviousGoodCurrentAndRecordsLatestFailure() {
        live();balance("100","20260921090000000");var good=normalize(ingest(OpenBankingCapability.BALANCE));
        clock.advance(Duration.ofSeconds(1));HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.BALANCE_PATH)).willReturn(serverError()));
        var failure=normalize(ingest(OpenBankingCapability.BALANCE));
        assertThat(failure.promoted()).isFalse();assertThat(failure.state()).isEqualTo(ValidationState.PARTIAL);
        assertThat(failure.issues()).anyMatch(q->q.rule()==RuleCode.SOURCE_SYNC_FAILED);
        assertThat(current(Capability.BALANCE).state().authoritative().id()).isEqualTo(good.id());
        assertThat(current(Capability.BALANCE).state().completeForLatestAttempt()).isFalse();
    }
    @Test void springConfigurationWiresCanonicalServiceAndProvider() { assertThat(wiredService).isNotNull();assertThat(wiredProvider.code()).isEqualTo(ProviderCode.OPEN_BANKING); }
    @Test void cleanDatabaseMigratesThroughV9AndRequiredEmptyDomainsExist() {
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM loanops.flyway_schema_history",Integer.class)).isEqualTo(11);
        for(String table:List.of("loan_accounts","loan_payments","card_accounts","card_bills","investment_positions","income_evidence")) assertThat(count(table)).isZero();
    }
    @Test void replayGoldenCanonicalizesOneAccountOneBalanceAndTwentyEightTransactions() {
        var balance=normalize(ingest(OpenBankingCapability.BALANCE));var transactions=normalize(ingest(OpenBankingCapability.TRANSACTIONS));
        assertThat(balance.promoted()).isTrue();assertThat(transactions.promoted()).isTrue();assertThat(count("financial_accounts")).isEqualTo(1);assertThat(count("balance_snapshots")).isEqualTo(1);assertThat(count("financial_transactions")).isEqualTo(28);
        assertThat(current(Capability.BALANCE).balance().balance()).isEqualByComparingTo("1500000");assertThat(current(Capability.BALANCE).balance().availableBalance()).isEqualByComparingTo("1400000");
        assertThat(current(Capability.TRANSACTIONS).transactions()).hasSize(28);assertThat(current(Capability.TRANSACTIONS).state().completeForLatestAttempt()).isTrue();
        HTTP.verify(0,anyRequestedFor(anyUrl()));
    }
    @Test void sameRunIsIdempotentIncludingIssuesAndEvidence() {
        var run=ingest(OpenBankingCapability.TRANSACTIONS);var first=normalize(run);int evidence=count("personal_source_evidence"),issues=count("personal_quality_issues");
        assertThat(normalize(run).id()).isEqualTo(first.id());assertThat(count("personal_validation_runs")).isEqualTo(1);assertThat(count("personal_source_evidence")).isEqualTo(evidence);assertThat(count("personal_quality_issues")).isEqualTo(issues);
    }
    @Test void repeatedReplayRetainsTransactionIdsHashAndNoDuplicateCanonicalRows() {
        var first=normalize(ingest(OpenBankingCapability.TRANSACTIONS));var ids=current(Capability.TRANSACTIONS).transactions().stream().map(FinancialTransaction::id).toList();
        clock.advance(Duration.ofSeconds(1));var next=normalize(ingest(OpenBankingCapability.TRANSACTIONS));
        assertThat(next.contentHash()).isEqualTo(first.contentHash());assertThat(current(Capability.TRANSACTIONS).transactions().stream().map(FinancialTransaction::id).toList()).isEqualTo(ids);
        assertThat(count("financial_transactions")).isEqualTo(28);assertThat(next.issues()).anySatisfy(q->{assertThat(q.rule()).isEqualTo(RuleCode.DUPLICATE_TRANSACTION);assertThat(q.resolution()).isEqualTo(Resolution.RESOLVED);assertThat(q.details()).isEqualTo("SOURCE_DUPLICATE_COUNT=28");});
    }
    @Test void nullableFieldsAreNotInferredAndAmountPrecisionIsPreserved() {
        live();first(page(1,false,null).replace("\"print_content\":\"SYNTHETIC 0\"","\"print_content\":null").replace("1000.10","123456789012345678.12"));normalize(ingest(OpenBankingCapability.TRANSACTIONS));
        var view=current(Capability.TRANSACTIONS);var t=view.transactions().getFirst();
        assertThat(t.amount()).isEqualByComparingTo("123456789012345678.12");assertThat(t.description()).isNull();assertThat(t.merchant()).isNull();assertThat(t.category()).isNull();assertThat(t.currency()).isNull();assertThat(t.postedAt()).isNull();
        assertThat(view.account().currency()).isNull();assertThat(view.account().institution()).isNull();assertThat(view.account().accountType()).isNull();
    }
    @Test void accountTypeFromBalanceAndNegativeBalanceArePreservedWithoutInventingInstitution() {
        live();balance("-100.25","20260921090000000");var run=ingest(OpenBankingCapability.BALANCE);assertThat(run.status()).as("source status: %s",run.errorCategory()).isEqualTo("SUCCESS");assertThat(normalize(run).promoted()).isTrue();var view=current(Capability.BALANCE);
        assertThat(view.account().accountType()).isEqualTo("1");assertThat(view.account().institution()).isNull();assertThat(view.balance().balance()).isEqualByComparingTo("-100.25");assertThat(view.balance().availableBalance()).isNull();
        assertThat(view.account().id().toString()).isNotEqualTo(view.account().sourceAccountKey());
    }
    @Test void timestampAndDirectionMappingAreExplicit() {
        normalize(ingest(OpenBankingCapability.TRANSACTIONS));var t=current(Capability.TRANSACTIONS).transactions().getFirst();
        assertThat(t.occurredAt()).isEqualTo(Instant.parse("2026-09-01T01:00:00Z"));assertThat(t.direction()).isEqualTo(Direction.DEBIT);assertThat(t.postedAt()).isNull();
        live();first(page(1,false,null).replace("출금","입금"));normalize(ingest(OpenBankingCapability.TRANSACTIONS));assertThat(current(Capability.TRANSACTIONS).transactions().getFirst().direction()).isEqualTo(Direction.CREDIT);
    }
    @Test void canonicalProvenanceConnectsSourceRunRawHashAndMode() {
        var run=ingest(OpenBankingCapability.TRANSACTIONS);normalize(run);var t=current(Capability.TRANSACTIONS).transactions().getFirst();
        var trace=personal.provenance(owner,consent.id(),t.provenanceId());
        assertThat(trace.sourceSyncRunId()).isEqualTo(run.id());assertThat(trace.sourceType()).isEqualTo(SourceType.OPEN_BANKING_TEST);assertThat(trace.sourceMode()).isEqualTo(SourceMode.DEMO_REPLAY);
        var raw=source.snapshots(run.id(),owner).getFirst();assertThat(trace.rawSnapshotId()).isEqualTo(raw.id());assertThat(trace.responseHash()).isEqualTo(raw.responseHash());assertThat(trace.endpoint()).isEqualTo(OpenBankingClient.TRANSACTIONS_PATH);
        assertThat(trace.rawRecordIndex()).isEqualTo(0);assertThat(trace.asOf()).isEqualTo(t.occurredAt());assertThat(trace.collectedAt()).isEqualTo(clock.instant());assertThat(trace.normalizedAt()).isEqualTo(clock.instant());
    }
    @Test void balanceProvenanceUsesObservedTimeNotReceiptTime() {
        clock.advance(Duration.ofHours(2));var run=ingest(OpenBankingCapability.BALANCE);normalize(run);var trace=personal.provenance(owner,consent.id(),current(Capability.BALANCE).balance().provenanceId());
        assertThat(trace.asOf()).isEqualTo(Instant.parse("2026-09-21T00:00:00Z"));assertThat(trace.collectedAt()).isEqualTo(clock.instant());assertThat(trace.sourceSyncRunId()).isEqualTo(run.id());
    }
    @Test void liveTestAndDemoOriginAreNeverConflated() {
        live();balance("100","20260921090000000");normalize(ingest(OpenBankingCapability.BALANCE));var trace=personal.provenance(owner,consent.id(),current(Capability.BALANCE).balance().provenanceId());
        assertThat(trace.sourceType()).isEqualTo(SourceType.OPEN_BANKING_TEST);assertThat(trace.sourceMode()).isEqualTo(SourceMode.LIVE_TEST);assertThat(consent.kind()).isEqualTo(ConsentKind.DEVELOPER_TEST);
    }
    @Test void incompleteSyncIsNotPromotedAndPreviousManifestSurvivesThenRetryPromotes() {
        live();first(page(1,false,null));var good=normalize(ingest(OpenBankingCapability.TRANSACTIONS));var ids=current(Capability.TRANSACTIONS).transactions().stream().map(FinancialTransaction::id).toList();
        clock.advance(Duration.ofSeconds(1));first(page(2,true,"page-2"));HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.TRANSACTIONS_PATH)).withQueryParam("befor_inquiry_trace_info",equalTo("page-2")).willReturn(okJson(page(0,false,null)).withFixedDelay(3000)));
        var sourceFailure=ingest(OpenBankingCapability.TRANSACTIONS);assertThat(sourceFailure.errorCategory()).isEqualTo("PAGINATION_INCOMPLETE");var failed=normalize(sourceFailure);
        assertThat(failed.state()).isEqualTo(ValidationState.PARTIAL);assertThat(failed.promoted()).isFalse();assertThat(failed.issues()).anyMatch(q->q.rule()==RuleCode.PAGINATION_INCOMPLETE);
        var view=current(Capability.TRANSACTIONS);assertThat(view.state().authoritative().id()).isEqualTo(good.id());assertThat(view.state().latestAttempt().id()).isEqualTo(failed.id());assertThat(view.state().completeForLatestAttempt()).isFalse();
        assertThat(view.transactions().stream().map(FinancialTransaction::id).toList()).isEqualTo(ids);assertThat(count("financial_transactions")).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));first(page(2,false,null));var retry=normalize(ingest(OpenBankingCapability.TRANSACTIONS));assertThat(retry.promoted()).isTrue();assertThat(current(Capability.TRANSACTIONS).transactions()).hasSize(2);assertThat(current(Capability.TRANSACTIONS).state().completeForLatestAttempt()).isTrue();
    }
    @Test void initialIncompleteHasNoCanonicalRecordsOrCurrentPointer() {
        live();first(page(1,true,"missing-page"));var result=normalize(ingest(OpenBankingCapability.TRANSACTIONS));assertThat(result.state()).isEqualTo(ValidationState.PARTIAL);
        assertThat(count("financial_transactions")).isZero();assertThat(count("financial_accounts")).isZero();assertThat(current(Capability.TRANSACTIONS).state().authoritative()).isNull();
    }
    @Test void staleBalanceIsRetainedForAuditButNotPromoted() {
        live();balance("100","20260921090000000");var good=normalize(ingest(OpenBankingCapability.BALANCE));clock.advance(Duration.ofSeconds(1));balance("999","20260919090000000");var stale=normalize(ingest(OpenBankingCapability.BALANCE));
        assertThat(stale.state()).isEqualTo(ValidationState.STALE);assertThat(stale.promoted()).isFalse();assertThat(stale.issues()).anyMatch(q->q.rule()==RuleCode.STALE_BALANCE);assertThat(count("balance_snapshots")).isEqualTo(2);
        assertThat(current(Capability.BALANCE).state().authoritative().id()).isEqualTo(good.id());assertThat(current(Capability.BALANCE).balance().balance()).isEqualByComparingTo("100");
    }
    @Test void balanceBecomesStaleAtReadTimeWithoutAResync() {
        normalize(ingest(OpenBankingCapability.BALANCE));clock.advance(Duration.ofHours(25));var view=current(Capability.BALANCE);
        assertThat(view.state().freshness()).isEqualTo(Freshness.STALE);assertThat(view.state().effectiveState()).isEqualTo(ValidationState.STALE);assertThat(view.state().warnings()).contains(RuleCode.STALE_BALANCE);assertThat(view.state().completeForLatestAttempt()).isFalse();
    }
    @Test void staleThresholdIsConfiguredAndEvaluationPolicyIsRetained() {
        clock.advance(Duration.ofHours(2));var run=ingest(OpenBankingCapability.BALANCE);var strict=service(Duration.ofHours(1),"strict-v1");
        assertThat(strict.normalize(owner,consent.id(),run.id()).state()).isEqualTo(ValidationState.STALE);
        var relaxed=normalize(run);assertThat(relaxed.state()).isEqualTo(ValidationState.VALIDATED_COMPLETE);assertThat(count("personal_validation_runs")).isEqualTo(2);
    }
    @Test void missingIncomeAndUnsupportedDomainsAreWarningsNotSystemFailures() {
        var result=normalize(ingest(OpenBankingCapability.TRANSACTIONS));assertThat(result.promoted()).isTrue();assertThat(result.issues()).anyMatch(q->q.rule()==RuleCode.MISSING_INCOME_EVIDENCE);
        assertThat(result.issues().stream().filter(q->q.rule()==RuleCode.SOURCE_CAPABILITY_MISSING).map(QualityIssue::affectedEntity).toList()).containsExactlyInAnyOrder("CARDS","LOANS","LEASES","INVESTMENTS","INCOME");
        for(String table:List.of("income_evidence","loan_accounts","loan_payments","card_accounts","card_bills","investment_positions")) assertThat(count(table)).isZero();
    }
    @Test void salaryLikeDescriptionDoesNotCreateVerifiedAnnualIncome() {
        live();first(page(1,false,null).replace("SYNTHETIC 0","급여").replace("출금","입금"));var result=normalize(ingest(OpenBankingCapability.TRANSACTIONS));assertThat(count("income_evidence")).isZero();assertThat(result.issues()).anyMatch(q->q.rule()==RuleCode.MISSING_INCOME_EVIDENCE);
    }
    @Test void consentRequiredWrongOwnerAndWrongConnectionCannotPromote() {
        var run=ingest(OpenBankingCapability.TRANSACTIONS);
        assertThatThrownBy(()->personal.normalize(owner,UUID.randomUUID(),run.id())).isInstanceOf(PersonalFinanceException.class);
        assertThatThrownBy(()->personal.normalize("other-owner",consent.id(),run.id())).isInstanceOf(PersonalFinanceException.class);
        var other=source.createConnection(owner,OpenBankingMode.DEMO_REPLAY,"000000000000000000000002",clock.instant().plus(Duration.ofDays(10)));
        var otherGrant=personal.grant(owner,ProviderCode.OPEN_BANKING,other,Set.of(Capability.TRANSACTIONS),clock.instant().plus(Duration.ofDays(1)));
        assertThatThrownBy(()->personal.normalize(owner,otherGrant.id(),run.id())).extracting("code").isEqualTo(CONSENT_DENIED);assertThat(count("financial_transactions")).isZero();
    }
    @Test void demoConsentIsExplicitAndCannotExceedSourceConsent() {
        assertThat(consent.kind()).isEqualTo(ConsentKind.DEMO);assertThat(consent.statusAt(clock.instant())).isEqualTo(ConsentStatus.ACTIVE);
        assertThatThrownBy(()->personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.BALANCE),clock.instant().plus(Duration.ofDays(20)))).extracting("code").isEqualTo(INVALID_REQUEST);
    }
    @Test void revokedConsentBlocksReprocessingAndReadingExistingCurrent() {
        var run=ingest(OpenBankingCapability.TRANSACTIONS);normalize(run);personal.revoke(owner,consent.id());
        assertThatThrownBy(()->normalize(run)).extracting("code").isEqualTo(CONSENT_DENIED);assertThatThrownBy(()->current(Capability.TRANSACTIONS)).extracting("code").isEqualTo(CONSENT_DENIED);
        assertThat(count("financial_transactions")).isEqualTo(28);assertThat(count("personal_validation_runs")).isEqualTo(1);
    }
    @Test void expiredCanonicalConsentBlocksPromotionBeforeUpstreamConsentExpires() {
        var run=ingest(OpenBankingCapability.TRANSACTIONS);clock.advance(Duration.ofDays(6));assertThatThrownBy(()->normalize(run)).extracting("code").isEqualTo(CONSENT_DENIED);assertThat(count("financial_transactions")).isZero();
    }
    @Test void consentScopeLimitsBothCanonicalPromotionAndTraceRead() {
        personal.revoke(owner,consent.id());consent=personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.BALANCE),clock.instant().plus(Duration.ofDays(1)));
        var run=ingest(OpenBankingCapability.TRANSACTIONS);assertThatThrownBy(()->normalize(run)).extracting("code").isEqualTo(CONSENT_DENIED);assertThatThrownBy(()->current(Capability.TRANSACTIONS)).extracting("code").isEqualTo(CONSENT_DENIED);
    }
    @Test void renewalDoesNotSilentlyExposeOldConsentCurrentState() {
        var run=ingest(OpenBankingCapability.TRANSACTIONS);normalize(run);personal.revoke(owner,consent.id());
        consent=personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.TRANSACTIONS),clock.instant().plus(Duration.ofDays(1)));
        assertThat(current(Capability.TRANSACTIONS).transactions()).isEmpty();assertThat(normalize(run).promoted()).isTrue();assertThat(current(Capability.TRANSACTIONS).transactions()).hasSize(28);
    }
    @Test void differentOwnerCannotReadProvenance() {
        normalize(ingest(OpenBankingCapability.TRANSACTIONS));var t=current(Capability.TRANSACTIONS).transactions().getFirst();assertThatThrownBy(()->personal.provenance("other-owner",consent.id(),t.provenanceId())).isInstanceOf(PersonalFinanceException.class);
    }
    @Test void reverseProcessingOrderDoesNotReplaceNewerBalance() {
        live();balance("100","20260921090000000");var old=ingest(OpenBankingCapability.BALANCE);clock.advance(Duration.ofMinutes(1));balance("200","20260921090100000");var newer=normalize(ingest(OpenBankingCapability.BALANCE));
        var oldResult=normalize(old);assertThat(oldResult.promoted()).isFalse();assertThat(oldResult.issues()).anyMatch(q->q.rule()==RuleCode.OLDER_OBSERVATION);assertThat(current(Capability.BALANCE).state().authoritative().id()).isEqualTo(newer.id());assertThat(current(Capability.BALANCE).balance().balance()).isEqualByComparingTo("200");
    }
    @Test void overlappingIncrementalPromotesConnectedCoverageAndPreservesManifest() {
        var first=sync.sync(connection,owner,OpenBankingCapability.TRANSACTIONS,from,from);normalize(first);assertThat(current(Capability.TRANSACTIONS).transactions()).hasSize(25);
        clock.advance(Duration.ofSeconds(1));var second=sync.incremental(connection,owner,through);var result=normalize(second);
        assertThat(result.coveredFrom()).isEqualTo(from);assertThat(result.coveredThrough()).isEqualTo(through);assertThat(current(Capability.TRANSACTIONS).transactions()).hasSize(28);assertThat(count("financial_transactions")).isEqualTo(28);
    }
    @Test void disjointBackfillCannotInventContinuousHistory() {
        var good=normalize(ingest(OpenBankingCapability.TRANSACTIONS));clock.advance(Duration.ofSeconds(1));var run=sync.sync(connection,owner,OpenBankingCapability.TRANSACTIONS,LocalDate.of(2026,8,1),LocalDate.of(2026,8,2));var disjoint=normalize(run);
        assertThat(disjoint.state()).isEqualTo(ValidationState.VALIDATED_COMPLETE);assertThat(disjoint.promoted()).isFalse();assertThat(disjoint.issues()).anyMatch(q->q.rule()==RuleCode.DISJOINT_TRANSACTION_WINDOW);assertThat(current(Capability.TRANSACTIONS).state().authoritative().id()).isEqualTo(good.id());
    }
    @Test void corruptedAuthenticatedSourceEvidenceCreatesInvalidAuditAndNoPromotion() {
        var good=normalize(ingest(OpenBankingCapability.BALANCE));clock.advance(Duration.ofSeconds(1));var run=ingest(OpenBankingCapability.BALANCE);
        jdbc.update("UPDATE loanops.raw_openbanking_snapshots SET payload_ciphertext='not-a-valid-envelope' WHERE sync_run_id=?",run.id());
        var invalid=normalize(run);assertThat(invalid.state()).isEqualTo(ValidationState.INVALID);assertThat(invalid.issues()).anyMatch(q->q.rule()==RuleCode.INVALID_SOURCE_DATA);assertThat(current(Capability.BALANCE).state().authoritative().id()).isEqualTo(good.id());
    }
    @Test void databaseFailureRollsBackCanonicalRowsIssuesAndPointer() {
        live();first(page(1,false,null));var good=normalize(ingest(OpenBankingCapability.TRANSACTIONS));clock.advance(Duration.ofSeconds(1));first(page(2,false,null));var next=ingest(OpenBankingCapability.TRANSACTIONS);
        jdbc.execute("ALTER TABLE loanops.personal_validation_transactions ADD CONSTRAINT synthetic_failure CHECK(false) NOT VALID");
        try {assertThatThrownBy(()->normalize(next)).extracting("code").isEqualTo(STORAGE_ERROR);assertThat(count("financial_transactions")).isEqualTo(1);assertThat(count("personal_validation_runs")).isEqualTo(1);assertThat(current(Capability.TRANSACTIONS).state().authoritative().id()).isEqualTo(good.id());}
        finally {jdbc.execute("ALTER TABLE loanops.personal_validation_transactions DROP CONSTRAINT synthetic_failure");}
    }
    @Test void concurrentNormalizationIsIdempotent() throws Exception {
        var run=ingest(OpenBankingCapability.TRANSACTIONS);
        try(var pool=Executors.newFixedThreadPool(2)) {var a=pool.submit(()->normalize(run));var b=pool.submit(()->normalize(run));assertThat(a.get(10,TimeUnit.SECONDS).id()).isEqualTo(b.get(10,TimeUnit.SECONDS).id());}
        assertThat(count("financial_transactions")).isEqualTo(28);assertThat(count("personal_validation_runs")).isEqualTo(1);
    }
    @Test void payloadAndRecordLoggingDoNotExposeIdentifiersDescriptionsOrSourceSecrets(CapturedOutput output) {
        normalize(ingest(OpenBankingCapability.TRANSACTIONS));var view=current(Capability.TRANSACTIONS);var record=view.transactions().getFirst();var trace=personal.provenance(owner,consent.id(),record.provenanceId());
        assertThat(record.toString()+trace+consent+view.account()).doesNotContain(record.id().toString(),record.sourceTransactionKey(),trace.rawSnapshotId().toString(),"SYNTHETIC",owner);
        for(String payload:jdbc.queryForList("SELECT payload_ciphertext FROM loanops.financial_transactions",String.class)) assertThat(payload).doesNotContain("SYNTHETIC","000000000000000000000001");
        assertThat(output.getAll()).doesNotContain(record.id().toString(),trace.id().toString(),"000000000000000000000001","synthetic-access","synthetic-secret");
    }
    @Test void readPaginationIsBoundedAndDeterministic() {
        normalize(ingest(OpenBankingCapability.TRANSACTIONS));var first=personal.current(owner,consent.id(),Capability.TRANSACTIONS,0,10);var second=personal.current(owner,consent.id(),Capability.TRANSACTIONS,10,10);
        assertThat(first.transactions()).hasSize(10);assertThat(second.transactions()).hasSize(10);assertThat(first.transactions().stream().map(FinancialTransaction::id).toList()).doesNotContainAnyElementsOf(second.transactions().stream().map(FinancialTransaction::id).toList());
        assertThatThrownBy(()->personal.current(owner,consent.id(),Capability.TRANSACTIONS,0,101)).extracting("code").isEqualTo(INVALID_REQUEST);
    }
    static final class MutableClock extends Clock {
        private Instant now;MutableClock(Instant now){this.now=now;}void advance(Duration d){now=now.plus(d);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}public Instant instant(){return now;}
    }
}
