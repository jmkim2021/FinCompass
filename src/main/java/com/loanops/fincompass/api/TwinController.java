package com.loanops.fincompass.api;
import com.loanops.fincompass.twin.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="fincompass.demo.enabled",havingValue="true")
public class TwinController {
 public static final String DEMO_OWNER="golden-homebuyer";
 private final FinancialTwinStore store;private final java.time.Clock clock;
 public TwinController(FinancialTwinStore store,java.time.Clock clock){this.store=store;this.clock=clock;}
 public record Assessment(UUID latestAttemptId,String latestAttemptStatus,boolean newerIncomplete,String freshness,java.util.List<String> warnings){}
 public record CurrentSnapshot(@com.fasterxml.jackson.annotation.JsonUnwrapped TwinModels.Snapshot snapshot,Assessment currentAssessment){}
 @GetMapping({"/financial-snapshots/latest","/analytics/cashflow","/analytics/debt"}) public CurrentSnapshot latest(){var snapshot=store.latest(DEMO_OWNER);var attempt=store.latestAttempt(DEMO_OWNER).orElse(snapshot);boolean incomplete=!attempt.financialSnapshotId().equals(snapshot.financialSnapshotId())&&!attempt.status().equals("COMPLETE");boolean stale=java.time.Duration.between(snapshot.asOf(),clock.instant()).compareTo(java.time.Duration.ofHours(24))>0;var warnings=new java.util.ArrayList<String>();if(incomplete)warnings.add("LATEST_ATTEMPT_INCOMPLETE_USING_PRIOR_SNAPSHOT");if(stale)warnings.add("STALE_SNAPSHOT");return new CurrentSnapshot(snapshot,new Assessment(attempt.financialSnapshotId(),attempt.status(),incomplete,stale?"STALE":"FRESH",warnings));}
 @GetMapping("/financial-snapshots/{id}") public TwinModels.Snapshot get(@PathVariable UUID id){return store.get(DEMO_OWNER,id);}
}
