package com.loanops.personal.openbanking;

import com.loanops.personal.gateway.PersonalFinanceProvider;
import com.loanops.personal.canonical.PersonalFinanceException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.loanops.personal.canonical.PersonalTypes.*;
import static com.loanops.personal.canonical.PersonalFinanceException.Code.*;

/** Reads Phase 1 evidence. No network/pagination/overlap/token lifecycle is reimplemented here. */
public final class OpenBankingPersonalFinanceProvider implements PersonalFinanceProvider {
    private final OpenBankingStore store;
    private final OpenBankingMapper mapper;
    private final OpenBankingJson json;
    private final SecretBox box;
    private final JdbcTemplate jdbc;
    OpenBankingPersonalFinanceProvider(OpenBankingStore store,OpenBankingMapper mapper,OpenBankingJson json,SecretBox box,JdbcTemplate jdbc) {
        this.store=store;this.mapper=mapper;this.json=json;this.box=box;this.jdbc=jdbc;
    }
    @Override public ProviderCode code() { return ProviderCode.OPEN_BANKING; }
    @Override public SourceConnection connection(UUID id,String owner) {
        try {
            var c=store.connection(id,owner);
            return new SourceConnection(c.id(),SourceMode.valueOf(c.mode().name()),c.consentUntil());
        } catch(OpenBankingException ex) { throw boundary(ex); }
    }
    @Override public SourceBatch read(UUID runId,String owner) {
        final SourceSyncRun run;
        try { run=store.run(runId,owner);store.connection(run.connectionId(),owner); }
        catch(OpenBankingException ex) { throw boundary(ex); }
        if("RUNNING".equals(run.status())) throw new PersonalFinanceException(SOURCE_NOT_READY);
        AccountObservation account=null;BalanceObservation balance=null;List<TransactionObservation> observations=new ArrayList<>();
        boolean valid=true;
        if(run.complete()&&"SUCCESS".equals(run.status())) {
            try {
                var snapshots=store.snapshots(runId,owner);
                if(snapshots.size()!=run.pageCount()||snapshots.isEmpty()) throw new PersonalFinanceException(INVALID_SOURCE_DATA);
                Map<String,String> existing=new HashMap<>();
                if(run.capability()==OpenBankingCapability.TRANSACTIONS) {
                    jdbc.query("SELECT identity_hash,mapped_ciphertext FROM loanops.open_banking_transaction_evidence WHERE connection_id=? AND transaction_date BETWEEN ? AND ?",
                            rs->{existing.put(rs.getString(1),rs.getString(2));},run.connectionId(),run.requestedFrom(),run.requestedTo());
                }
                for(var raw:snapshots) {
                    String encrypted=jdbc.queryForObject("SELECT payload_ciphertext FROM loanops.raw_openbanking_snapshots WHERE id=?",String.class,raw.id());
                    String body=box.open("raw:"+raw.id(),encrypted);
                    var response=new OpenBankingModels.RawResponse(raw.endpoint(),raw.requestedAt(),raw.receivedAt(),raw.httpStatus(),body);
                    JsonNode tree=json.parse(body);
                    if(account==null) account=new AccountObservation(run.connectionId().toString(),null,
                            run.capability()==OpenBankingCapability.BALANCE?optional(tree,"account_type"):null,null,
                            evidence(raw,raw.receivedAt(),null));
                    if(run.capability()==OpenBankingCapability.BALANCE) {
                        if(snapshots.size()!=1) throw new PersonalFinanceException(INVALID_SOURCE_DATA);
                        var mapped=mapper.balance(response,run.mode());
                        balance=new BalanceObservation(mapped.balance(),mapped.available(),null,evidence(raw,mapped.observedAt(),null));
                    } else if(run.capability()==OpenBankingCapability.TRANSACTIONS) {
                        var mapped=mapper.transactions(response,run.requestedFrom(),run.requestedTo());
                        int index=0;
                        for(var t:mapped.transactions()) {
                            String key=store.identity(t); // Exactly the Phase 1 ob-composite-v1 rule.
                            if(!existing.containsKey(key)) throw new PersonalFinanceException(INVALID_SOURCE_DATA);
                            var stored=json.parse(box.open("transaction:"+run.connectionId()+":"+key,existing.get(key)));
                            // Authenticate the durable Phase 1 evidence and require agreement with its source observation.
                            if(!stored.equals(json.parse(json.write(t)))) throw new PersonalFinanceException(INVALID_SOURCE_DATA);
                            Instant occurred=t.date().atTime(t.time()).atZone(ZoneId.of("Asia/Seoul")).toInstant();
                            JsonNode row=tree.get("res_list").get(index);
                            observations.add(new TransactionObservation(key,occurred,null,t.amount(),"입금".equals(t.direction())?Direction.CREDIT:Direction.DEBIT,
                                    row.hasNonNull("print_content")?t.description():null,null,null,t.balanceAfter(),null,evidence(raw,occurred,index)));
                            index++;
                        }
                    }
                }
                if(run.capability()==OpenBankingCapability.TRANSACTIONS&&observations.size()!=run.recordCount()) throw new PersonalFinanceException(INVALID_SOURCE_DATA);
            } catch(OpenBankingException|PersonalFinanceException ex) { valid=false;account=null;balance=null;observations.clear(); }
        }
        return new SourceBatch(run.id(),run.connectionId(),SourceType.OPEN_BANKING_TEST,SourceMode.valueOf(run.mode().name()),
                Capability.valueOf(run.capability().name()),run.status(),run.complete(),run.errorCategory(),run.requestedFrom(),run.requestedTo(),
                run.startedAt(),run.completedAt(),run.duplicateCount(),Set.of(Capability.BALANCE,Capability.TRANSACTIONS),account,balance,observations,valid);
    }
    private Evidence evidence(RawOpenBankingSnapshot raw,Instant asOf,Integer index) {
        return new Evidence(raw.id(),asOf,raw.receivedAt(),raw.endpoint(),raw.responseHash(),index);
    }
    private String optional(JsonNode node,String key) { return node.hasNonNull(key)&&node.get(key).isTextual()?node.get(key).asText():null; }
    private PersonalFinanceException boundary(OpenBankingException ex) {
        return new PersonalFinanceException(ex.category()==OpenBankingException.Category.NOT_FOUND?NOT_FOUND:CONSENT_DENIED);
    }
}
