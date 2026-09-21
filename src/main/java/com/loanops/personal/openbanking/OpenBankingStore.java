package com.loanops.personal.openbanking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;

/** Fixed SQL follows existing operational JDBC usage. All lifecycle writes commit independently. */
public final class OpenBankingStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecretBox box;
    private final OpenBankingJson json;
    private final Clock clock;
    OpenBankingStore(JdbcTemplate jdbc, PlatformTransactionManager manager, SecretBox box, OpenBankingJson json, Clock clock) {
        this.jdbc = jdbc; this.box = box; this.json = json; this.clock = clock;
        tx = new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public UUID createConnection(String owner, OpenBankingMode mode, String account, Instant consentUntil) {
        if (owner == null || !owner.matches("[A-Za-z0-9_-]{1,100}") || account == null || !account.matches("[0-9]{24}")
                || consentUntil == null || !consentUntil.isAfter(clock.instant())) throw new OpenBankingException(INVALID_REQUEST);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO loanops.open_banking_connections(id,owner_ref,mode,account_ciphertext,consent_until,created_at)
                VALUES (?,?,?,?,?,?)""", id,owner,mode.name(),box.seal("account:"+id, account),ts(consentUntil),ts(clock.instant())));
        return id;
    }
    public Connection connection(UUID id, String owner) {
        var rows = jdbc.query("SELECT * FROM loanops.open_banking_connections WHERE id=? AND owner_ref=?",
                (rs, n) -> new Connection(rs.getObject("id", UUID.class),rs.getString("owner_ref"),
                        OpenBankingMode.valueOf(rs.getString("mode")),rs.getString("account_ciphertext"),instant(rs,"consent_until")),id,owner);
        if (rows.isEmpty()) throw new OpenBankingException(NOT_FOUND);
        Connection c = rows.getFirst();
        if (!c.consentUntil().isAfter(clock.instant())) throw new OpenBankingException(CONSENT_EXPIRED);
        return c;
    }
    String account(Connection connection) { return box.open("account:"+connection.id(), connection.accountCiphertext()); }
    void saveState(Connection connection, String hash, Instant expiresAt) {
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM loanops.open_banking_oauth_states WHERE connection_id=? OR expires_at<=?",connection.id(),ts(clock.instant()));
            jdbc.update("INSERT INTO loanops.open_banking_oauth_states VALUES(?,?,?)",hash,connection.id(),ts(expiresAt));
        });
    }
    boolean consumeState(Connection connection, String hash) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.update("DELETE FROM loanops.open_banking_oauth_states WHERE state_hash=? AND connection_id=? AND expires_at>?",
                hash,connection.id(),ts(clock.instant())) == 1));
    }
    // A row lock serializes refreshes across processes, preventing refresh-token rotation races.
    Token updateToken(Connection connection, Function<Token,Token> update) {
        return tx.execute(s -> {
            List<String> rows = jdbc.query("SELECT token_ciphertext FROM loanops.open_banking_connections WHERE id=? FOR UPDATE",
                    (rs,n) -> rs.getString(1),connection.id());
            Token current = rows.getFirst() == null ? null : decodeToken(connection.id(), rows.getFirst());
            Token result = update.apply(current);
            if (result != current) {
                Map<String,Object> data = new LinkedHashMap<>();
                data.put("access",result.accessToken()); data.put("refresh",result.refreshToken());
                data.put("expires",result.expiresAt().toString()); data.put("scope",result.scope());
                jdbc.update("UPDATE loanops.open_banking_connections SET token_ciphertext=?,token_expires_at=? WHERE id=?",
                        box.seal("token:"+connection.id(),json.write(data)),ts(result.expiresAt()),connection.id());
            }
            return result;
        });
    }
    private Token decodeToken(UUID id, String ciphertext) {
        var n = json.parse(box.open("token:"+id,ciphertext));
        return new Token(n.get("access").asText(),n.get("refresh").isNull()?null:n.get("refresh").asText(),
                Instant.parse(n.get("expires").asText()),n.get("scope").asText());
    }
    UUID start(Connection connection, OpenBankingCapability capability, LocalDate from, LocalDate to) {
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> jdbc.update("""
                    INSERT INTO loanops.source_sync_runs(id,connection_id,source,mode,capability,status,requested_from,requested_to,started_at)
                    VALUES(?,?,'OPEN_BANKING',?,?,'RUNNING',?,?,?)""",id,connection.id(),connection.mode().name(),capability.name(),from,to,ts(clock.instant())));
        } catch (DuplicateKeyException ex) { throw new OpenBankingException(SYNC_ALREADY_RUNNING); }
        return id;
    }
    RawResponse sanitized(RawResponse response, Collection<String> secrets) {
        return new RawResponse(response.endpoint(), response.requestedAt(), response.receivedAt(), response.httpStatus(),
                json.sanitize(response.body(), secrets).body());
    }
    UUID raw(UUID run, int page, RawResponse response, Collection<String> secrets) {
        UUID id = UUID.randomUUID();
        var sanitized = json.sanitize(response.body(),secrets);
        String result = null;
        try { String v = json.parse(sanitized.body()).path("rsp_code").asText(""); if (v.matches("[A-Za-z0-9_-]{1,32}")) result = v; }
        catch (OpenBankingException ignored) { /* malformed bodies are retained as a safe marker */ }
        final String resultStatus = result;
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO loanops.raw_openbanking_snapshots
                    (id,sync_run_id,source,endpoint,requested_at,received_at,http_status,result_status,response_hash,payload_ciphertext,api_version,redacted,page_number)
                    VALUES(?,?,'OPEN_BANKING',?,?,?,?,?,?,?,?,?,?)""",id,run,response.endpoint(),ts(response.requestedAt()),ts(response.receivedAt()),
                    response.httpStatus(),resultStatus,OpenBankingJson.hash(response.body()),box.seal("raw:"+id,sanitized.body()),"KFTC-v2.0",sanitized.redacted(),page);
            jdbc.update("UPDATE loanops.source_sync_runs SET page_count=page_count+1 WHERE id=? AND status='RUNNING'",run);
        });
        return id;
    }
    void records(UUID run, int records) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE loanops.source_sync_runs SET record_count=record_count+? WHERE id=? AND status='RUNNING'",records,run));
    }
    void fail(UUID run, OpenBankingException.Category error, OpenBankingException.Category cause, boolean unsupported) {
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE loanops.source_sync_runs SET status=?,completed_at=?,complete=false,error_category=?,failure_cause=?
                WHERE id=? AND status='RUNNING'""",unsupported?"UNSUPPORTED":"FAILED",ts(clock.instant()),error.name(),cause == null?null:cause.name(),run));
    }
    Optional<Checkpoint> checkpoint(Connection connection, OpenBankingCapability capability) {
        return jdbc.query("SELECT * FROM loanops.open_banking_sync_checkpoints WHERE connection_id=? AND capability=?",(rs,n) ->
                new Checkpoint(rs.getObject("covered_from",LocalDate.class),rs.getObject("covered_through",LocalDate.class),
                        rs.getObject("successful_run_id",UUID.class),instant(rs,"synced_at")),connection.id(),capability.name()).stream().findFirst();
    }
    void successBalance(Connection connection, UUID run, Balance balance) {
        tx.executeWithoutResult(s -> {
            checkpointWrite(connection,OpenBankingCapability.BALANCE,run,null,null,
                    box.seal("balance:"+connection.id(),json.write(balance)));
            finish(run,0,0);
        });
    }
    void successTransactions(Connection connection, UUID run, LocalDate from, LocalDate to, List<TransactionEvidence> evidence) {
        tx.executeWithoutResult(s -> {
            int inserted = 0;
            for (var e : evidence) inserted += jdbc.update("""
                    INSERT INTO loanops.open_banking_transaction_evidence(connection_id,identity_hash,identity_rule_version,first_raw_snapshot_id,transaction_date,mapped_ciphertext)
                    VALUES(?,?,'ob-composite-v1',?,?,?) ON CONFLICT(connection_id,identity_hash) DO NOTHING""",
                    connection.id(),e.identity(),e.rawSnapshotId(),e.transaction().date(),box.seal("transaction:"+connection.id()+":"+e.identity(),json.write(e.transaction())));
            Optional<Checkpoint> old = checkpoint(connection,OpenBankingCapability.TRANSACTIONS);
            if (old.isEmpty()) checkpointWrite(connection,OpenBankingCapability.TRANSACTIONS,run,from,to,null);
            else {
                var previous = old.get();
                // Only merge connected intervals. A disjoint backfill cannot claim continuous coverage.
                if (!from.isAfter(previous.through().plusDays(1)) && !to.isBefore(previous.from().minusDays(1)))
                    checkpointWrite(connection,OpenBankingCapability.TRANSACTIONS,run,
                            from.isBefore(previous.from())?from:previous.from(),to.isAfter(previous.through())?to:previous.through(),null);
            }
            finish(run,inserted,evidence.size()-inserted);
        });
    }
    private void checkpointWrite(Connection c, OpenBankingCapability capability, UUID run, LocalDate from, LocalDate to, String payload) {
        jdbc.update("""
                INSERT INTO loanops.open_banking_sync_checkpoints(connection_id,capability,successful_run_id,covered_from,covered_through,synced_at,mapped_ciphertext)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(connection_id,capability) DO UPDATE SET
                successful_run_id=EXCLUDED.successful_run_id,covered_from=EXCLUDED.covered_from,covered_through=EXCLUDED.covered_through,
                synced_at=EXCLUDED.synced_at,mapped_ciphertext=EXCLUDED.mapped_ciphertext""",c.id(),capability.name(),run,from,to,ts(clock.instant()),payload);
    }
    private void finish(UUID run, int inserted, int duplicates) {
        jdbc.update("UPDATE loanops.source_sync_runs SET status='SUCCESS',complete=true,completed_at=?,inserted_count=?,duplicate_count=? WHERE id=? AND status='RUNNING'",
                ts(clock.instant()),inserted,duplicates,run);
    }
    public SourceSyncRun run(UUID id, String owner) {
        var rows = jdbc.query("""
                SELECT r.* FROM loanops.source_sync_runs r JOIN loanops.open_banking_connections c ON c.id=r.connection_id
                WHERE r.id=? AND c.owner_ref=?""",(rs,n) -> new SourceSyncRun(rs.getObject("id",UUID.class),rs.getObject("connection_id",UUID.class),
                rs.getString("source"),OpenBankingMode.valueOf(rs.getString("mode")),OpenBankingCapability.valueOf(rs.getString("capability")),rs.getString("status"),
                rs.getObject("requested_from",LocalDate.class),rs.getObject("requested_to",LocalDate.class),instant(rs,"started_at"),instant(rs,"completed_at"),
                rs.getInt("page_count"),rs.getInt("record_count"),rs.getInt("inserted_count"),rs.getInt("duplicate_count"),rs.getBoolean("complete"),
                rs.getString("error_category"),rs.getString("failure_cause")),id,owner);
        if (rows.isEmpty()) throw new OpenBankingException(NOT_FOUND);
        return rows.getFirst();
    }
    public Optional<Balance> latestBalance(UUID id, String owner) {
        Connection c = connection(id,owner);
        var rows = jdbc.query("SELECT mapped_ciphertext FROM loanops.open_banking_sync_checkpoints WHERE connection_id=? AND capability='BALANCE'",
                (rs,n) -> rs.getString(1),c.id());
        if (rows.isEmpty()) return Optional.empty();
        var n = json.parse(box.open("balance:"+id,rows.getFirst()));
        return Optional.of(new Balance(n.get("balance").decimalValue(),n.get("available").isNull()?null:n.get("available").decimalValue(),
                Instant.parse(n.get("observedAt").asText()),n.get("sourceType").asText()));
    }
    public Optional<Checkpoint> checkpoint(UUID id, String owner, OpenBankingCapability capability) {
        return checkpoint(connection(id, owner), capability);
    }
    public List<RawOpenBankingSnapshot> snapshots(UUID runId, String owner) {
        run(runId, owner); // Ownership check before returning metadata; payloads remain internal.
        return jdbc.query("SELECT * FROM loanops.raw_openbanking_snapshots WHERE sync_run_id=? ORDER BY page_number", (rs,n) ->
                new RawOpenBankingSnapshot(rs.getObject("id",UUID.class),runId,rs.getString("source"),rs.getString("endpoint"),
                        instant(rs,"requested_at"),instant(rs,"received_at"),rs.getInt("http_status"),rs.getString("result_status"),
                        rs.getString("response_hash"),rs.getString("api_version"),rs.getBoolean("redacted"),rs.getInt("page_number")),runId);
    }
    String identity(ObservedTransaction observation) {
        return box.identity(json.write(List.of(observation.date().toString(),observation.time().toString(),observation.direction(),observation.type(),
                observation.amount().toPlainString(),observation.balanceAfter().toPlainString(),observation.description(),observation.branch())));
    }
    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null?null:value.toInstant(); }
}
