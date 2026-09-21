package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.loanops.personal.canonical.*;
import com.loanops.personal.canonical.PersonalFinanceModels.*;
import com.loanops.personal.gateway.PersonalFinanceProvider;
import com.loanops.personal.publicmydata.*;
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
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"fincompass.open-banking.enabled=true","fincompass.personal.enabled=true"})
@ExtendWith(OutputCaptureExtension.class)
class PublicMyDataIntegrationTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
        r.add("fincompass.open-banking.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager tx; @Autowired ObjectMapper json;
    @Autowired List<PersonalFinanceProvider> wiredProviders;
    MutableClock clock;PersonalFinanceService service;PersonalFinanceStore store;PersonalFinanceCipher cipher;
    OpenBankingStore ob;OpenBankingSyncService sync;OpenBankingPersonalFinanceProvider gateway;
    MockPublicMyDataProvider mock;String overridePayload;UUID user,connection;ConsentGrant grant;
    final String owner="explicit-demo-home-buyer";final String main="main-home-buyer-v1";
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE loanops.open_banking_connections,loanops.financial_users CASCADE");
        clock=new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        var box=new SecretBox(Base64.getEncoder().encodeToString(new byte[32]),"synthetic-test");var obJson=new OpenBankingJson(json);
        ob=new OpenBankingStore(jdbc,tx,box,obJson,clock);var mapper=new OpenBankingMapper(obJson);
        var config=new OpenBankingProperties(true,OpenBankingMode.DEMO_REPLAY,"","","","","","","",Base64.getEncoder().encodeToString(new byte[32]),"synthetic-test",180,3,100,Duration.ofSeconds(2),Duration.ofSeconds(5),1048576);
        sync=new OpenBankingSyncService(new ReplayOpenBankingProvider(obJson,clock),ob,mapper,config,clock);
        gateway=new OpenBankingPersonalFinanceProvider(ob,mapper,obJson,box,jdbc);
        cipher=new PersonalFinanceCipher(box,json);store=new PersonalFinanceStore(jdbc,tx,cipher);
        mock=new MockPublicMyDataProvider(jdbc,cipher,json) { @Override public String request(String fixture,Capability c) { return overridePayload==null?super.request(fixture,c):overridePayload; } };
        service=service(new IncomeQualityProperties());user=service.createDemoUser(owner);
        connection=service.connectPublicMyDataMock(owner,user,clock.instant().plus(Duration.ofDays(1000)));
        grant=service.grant(owner,ProviderCode.PUBLIC_MYDATA,connection,Set.of(Capability.INCOME,Capability.EMPLOYMENT),clock.instant().plus(Duration.ofDays(900)));
    }
    PersonalFinanceService service(IncomeQualityProperties policy) { return new PersonalFinanceService(List.of(gateway,mock),store,cipher,new PersonalQualityProperties(Duration.ofHours(24),"personal-dq-v1"),clock,policy); }
    UUID collect(Capability cap) { return service.collectMock(owner,connection,grant.id(),cap,main); }
    ValidationResult load(Capability cap) {return service.normalize(owner,grant.id(),collect(cap));}
    IncomeView view(Capability cap) {return service.currentIncome(owner,grant.id(),cap);}
    ObjectNode document(Capability cap) throws Exception {return (ObjectNode)json.readTree(new MockPublicMyDataProvider(jdbc,cipher,json).request(main,cap));}
    ObjectNode item(ObjectNode root) {return (ObjectNode)root.get("evidence").get(0);}
    int count(String table) {return jdbc.queryForObject("SELECT count(*) FROM loanops."+table,Integer.class);}
    void changed(String amount,String asOf) throws Exception {var d=document(Capability.INCOME);item(d).put("verifiedAnnualIncome",amount);item(d).put("asOf",asOf);overridePayload=d.toString();}
    @Test void springWiresBothProviders() {assertThat(wiredProviders.stream().map(PersonalFinanceProvider::code)).containsExactlyInAnyOrder(ProviderCode.OPEN_BANKING,ProviderCode.PUBLIC_MYDATA);}
    @Test void mainPersonaHasSeparateIncomeAndEmploymentEvidence() {
        assertThat(load(Capability.INCOME).promoted()).isTrue();assertThat(load(Capability.EMPLOYMENT).promoted()).isTrue();
        var income=view(Capability.INCOME).evidence().getFirst();assertThat(income.verifiedAnnualIncome()).isEqualByComparingTo("66000000");assertThat(income.incomeYear()).isEqualTo(2025);
        assertThat(income.currency()).isEqualTo("KRW");assertThat(income.employmentStatus()).isNull();assertThat(income.evidenceType()).isEqualTo(EvidenceType.INCOME_CERTIFICATE);
        var employment=view(Capability.EMPLOYMENT).evidence().getFirst();assertThat(employment.employmentStatus()).isEqualTo(EmploymentStatus.EMPLOYED);assertThat(employment.verifiedAnnualIncome()).isNull();assertThat(employment.incomeYear()).isNull();
    }
    @Test void selfEmployedPersonaIsDeterministic() {
        for(var c:List.of(Capability.INCOME,Capability.EMPLOYMENT)) service.normalize(owner,grant.id(),service.collectMock(owner,connection,grant.id(),c,"self-employed-v1"));
        assertThat(view(Capability.INCOME).evidence().getFirst().verifiedAnnualIncome()).isEqualByComparingTo("48000000");assertThat(view(Capability.EMPLOYMENT).evidence().getFirst().employmentStatus()).isEqualTo(EmploymentStatus.SELF_EMPLOYED);
    }
    @Test void missingPersonaIsPartialAndDoesNotInventIncome() {
        var run=service.collectMock(owner,connection,grant.id(),Capability.INCOME,"missing-income-v1");var result=service.normalize(owner,grant.id(),run);
        assertThat(result.state()).isEqualTo(ValidationState.PARTIAL);assertThat(result.promoted()).isFalse();assertThat(result.issues()).anyMatch(q->q.rule()==RuleCode.MISSING_INCOME_EVIDENCE);assertThat(count("income_evidence")).isZero();
    }
    @Test void unknownEmploymentIsPreservedAsIncomplete() {
        var result=service.normalize(owner,grant.id(),service.collectMock(owner,connection,grant.id(),Capability.EMPLOYMENT,"missing-income-v1"));
        assertThat(result.promoted()).isFalse();assertThat(result.issues()).anyMatch(q->q.rule()==RuleCode.SOURCE_CAPABILITY_MISSING);assertThat(view(Capability.EMPLOYMENT).evidence()).isEmpty();
    }
    @Test void publicMyDataDoesNotNeedAnyOpenBankingRows() {
        load(Capability.INCOME);assertThat(count("open_banking_connections")).isZero();assertThat(count("source_sync_runs")).isZero();assertThat(count("raw_openbanking_snapshots")).isZero();
        assertThat(jdbc.queryForObject("SELECT source_connection_id IS NULL FROM loanops.consent_grants",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT raw_snapshot_id IS NULL AND source_sync_run_id IS NULL FROM loanops.personal_source_evidence",Boolean.class)).isTrue();
        assertThat(count("provider_sync_runs")).isEqualTo(1);assertThat(count("provider_evidence")).isEqualTo(1);
    }
    @Test void provenanceUsesGenericEvidenceAndConsent() {
        var result=load(Capability.INCOME);var value=view(Capability.INCOME).evidence().getFirst();var trace=service.provenance(owner,grant.id(),value.provenanceId());
        assertThat(trace.providerEvidenceId()).isEqualTo(value.providerEvidenceId());assertThat(trace.rawSnapshotId()).isNull();assertThat(trace.sourceSyncRunId()).isEqualTo(result.sourceSyncRunId());assertThat(trace.consentGrantId()).isEqualTo(grant.id());
        assertThat(trace.sourceType()).isEqualTo(SourceType.PUBLIC_MYDATA_MOCK);assertThat(trace.sourceMode()).isEqualTo(SourceMode.DEMO_REPLAY);assertThat(trace.responseHash()).hasSize(64);assertThat(trace.endpoint()).startsWith("mock:");
        assertThat(trace.asOf()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));assertThat(trace.collectedAt()).isEqualTo(clock.instant());assertThat(trace.normalizedAt()).isEqualTo(clock.instant());
    }
    @Test void sameReplayReusesRunEvidenceCanonicalIdentityAndHash() {
        var first=load(Capability.INCOME);var id=view(Capability.INCOME).evidence().getFirst().id();clock.advance(Duration.ofSeconds(1));var second=load(Capability.INCOME);
        assertThat(second.id()).isEqualTo(first.id());assertThat(second.contentHash()).isEqualTo(first.contentHash());assertThat(view(Capability.INCOME).evidence().getFirst().id()).isEqualTo(id);
        assertThat(count("provider_sync_runs")).isEqualTo(1);assertThat(count("provider_evidence")).isEqualTo(1);assertThat(count("income_evidence")).isEqualTo(1);
    }
    @Test void nullIncomeIsNotZeroOrSalaryInference() throws Exception {
        var d=document(Capability.INCOME);item(d).putNull("verifiedAnnualIncome");overridePayload=d.toString();var result=load(Capability.INCOME);
        assertThat(result.state()).isEqualTo(ValidationState.PARTIAL);assertThat(view(Capability.INCOME).evidence()).isEmpty();assertThat(jdbc.queryForObject("SELECT is_verified FROM loanops.income_evidence",Boolean.class)).isFalse();
    }
    @Test void decimalPrecisionIsExact() throws Exception {
        changed("123456789012345678.12","2026-09-01T00:00:00Z");load(Capability.INCOME);assertThat(view(Capability.INCOME).evidence().getFirst().verifiedAnnualIncome()).isEqualByComparingTo("123456789012345678.12");
    }
    @Test void zeroIncomeIsExplicitValidEvidence() throws Exception {changed("0","2026-09-01T00:00:00Z");assertThat(load(Capability.INCOME).promoted()).isTrue();}
    @Test void scopesAreProviderSpecific() {
        service.revoke(owner,grant.id());assertThatThrownBy(()->service.grant(owner,ProviderCode.PUBLIC_MYDATA,connection,Set.of(Capability.BALANCE),clock.instant().plusSeconds(60))).isInstanceOf(PersonalFinanceException.class);
        var obConnection=ob.createConnection(owner,OpenBankingMode.DEMO_REPLAY,"000000000000000000000001",clock.instant().plusSeconds(3600));
        assertThatThrownBy(()->service.grant(owner,ProviderCode.OPEN_BANKING,obConnection,Set.of(Capability.INCOME),clock.instant().plusSeconds(60))).isInstanceOf(PersonalFinanceException.class);
    }
    @Test void incomeScopeDoesNotCollectEmployment() {
        service.revoke(owner,grant.id());grant=service.grant(owner,ProviderCode.PUBLIC_MYDATA,connection,Set.of(Capability.INCOME),clock.instant().plusSeconds(3600));
        assertThat(load(Capability.INCOME).promoted()).isTrue();assertThatThrownBy(()->collect(Capability.EMPLOYMENT)).isInstanceOf(PersonalFinanceException.class);assertAudit("CONSENT_SCOPE_DENIED");
    }
    @Test void missingConsentProducesAuditWithoutCollectingPayload() {
        assertThatThrownBy(()->service.collectMock(owner,connection,null,Capability.INCOME,main)).isInstanceOf(PersonalFinanceException.class);assertAudit("CONSENT_MISSING");assertThat(count("provider_evidence")).isZero();
    }
    @Test void expiredConsentBlocksCollectionAndReadButPreservesCurrent() {
        var first=load(Capability.INCOME);clock.advance(Duration.ofDays(901));assertThatThrownBy(()->collect(Capability.INCOME)).isInstanceOf(PersonalFinanceException.class);assertAudit("CONSENT_EXPIRED");assertCurrentId(first.id());assertThatThrownBy(()->view(Capability.INCOME)).isInstanceOf(PersonalFinanceException.class);
    }
    @Test void revokedConsentBlocksCollectionAndReadButPreservesAudit() {
        var first=load(Capability.INCOME);service.revoke(owner,grant.id());assertThatThrownBy(()->collect(Capability.INCOME)).isInstanceOf(PersonalFinanceException.class);assertAudit("CONSENT_REVOKED");assertCurrentId(first.id());assertThat(count("provider_evidence")).isEqualTo(1);
    }
    @Test void crossOwnerAndCrossConnectionCannotReadOrCollect() {
        load(Capability.INCOME);assertThatThrownBy(()->service.currentIncome("other",grant.id(),Capability.INCOME)).isInstanceOf(PersonalFinanceException.class);
        var other=service.connectPublicMyDataMock(owner,user,clock.instant().plusSeconds(3600));assertThatThrownBy(()->service.collectMock(owner,other,grant.id(),Capability.INCOME,main)).isInstanceOf(PersonalFinanceException.class);
        assertThatThrownBy(()->service.connectPublicMyDataMock("other",user,clock.instant().plusSeconds(3600))).isInstanceOf(PersonalFinanceException.class);
    }
    @Test void recentCollectionDoesNotFreshenOldAsOf() throws Exception {
        var good=load(Capability.INCOME);changed("66000000","2025-09-01T00:00:00Z");var stale=load(Capability.INCOME);assertThat(stale.state()).isEqualTo(ValidationState.STALE);assertThat(stale.promoted()).isFalse();assertCurrentId(good.id());assertThat(stale.issues()).anyMatch(q->q.rule()==RuleCode.STALE_INCOME_EVIDENCE);assertThat(view(Capability.INCOME).state().completeForLatestAttempt()).isFalse();
    }
    @Test void incomeYearRelevanceIsSeparateFromEvidenceFreshness() throws Exception {
        var d=document(Capability.INCOME);item(d).put("incomeYear",2020);overridePayload=d.toString();var result=load(Capability.INCOME);
        assertThat(result.state()).isEqualTo(ValidationState.STALE);assertThat(result.issues()).anyMatch(q->q.rule()==RuleCode.INCOME_YEAR_OUTDATED);assertThat(result.issues()).noneMatch(q->q.rule()==RuleCode.STALE_INCOME_EVIDENCE);
    }
    @Test void staleThresholdCanChangeWithoutRewritingPriorEvaluation() {
        UUID run=collect(Capability.INCOME);var strict=service(new IncomeQualityProperties(Duration.ofDays(1),2));var stale=strict.normalize(owner,grant.id(),run);var fresh=service.normalize(owner,grant.id(),run);
        assertThat(stale.state()).isEqualTo(ValidationState.STALE);assertThat(fresh.promoted()).isTrue();assertThat(count("personal_validation_runs")).isEqualTo(2);
    }
    @Test void readTimeFreshnessAgesWithoutNewCollection() {
        load(Capability.INCOME);clock.advance(Duration.ofDays(181));assertThat(view(Capability.INCOME).state().effectiveState()).isEqualTo(ValidationState.STALE);assertThat(view(Capability.INCOME).state().completeForLatestAttempt()).isFalse();
    }
    @Test void conflictingSameYearSameAsOfDoesNotReplaceCurrent() throws Exception {
        var good=load(Capability.INCOME);changed("67000000","2026-09-01T00:00:00Z");var bad=load(Capability.INCOME);assertThat(bad.state()).isEqualTo(ValidationState.INVALID);assertThat(bad.issues()).anyMatch(q->q.rule()==RuleCode.CONFLICTING_INCOME_EVIDENCE);assertCurrentId(good.id());
    }
    @Test void ambiguousBundleIsNotAutoResolved() throws Exception {
        var d=document(Capability.INCOME);((ArrayNode)d.get("evidence")).add(item(d).deepCopy().put("verifiedAnnualIncome","67000000"));overridePayload=d.toString();var result=load(Capability.INCOME);assertThat(result.promoted()).isFalse();assertThat(result.issues()).anyMatch(q->q.rule()==RuleCode.CONFLICTING_INCOME_EVIDENCE);
    }
    @Test void newerValidEvidenceDeterministicallyReplacesCurrent() throws Exception {
        var old=load(Capability.INCOME);changed("67000000","2026-09-20T00:00:00Z");var next=load(Capability.INCOME);assertThat(next.promoted()).isTrue();assertThat(view(Capability.INCOME).state().authoritative().id()).isEqualTo(next.id()).isNotEqualTo(old.id());assertThat(view(Capability.INCOME).evidence().getFirst().verifiedAnnualIncome()).isEqualByComparingTo("67000000");
    }
    @Test void olderObservationCannotRollCurrentBack() throws Exception {
        load(Capability.INCOME);changed("67000000","2026-09-20T00:00:00Z");var good=load(Capability.INCOME);changed("68000000","2026-09-10T00:00:00Z");var bad=load(Capability.INCOME);assertThat(bad.promoted()).isFalse();assertThat(bad.issues()).anyMatch(q->q.rule()==RuleCode.OLDER_OBSERVATION);assertCurrentId(good.id());
    }
    @Test void malformedResponseIsAuditedAndPreviousValidSurvives() {
        var good=load(Capability.INCOME);overridePayload="not-json";var invalid=load(Capability.INCOME);assertThat(invalid.state()).isEqualTo(ValidationState.INVALID);assertThat(invalid.promoted()).isFalse();assertCurrentId(good.id());assertThat(count("provider_evidence")).isEqualTo(2);
    }
    @Test void unsupportedSourceStateIsPartial() {
        UUID run=collect(Capability.INCOME);jdbc.update("UPDATE loanops.provider_sync_runs SET status='UNSUPPORTED',complete=false WHERE id=?",run);var result=service.normalize(owner,grant.id(),run);assertThat(result.state()).isEqualTo(ValidationState.PARTIAL);assertThat(result.issues()).anyMatch(q->q.rule()==RuleCode.SOURCE_CAPABILITY_MISSING);
    }
    @Test void futureIncomeYearAndFutureEvidenceAreInvalid() throws Exception {
        var d=document(Capability.INCOME);item(d).put("incomeYear",2027);overridePayload=d.toString();assertThat(load(Capability.INCOME).state()).isEqualTo(ValidationState.INVALID);
        d=document(Capability.INCOME);item(d).put("asOf","2027-01-01T00:00:00Z");overridePayload=d.toString();assertThat(load(Capability.INCOME).state()).isEqualTo(ValidationState.INVALID);
    }
    @Test void unknownSensitiveFieldsAreOmittedNotStored(CapturedOutput output) throws Exception {
        var d=document(Capability.INCOME);item(d).put("residentRegistrationNumber","SYNTHETIC-FORBIDDEN-MARKER");overridePayload=d.toString();assertThat(load(Capability.INCOME).state()).isEqualTo(ValidationState.INVALID);
        UUID raw=jdbc.queryForObject("SELECT id FROM loanops.provider_evidence",UUID.class);String encrypted=jdbc.queryForObject("SELECT payload_ciphertext FROM loanops.provider_evidence",String.class);
        assertThat(cipher.open("provider:"+raw,encrypted,String.class)).isEqualTo("{\"rejected\":true}");assertThat(output.getAll()).doesNotContain("SYNTHETIC-FORBIDDEN-MARKER",raw.toString());
    }
    @Test void financialValuesAndEvidenceIdsDoNotAppearInLogsOrToString(CapturedOutput output) {
        var result=load(Capability.INCOME);var value=view(Capability.INCOME).evidence().getFirst();assertThat(value.toString()).doesNotContain("66000000",value.id().toString(),grant.id().toString());assertThat(output.getAll()).doesNotContain(value.id().toString(),value.providerEvidenceId().toString(),"66000000");assertThat(result.toString()).doesNotContain(result.sourceSyncRunId().toString());
    }
    @Test void explicitDemoUserLinksProvidersWithoutChangingOpenBankingFixture() {
        load(Capability.INCOME);load(Capability.EMPLOYMENT);
        UUID obConnection=ob.createConnection(owner,OpenBankingMode.DEMO_REPLAY,"000000000000000000000001",clock.instant().plusSeconds(3600));var obGrant=service.grant(owner,ProviderCode.OPEN_BANKING,obConnection,Set.of(Capability.BALANCE,Capability.TRANSACTIONS),clock.instant().plusSeconds(1800));
        for(var cap:List.of(OpenBankingCapability.BALANCE,OpenBankingCapability.TRANSACTIONS)) service.normalize(owner,obGrant.id(),sync.sync(obConnection,owner,cap,LocalDate.of(2026,9,1),LocalDate.of(2026,9,2)).id());
        assertThat(obGrant.userId()).isEqualTo(user);assertThat(grant.userId()).isEqualTo(user);assertThat(service.current(owner,obGrant.id(),Capability.TRANSACTIONS,0,100).transactions()).hasSize(28);assertThat(service.current(owner,obGrant.id(),Capability.BALANCE,0,100).balance().balance()).isEqualByComparingTo("1500000");
        assertThat(count("provider_connections")).isEqualTo(2);assertThat(count("financial_users")).isEqualTo(1);
    }
    @Test void sameFixtureForDifferentExplicitUsersDoesNotMerge() {
        load(Capability.INCOME);UUID otherUser=service.createDemoUser("other-user"),otherConnection=service.connectPublicMyDataMock("other-user",otherUser,clock.instant().plusSeconds(3600));var other=service.grant("other-user",ProviderCode.PUBLIC_MYDATA,otherConnection,Set.of(Capability.INCOME),clock.instant().plusSeconds(1800));
        service.normalize("other-user",other.id(),service.collectMock("other-user",otherConnection,other.id(),Capability.INCOME,main));assertThat(count("financial_users")).isEqualTo(2);assertThat(count("income_evidence")).isEqualTo(2);assertThat(view(Capability.INCOME).evidence().getFirst().userId()).isEqualTo(user);
    }
    @Test void concurrentReplayIsIdempotent() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {var a=pool.submit(()->load(Capability.INCOME));var b=pool.submit(()->load(Capability.INCOME));assertThat(a.get(10,TimeUnit.SECONDS).id()).isEqualTo(b.get(10,TimeUnit.SECONDS).id());}
        assertThat(count("provider_evidence")).isEqualTo(1);assertThat(count("income_evidence")).isEqualTo(1);
    }
    @Test void renewedConsentRequiresRenormalization() {
        load(Capability.INCOME);service.revoke(owner,grant.id());grant=service.grant(owner,ProviderCode.PUBLIC_MYDATA,connection,Set.of(Capability.INCOME),clock.instant().plusSeconds(1800));assertThat(view(Capability.INCOME).evidence()).isEmpty();assertThat(load(Capability.INCOME).promoted()).isTrue();
    }
    @Test void consentExpiringAfterCollectionPreventsNormalizationAndAuditsDenial() {
        var good=load(Capability.INCOME);UUID rawRun=collect(Capability.EMPLOYMENT);clock.advance(Duration.ofDays(901));
        assertThatThrownBy(()->service.normalize(owner,grant.id(),rawRun)).isInstanceOf(PersonalFinanceException.class);assertAudit("CONSENT_EXPIRED");assertCurrentId(good.id());assertThat(count("provider_evidence")).isEqualTo(2);
    }
    @Test void revokedConsentCannotNormalizePreviouslyCollectedEvidence() {
        UUID run=collect(Capability.INCOME);service.revoke(owner,grant.id());assertThatThrownBy(()->service.normalize(owner,grant.id(),run)).isInstanceOf(PersonalFinanceException.class);assertAudit("CONSENT_REVOKED");assertThat(count("income_evidence")).isZero();
    }
    @Test void unknownFixtureDoesNotTurnIntoImplicitFallback() {
        assertThatThrownBy(()->service.collectMock(owner,connection,grant.id(),Capability.INCOME,"unknown-fixture")).isInstanceOf(PersonalFinanceException.class);assertThat(count("provider_evidence")).isZero();
    }
    @Test void currentReadReevaluatesIncomeYearRelevance() {
        load(Capability.INCOME);clock.advance(Duration.ofDays(850));var policy=service(new IncomeQualityProperties(Duration.ofDays(1825),1));
        var state=policy.currentIncome(owner,grant.id(),Capability.INCOME).state();assertThat(state.warnings()).contains(RuleCode.INCOME_YEAR_OUTDATED);assertThat(state.completeForLatestAttempt()).isFalse();
    }
    @Test void schemaRejectsWrongProviderScopes() {
        assertThatThrownBy(()->jdbc.update("UPDATE loanops.consent_grants SET scopes=ARRAY['BALANCE'] WHERE id=?",grant.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void canonicalWriteFailureRollsBackButSourceEvidenceSurvives() {
        var good=load(Capability.INCOME);
        try {
            jdbc.execute("ALTER TABLE loanops.income_evidence ADD CONSTRAINT synthetic_fail CHECK(false) NOT VALID");
            overridePayload=mock.request(main,Capability.INCOME).replace("66000000","67000000").replace("2026-09-01","2026-09-20");UUID run=collect(Capability.INCOME);
            assertThatThrownBy(()->service.normalize(owner,grant.id(),run)).extracting("code").isEqualTo(PersonalFinanceException.Code.STORAGE_ERROR);
            assertCurrentId(good.id());assertThat(count("provider_evidence")).isEqualTo(2);assertThat(count("income_evidence")).isEqualTo(1);
        } finally {jdbc.execute("ALTER TABLE loanops.income_evidence DROP CONSTRAINT IF EXISTS synthetic_fail");}
    }
    @Test void sourceObservationReceiptAndNormalizationTimesRemainDistinct() {
        UUID run=collect(Capability.INCOME);Instant receipt=clock.instant();clock.advance(Duration.ofMinutes(5));service.normalize(owner,grant.id(),run);
        var e=view(Capability.INCOME).evidence().getFirst();assertThat(e.asOf()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));assertThat(e.collectedAt()).isEqualTo(receipt);assertThat(e.normalizedAt()).isEqualTo(clock.instant());
    }
    @Test void overflowingIncomeYearIsRejectedBeforePersistence() throws Exception {
        var d=document(Capability.INCOME);item(d).put("incomeYear",4294969321L);overridePayload=d.toString();assertThat(load(Capability.INCOME).state()).isEqualTo(ValidationState.INVALID);assertThat(count("income_evidence")).isZero();
    }
    @Test void futureAsOfIsPreservedInAuditEvenThoughPromotionIsBlocked() throws Exception {
        changed("66000000","2027-01-01T00:00:00Z");assertThat(load(Capability.INCOME).promoted()).isFalse();assertThat(jdbc.queryForObject("SELECT as_of FROM loanops.provider_evidence",java.sql.Timestamp.class).toInstant()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }
    @Test void employmentConflictAtSameAsOfIsNotAutoResolved() throws Exception {
        var good=load(Capability.EMPLOYMENT);var d=document(Capability.EMPLOYMENT);item(d).put("employmentStatus","UNEMPLOYED");overridePayload=d.toString();var bad=load(Capability.EMPLOYMENT);
        assertThat(bad.state()).isEqualTo(ValidationState.INVALID);assertThat(view(Capability.EMPLOYMENT).state().authoritative().id()).isEqualTo(good.id());
    }
    void assertAudit(String code) {assertThat(jdbc.queryForList("SELECT rule_code FROM loanops.provider_access_issues",String.class)).contains(code);}
    void assertCurrentId(UUID id) {assertThat(jdbc.queryForObject("SELECT validation_run_id FROM loanops.provider_current_evidence WHERE provider_connection_id=? AND capability='INCOME'",UUID.class,connection)).isEqualTo(id);}
    static class MutableClock extends Clock { private Instant now;MutableClock(Instant now){this.now=now;}void advance(Duration d){now=now.plus(d);}public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;} }
}
