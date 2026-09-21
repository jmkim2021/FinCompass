package com.loanops.personal.canonical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.dao.DataAccessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import com.loanops.personal.gateway.PersonalFinanceProvider.*;
import com.loanops.personal.quality.PersonalQualityRules.Finding;
import static com.loanops.personal.canonical.PersonalFinanceModels.*;
import static com.loanops.personal.canonical.PersonalTypes.*;
import static com.loanops.personal.canonical.PersonalFinanceException.Code.*;

/** All writes are executed inside one service transaction. No product table is read or written. */
public final class PersonalFinanceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PersonalFinanceCipher cipher;
    final ProviderLedger ledger;
    public PersonalFinanceStore(JdbcTemplate jdbc,PlatformTransactionManager manager,PersonalFinanceCipher cipher) {
        this.jdbc=jdbc;this.cipher=cipher;this.ledger=new ProviderLedger(jdbc);tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    <T> T atomic(Supplier<T> action) {
        try { return tx.execute(status->action.get()); }
        catch(DataAccessException | org.springframework.transaction.TransactionException ex) { throw new PersonalFinanceException(STORAGE_ERROR); }
    }
    void lockConnection(UUID connection,String owner) {
        ledger.lock(connection,owner);
    }
    ConsentGrant grant(String owner,ProviderCode provider,SourceConnection c,Set<Capability> scopes,Instant until,Instant now) {
        UUID user=UUID.randomUUID();
        jdbc.update("INSERT INTO loanops.financial_users VALUES(?,?,?) ON CONFLICT(owner_ref) DO NOTHING",user,owner,ts(now));
        user=jdbc.queryForObject("SELECT id FROM loanops.financial_users WHERE owner_ref=?",UUID.class,owner);
        var existing=jdbc.query("SELECT * FROM loanops.consent_grants WHERE provider_connection_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY granted_at DESC,id",this::consent,c.id(),ts(now));
        if(!existing.isEmpty()) {
            var old=existing.getFirst();
            if(!old.scopes().equals(scopes)||!old.expiresAt().equals(until)) throw new PersonalFinanceException(INVALID_REQUEST);
            return old;
        }
        UUID id=UUID.randomUUID();
        ConsentKind kind=c.mode()==SourceMode.DEMO_REPLAY?ConsentKind.DEMO:ConsentKind.DEVELOPER_TEST;
        String sqlScopes="{"+String.join(",",scopes.stream().map(Enum::name).sorted().toList())+"}";
        jdbc.update("""
                INSERT INTO loanops.consent_grants(id,financial_user_id,source_provider,source_connection_id,source_mode,consent_kind,scopes,granted_at,expires_at,provider_connection_id)
                VALUES(?,?,?,?,?,?,?::text[],?,?,?)""",id,user,provider.name(),provider==ProviderCode.OPEN_BANKING?c.id():null,c.mode().name(),kind.name(),sqlScopes,ts(now),ts(until),c.id());
        return consent(id,owner);
    }
    ConsentGrant consent(UUID id,String owner) {
        var rows=jdbc.query("SELECT c.* FROM loanops.consent_grants c JOIN loanops.financial_users u ON u.id=c.financial_user_id WHERE c.id=? AND u.owner_ref=?",this::consent,id,owner);
        if(rows.isEmpty()) throw new PersonalFinanceException(NOT_FOUND);return rows.getFirst();
    }
    private ConsentGrant consent(ResultSet r,int row) throws SQLException {
        Set<Capability> scopes=new HashSet<>();for(String value:(String[])r.getArray("scopes").getArray()) scopes.add(Capability.valueOf(value));
        return new ConsentGrant(uuid(r,"id"),uuid(r,"financial_user_id"),ProviderCode.valueOf(r.getString("source_provider")),uuid(r,"provider_connection_id"),
                SourceMode.valueOf(r.getString("source_mode")),ConsentKind.valueOf(r.getString("consent_kind")),scopes,time(r,"granted_at"),time(r,"expires_at"),time(r,"revoked_at"));
    }
    String owner(UUID user) { return jdbc.queryForObject("SELECT owner_ref FROM loanops.financial_users WHERE id=?",String.class,user); }
    void revoke(UUID id,Instant now) { jdbc.update("UPDATE loanops.consent_grants SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",ts(now),id); }
    Optional<FinancialAccount> account(UUID connection) {
        return jdbc.query("SELECT id,payload_ciphertext FROM loanops.financial_accounts WHERE source_connection_id=?",(r,n)->cipher.open("account:"+uuid(r,"id"),r.getString(2),FinancialAccount.class),connection).stream().findFirst();
    }
    FinancialAccount createAccount(ConsentGrant consent,AccountObservation source,UUID provenance) {
        UUID id=UUID.randomUUID();
        FinancialAccount account=new FinancialAccount(id,consent.userId(),source.sourceAccountKey(),source.institution(),source.accountType(),source.currency(),provenance);
        jdbc.update("INSERT INTO loanops.financial_accounts VALUES(?,?,?,?,?,?,?)",id,consent.userId(),consent.provider().name(),consent.connectionId(),source.sourceAccountKey(),provenance,cipher.seal("account:"+id,account));
        return account;
    }
    Optional<ValidationResult> existing(UUID run,UUID consent,String version,long seconds) {
        return jdbc.queryForList("SELECT id FROM loanops.personal_validation_runs WHERE provider_sync_run_id=? AND consent_grant_id=? AND policy_version=? AND balance_stale_after_seconds=?",UUID.class,run,consent,version,seconds)
                .stream().findFirst().map(this::validation);
    }
    void validation(UUID id,ConsentGrant grant,SourceBatch source,UUID account,ValidationState state,boolean promoted,
            LocalDate from,LocalDate through,Instant asOf,Instant now,String hash,String version,long staleSeconds) {
        jdbc.update("""
                INSERT INTO loanops.personal_validation_runs(id,consent_grant_id,source_sync_run_id,financial_account_id,capability,state,promoted,
                requested_from,requested_through,covered_from,covered_through,source_started_at,as_of,evaluated_at,policy_version,balance_stale_after_seconds,content_hash,provider_sync_run_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",id,grant.id(),source.sourceType()==SourceType.OPEN_BANKING_TEST?source.runId():null,account,source.capability().name(),state.name(),promoted,
                source.from(),source.through(),from,through,ts(source.startedAt()),ts(asOf),ts(now),version,staleSeconds,hash,source.runId());
    }
    UUID evidence(UUID id,UUID validation,SourceBatch source,Evidence evidence,String kind,Instant now) {
        boolean legacy=source.sourceType()==SourceType.OPEN_BANKING_TEST;
        jdbc.update("INSERT INTO loanops.personal_source_evidence(id,validation_run_id,source_sync_run_id,raw_snapshot_id,source_type,source_mode,entity_kind,as_of,collected_at,normalized_at,raw_record_index,provider_evidence_id,provider_sync_run_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,validation,legacy?source.runId():null,legacy?evidence.rawId():null,source.sourceType().name(),source.sourceMode().name(),kind,ts(evidence.asOf()),ts(evidence.collectedAt()),ts(now),evidence.rowIndex(),evidence.rawId(),source.runId());return id;
    }
    void balance(FinancialAccount account,BalanceObservation observation,UUID evidence) {
        UUID id=UUID.randomUUID();var b=new BalanceSnapshot(id,account.id(),observation.balance(),observation.available(),observation.currency(),observation.evidence().asOf(),evidence);
        jdbc.update("INSERT INTO loanops.balance_snapshots VALUES(?,?,?,?,?)",id,account.id(),evidence,ts(b.asOf()),cipher.seal("balance:"+id,b));
    }
    UUID transaction(FinancialAccount account,TransactionObservation t,UUID evidence) {
        var existing=jdbc.queryForList("SELECT id FROM loanops.financial_transactions WHERE financial_account_id=? AND source_transaction_key=?",UUID.class,account.id(),t.sourceKey());
        if(!existing.isEmpty()) return existing.getFirst();
        UUID id=UUID.randomUUID();var value=new FinancialTransaction(id,account.id(),t.occurredAt(),t.postedAt(),t.amount(),t.direction(),t.description(),t.merchant(),t.category(),t.balanceAfter(),t.currency(),t.sourceKey(),evidence);
        jdbc.update("INSERT INTO loanops.financial_transactions VALUES(?,?,?,?,?,?)",id,account.id(),t.sourceKey(),evidence,ts(t.occurredAt()),cipher.seal("transaction:"+id,value));return id;
    }
    void member(UUID validation,UUID transaction,UUID evidence) {
        jdbc.update("INSERT INTO loanops.personal_validation_transactions VALUES(?,?,?) ON CONFLICT(validation_run_id,transaction_id) DO UPDATE SET observation_evidence_id=EXCLUDED.observation_evidence_id",validation,transaction,evidence);
    }
    void inherit(UUID previous,UUID next) {
        jdbc.update("INSERT INTO loanops.personal_validation_transactions SELECT ?,transaction_id,observation_evidence_id FROM loanops.personal_validation_transactions WHERE validation_run_id=?",next,previous);
    }
    void promote(UUID account,Capability capability,UUID validation) {
        jdbc.update("INSERT INTO loanops.personal_current_states VALUES(?,?,?) ON CONFLICT(financial_account_id,capability) DO UPDATE SET validation_run_id=EXCLUDED.validation_run_id",account,capability.name(),validation);
    }
    Optional<ValidationResult> current(UUID account,Capability capability) {
        return jdbc.queryForList("SELECT validation_run_id FROM loanops.personal_current_states WHERE financial_account_id=? AND capability=?",UUID.class,account,capability.name()).stream().findFirst().map(this::validation);
    }
    Optional<ValidationResult> latest(UUID consent,Capability capability) {
        return jdbc.queryForList("SELECT id FROM loanops.personal_validation_runs WHERE consent_grant_id=? AND capability=? ORDER BY validation_sequence DESC LIMIT 1",UUID.class,consent,capability.name()).stream().findFirst().map(this::validation);
    }
    boolean sameConsent(UUID validation,UUID consent) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT consent_grant_id=? FROM loanops.personal_validation_runs WHERE id=?",Boolean.class,consent,validation)); }
    Instant started(UUID validation) { return jdbc.queryForObject("SELECT source_started_at FROM loanops.personal_validation_runs WHERE id=?",Timestamp.class,validation).toInstant(); }
    void findings(UUID validation,List<Finding> findings,Instant now) {
        for(var f:findings) jdbc.update("INSERT INTO loanops.personal_quality_issues VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),validation,f.rule().name(),f.severity().name(),ts(now),f.affectedEntity(),f.details(),f.resolution().name());
    }
    ValidationResult validation(UUID id) {
        var issues=jdbc.query("""
                SELECT q.*,v.provider_sync_run_id FROM loanops.personal_quality_issues q JOIN loanops.personal_validation_runs v ON v.id=q.validation_run_id
                WHERE q.validation_run_id=? ORDER BY q.rule_code,q.affected_entity""",(r,n)->new QualityIssue(uuid(r,"id"),id,RuleCode.valueOf(r.getString("rule_code")),Severity.valueOf(r.getString("severity")),time(r,"detected_at"),
                uuid(r,"provider_sync_run_id"),r.getString("affected_entity"),r.getString("details"),Resolution.valueOf(r.getString("resolution_state"))),id);
        return jdbc.queryForObject("SELECT * FROM loanops.personal_validation_runs WHERE id=?",(r,n)->new ValidationResult(id,uuid(r,"provider_sync_run_id"),uuid(r,"financial_account_id"),Capability.valueOf(r.getString("capability")),ValidationState.valueOf(r.getString("state")),
                r.getBoolean("promoted"),r.getObject("covered_from",LocalDate.class),r.getObject("covered_through",LocalDate.class),time(r,"as_of"),time(r,"evaluated_at"),r.getString("content_hash"),r.getString("policy_version"),issues),id);
    }
    Optional<BalanceSnapshot> balance(UUID validation) {
        return jdbc.query("SELECT b.id,b.payload_ciphertext FROM loanops.balance_snapshots b JOIN loanops.personal_source_evidence p ON p.id=b.provenance_id WHERE p.validation_run_id=?",
                (r,n)->cipher.open("balance:"+uuid(r,"id"),r.getString(2),BalanceSnapshot.class),validation).stream().findFirst();
    }
    List<FinancialTransaction> transactions(UUID validation,int offset,int limit) {
        return jdbc.query("""
                SELECT t.id,t.payload_ciphertext FROM loanops.financial_transactions t JOIN loanops.personal_validation_transactions m ON m.transaction_id=t.id
                WHERE m.validation_run_id=? ORDER BY t.occurred_at,t.source_transaction_key LIMIT ? OFFSET ?""",
                (r,n)->cipher.open("transaction:"+uuid(r,"id"),r.getString(2),FinancialTransaction.class),validation,limit,offset);
    }
    Provenance provenance(UUID id,ConsentGrant grant) {
        var rows=jdbc.query("""
                SELECT p.*,r.endpoint,r.response_hash,v.capability,v.consent_grant_id FROM loanops.personal_source_evidence p
                JOIN loanops.provider_evidence r ON r.id=p.provider_evidence_id
                JOIN loanops.personal_validation_runs v ON v.id=p.validation_run_id
                JOIN loanops.consent_grants c ON c.id=v.consent_grant_id
                WHERE p.id=? AND c.financial_user_id=? AND c.provider_connection_id=?""",(r,n)->{
            if(!grant.scopes().contains(Capability.valueOf(r.getString("capability")))) throw new PersonalFinanceException(CONSENT_DENIED);
            return new Provenance(id,uuid(r,"validation_run_id"),uuid(r,"provider_sync_run_id"),uuid(r,"raw_snapshot_id"),SourceType.valueOf(r.getString("source_type")),SourceMode.valueOf(r.getString("source_mode")),time(r,"as_of"),time(r,"collected_at"),time(r,"normalized_at"),r.getString("endpoint"),r.getString("response_hash"),(Integer)r.getObject("raw_record_index"),uuid(r,"provider_evidence_id"),uuid(r,"consent_grant_id"));
        },id,grant.userId(),grant.connectionId());
        if(rows.isEmpty()) throw new PersonalFinanceException(NOT_FOUND);return rows.getFirst();
    }
    boolean hasIncome(ConsentGrant grant,Instant now,com.loanops.personal.quality.IncomeQualityProperties policy) {
        var ids=jdbc.queryForList("""
            SELECT v.id FROM loanops.provider_current_evidence current
            JOIN loanops.personal_validation_runs v ON v.id=current.validation_run_id
            JOIN loanops.consent_grants c ON c.id=v.consent_grant_id
            JOIN loanops.provider_connections pc ON pc.id=current.provider_connection_id
            WHERE c.financial_user_id=? AND current.capability='INCOME' AND c.scopes @> ARRAY['INCOME']::text[]
            AND c.revoked_at IS NULL AND c.expires_at>? AND pc.consent_until>? AND v.state='VALIDATED_COMPLETE'""",UUID.class,grant.userId(),ts(now),ts(now));
        var quality=new com.loanops.personal.quality.IncomeQualityRules(policy);
        return ids.stream().flatMap(id->income(id).stream()).anyMatch(i->i.verifiedAnnualIncome()!=null&&i.incomeYear()!=null&&!quality.stale(i.asOf(),now)&&!quality.outdated(i.incomeYear(),now));
    }
    UUID collectMock(ConsentGrant grant,Capability capability,String body,Instant now) {
        String hash=cipher.contentHash(body);
        var duplicate=jdbc.queryForList("SELECT e.provider_sync_run_id FROM loanops.provider_evidence e JOIN loanops.provider_sync_runs s ON s.id=e.provider_sync_run_id WHERE s.connection_id=? AND s.capability=? AND e.consent_grant_id=? AND e.response_hash=?",UUID.class,grant.connectionId(),capability.name(),grant.id(),hash);
        if(!duplicate.isEmpty()) return duplicate.getFirst();
        UUID run=UUID.randomUUID(),raw=UUID.randomUUID();String fixture=null,version=null;Instant observed=null;
        try { var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);fixture=tree.path("fixtureId").isMissingNode()?null:tree.get("fixtureId").asText();version=tree.path("contractVersion").isMissingNode()?null:tree.get("contractVersion").asText();
            if(tree.path("evidence").isArray()) for(var e:tree.get("evidence")) if(e.hasNonNull("asOf")) {var time=Instant.parse(e.get("asOf").asText());if(observed==null||time.isBefore(observed)) observed=time;} }
        catch(Exception ignored) { throw new PersonalFinanceException(INVALID_SOURCE_DATA); }
        jdbc.update("INSERT INTO loanops.provider_sync_runs VALUES(?,?,?,'SUCCESS',true,?,?,NULL)",run,grant.connectionId(),capability.name(),ts(now),ts(now));
        jdbc.update("INSERT INTO loanops.provider_evidence VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL,?)",raw,run,fixture,version,ts(observed==null?now:observed),ts(now),hash,"PUBLIC_MYDATA_MOCK","DEMO_REPLAY","mock:public-mydata/minimal-v1",cipher.seal("provider:"+raw,body),grant.id());
        return run;
    }
    void auditDeniedRun(String owner,UUID run,UUID consent,Instant now) {
        var rows=jdbc.queryForList("SELECT s.connection_id,s.capability FROM loanops.provider_sync_runs s JOIN loanops.provider_connections c ON c.id=s.connection_id JOIN loanops.financial_users u ON u.id=c.financial_user_id WHERE s.id=? AND u.owner_ref=?",run,owner);
        if(!rows.isEmpty()) {var row=rows.getFirst();accessIssue(owner,(UUID)row.get("connection_id"),consent,Capability.valueOf((String)row.get("capability")),now);}
    }
    void accessIssue(String owner,UUID connection,UUID consent,Capability capability,Instant now) {
        // Only owned connections may acquire a denial audit. Unknown owners learn no metadata.
        ledger.lock(connection,owner);
        RuleCode code=RuleCode.CONSENT_MISSING;
        if(consent!=null) try {
            var grant=consent(consent,owner);
            if(!grant.connectionId().equals(connection)||!grant.scopes().contains(capability)) code=RuleCode.CONSENT_SCOPE_DENIED;
            else if(grant.statusAt(now)==ConsentStatus.REVOKED) code=RuleCode.CONSENT_REVOKED;
            else if(grant.statusAt(now)==ConsentStatus.EXPIRED) code=RuleCode.CONSENT_EXPIRED;
        } catch(PersonalFinanceException ignored) { }
        jdbc.update("INSERT INTO loanops.provider_access_issues VALUES(?,?,?,?,?)",UUID.randomUUID(),connection,capability.name(),code.name(),ts(now));
    }
    Optional<ValidationResult> incomeCurrent(UUID connection,Capability capability) {
        return jdbc.queryForList("SELECT validation_run_id FROM loanops.provider_current_evidence WHERE provider_connection_id=? AND capability=?",UUID.class,connection,capability.name()).stream().findFirst().map(this::validation);
    }
    void incomePolicy(UUID validation,long seconds,int years) {
        jdbc.update("UPDATE loanops.personal_validation_runs SET income_stale_after_seconds=?,max_income_year_age=? WHERE id=?",seconds,years,validation);
    }
    void income(UUID validation,ConsentGrant grant,SourceBatch source,com.loanops.personal.gateway.PersonalFinanceProvider.IncomeObservation observation,Instant now,boolean verified) {
        UUID provenance=evidence(UUID.randomUUID(),validation,source,observation.evidence(),"INCOME",now),id=UUID.randomUUID();
        var value=new IncomeEvidence(id,grant.userId(),observation.annualIncome(),observation.incomeYear(),observation.employmentStatus(),observation.evidenceType(),observation.evidence().asOf(),provenance,
            observation.currency(),source.sourceType(),source.sourceMode(),observation.evidence().rawId(),grant.id(),observation.evidence().collectedAt(),now);
        jdbc.update("INSERT INTO loanops.income_evidence VALUES(?,?,?, ?,?)",id,grant.userId(),provenance,verified,cipher.seal("income:"+id,value));
    }
    List<IncomeEvidence> income(UUID validation) {
        return jdbc.query("SELECT i.id,i.payload_ciphertext FROM loanops.income_evidence i JOIN loanops.personal_source_evidence p ON p.id=i.provenance_id WHERE p.validation_run_id=? ORDER BY p.raw_record_index,i.id",
            (r,n)->cipher.open("income:"+uuid(r,"id"),r.getString(2),IncomeEvidence.class),validation);
    }
    void promoteIncome(UUID connection,Capability capability,UUID validation) {
        jdbc.update("INSERT INTO loanops.provider_current_evidence VALUES(?,?,?) ON CONFLICT(provider_connection_id,capability) DO UPDATE SET validation_run_id=EXCLUDED.validation_run_id",connection,capability.name(),validation);
    }
    private static UUID uuid(ResultSet r,String column)throws SQLException{return r.getObject(column,UUID.class);}
    private static Instant time(ResultSet r,String column)throws SQLException{Timestamp t=r.getTimestamp(column);return t==null?null:t.toInstant();}
    private static Timestamp ts(Instant time){return time==null?null:Timestamp.from(time);}
}
