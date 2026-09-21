package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingCapability.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class OpenBankingIntegrationTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
    static final WireMockServer HTTP=new WireMockServer(wireMockConfig().dynamicPort());
    static { HTTP.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @AfterAll static void closeHttp() { HTTP.stop(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper objectMapper;
    final Clock clock=Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"),ZoneOffset.UTC);
    final LocalDate from=LocalDate.of(2026,9,1),to=LocalDate.of(2026,9,21);
    final String owner="synthetic-test-owner";
    SecretBox box; OpenBankingJson json; OpenBankingStore store; OpenBankingProperties config;
    OpenBankingClient client; OpenBankingTokenService tokens; OpenBankingSyncService sync;
    UUID id;
    @BeforeEach void setup() {
        HTTP.resetAll();
        jdbc.execute("TRUNCATE loanops.open_banking_connections CASCADE");
        json=new OpenBankingJson(objectMapper); box=new SecretBox(Base64.getEncoder().encodeToString(new byte[32]),"synthetic-test");
        store=new OpenBankingStore(jdbc,transactions,box,json,clock);
        config=config(OpenBankingMode.LIVE_TEST,10);
        config.validate(); client=new OpenBankingClient(config,clock,json); tokens=new OpenBankingTokenService(store,client,config,clock);
        sync=new OpenBankingSyncService(new LiveTestOpenBankingProvider(client,tokens,store,config,clock),store,new OpenBankingMapper(json),config,clock);
        id=store.createConnection(owner,OpenBankingMode.LIVE_TEST,"000000000000000000000001",clock.instant().plusSeconds(3600));
        saveToken("synthetic-access","synthetic-refresh",clock.instant().plusSeconds(3600),"inquiry");
    }
    private OpenBankingProperties config(OpenBankingMode mode,int pages) {
        return new OpenBankingProperties(true,mode,HTTP.baseUrl(),HTTP.baseUrl()+"/authorize",HTTP.baseUrl()+"/token","synthetic-client","synthetic-secret",
                "http://localhost/callback","TEST000001",Base64.getEncoder().encodeToString(new byte[32]),"synthetic-test",180,3,pages,Duration.ofSeconds(1),Duration.ofMillis(400),1024*1024);
    }
    private void saveToken(String access,String refresh,Instant expiry,String scope) {
        store.updateToken(store.connection(id,owner),old->new Token(access,refresh,expiry,scope));
    }
    private void tokenResponse() {
        HTTP.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"token_type\":\"Bearer\",\"access_token\":\"synthetic-new-access\",\"refresh_token\":\"synthetic-new-refresh\",\"expires_in\":3600,\"scope\":\"inquiry\"}")));
    }
    private String state() {
        String query=tokens.authorization(id,owner).getRawQuery();
        return Arrays.stream(query.split("&")).filter(s->s.startsWith("state=")).map(s->URLDecoder.decode(s.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    private ObjectNode row(int index) {
        ObjectNode n=objectMapper.createObjectNode();
        n.put("tran_date","20260901");n.put("tran_time","100000");n.put("inout_type","출금");n.put("tran_type","체크카드");
        n.put("tran_amt","1000");n.put("after_balance_amt",Integer.toString(100000-index*1000));n.put("print_content","SYNTHETIC "+index);n.put("branch_name","DEMO");return n;
    }
    private String page(int count,boolean next,String cursor) {
        ObjectNode n=objectMapper.createObjectNode();n.put("rsp_code","A0000");n.put("bank_rsp_code","000");n.put("next_page_yn",next?"Y":"N");n.put("page_record_cnt",count);
        if(cursor!=null)n.put("befor_inquiry_trace_info",cursor);
        ArrayNode a=n.putArray("res_list");for(int i=0;i<count;i++)a.add(row(i));return n.toString();
    }
    private void firstPage(String body) { HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.TRANSACTIONS_PATH)).withQueryParam("befor_inquiry_trace_info",absent()).willReturn(okJson(body))); }
    private void nextPage(String cursor,String body) { HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.TRANSACTIONS_PATH)).withQueryParam("befor_inquiry_trace_info",equalTo(cursor)).willReturn(okJson(body))); }
    private void balance(String body) { HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.BALANCE_PATH)).willReturn(okJson(body))); }
    private String validBalance() { return "{\"rsp_code\":\"A0000\",\"balance_amt\":\"1500000\",\"api_tran_dtm\":\"20260921090000000\"}"; }
    private SourceSyncRun transactionSync() { return sync.sync(id,owner,TRANSACTIONS,from,to); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM loanops."+table,Integer.class); }

    @Test void oauthAcquisitionSavesEncryptedTokenAndStateIsSingleUse(CapturedOutput output) {
        tokenResponse();String state=state();tokens.exchange(id,owner,"synthetic-code",state);
        assertThat(tokens.valid(store.connection(id,owner)).accessToken()).isEqualTo("synthetic-new-access");
        HTTP.verify(postRequestedFor(urlEqualTo("/token")).withRequestBody(containing("grant_type=authorization_code")).withRequestBody(containing("client_secret=synthetic-secret")));
        assertThatThrownBy(()->tokens.exchange(id,owner,"synthetic-code",state)).isInstanceOf(OpenBankingException.class);
        assertThat(jdbc.queryForObject("SELECT token_ciphertext FROM loanops.open_banking_connections WHERE id=?",String.class,id)).doesNotContain("synthetic-new-access","synthetic-new-refresh");
        assertThat(count("raw_openbanking_snapshots")).isZero();
        assertThat(output.getAll()).doesNotContain("synthetic-code","synthetic-new-access","synthetic-new-refresh","synthetic-secret");
    }
    @Test void oauthRejectsWrongOwnerWrongStateAndExpiredState() {
        String state=state();
        assertThatThrownBy(()->tokens.exchange(id,"different-owner","synthetic-code",state)).isInstanceOf(OpenBankingException.class);
        assertThatThrownBy(()->tokens.exchange(id,owner,"synthetic-code","wrong-state")).isInstanceOf(OpenBankingException.class);
        jdbc.update("UPDATE loanops.open_banking_oauth_states SET expires_at=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
        assertThatThrownBy(()->tokens.exchange(id,owner,"synthetic-code",state)).isInstanceOf(OpenBankingException.class);
        HTTP.verify(0,postRequestedFor(urlEqualTo("/token")));
    }
    @Test void failedExchangeConsumesStateAndPreservesExistingToken() {
        HTTP.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(400).withBody("synthetic-secret")));
        String state=state();
        assertThatThrownBy(()->tokens.exchange(id,owner,"synthetic-code",state)).extracting("category").isEqualTo(TOKEN_EXCHANGE_FAILED);
        assertThat(tokens.valid(store.connection(id,owner)).accessToken()).isEqualTo("synthetic-access");
        assertThat(count("open_banking_oauth_states")).isZero();
    }
    @Test void invalidBearerValueIsRejectedWithoutEscapingOrLoggingTheUpstreamSecret(CapturedOutput output) {
        HTTP.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"token_type\":\"Bearer\",\"access_token\":\"synthetic-한글-secret\",\"expires_in\":3600,\"scope\":\"inquiry\"}")));
        String state=state();
        assertThatThrownBy(()->tokens.exchange(id,owner,"synthetic-code",state)).extracting("category").isEqualTo(TOKEN_EXCHANGE_FAILED);
        assertThat(tokens.valid(store.connection(id,owner)).accessToken()).isEqualTo("synthetic-access");
        assertThat(output.getAll()).doesNotContain("synthetic-한글-secret");
    }
    @Test void expiredTokenRefreshRotatesAndPersists() {
        saveToken("synthetic-expired","synthetic-refresh",clock.instant().minusSeconds(1),"inquiry");tokenResponse();balance(validBalance());
        assertThat(sync.sync(id,owner,BALANCE,null,null).complete()).isTrue();
        HTTP.verify(postRequestedFor(urlEqualTo("/token")).withRequestBody(containing("grant_type=refresh_token")));
        HTTP.verify(getRequestedFor(urlPathEqualTo(OpenBankingClient.BALANCE_PATH)).withHeader("Authorization",equalTo("Bearer synthetic-new-access")));
        assertThat(tokens.valid(store.connection(id,owner)).refreshToken()).isEqualTo("synthetic-new-refresh");
    }
    @Test void refreshMayOmitUnchangedScopeAndRefreshToken() {
        saveToken("synthetic-expired","synthetic-refresh",clock.instant().minusSeconds(1),"inquiry");
        HTTP.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"token_type\":\"Bearer\",\"access_token\":\"synthetic-new-access\",\"expires_in\":3600}")));
        Token refreshed=tokens.valid(store.connection(id,owner));
        assertThat(refreshed.scope()).isEqualTo("inquiry");assertThat(refreshed.refreshToken()).isEqualTo("synthetic-refresh");
    }
    @Test void expiredTokenWithoutRefreshIsExplicitFailure() {
        saveToken("synthetic-expired",null,clock.instant().minusSeconds(1),"inquiry");
        assertThat(sync.sync(id,owner,BALANCE,null,null).errorCategory()).isEqualTo("TOKEN_EXPIRED");
        HTTP.verify(0,postRequestedFor(urlEqualTo("/token")));
    }
    @Test void refreshFailureDoesNotReplaceTokenOrPreviousBalance(CapturedOutput output) {
        balance(validBalance());var previous=sync.sync(id,owner,BALANCE,null,null);
        saveToken("synthetic-expired","synthetic-refresh",clock.instant().minusSeconds(1),"inquiry");
        String encrypted=jdbc.queryForObject("SELECT token_ciphertext FROM loanops.open_banking_connections WHERE id=?",String.class,id);
        HTTP.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(401).withBody("synthetic-secret synthetic-refresh")));
        var failed=sync.sync(id,owner,BALANCE,null,null);
        assertThat(failed.errorCategory()).isEqualTo("TOKEN_REFRESH_FAILED");assertThat(failed.complete()).isFalse();
        assertThat(store.checkpoint(store.connection(id,owner),BALANCE).orElseThrow().runId()).isEqualTo(previous.id());
        assertThat(jdbc.queryForObject("SELECT token_ciphertext FROM loanops.open_banking_connections WHERE id=?",String.class,id)).isEqualTo(encrypted);
        assertThat(output.getAll()).doesNotContain("synthetic-secret","synthetic-refresh","synthetic-expired");
    }
    @Test void concurrentExpiredTokenRefreshHappensOnlyOnce() throws Exception {
        saveToken("synthetic-expired","synthetic-refresh",clock.instant().minusSeconds(1),"inquiry");tokenResponse();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var c=store.connection(id,owner);
            var a=pool.submit(()->tokens.valid(c));var b=pool.submit(()->tokens.valid(c));
            assertThat(a.get(5,TimeUnit.SECONDS).accessToken()).isEqualTo(b.get(5,TimeUnit.SECONDS).accessToken());
        }
        HTTP.verify(1,postRequestedFor(urlEqualTo("/token")));
    }
    @Test void successfulBalancePreservesRawHashAndEncryptedAccount() {
        balance(validBalance());var run=sync.sync(id,owner,BALANCE,null,null);
        assertThat(run.complete()).isTrue();assertThat(run.pageCount()).isEqualTo(1);assertThat(run.recordCount()).isEqualTo(1);
        assertThat(store.latestBalance(id,owner).orElseThrow().balance()).isEqualByComparingTo("1500000");
        assertThat(jdbc.queryForObject("SELECT response_hash FROM loanops.raw_openbanking_snapshots",String.class)).isEqualTo(OpenBankingJson.hash(validBalance()));
        assertThat(jdbc.queryForObject("SELECT account_ciphertext FROM loanops.open_banking_connections",String.class)).doesNotContain("000000000000000000000001");
    }
    @Test void invalidBalanceDoesNotReplacePreviousSuccess() {
        balance(validBalance());var previous=sync.sync(id,owner,BALANCE,null,null);
        balance("{\"rsp_code\":\"A0000\",\"balance_amt\":\"invalid\"}");var failed=sync.sync(id,owner,BALANCE,null,null);
        assertThat(failed.errorCategory()).isEqualTo("INVALID_RESPONSE");
        assertThat(store.checkpoint(store.connection(id,owner),BALANCE).orElseThrow().runId()).isEqualTo(previous.id());
        assertThat(count("raw_openbanking_snapshots")).isEqualTo(2);
    }
    @Test void onePageIsComplete() {
        firstPage(page(2,false,null));var run=transactionSync();
        assertThat(run.complete()).isTrue();assertThat(run.pageCount()).isEqualTo(1);assertThat(run.recordCount()).isEqualTo(2);assertThat(run.insertedCount()).isEqualTo(2);
        HTTP.verify(getRequestedFor(urlPathEqualTo(OpenBankingClient.TRANSACTIONS_PATH)).withQueryParam("from_date",equalTo("20260901")).withQueryParam("to_date",equalTo("20260921")).withQueryParam("sort_order",equalTo("A")));
    }
    @Test void exactTwentyFiveBoundaryHonorsFinalFlag() {
        firstPage(page(25,false,null));var run=transactionSync();assertThat(run.complete()).isTrue();assertThat(run.recordCount()).isEqualTo(25);
        HTTP.verify(1,getRequestedFor(urlPathEqualTo(OpenBankingClient.TRANSACTIONS_PATH)));
    }
    @Test void multiplePagesAndEmptyFinalPageHonorCursor() {
        firstPage(page(25,true,"cursor-2"));nextPage("cursor-2",page(2,true,"cursor-3"));nextPage("cursor-3",page(0,false,null));
        var run=transactionSync();assertThat(run.complete()).isTrue();assertThat(run.pageCount()).isEqualTo(3);assertThat(run.recordCount()).isEqualTo(27);
        assertThat(run.insertedCount()).isEqualTo(25);assertThat(run.duplicateCount()).isEqualTo(2);
    }
    @Test void duplicateRecordsInOnePageRemainRawButAreNotInsertedTwice() {
        var n=(ObjectNode)json.parse(page(2,false,null));((ArrayNode)n.get("res_list")).set(1,row(0));firstPage(n.toString());
        var run=transactionSync();assertThat(run.insertedCount()).isEqualTo(1);assertThat(run.duplicateCount()).isEqualTo(1);assertThat(run.recordCount()).isEqualTo(2);
    }
    @Test void overlapAndIncrementalUseCompletedRangeNotLastTransactionDate() {
        firstPage(page(2,false,null));var first=sync.sync(id,owner,TRANSACTIONS,from,LocalDate.of(2026,9,5));
        firstPage(page(0,false,null));var incremental=sync.incremental(id,owner,LocalDate.of(2026,9,8));
        assertThat(incremental.requestedFrom()).isEqualTo(LocalDate.of(2026,9,2));assertThat(incremental.complete()).isTrue();
        firstPage(page(2,false,null));var overlap=sync.sync(id,owner,TRANSACTIONS,from,LocalDate.of(2026,9,5));
        assertThat(overlap.insertedCount()).isZero();assertThat(overlap.duplicateCount()).isEqualTo(2);
        assertThat(store.checkpoint(store.connection(id,owner),TRANSACTIONS).orElseThrow().through()).isEqualTo(LocalDate.of(2026,9,8));
        assertThat(first.complete()).isTrue();
    }
    @Test void initialIncrementalAndDisjointBackfillDoNotInventCoverage() {
        firstPage(page(0,false,null));var first=sync.incremental(id,owner,to);
        assertThat(first.requestedFrom()).isEqualTo(to.minusDays(179));
        sync.sync(id,owner,TRANSACTIONS,LocalDate.of(2025,1,1),LocalDate.of(2025,1,2));
        assertThat(store.checkpoint(store.connection(id,owner),TRANSACTIONS).orElseThrow().from()).isEqualTo(to.minusDays(179));
    }
    @Test void thirdPageTimeoutIsIncompleteAndDoesNotAdvancePreviousCheckpoint() {
        firstPage(page(1,false,null));var previous=transactionSync();
        firstPage(page(2,true,"cursor-2"));nextPage("cursor-2",page(3,true,"cursor-3"));
        HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.TRANSACTIONS_PATH)).withQueryParam("befor_inquiry_trace_info",equalTo("cursor-3")).willReturn(okJson(page(0,false,null)).withFixedDelay(1500)));
        var failed=transactionSync();assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorCategory()).isEqualTo("PAGINATION_INCOMPLETE");
        assertThat(failed.failureCause()).isEqualTo("TIMEOUT");assertThat(failed.pageCount()).isEqualTo(2);assertThat(failed.recordCount()).isEqualTo(5);assertThat(failed.complete()).isFalse();
        assertThat(count("open_banking_transaction_evidence")).isEqualTo(1);
        assertThat(store.checkpoint(store.connection(id,owner),TRANSACTIONS).orElseThrow().runId()).isEqualTo(previous.id());
    }
    @Test void firstRequestTimeoutIsExplicitAndNoRawIsInvented() {
        HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.TRANSACTIONS_PATH)).willReturn(okJson(page(0,false,null)).withFixedDelay(1500)));
        var run=transactionSync();assertThat(run.errorCategory()).isEqualTo("TIMEOUT");assertThat(run.complete()).isFalse();assertThat(run.pageCount()).isZero();
        assertThat(count("raw_openbanking_snapshots")).isZero();
    }
    @Test void missingFinalFlagAndMismatchedCountAreIncomplete() {
        for(String body:List.of(page(1,false,null).replace("\"next_page_yn\":\"N\",",""),page(1,false,null).replace("\"page_record_cnt\":1","\"page_record_cnt\":2"))) {
            firstPage(body);var run=transactionSync();assertThat(run.complete()).isFalse();assertThat(run.errorCategory()).isEqualTo("PAGINATION_INCOMPLETE");
        }
        assertThat(count("open_banking_transaction_evidence")).isZero();
    }
    @Test void repeatedCursorAndPageLimitCannotClaimComplete() {
        firstPage(page(1,true,"repeat"));nextPage("repeat",page(1,true,"repeat"));assertThat(transactionSync().errorCategory()).isEqualTo("PAGINATION_INCOMPLETE");
        var limited=new OpenBankingSyncService(new LiveTestOpenBankingProvider(client,tokens,store,config,clock),store,new OpenBankingMapper(json),config(OpenBankingMode.LIVE_TEST,1),clock);
        assertThat(limited.sync(id,owner,TRANSACTIONS,from,to).errorCategory()).isEqualTo("PAGINATION_INCOMPLETE");
    }
    @Test void invalidTransactionDateAndDirectionCannotBePromoted() {
        for(String body:List.of(page(1,false,null).replace("20260901","20250101"),page(1,false,null).replace("출금","unknown"))) {
            firstPage(body);assertThat(transactionSync().complete()).isFalse();
        }
        assertThat(count("open_banking_transaction_evidence")).isZero();
    }
    @Test void partialCapabilityFailureDoesNotStopOtherCapabilities() {
        balance("{\"rsp_code\":\"A9999\"}");firstPage(page(1,false,null));
        var runs=sync.syncAll(id,owner,from,to);
        assertThat(runs).hasSize(5);assertThat(runs.get(0).errorCategory()).isEqualTo("UPSTREAM_ERROR");assertThat(runs.get(1).complete()).isTrue();
        assertThat(runs.subList(2,5)).allSatisfy(run->{assertThat(run.status()).isEqualTo("UNSUPPORTED");assertThat(run.errorCategory()).isEqualTo("SOURCE_CAPABILITY_MISSING");});
    }
    @Test void missingInquiryScopeAndMissingTokenAreExplicitStates() {
        saveToken("synthetic-access",null,clock.instant().plusSeconds(3600),"login");
        assertThat(sync.sync(id,owner,BALANCE,null,null).status()).isEqualTo("UNSUPPORTED");
        jdbc.update("UPDATE loanops.open_banking_connections SET token_ciphertext=NULL,token_expires_at=NULL WHERE id=?",id);
        assertThat(sync.sync(id,owner,BALANCE,null,null).errorCategory()).isEqualTo("TOKEN_REQUIRED");
        HTTP.verify(0,getRequestedFor(urlPathEqualTo(OpenBankingClient.BALANCE_PATH)));
    }
    @Test void consentOwnerAndModeBoundariesAreEnforced() {
        assertThatThrownBy(()->sync.sync(id,"other",BALANCE,null,null)).extracting("category").isEqualTo(NOT_FOUND);
        var demo=store.createConnection(owner,OpenBankingMode.DEMO_REPLAY,"000000000000000000000001",clock.instant().plusSeconds(3600));
        assertThatThrownBy(()->sync.sync(demo,owner,BALANCE,null,null)).extracting("category").isEqualTo(INVALID_REQUEST);
        jdbc.update("UPDATE loanops.open_banking_connections SET consent_until=? WHERE id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),id);
        assertThatThrownBy(()->sync.sync(id,owner,BALANCE,null,null)).extracting("category").isEqualTo(CONSENT_EXPIRED);
    }
    @Test void concurrentSameCapabilityCannotCreateTwoActiveRuns() {
        var c=store.connection(id,owner);store.start(c,TRANSACTIONS,from,to);
        assertThatThrownBy(()->transactionSync()).extracting("category").isEqualTo(SYNC_ALREADY_RUNNING);
    }
    @Test void deterministicReplayUsesRawMappingAndProvenanceWithoutHttp() {
        var c=store.createConnection(owner,OpenBankingMode.DEMO_REPLAY,"000000000000000000000001",clock.instant().plusSeconds(3600));
        var replay=new OpenBankingSyncService(new ReplayOpenBankingProvider(json,clock),store,new OpenBankingMapper(json),config(OpenBankingMode.DEMO_REPLAY,10),clock);
        var first=replay.sync(c,owner,TRANSACTIONS,from,to);var second=replay.sync(c,owner,TRANSACTIONS,from,to);
        assertThat(first.pageCount()).isEqualTo(2);assertThat(first.insertedCount()).isEqualTo(28);assertThat(second.duplicateCount()).isEqualTo(28);
        var hashes=jdbc.queryForList("SELECT response_hash FROM loanops.raw_openbanking_snapshots WHERE sync_run_id=? ORDER BY page_number",String.class,first.id());
        assertThat(jdbc.queryForList("SELECT response_hash FROM loanops.raw_openbanking_snapshots WHERE sync_run_id=? ORDER BY page_number",String.class,second.id())).isEqualTo(hashes);
        assertThat(replay.sync(c,owner,BALANCE,null,null).complete()).isTrue();assertThat(store.latestBalance(c,owner).orElseThrow().sourceType()).isEqualTo("DEMO_REPLAY");
        HTTP.verify(0,anyRequestedFor(anyUrl()));
    }
    @Test void rawSecretsAreRedactedEvenInsideEncryptedEvidence(CapturedOutput output) {
        balance(validBalance().replace("}",",\"access_token\":\"synthetic-access\",\"Authorization\":\"Bearer synthetic-access\",\"error_text\":\"synthetic-secret\"}"));
        var run=sync.sync(id,owner,BALANCE,null,null);assertThat(run.complete()).isTrue();
        jdbc.query("SELECT id,payload_ciphertext,redacted FROM loanops.raw_openbanking_snapshots",rs->{
            String plaintext=box.open("raw:"+rs.getObject("id",UUID.class),rs.getString("payload_ciphertext"));
            assertThat(plaintext).doesNotContain("synthetic-access","synthetic-secret");assertThat(rs.getBoolean("redacted")).isTrue();
        });
        assertThat(output.getAll()).doesNotContain("synthetic-access","synthetic-secret");
    }
    @Test void reflectedSecretCannotEnterMappedTransactionOrRawFieldNames() {
        String body=page(1,false,null).replace("SYNTHETIC 0","synthetic-access");
        var node=(ObjectNode)json.parse(body);node.put("synthetic-secret","reflected-key");firstPage(node.toString());
        assertThat(transactionSync().complete()).isTrue();
        jdbc.query("SELECT identity_hash,mapped_ciphertext FROM loanops.open_banking_transaction_evidence",rs->{
            assertThat(box.open("transaction:"+id+":"+rs.getString("identity_hash"),rs.getString("mapped_ciphertext"))).doesNotContain("synthetic-access");
        });
        jdbc.query("SELECT id,payload_ciphertext FROM loanops.raw_openbanking_snapshots",rs->{
            assertThat(box.open("raw:"+rs.getObject("id",UUID.class),rs.getString("payload_ciphertext"))).doesNotContain("synthetic-access","synthetic-secret");
        });
    }
    @Test void databaseFailureRollsBackAllNewEvidenceAndPreservesSuccessfulCheckpoint() {
        firstPage(page(1,false,null));var previous=transactionSync();
        var node=(ObjectNode)json.parse(page(3,false,null));((ObjectNode)node.get("res_list").get(2)).put("tran_date","20260902");firstPage(node.toString());
        jdbc.execute("ALTER TABLE loanops.open_banking_transaction_evidence ADD CONSTRAINT synthetic_test_failure CHECK(transaction_date <= DATE '2026-09-01')");
        try {
            var failed=transactionSync();assertThat(failed.errorCategory()).isEqualTo("STORAGE_ERROR");assertThat(failed.complete()).isFalse();
            assertThat(count("open_banking_transaction_evidence")).isEqualTo(1);
            assertThat(store.checkpoint(id,owner,TRANSACTIONS).orElseThrow().runId()).isEqualTo(previous.id());
            assertThat(store.snapshots(failed.id(),owner)).hasSize(1);
        } finally { jdbc.execute("ALTER TABLE loanops.open_banking_transaction_evidence DROP CONSTRAINT synthetic_test_failure"); }
    }
    @Test void httpRedirectIsNotFollowedWithBearerCredentials() {
        HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.BALANCE_PATH)).willReturn(aResponse().withStatus(302).withHeader("Location",HTTP.baseUrl()+"/redirect-target")));
        assertThat(sync.sync(id,owner,BALANCE,null,null).errorCategory()).isEqualTo("HTTP_ERROR");
        HTTP.verify(0,getRequestedFor(urlPathEqualTo("/redirect-target")));
    }
    @Test void boundedResponseFailureDoesNotPromoteBalance() {
        HTTP.stubFor(get(urlPathEqualTo(OpenBankingClient.BALANCE_PATH)).willReturn(ok("x".repeat(1024*1024+1))));
        assertThat(sync.sync(id,owner,BALANCE,null,null).complete()).isFalse();assertThat(store.latestBalance(id,owner)).isEmpty();
    }
}
