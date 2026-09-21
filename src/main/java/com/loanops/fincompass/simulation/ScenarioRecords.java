package com.loanops.fincompass.simulation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.loanops.fincompass.simulation.SimulationModels.*;

/** API and immutable storage contracts; monetary values remain typed BigDecimal in engine records. */
public final class ScenarioRecords {
    private ScenarioRecords() {}
    public record LoanRequest(UUID financialSnapshotId, LoanInput loan) {}
    public record StressRequest(List<Shock> shocks) {
        public StressRequest { if (shocks != null) shocks = List.copyOf(shocks); }
    }
    public record ComparisonRequest(List<UUID> scenarioIds) {
        public ComparisonRequest { if (scenarioIds != null) scenarioIds = List.copyOf(scenarioIds); }
    }
    public record Scenario(UUID scenarioId, UUID financialSnapshotId, Instant createdAt,
                           String calculationRuleVersion, String simulationRuleVersion, String kind,
                           UUID parentScenarioId, LoanInput loanInput, HomeInput homeInput,
                           LoanResult loanResult, HomeResult homeResult, List<StressCase> stressResults,
                           List<UUID> comparisonScenarioIds, List<HomeResult> comparisonResults,
                           List<String> warnings) {
        public Scenario {
            stressResults = List.copyOf(stressResults);
            comparisonScenarioIds = List.copyOf(comparisonScenarioIds);
            comparisonResults = List.copyOf(comparisonResults);
            warnings = List.copyOf(warnings);
        }
        @Override public String toString() { return "Scenario[kind=" + kind + ", financial data redacted]"; }
    }
}
