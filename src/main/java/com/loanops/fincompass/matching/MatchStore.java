package com.loanops.fincompass.matching;
import com.loanops.api.ApiNotFoundException;
import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.fincompass.simulation.ScenarioStore;
import com.loanops.personal.canonical.PersonalFinanceCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.util.*;
import static com.loanops.fincompass.matching.MatchRecords.*;
public final class MatchStore {
 private final JdbcTemplate jdbc;private final PersonalFinanceCipher cipher;private final FinancialTwinStore twins;private final ScenarioStore scenarios;private final TransactionTemplate tx;
 public MatchStore(JdbcTemplate jdbc,PersonalFinanceCipher cipher,FinancialTwinStore twins,ScenarioStore scenarios,PlatformTransactionManager manager){this.jdbc=jdbc;this.cipher=cipher;this.twins=twins;this.scenarios=scenarios;this.tx=new TransactionTemplate(manager);}
 public Run save(String owner,Run run){return tx.execute(s->{var snap=twins.get(owner,run.financialSnapshotId());if(run.scenarioId()!=null&& !scenarios.get(owner,run.scenarioId()).financialSnapshotId().equals(run.financialSnapshotId()))throw new IllegalArgumentException("Match scenario snapshot mismatch");
  jdbc.update("INSERT INTO loanops.product_match_run VALUES (?,?,?,?,?,?,?,?,?)",run.matchRunId(),snap.financialUserId(),run.financialSnapshotId(),run.scenarioId(),Timestamp.from(run.createdAt()),run.calculationRuleVersion(),run.simulationRuleVersion(),run.scoreRuleVersion(),run.ratePolicyVersion());
  jdbc.update("INSERT INTO loanops.product_match_payload VALUES (?,?,?)",run.matchRunId(),cipher.contentHash(run),cipher.seal("product-match:"+run.matchRunId(),run));
  for(var c:run.candidates()){
   var p=c.product();jdbc.update("INSERT INTO loanops.product_match_candidate(match_run_id,product_term_id,loan_product_id,term_parent_product_id,product_publication_run_id,term_publication_run_id,product_source_run_id,term_source_run_id,product_raw_snapshot_id,term_raw_snapshot_id,product_raw_hash,term_raw_hash,status,simulation_rate,score,review_rank) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",run.matchRunId(),p.productTermId(),p.loanProductId(),p.termParentProductId(),p.productPublicationRunId(),p.termPublicationRunId(),p.productSourceRunId(),p.termSourceRunId(),p.productRawSnapshotId(),p.termRawSnapshotId(),p.productRawHash(),p.termRawHash(),c.status().name(),c.simulationAnnualRate(),c.score()==null?null:c.score().total(),c.rank());
   if(c.score()!=null)for(var x:c.score().components())jdbc.update("INSERT INTO loanops.candidate_score_component VALUES (?,?,?,?,?)",run.matchRunId(),p.productTermId(),x.name(),x.rawScore(),x.weightedScore());
   jdbc.update("INSERT INTO loanops.candidate_scenario_result VALUES (?,?,?)",run.matchRunId(),p.productTermId(),cipher.seal("candidate-result:"+run.matchRunId()+":"+p.productTermId(),c));
  }return run;});}
 public Run get(String owner,UUID id){var rows=jdbc.query("SELECT r.id,p.payload_ciphertext FROM loanops.product_match_run r JOIN loanops.product_match_payload p ON p.match_run_id=r.id JOIN loanops.financial_users u ON u.id=r.financial_user_id WHERE u.owner_ref=? AND r.id=?",(rs,n)->decode(rs.getObject(1,UUID.class),rs.getString(2)),owner,id);if(rows.isEmpty())throw new ApiNotFoundException("Product match run not found");return rows.getFirst();}
 public List<Run> list(String owner){return jdbc.query("SELECT r.id,p.payload_ciphertext FROM loanops.product_match_run r JOIN loanops.product_match_payload p ON p.match_run_id=r.id JOIN loanops.financial_users u ON u.id=r.financial_user_id WHERE u.owner_ref=? ORDER BY r.created_at DESC,r.id DESC LIMIT 100",(rs,n)->decode(rs.getObject(1,UUID.class),rs.getString(2)),owner);}
 private Run decode(UUID id,String payload){return cipher.open("product-match:"+id,payload,Run.class);}
}
