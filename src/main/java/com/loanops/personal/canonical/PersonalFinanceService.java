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
    public PersonalFinanceService(List<PersonalFinanceProvider> providers,PersonalFinanceStore store,PersonalFinanceCipher cipher,
            PersonalQualityProperties config,Clock clock) {
        this.providers=providers.stream().collect(Collectors.toUnmodifiableMap(PersonalFinanceProvider::code,Function.identity()));
        this.store=store;this.cipher=cipher;this.config=config;this.rules=new PersonalQualityRules(config);this.clock=clock;
    }
    public ConsentGrant grant(String owner,ProviderCode provider,UUID connectionId,Set<Capability> scopes,Instant expiresAt) {
        return store.atomic(()->{
            store.lockConnection(connectionId,owner);
            var connection=provider(provider).connection(connectionId,owner);
            if(scopes==null||scopes.isEmpty()||!Set.of(Capability.BALANCE,Capability.TRANSACTIONS).containsAll(scopes)
                    ||expiresAt==null||!expiresAt.isAfter(clock.instant())||expiresAt.isAfter(connection.consentUntil()))
                throw new PersonalFinanceException(INVALID_REQUEST);
            return store.grant(owner,provider,connection,scopes,expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),clock.instant());
        });
    }
    public void revoke(String owner,UUID consentId) {
        store.atomic(()->{var grant=store.consent(consentId,owner);store.lockConnection(grant.connectionId(),owner);store.revoke(consentId,clock.instant());return null;});
    }
    public ValidationResult normalize(String owner,UUID consentId,UUID sourceRunId) {
        return store.atomic(()->{
            ConsentGrant grant=authorized(owner,consentId,null);
            SourceBatch source=provider(grant.provider()).read(sourceRunId,owner);
            if(!source.connectionId().equals(grant.connectionId())||source.sourceMode()!=grant.mode()
                    ||!grant.scopes().contains(source.capability())) throw new PersonalFinanceException(CONSENT_DENIED);
            var existing=store.existing(sourceRunId,consentId,config.policyVersion(),config.balanceStaleAfter().getSeconds());
            if(existing.isPresent()) return existing.get();
            Instant now=clock.instant();
            var assessment=rules.assess(source,now,store.hasIncome(grant,now));
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
        });
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
    private ConsentGrant authorized(String owner,UUID consentId,Capability scope) {
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
