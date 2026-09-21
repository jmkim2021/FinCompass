package com.loanops.personal.canonical;

import com.loanops.personal.gateway.PersonalFinanceProvider;
import com.loanops.personal.gateway.PersonalFinanceProvider.*;
import com.loanops.personal.quality.*;
import com.loanops.personal.quality.PersonalQualityRules.Finding;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.loanops.personal.canonical.PersonalFinanceModels.*;
import static com.loanops.personal.canonical.PersonalTypes.*;
import static com.loanops.personal.canonical.PersonalFinanceException.Code.*;

/** Canonical normalization, DQ and atomic promotion. It neither calls financial HTTP endpoints nor calculates a Twin. */
public final class PersonalFinanceService {
    private final Map<ProviderCode,PersonalFinanceProvider> providers;
    private final PersonalFinanceStore store;
    private final PersonalFinanceCipher cipher;
    private final PersonalQualityProperties config;
    private final PersonalQualityRules rules;
    private final Clock clock;
    private final IncomeQualityProperties incomeConfig;
    public PersonalFinanceService(List<PersonalFinanceProvider> providers,PersonalFinanceStore store,PersonalFinanceCipher cipher,
            PersonalQualityProperties config,Clock clock) {
        this(providers,store,cipher,config,clock,new IncomeQualityProperties());
    }
    public PersonalFinanceService(List<PersonalFinanceProvider> providers,PersonalFinanceStore store,PersonalFinanceCipher cipher,
            PersonalQualityProperties config,Clock clock,IncomeQualityProperties incomeConfig) {
        this.incomeConfig=incomeConfig;
        this.providers=providers.stream().collect(Collectors.toUnmodifiableMap(PersonalFinanceProvider::code,Function.identity()));
        this.store=store;this.cipher=cipher;this.config=config;this.rules=new PersonalQualityRules(config);this.clock=clock;
    }
    public ConsentGrant grant(String owner,ProviderCode provider,UUID connectionId,Set<Capability> scopes,Instant expiresAt) {
        return store.atomic(()->{
            var connection=provider(provider).connection(connectionId,owner);
            store.ledger.register(connectionId,store.ledger.user(owner,clock.instant()),owner,provider,connection.mode(),connection.consentUntil(),clock.instant());
            store.lockConnection(connectionId,owner);
            if(scopes==null||scopes.isEmpty()||!provider(provider).consentScopes().containsAll(scopes)
                    ||expiresAt==null||!expiresAt.isAfter(clock.instant())||expiresAt.isAfter(connection.consentUntil()))
                throw new PersonalFinanceException(INVALID_REQUEST);
            return store.grant(owner,provider,connection,scopes,expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),clock.instant());
        });
    }
    public void revoke(String owner,UUID consentId) {
        store.atomic(()->{if(consentId==null) throw new PersonalFinanceException(CONSENT_DENIED);
        var grant=store.consent(consentId,owner);store.lockConnection(grant.connectionId(),owner);store.revoke(consentId,clock.instant());return null;});
    }
    public ValidationResult normalize(String owner,UUID consentId,UUID sourceRunId) {
        try { return store.atomic(()->{
            ConsentGrant grant=authorized(owner,consentId,null);
            SourceBatch source=provider(grant.provider()).read(sourceRunId,owner);
            if(!source.connectionId().equals(grant.connectionId())||source.sourceMode()!=grant.mode()
                    ||!grant.scopes().contains(source.capability())) throw new PersonalFinanceException(CONSENT_DENIED);
            store.ledger.importOpenBanking(source);
            if(source.sourceType()==SourceType.PUBLIC_MYDATA_MOCK) return normalizeIncome(grant,source);
            var existing=store.existing(sourceRunId,consentId,config.policyVersion(),config.balanceStaleAfter().getSeconds());
            if(existing.isPresent()) return existing.get();
            Instant now=clock.instant();
            var assessment=rules.assess(source,now,store.hasIncome(grant,now,incomeConfig));
            List<Finding> findings=new ArrayList<>(assessment.findings());
            var account=store.account(grant.connectionId()).orElse(null);
            UUID evaluation=UUID.randomUUID(),accountEvidence=UUID.randomUUID();
            boolean mapped=source.complete()&&source.mappingValid()&&source.account()!=null;
            if(mapped&&account==null) account=store.createAccount(grant,source.account(),accountEvidence);
            ValidationResult previous=account==null?null:store.current(account.id(),source.capability()).filter(v->store.sameConsent(v.id(),grant.id())).orElse(null);
            boolean promote=assessment.state()==ValidationState.VALIDATED_COMPLETE&&mapped;
            LocalDate coveredFrom=source.from(),coveredThrough=source.through();
            Instant asOf=source.balance()!=null?source.balance().evidence().asOf():source.completedAt();
            if(promote&&previous!=null) {
                boolean older=source.startedAt().isBefore(store.started(previous.id()))
                        ||(source.capability()==Capability.BALANCE&&asOf.isBefore(previous.asOf()));
                if(older) {
                    promote=false;findings.add(new Finding(RuleCode.OLDER_OBSERVATION,Severity.WARNING,source.capability().name(),"PREVIOUS_CURRENT_IS_NEWER",Resolution.OPEN));
                } else if(source.capability()==Capability.TRANSACTIONS) {
                    boolean disjoint=coveredFrom.isAfter(previous.coveredThrough().plusDays(1))||coveredThrough.isBefore(previous.coveredFrom().minusDays(1));
                    if(disjoint) {
                        promote=false;findings.add(new Finding(RuleCode.DISJOINT_TRANSACTION_WINDOW,Severity.WARNING,"TRANSACTIONS","UNQUERIED_GAP_NOT_PROMOTED",Resolution.OPEN));
                    } else {
                        coveredFrom=coveredFrom.isBefore(previous.coveredFrom())?coveredFrom:previous.coveredFrom();
                        coveredThrough=coveredThrough.isAfter(previous.coveredThrough())?coveredThrough:previous.coveredThrough();
                    }
                }
            }
            String hash=mapped?contentHash(source):null;
            store.validation(evaluation,grant,source,account==null?null:account.id(),assessment.state(),promote,coveredFrom,coveredThrough,asOf,now,
                    hash,config.policyVersion(),config.balanceStaleAfter().getSeconds());
            if(mapped) {
                store.evidence(accountEvidence,evaluation,source,source.account().evidence(),"ACCOUNT",now);
                if(source.balance()!=null) {
                    UUID evidence=store.evidence(UUID.randomUUID(),evaluation,source,source.balance().evidence(),"BALANCE",now);
                    store.balance(account,source.balance(),evidence);
                }
                if(promote&&previous!=null&&source.capability()==Capability.TRANSACTIONS) store.inherit(previous.id(),evaluation);
                Set<String> seen=new HashSet<>();
                for(var observation:source.transactions()) {
                    if(!seen.add(observation.sourceKey())) continue; // Phase 1 identity; raw occurrences remain preserved.
                    UUID evidence=store.evidence(UUID.randomUUID(),evaluation,source,observation.evidence(),"TRANSACTION",now);
                    UUID transaction=store.transaction(account,observation,evidence);
                    store.member(evaluation,transaction,evidence);
                }
            }
            store.findings(evaluation,findings,now);
            authorized(owner,consentId,source.capability()); // Do not promote after a consent expires while mapping.
            if(promote) store.promote(account.id(),source.capability(),evaluation);
            return store.validation(evaluation);
        }); } catch(PersonalFinanceException ex) {
            if(ex.code()==CONSENT_DENIED||ex.code()==NOT_FOUND) {
                try { store.atomic(()->{store.auditDeniedRun(owner,sourceRunId,consentId,clock.instant());return null;}); } catch(PersonalFinanceException ignored) { }
            }
            throw ex;
        }
    }
    /** Current records always travel with completeness/freshness and the latest attempt; never return an unqualified complete history. */
    public CanonicalView current(String owner,UUID consentId,Capability capability,int offset,int limit) {
        if(offset<0||offset>1000000||limit<1||limit>100) throw new PersonalFinanceException(INVALID_REQUEST);
        return store.atomic(()->{
            var grant=authorized(owner,consentId,capability);
            var account=store.account(grant.connectionId()).orElse(null);
            var current=account==null?null:store.current(account.id(),capability).filter(v->store.sameConsent(v.id(),grant.id())).orElse(null);
            var latest=store.latest(consentId,capability).orElse(null);
            Freshness freshness=current!=null&&capability==Capability.BALANCE?(rules.stale(current.asOf(),clock.instant())?Freshness.STALE:Freshness.FRESH):Freshness.NOT_EVALUATED;
            ValidationState effective=current==null?ValidationState.PARTIAL:freshness==Freshness.STALE?ValidationState.STALE:current.state();
            Set<RuleCode> warnings=new LinkedHashSet<>();
            if(latest!=null) latest.issues().stream().filter(q->q.resolution()==Resolution.OPEN).forEach(q->warnings.add(q.rule()));
            if(freshness==Freshness.STALE) warnings.add(RuleCode.STALE_BALANCE);
            boolean latestComplete=current!=null&&latest!=null&&current.id().equals(latest.id())&&effective==ValidationState.VALIDATED_COMPLETE;
            var state=new CurrentState(current,latest,freshness,effective,latestComplete,List.copyOf(warnings));
            return new CanonicalView(state,current==null?null:account,current!=null&&capability==Capability.BALANCE?store.balance(current.id()).orElse(null):null,
                    current!=null&&capability==Capability.TRANSACTIONS?store.transactions(current.id(),offset,limit):List.of());
        });
    }
    public Provenance provenance(String owner,UUID consentId,UUID provenanceId) {
        return store.atomic(()->store.provenance(provenanceId,authorized(owner,consentId,null)));
    }
    public UUID createDemoUser(String owner) { return store.atomic(()->store.ledger.user(owner,clock.instant())); }
    public UUID connectPublicMyDataMock(String owner,UUID financialUserId,Instant expiresAt) {
        if(expiresAt==null||!expiresAt.isAfter(clock.instant())) throw new PersonalFinanceException(INVALID_REQUEST);
        return store.atomic(()->{UUID id=UUID.randomUUID();store.ledger.register(id,financialUserId,owner,ProviderCode.PUBLIC_MYDATA,SourceMode.DEMO_REPLAY,expiresAt,clock.instant());return id;});
    }
    public UUID collectMock(String owner,UUID connection,UUID consentId,Capability capability,String fixture) {
        try {
            return store.atomic(()->{
                store.ledger.connection(connection,owner,ProviderCode.PUBLIC_MYDATA);
                var grant=authorized(owner,consentId,capability);
                if(grant.provider()!=ProviderCode.PUBLIC_MYDATA||!grant.connectionId().equals(connection)) throw new PersonalFinanceException(CONSENT_DENIED);
                var source=(com.loanops.personal.publicmydata.PublicMyDataProvider)provider(ProviderCode.PUBLIC_MYDATA);
                String body=com.loanops.personal.publicmydata.MockPublicMyDataProvider.minimize(source.request(fixture,capability),new com.fasterxml.jackson.databind.ObjectMapper());
                authorized(owner,consentId,capability);
                return store.collectMock(grant,capability,body,clock.instant());
            });
        } catch(PersonalFinanceException ex) {
            if(ex.code()==CONSENT_DENIED||ex.code()==NOT_FOUND) {
                try { store.atomic(()->{store.accessIssue(owner,connection,consentId,capability,clock.instant());return null;}); } catch(PersonalFinanceException ignored) { }
            }
            throw ex;
        }
    }
    private ValidationResult normalizeIncome(ConsentGrant grant,SourceBatch source) {
        if(grant.provider()!=ProviderCode.PUBLIC_MYDATA||!Set.of(Capability.INCOME,Capability.EMPLOYMENT).contains(source.capability())) throw new PersonalFinanceException(CONSENT_DENIED);
        String version="income-v1-age"+incomeConfig.maxIncomeYearAge()+"-sec"+incomeConfig.incomeEvidenceStaleAfter().getSeconds();
        long threshold=incomeConfig.incomeEvidenceStaleAfter().getSeconds();
        var existing=store.existing(source.runId(),grant.id(),version,config.balanceStaleAfter().getSeconds());
        if(existing.isPresent()) return existing.get();
        Instant now=clock.instant();
        var previous=store.incomeCurrent(grant.connectionId(),source.capability()).filter(v->store.sameConsent(v.id(),grant.id())).orElse(null);
        var before=previous==null?List.<IncomeEvidence>of():store.income(previous.id());
        var assessment=new IncomeQualityRules(incomeConfig).assess(source,before,now);
        List<Finding> findings=new ArrayList<>(assessment.findings());
        boolean promote=assessment.state()==ValidationState.VALIDATED_COMPLETE;
        Instant asOf=source.incomes().stream().map(i->i.evidence().asOf()).min(Instant::compareTo).orElse(source.completedAt());
        if(promote&&previous!=null&&(asOf.isBefore(previous.asOf())||(!before.isEmpty()&&source.capability()==Capability.INCOME&&source.incomes().getFirst().incomeYear()<before.getFirst().incomeYear()))) {
            promote=false;findings.add(IncomeQualityRules.issue(RuleCode.OLDER_OBSERVATION,"PREVIOUS_CURRENT_IS_NEWER"));
        }
        UUID validation=UUID.randomUUID();
        // Hash business fields only; run IDs and collection times must not change deterministic content identity.
        var content=source.incomes().stream().map(i->Arrays.asList(i.annualIncome(),i.incomeYear(),i.employmentStatus(),i.evidenceType(),i.currency(),i.evidence().asOf())).toList();
        store.validation(validation,grant,source,null,assessment.state(),promote,null,null,asOf,now,cipher.contentHash(content),version,config.balanceStaleAfter().getSeconds());
        store.incomePolicy(validation,threshold,incomeConfig.maxIncomeYearAge());
        if(source.mappingValid()) for(var i:source.incomes()) store.income(validation,grant,source,i,now,assessment.state()==ValidationState.VALIDATED_COMPLETE&&source.capability()==Capability.INCOME);
        store.findings(validation,findings,now);
        authorized(store.owner(grant.userId()),grant.id(),source.capability());
        if(promote) store.promoteIncome(grant.connectionId(),source.capability(),validation);
        return store.validation(validation);
    }
    public IncomeView currentIncome(String owner,UUID consentId,Capability capability) {
        if(!Set.of(Capability.INCOME,Capability.EMPLOYMENT).contains(capability)) throw new PersonalFinanceException(INVALID_REQUEST);
        return store.atomic(()->{
            var grant=authorized(owner,consentId,capability);
            if(grant.provider()!=ProviderCode.PUBLIC_MYDATA) throw new PersonalFinanceException(CONSENT_DENIED);
            var current=store.incomeCurrent(grant.connectionId(),capability).filter(v->store.sameConsent(v.id(),grant.id())).orElse(null);
            var latest=store.latest(consentId,capability).orElse(null);var records=current==null?List.<IncomeEvidence>of():store.income(current.id());
            var rules=new IncomeQualityRules(incomeConfig);Set<RuleCode> warnings=new LinkedHashSet<>();
            if(latest!=null) latest.issues().stream().filter(q->q.resolution()==Resolution.OPEN).forEach(q->warnings.add(q.rule()));
            boolean stale=records.stream().anyMatch(i->rules.stale(i.asOf(),clock.instant()));
            boolean oldYear=records.stream().anyMatch(i->rules.outdated(i.incomeYear(),clock.instant()));
            if(stale) warnings.add(RuleCode.STALE_INCOME_EVIDENCE);if(oldYear) warnings.add(RuleCode.INCOME_YEAR_OUTDATED);
            var effective=current==null?ValidationState.PARTIAL:stale||oldYear?ValidationState.STALE:current.state();
            return new IncomeView(new CurrentState(current,latest,current==null?Freshness.NOT_EVALUATED:stale||oldYear?Freshness.STALE:Freshness.FRESH,effective,
                current!=null&&latest!=null&&current.id().equals(latest.id())&&effective==ValidationState.VALIDATED_COMPLETE,List.copyOf(warnings)),records);
        });
    }
    private ConsentGrant authorized(String owner,UUID consentId,Capability scope) {
        if(consentId==null) throw new PersonalFinanceException(CONSENT_DENIED);
        var grant=store.consent(consentId,owner);
        store.lockConnection(grant.connectionId(),owner);
        grant=store.consent(consentId,owner);
        if(grant.statusAt(clock.instant())!=ConsentStatus.ACTIVE||(scope!=null&&!grant.scopes().contains(scope))) throw new PersonalFinanceException(CONSENT_DENIED);
        var upstream=provider(grant.provider()).connection(grant.connectionId(),owner);
        if(upstream.mode()!=grant.mode()||!upstream.consentUntil().isAfter(clock.instant())) throw new PersonalFinanceException(CONSENT_DENIED);
        return grant;
    }
    private PersonalFinanceProvider provider(ProviderCode code) {
        var result=providers.get(code);if(result==null) throw new PersonalFinanceException(INVALID_REQUEST);return result;
    }
    private String contentHash(SourceBatch source) {
        Map<String,Object> content=new LinkedHashMap<>();
        content.put("sourceType",source.sourceType());content.put("sourceMode",source.sourceMode());content.put("capability",source.capability());
        content.put("from",source.from());content.put("through",source.through());
        if(source.balance()!=null) {
            content.put("balance",source.balance().balance());content.put("available",source.balance().available());
            content.put("asOf",source.balance().evidence().asOf());content.put("currency",source.balance().currency());
        }
        content.put("transactionKeys",source.transactions().stream().map(TransactionObservation::sourceKey).distinct().sorted().toList());
        return cipher.contentHash(content);
    }
}
