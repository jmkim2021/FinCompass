package com.loanops.personal.openbanking;

import java.time.*;
import java.util.*;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;

public final class OpenBankingSyncService {
    private final OpenBankingProvider provider;
    private final OpenBankingStore store;
    private final OpenBankingMapper mapper;
    private final OpenBankingProperties config;
    private final Clock clock;
    OpenBankingSyncService(OpenBankingProvider provider,OpenBankingStore store,OpenBankingMapper mapper,OpenBankingProperties config,Clock clock) {
        this.provider=provider; this.store=store; this.mapper=mapper; this.config=config; this.clock=clock;
    }
    public List<CapabilityState> capabilities() { return provider.capabilities(); }
    public List<SourceSyncRun> syncAll(UUID id,String owner,LocalDate from,LocalDate to) {
        List<SourceSyncRun> runs=new ArrayList<>();
        for(var capability:OpenBankingCapability.values()) runs.add(sync(id,owner,capability,from,to));
        return List.copyOf(runs);
    }
    public SourceSyncRun incremental(UUID id,String owner,LocalDate to) {
        Connection c=checked(id,owner);
        LocalDate from=store.checkpoint(c,OpenBankingCapability.TRANSACTIONS)
                .map(cp -> cp.through().minusDays(config.overlapDays()).isBefore(cp.from())?cp.from():cp.through().minusDays(config.overlapDays()))
                .orElse(to.minusDays(config.initialLookbackDays()-1));
        return sync(id,owner,OpenBankingCapability.TRANSACTIONS,from,to);
    }
    public SourceSyncRun sync(UUID id,String owner,OpenBankingCapability capability,LocalDate from,LocalDate to) {
        Connection c=checked(id,owner);
        if(capability==OpenBankingCapability.TRANSACTIONS) validateRange(from,to);
        UUID run=store.start(c,capability,capability==OpenBankingCapability.TRANSACTIONS?from:null,capability==OpenBankingCapability.TRANSACTIONS?to:null);
        int pages=0;
        try {
            if(provider.capabilities().stream().noneMatch(v -> v.capability()==capability&&v.supported())) {
                store.fail(run,SOURCE_CAPABILITY_MISSING,null,true); return store.run(run,owner);
            }
            if(capability==OpenBankingCapability.BALANCE) {
                var response=provider.balance(c);
                store.raw(run,1,response.raw(),response.secrets());
                Balance balance=mapper.balance(store.sanitized(response.raw(),response.secrets()),c.mode());
                store.connection(id,owner);
                store.records(run,1); store.successBalance(c,run,balance);
            } else {
                String cursor=null; Set<String> seen=new HashSet<>();
                List<TransactionEvidence> evidence=new ArrayList<>();
                while(true) {
                    if(pages>=config.maxPages()) throw new OpenBankingException(PAGINATION_INCOMPLETE);
                    store.connection(id,owner);
                    var response=provider.transactions(c,from,to,cursor);
                    UUID raw=store.raw(run,++pages,response.raw(),response.secrets());
                    TransactionPage page=mapper.transactions(store.sanitized(response.raw(),response.secrets()),from,to);
                    store.records(run,page.transactions().size());
                    for(var t:page.transactions()) evidence.add(new TransactionEvidence(store.identity(t),raw,t));
                    if(!page.hasNext()) break;
                    if(!seen.add(page.nextCursor())) throw new OpenBankingException(PAGINATION_INCOMPLETE);
                    cursor=page.nextCursor();
                }
                store.connection(id,owner);
                store.successTransactions(c,run,from,to,evidence);
            }
        } catch(OpenBankingException ex) {
            var category=capability==OpenBankingCapability.TRANSACTIONS&&pages>0?PAGINATION_INCOMPLETE:ex.category();
            store.fail(run,category,category==ex.category()?null:ex.category(),ex.category()==SOURCE_CAPABILITY_MISSING);
        } catch(org.springframework.dao.DataAccessException ex) {
            store.fail(run,STORAGE_ERROR,null,false);
        }
        return store.run(run,owner);
    }
    private Connection checked(UUID id,String owner) {
        Connection c=store.connection(id,owner);
        if(c.mode()!=provider.mode()) throw new OpenBankingException(INVALID_REQUEST);
        return c;
    }
    private void validateRange(LocalDate from,LocalDate to) {
        if(from==null||to==null||from.isAfter(to)||to.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))
                ||java.time.temporal.ChronoUnit.DAYS.between(from,to)>=366) throw new OpenBankingException(INVALID_REQUEST);
    }
}
