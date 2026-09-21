package com.loanops.fincompass.twin;

import com.loanops.personal.canonical.PersonalFinanceCipher;
import com.loanops.api.ApiNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.loanops.fincompass.twin.TwinModels.*;

/** Immutable, encrypted analytics history. All reads are scoped by the canonical owner. */
public final class FinancialTwinStore {
 private final JdbcTemplate jdbc; private final PersonalFinanceCipher cipher; private final TransactionTemplate tx;
 public FinancialTwinStore(JdbcTemplate jdbc,PersonalFinanceCipher cipher,PlatformTransactionManager manager){this.jdbc=jdbc;this.cipher=cipher;this.tx=new TransactionTemplate(manager);}
 public Snapshot save(String owner,Input input,Result result,String fingerprint,Clock clock){return tx.execute(status->{
  UUID user=ownerId(owner); if(!user.equals(input.financialUserId()))throw new IllegalArgumentException("Snapshot owner mismatch");
  jdbc.queryForObject("SELECT id FROM loanops.financial_users WHERE id=? FOR UPDATE",UUID.class,user);
  var found=jdbc.query("SELECT id,payload_ciphertext FROM loanops.financial_snapshot WHERE financial_user_id=? AND input_fingerprint=?",(r,n)->decode(r.getObject(1,UUID.class),r.getString(2)),user,fingerprint);
  if(!found.isEmpty())return found.getFirst();
  UUID id=UUID.randomUUID(); String state=result.metrics().values().stream().allMatch(m->m.availability()==Availability.AVAILABLE)?"COMPLETE":"INCOMPLETE";
  Snapshot snap=new Snapshot(id,user,input.asOf(),clock.instant(),state,TwinCalculator.RULE_VERSION,fingerprint,input,result);
  jdbc.update("INSERT INTO loanops.financial_snapshot VALUES (?,?,?,?,?,?,?,?)",id,user,Timestamp.from(input.asOf()),Timestamp.from(snap.createdAt()),state,snap.calculationRuleVersion(),fingerprint,cipher.seal("snapshot:"+id,snap));
  int ordinal=0;for(var ref:input.sourceRefs())jdbc.update("INSERT INTO loanops.financial_snapshot_source_ref VALUES (?,?,?,?,?,?,?,?,?,?)",id,ordinal++,ref.recordId(),ref.provenanceId(),ref.validationRunId(),ref.sourceType(),ref.sourceMode(),Timestamp.from(ref.asOf()),ref.contentHash(),ref.purpose());
  result.metrics().forEach((name,value)->jdbc.update("INSERT INTO loanops.financial_metric VALUES (?,?,?,?)",id,name,value.availability().name(),cipher.seal("metric:"+id+":"+name,value)));
  for(var f:input.facts()){
   var existing=jdbc.query("SELECT financial_user_id,payload_ciphertext FROM loanops.user_entered_financial_fact WHERE id=?",(r,n)->Map.entry(r.getObject(1,UUID.class),r.getString(2)),f.id());
   if(existing.isEmpty())jdbc.update("INSERT INTO loanops.user_entered_financial_fact VALUES (?,?,?,?,?)",f.id(),user,f.kind(),Timestamp.from(f.asOf()),cipher.seal("fact:"+f.id(),f));
   else if(!existing.getFirst().getKey().equals(user)||!cipher.open("fact:"+f.id(),existing.getFirst().getValue(),Fact.class).equals(f))throw new IllegalArgumentException("Immutable fact changed");
  }return snap;
 });}
 public UUID ownerId(String owner){var ids=jdbc.queryForList("SELECT id FROM loanops.financial_users WHERE owner_ref=?",UUID.class,owner);if(ids.isEmpty())throw new ApiNotFoundException("Financial user not found");return ids.getFirst();}
 public Optional<Snapshot> latestOptional(String owner){var rows=jdbc.query("SELECT s.id,s.payload_ciphertext FROM loanops.financial_snapshot s JOIN loanops.financial_users u ON u.id=s.financial_user_id WHERE u.owner_ref=? ORDER BY CASE WHEN s.status='COMPLETE' THEN 0 ELSE 1 END,s.created_at DESC,s.id DESC LIMIT 1",(r,n)->decode(r.getObject(1,UUID.class),r.getString(2)),owner);return rows.stream().findFirst();}
 public Optional<Snapshot> latestAttempt(String owner){return jdbc.query("SELECT s.id,s.payload_ciphertext FROM loanops.financial_snapshot s JOIN loanops.financial_users u ON u.id=s.financial_user_id WHERE u.owner_ref=? ORDER BY s.created_at DESC,s.id DESC LIMIT 1",(r,n)->decode(r.getObject(1,UUID.class),r.getString(2)),owner).stream().findFirst();}
 public Snapshot latest(String owner){return latestOptional(owner).orElseThrow(()->new ApiNotFoundException("Financial snapshot not found"));}
 public Snapshot get(String owner,UUID id){var rows=jdbc.query("SELECT s.id,s.payload_ciphertext FROM loanops.financial_snapshot s JOIN loanops.financial_users u ON u.id=s.financial_user_id WHERE u.owner_ref=? AND s.id=?",(r,n)->decode(r.getObject(1,UUID.class),r.getString(2)),owner,id);if(rows.isEmpty())throw new ApiNotFoundException("Financial snapshot not found");return rows.getFirst();}
 public List<Snapshot> history(String owner){return jdbc.query("SELECT s.id,s.payload_ciphertext FROM loanops.financial_snapshot s JOIN loanops.financial_users u ON u.id=s.financial_user_id WHERE u.owner_ref=? ORDER BY s.created_at,s.id",(r,n)->decode(r.getObject(1,UUID.class),r.getString(2)),owner);}
 private Snapshot decode(UUID id,String body){return cipher.open("snapshot:"+id,body,Snapshot.class);}
}
