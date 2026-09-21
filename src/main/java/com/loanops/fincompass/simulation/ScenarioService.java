package com.loanops.fincompass.simulation;

import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.fincompass.twin.TwinModels.Snapshot;
import java.time.Clock;
import java.util.*;
import static com.loanops.fincompass.simulation.ScenarioRecords.*;
import static com.loanops.fincompass.simulation.SimulationModels.*;

/** Thin orchestration around the one deterministic engine; persisted IDs never trigger recalculation. */
public final class ScenarioService {
    private final ScenarioStore store;
    private final FinancialTwinStore twins;
    private final SimulationEngine engine;
    private final Clock clock;
    public ScenarioService(ScenarioStore store, FinancialTwinStore twins, SimulationEngine engine, Clock clock) {
        this.store=store;this.twins=twins;this.engine=engine;this.clock=clock;
    }
    public Scenario createLoan(String owner, UUID snapshotId, LoanInput input) {
        if (snapshotId == null || input == null) throw new IllegalArgumentException("Snapshot and loan inputs required");
        Snapshot snapshot = twins.get(owner,snapshotId);
        LoanResult result = engine.loan(input,new LoanContext(snapshot.result().monthlyDebtService(),snapshot.result().verifiedAnnualIncome()));
        return store.save(owner,new Scenario(UUID.randomUUID(),snapshotId,clock.instant(),snapshot.calculationRuleVersion(),
                SimulationEngine.RULE_VERSION,"LOAN",null,input,null,result,null,List.of(),List.of(),List.of(),
                warnings(snapshot.result().warnings(),result.warnings())));
    }
    public Scenario createHome(String owner, HomeInput input) {
        if (input == null || input.financialSnapshotId() == null) throw new IllegalArgumentException("Snapshot and home inputs required");
        Snapshot snapshot=twins.get(owner,input.financialSnapshotId());
        HomeResult result=engine.home(snapshot,input);
        return store.save(owner,new Scenario(UUID.randomUUID(),snapshot.financialSnapshotId(),clock.instant(),snapshot.calculationRuleVersion(),
                SimulationEngine.RULE_VERSION,"HOME",null,null,input,null,result,List.of(),List.of(),List.of(),
                warnings(snapshot.result().warnings(),result.warnings())));
    }
    public Scenario stress(String owner, UUID parentId, List<Shock> requested) {
        Scenario parent=store.get(owner,parentId);
        if (!"HOME".equals(parent.kind()) || parent.homeInput()==null) throw new IllegalArgumentException("Stress parent must be a home scenario");
        Snapshot snapshot=twins.get(owner,parent.financialSnapshotId());
        if(requested!=null&&requested.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Stress cases must not be null");
        List<Shock> shocks=requested==null?SimulationEngine.defaultShocks():List.copyOf(requested);
        if (shocks.isEmpty() || shocks.size()>20) throw new IllegalArgumentException("One to twenty stress cases required");
        List<StressCase> results=engine.stress(snapshot,parent.homeInput(),shocks);
        List<String> warnings=new ArrayList<>(parent.warnings());
        results.forEach(result->warnings.addAll(result.result().warnings()));
        return store.save(owner,new Scenario(UUID.randomUUID(),snapshot.financialSnapshotId(),clock.instant(),snapshot.calculationRuleVersion(),
                SimulationEngine.RULE_VERSION,"STRESS",parentId,null,parent.homeInput(),null,parent.homeResult(),results,List.of(),List.of(),
                warnings.stream().distinct().toList()));
    }
    public Scenario compare(String owner, List<UUID> scenarioIds) {
        if (scenarioIds==null || scenarioIds.size()<2 || scenarioIds.size()>10 || scenarioIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(scenarioIds).size()!=scenarioIds.size()) throw new IllegalArgumentException("Two to ten distinct scenario IDs required");
        List<Scenario> scenarios=scenarioIds.stream().map(id->store.get(owner,id)).toList();
        Scenario first=scenarios.getFirst();
        for (Scenario scenario:scenarios) {
            if (!"HOME".equals(scenario.kind()) || scenario.homeResult()==null
                    || !scenario.financialSnapshotId().equals(first.financialSnapshotId())
                    || !scenario.calculationRuleVersion().equals(first.calculationRuleVersion())
                    || !scenario.simulationRuleVersion().equals(first.simulationRuleVersion()))
                throw new IllegalArgumentException("Comparison requires home scenarios from the same snapshot and rule versions");
        }
        return store.save(owner,new Scenario(UUID.randomUUID(),first.financialSnapshotId(),clock.instant(),first.calculationRuleVersion(),
                first.simulationRuleVersion(),"COMPARISON",null,null,null,null,null,List.of(),scenarioIds,
                scenarios.stream().map(Scenario::homeResult).toList(),scenarios.stream().flatMap(value->value.warnings().stream()).distinct().toList()));
    }
    public Scenario get(String owner, UUID id) { return store.get(owner,id); }
    public List<Scenario> list(String owner) { return store.list(owner); }
    private static List<String> warnings(List<String> first,List<String> second) {
        return java.util.stream.Stream.concat(first.stream(),second.stream()).distinct().toList();
    }
}
