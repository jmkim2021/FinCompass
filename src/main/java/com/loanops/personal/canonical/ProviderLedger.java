package com.loanops.personal.canonical;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static com.loanops.personal.canonical.PersonalTypes.*;
import static com.loanops.personal.canonical.PersonalFinanceException.Code.*;
import com.loanops.personal.gateway.PersonalFinanceProvider.*;

/** Provider-neutral metadata. All mutation methods run in the caller's canonical transaction. */
public final class ProviderLedger {
    private final JdbcTemplate jdbc;
    public ProviderLedger(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public UUID user(String owner,Instant now) {
        if(owner==null||!owner.matches("[A-Za-z0-9_-]{1,100}")) throw new PersonalFinanceException(INVALID_REQUEST);
        jdbc.update("INSERT INTO loanops.financial_users VALUES(?,?,?) ON CONFLICT(owner_ref) DO NOTHING",UUID.randomUUID(),owner,Timestamp.from(now));
        return jdbc.queryForObject("SELECT id FROM loanops.financial_users WHERE owner_ref=?",UUID.class,owner);
    }
    public void register(UUID id,UUID user,String owner,ProviderCode provider,SourceMode mode,Instant until,Instant now) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM loanops.financial_users WHERE id=? AND owner_ref=?)",Boolean.class,user,owner))) throw new PersonalFinanceException(NOT_FOUND);
        jdbc.update("INSERT INTO loanops.provider_connections VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",id,user,provider.name(),mode.name(),Timestamp.from(until),Timestamp.from(now),provider==ProviderCode.OPEN_BANKING?id:null);
        connection(id,owner,provider);
    }
    public SourceConnection connection(UUID id,String owner,ProviderCode provider) {
        var rows=jdbc.query("SELECT c.* FROM loanops.provider_connections c JOIN loanops.financial_users u ON u.id=c.financial_user_id WHERE c.id=? AND u.owner_ref=? AND c.provider=?",
            (r,n)->new SourceConnection(r.getObject("id",UUID.class),SourceMode.valueOf(r.getString("mode")),r.getTimestamp("consent_until").toInstant()),id,owner,provider.name());
        if(rows.isEmpty()) throw new PersonalFinanceException(NOT_FOUND);return rows.getFirst();
    }
    public void lock(UUID id,String owner) {
        var rows=jdbc.queryForList("SELECT c.id FROM loanops.provider_connections c JOIN loanops.financial_users u ON u.id=c.financial_user_id WHERE c.id=? AND u.owner_ref=? FOR UPDATE OF c",UUID.class,id,owner);
        if(rows.isEmpty()) throw new PersonalFinanceException(NOT_FOUND);
    }
    public void importOpenBanking(SourceBatch s) {
        if(s.sourceType()!=SourceType.OPEN_BANKING_TEST) return;
        jdbc.update("INSERT INTO loanops.provider_sync_runs SELECT id,connection_id,capability,status,complete,started_at,completed_at,id FROM loanops.source_sync_runs WHERE id=? AND status<>'RUNNING' ON CONFLICT(id) DO NOTHING",s.runId());
        jdbc.update("""
            INSERT INTO loanops.provider_evidence
            SELECT r.id,r.sync_run_id,NULL,NULL,r.received_at,r.received_at,r.response_hash,'OPEN_BANKING_TEST',c.mode,r.endpoint,NULL,r.id,NULL
            FROM loanops.raw_openbanking_snapshots r JOIN loanops.source_sync_runs s ON s.id=r.sync_run_id
            JOIN loanops.open_banking_connections c ON c.id=s.connection_id WHERE s.id=? ON CONFLICT(id) DO NOTHING""",s.runId());
    }
}
