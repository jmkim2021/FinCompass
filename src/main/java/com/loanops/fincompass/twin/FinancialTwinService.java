package com.loanops.fincompass.twin;

import com.loanops.personal.canonical.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.loanops.fincompass.twin.TwinModels.*;
import static com.loanops.personal.canonical.PersonalTypes.*;
import static com.loanops.personal.canonical.PersonalFinanceModels.*;

public final class FinancialTwinService {
 private final PersonalFinanceService personal;private final FinancialTwinStore store;private final PersonalFinanceCipher cipher;private final Clock clock;private final TwinCalculator calculator=new TwinCalculator();
 public FinancialTwinService(PersonalFinanceService personal,FinancialTwinStore store,PersonalFinanceCipher cipher,Clock clock){this.personal=personal;this.store=store;this.cipher=cipher;this.clock=clock;}
 public Snapshot create(String owner,UUID obConsent,UUID incomeConsent,List<Fact> facts,Map<UUID,Category> classifications){
  var balance=personal.current(owner,obConsent,Capability.BALANCE,0,100);var page=personal.current(owner,obConsent,Capability.TRANSACTIONS,0,100);
  var income=personal.currentIncome(owner,incomeConsent,Capability.INCOME);var refs=new ArrayList<SourceRef>();var transactions=new ArrayList<ClassifiedTransaction>();var warnings=new ArrayList<String>();
  boolean balanceOk=usable(balance.state()),txOk=page.state().effectiveState()==ValidationState.VALIDATED_COMPLETE&&page.state().completeForLatestAttempt(),incomeOk=usable(income.state());
  if(!balanceOk)warnings.add("STALE_OR_MISSING_BALANCE");if(!txOk)warnings.add("PARTIAL_TRANSACTION_HISTORY");if(!incomeOk)warnings.add("MISSING_VERIFIED_INCOME");
  if(balance.balance()!=null)refs.add(ref(owner,obConsent,balance.balance().id(),balance.balance().provenanceId(),"LIQUID_ASSETS",balance.balance()));
  int offset=0;UUID validation=page.state().authoritative()==null?null:page.state().authoritative().id();
  while(true){if(offset>0)page=personal.current(owner,obConsent,Capability.TRANSACTIONS,offset,100);
   if(!Objects.equals(validation,page.state().authoritative()==null?null:page.state().authoritative().id()))throw new IllegalArgumentException("Source changed while reading snapshot; retry");
   for(var t:page.transactions()){transactions.add(new ClassifiedTransaction(t.id(),t.amount(),t.direction().name(),classifications.getOrDefault(t.id(),Category.UNCLASSIFIED)));refs.add(ref(owner,obConsent,t.id(),t.provenanceId(),"CASHFLOW",t));}
   if(page.transactions().size()<100)break;offset+=100;if(offset>100000)throw new IllegalArgumentException("Transaction history exceeds snapshot bound");
  }
  BigDecimal verified=null;if(incomeOk&&income.evidence().size()==1)verified=income.evidence().getFirst().verifiedAnnualIncome();else incomeOk=false;
  for(var e:income.evidence())refs.add(ref(owner,incomeConsent,e.id(),e.provenanceId(),"VERIFIED_ANNUAL_INCOME",e));
  var employment=personal.currentIncome(owner,incomeConsent,Capability.EMPLOYMENT);
  for(var e:employment.evidence())refs.add(ref(owner,incomeConsent,e.id(),e.provenanceId(),"EMPLOYMENT",e));
  if(!usable(employment.state()))warnings.add("MISSING_EMPLOYMENT_EVIDENCE");
  int months=0;var state=page.state().authoritative();if(state!=null&&state.coveredFrom()!=null&&state.coveredThrough()!=null){var from=state.coveredFrom();var to=state.coveredThrough();if(from.getDayOfMonth()==1&&to.getDayOfMonth()==to.lengthOfMonth())months=(int)ChronoUnit.MONTHS.between(YearMonth.from(from),YearMonth.from(to))+1;}
  if(months==0){txOk=false;warnings.add("PARTIAL_TRANSACTION_HISTORY");}
  UUID user=store.ownerId(owner);Instant asOf=clock.instant();
  for(var f:facts){if(f.id()==null||f.kind()==null||f.value()==null||f.asOf()==null||f.evidenceId()==null)throw new IllegalArgumentException("Incomplete supplemental fact");if(!"USER_INPUT".equals(f.sourceType())||!Set.of("DEMO_REPLAY","MANUAL").contains(f.sourceMode())||!"KRW".equals(f.currency()))throw new IllegalArgumentException("Unsupported supplemental fact provenance");refs.add(new SourceRef(f.id(),null,null,f.sourceType(),f.sourceMode(),f.asOf(),cipher.contentHash(f),f.kind()));}
  // The OB contract lacks currency. An explicit account-currency assertion is required rather than inferred.
  boolean currency=facts.stream().anyMatch(f->f.kind().equals("ACCOUNT_CURRENCY_KRW")&&f.value().compareTo(BigDecimal.ONE)==0);
  if(!currency){balanceOk=false;txOk=false;warnings.add("UNCONFIRMED_ACCOUNT_CURRENCY");}
  sourceState(refs,balance.state(),"CURRENT_BALANCE");sourceState(refs,page.state(),"CURRENT_TRANSACTIONS");sourceState(refs,income.state(),"CURRENT_INCOME");sourceState(refs,employment.state(),"CURRENT_EMPLOYMENT");
  Input input=new Input(user,asOf,refs,facts,balance.balance()==null?null:balance.balance().balance(),verified,transactions,months,balanceOk,txOk,incomeOk,warnings);
  return store.save(owner,input,calculator.calculate(input),calculator.fingerprint(input),clock);
 }
 private SourceRef ref(String owner,UUID consent,UUID id,UUID provenance,String purpose,Object record){var p=personal.provenance(owner,consent,provenance);return new SourceRef(id,p.id(),p.validationRunId(),p.sourceType().name(),p.sourceMode().name(),p.asOf(),cipher.contentHash(record),purpose);}
 private void sourceState(List<SourceRef> refs,CurrentState state,String purpose){for(var v:java.util.stream.Stream.of(state.authoritative(),state.latestAttempt()).filter(Objects::nonNull).distinct().toList())refs.add(new SourceRef(v.id(),null,v.id(),"DERIVED",refs.stream().filter(r->r.validationRunId()!=null).map(SourceRef::sourceMode).findFirst().orElse("MANUAL"),v.evaluatedAt(),cipher.contentHash(state),purpose));}
 private boolean usable(CurrentState state){return state.effectiveState()==ValidationState.VALIDATED_COMPLETE&&state.completeForLatestAttempt()&&state.freshness()==Freshness.FRESH;}
}
