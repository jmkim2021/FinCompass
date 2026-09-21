package com.loanops.personal.openbanking;

import com.loanops.personal.canonical.PersonalFinanceService;
import com.loanops.personal.canonical.PersonalFinanceModels.CurrentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.loanops.personal.canonical.PersonalTypes.*;

/** Explicit synthetic persona, collected through the same raw, mapping and canonical quality path as Phase 1. */
@Component
@ConditionalOnProperty(prefix="fincompass.demo",name="enabled",havingValue="true")
public final class GoldenHomeBuyerSources {
    public static final LocalDate FROM=LocalDate.of(2026,8,1), THROUGH=LocalDate.of(2026,8,31);
    private static final String SYNTHETIC_ACCOUNT="000000000000000000000004";
    private static final Map<String,String> LABELS=Map.of(
            "GOLDEN_V1_INCOME","INCOME", "GOLDEN_V1_LIVING_EXPENSE","LIVING_EXPENSE",
            "GOLDEN_V1_DEBT_SERVICE","DEBT_SERVICE", "GOLDEN_V1_TRANSFER","TRANSFER");
    private final PersonalFinanceService personal;
    private final OpenBankingStore ob;
    private final OpenBankingSyncService sync;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public GoldenHomeBuyerSources(PersonalFinanceService personal,OpenBankingStore ob,OpenBankingMapper mapper,
            OpenBankingProperties config,OpenBankingJson json,JdbcTemplate jdbc,ObjectProvider<Clock> clocks) {
        this(personal,ob,mapper,config,json,jdbc,clocks.getIfAvailable(Clock::systemUTC));
    }
    public GoldenHomeBuyerSources(PersonalFinanceService personal,OpenBankingStore ob,OpenBankingMapper mapper,
            OpenBankingProperties config,OpenBankingJson json,JdbcTemplate jdbc,Clock clock) {
        if(config.mode()!=OpenBankingMode.DEMO_REPLAY) throw new IllegalStateException("Golden sources require DEMO_REPLAY");
        this.personal=personal;this.ob=ob;this.jdbc=jdbc;this.clock=clock;
        this.sync=new OpenBankingSyncService(new ReplayOpenBankingProvider(json,clock,"golden-homebuyer-v1"),ob,mapper,config,clock);
    }
    public record Seed(UUID userId,UUID obConsentId,UUID incomeConsentId,Map<UUID,String> classifications) {
        public Seed { classifications=Map.copyOf(classifications); }
    }
    private record Binding(UUID consentId,UUID connectionId) { }

    /** Reuses this owner's active golden connections and already validated records on repeated bootstrap. */
    public synchronized Seed seed(String owner) {
        UUID user=personal.createDemoUser(owner);
        Binding income=findIncome(owner);
        if(income==null) {
            UUID connection=personal.connectPublicMyDataMock(owner,user,clock.instant().plus(Duration.ofDays(30)));
            var grant=personal.grant(owner,ProviderCode.PUBLIC_MYDATA,connection,Set.of(Capability.INCOME,Capability.EMPLOYMENT),clock.instant().plus(Duration.ofDays(29)));
            income=new Binding(grant.id(),connection);
        }
        for(var capability:List.of(Capability.INCOME,Capability.EMPLOYMENT)) {
            var state=personal.currentIncome(owner,income.consentId(),capability).state();
            if(!complete(state)) personal.normalize(owner,income.consentId(),personal.collectMock(owner,income.connectionId(),income.consentId(),capability,"main-home-buyer-v1"));
            requireComplete(personal.currentIncome(owner,income.consentId(),capability).state());
        }
        Binding banking=findBanking(owner);
        if(banking==null) {
            UUID connection=ob.createConnection(owner,OpenBankingMode.DEMO_REPLAY,SYNTHETIC_ACCOUNT,clock.instant().plus(Duration.ofDays(30)));
            var grant=personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.BALANCE,Capability.TRANSACTIONS),clock.instant().plus(Duration.ofDays(29)));
            banking=new Binding(grant.id(),connection);
        }
        for(var capability:List.of(Capability.BALANCE,Capability.TRANSACTIONS)) {
            var current=personal.current(owner,banking.consentId(),capability,0,100);
            if(!complete(current.state())) personal.normalize(owner,banking.consentId(),sync.sync(banking.connectionId(),owner,OpenBankingCapability.valueOf(capability.name()),FROM,THROUGH).id());
            requireComplete(personal.current(owner,banking.consentId(),capability,0,100).state());
        }
        Map<UUID,String> classifications=new LinkedHashMap<>();
        var transactions=personal.current(owner,banking.consentId(),Capability.TRANSACTIONS,0,100).transactions();
        for(var transaction:transactions) {
            String category=LABELS.get(transaction.description());
            if(category==null) throw new IllegalStateException("Unexpected golden transaction label");
            classifications.put(transaction.id(),category);
        }
        if(transactions.size()!=32) throw new IllegalStateException("Golden transaction set is incomplete");
        return new Seed(user,banking.consentId(),income.consentId(),classifications);
    }
    private Binding findIncome(String owner) {
        return active(owner,ProviderCode.PUBLIC_MYDATA).stream().filter(binding->Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM loanops.provider_evidence e JOIN loanops.provider_sync_runs r ON r.id=e.provider_sync_run_id
                WHERE r.connection_id=? AND e.fixture_evidence_id='PMD-DEMO-001' AND e.consent_grant_id=?)
                """,Boolean.class,binding.connectionId(),binding.consentId()))).findFirst().orElse(null);
    }
    private Binding findBanking(String owner) {
        return active(owner,ProviderCode.OPEN_BANKING).stream()
                .filter(binding->SYNTHETIC_ACCOUNT.equals(ob.account(ob.connection(binding.connectionId(),owner))))
                .findFirst().orElse(null);
    }
    private List<Binding> active(String owner,ProviderCode provider) {
        return jdbc.query("""
                SELECT g.id,g.provider_connection_id FROM loanops.consent_grants g
                JOIN loanops.financial_users u ON u.id=g.financial_user_id
                JOIN loanops.provider_connections c ON c.id=g.provider_connection_id
                WHERE u.owner_ref=? AND g.source_provider=? AND g.source_mode='DEMO_REPLAY'
                AND g.revoked_at IS NULL AND g.expires_at>? AND c.consent_until>?
                AND cardinality(g.scopes)=2 ORDER BY g.granted_at DESC,g.id
                """,(rs,n)->new Binding(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),owner,provider.name(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
    }
    private static boolean complete(CurrentState state) {
        return state.completeForLatestAttempt()&&state.effectiveState()==ValidationState.VALIDATED_COMPLETE;
    }
    private static void requireComplete(CurrentState state) {
        if(!complete(state)) throw new IllegalStateException("Golden source did not pass canonical validation: "+state.effectiveState());
    }
}
