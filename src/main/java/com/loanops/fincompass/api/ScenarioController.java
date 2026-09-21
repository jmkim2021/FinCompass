package com.loanops.fincompass.api;

import com.loanops.fincompass.simulation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
import static com.loanops.fincompass.simulation.ScenarioRecords.*;
import static com.loanops.fincompass.simulation.SimulationModels.*;
import static com.loanops.fincompass.api.TwinController.DEMO_OWNER;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="fincompass.demo.enabled",havingValue="true")
public class ScenarioController {
    private final ScenarioService scenarios;
    private final TargetScenarioSearch targetSearch;
    public ScenarioController(ScenarioService scenarios,TargetScenarioSearch targetSearch) { this.scenarios=scenarios;this.targetSearch=targetSearch; }
    @PostMapping("/scenarios/loan") public Scenario loan(@RequestBody LoanRequest request) {
        if(request==null)throw new IllegalArgumentException("Loan request required");
        return scenarios.createLoan(DEMO_OWNER,request.financialSnapshotId(),request.loan());
    }
    @PostMapping("/scenarios/home-purchase") public Scenario home(@RequestBody HomeInput request) {
        return scenarios.createHome(DEMO_OWNER,request);
    }
    @PostMapping("/scenarios/home-purchase/target-search") public TargetScenarioSearch.Result targetSearch(@RequestBody TargetScenarioSearch.Request request) {
        return targetSearch.search(DEMO_OWNER,request);
    }
    @PostMapping("/scenarios/{id}/stress-test") public Scenario stress(@PathVariable UUID id,@RequestBody(required=false) StressRequest request) {
        return scenarios.stress(DEMO_OWNER,id,request==null?null:request.shocks());
    }
    @PostMapping("/scenario-comparisons") public Scenario compare(@RequestBody ComparisonRequest request) {
        if(request==null)throw new IllegalArgumentException("Comparison request required");
        return scenarios.compare(DEMO_OWNER,request.scenarioIds());
    }
    @GetMapping("/scenarios/{id}") public Scenario get(@PathVariable UUID id) { return scenarios.get(DEMO_OWNER,id); }
    @GetMapping("/scenarios") public List<Scenario> list() { return scenarios.list(DEMO_OWNER); }
}
