package com.loanops.fincompass.simulation;

import com.loanops.api.ApiNotFoundException;
import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.personal.canonical.PersonalFinanceCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import static com.loanops.fincompass.simulation.ScenarioRecords.*;

/** Append-only encrypted simulation history. No latest canonical/product values are reread on replay. */
public final class ScenarioStore {
    private final JdbcTemplate jdbc;
    private final PersonalFinanceCipher cipher;
    private final FinancialTwinStore twins;
    private final TransactionTemplate transaction;
    public ScenarioStore(JdbcTemplate jdbc, PersonalFinanceCipher cipher, FinancialTwinStore twins,
                         PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.cipher = cipher; this.twins = twins;
        this.transaction = new TransactionTemplate(manager);
    }
    public Scenario save(String owner, Scenario scenario) {
        return transaction.execute(status -> {
            var snapshot = twins.get(owner, scenario.financialSnapshotId());
            if (scenario.parentScenarioId() != null) {
                var parent = get(owner, scenario.parentScenarioId());
                if (!parent.financialSnapshotId().equals(scenario.financialSnapshotId()))
                    throw new IllegalArgumentException("Scenario parent snapshot mismatch");
            }
            for (UUID reference : scenario.comparisonScenarioIds()) {
                if (!get(owner, reference).financialSnapshotId().equals(scenario.financialSnapshotId()))
                    throw new IllegalArgumentException("Comparison snapshot mismatch");
            }
            jdbc.update("""
                    INSERT INTO loanops.scenario(id,financial_user_id,financial_snapshot_id,kind,parent_scenario_id,
                    calculation_rule_version,simulation_rule_version,created_at) VALUES (?,?,?,?,?,?,?,?)
                    """, scenario.scenarioId(), snapshot.financialUserId(), scenario.financialSnapshotId(), scenario.kind(),
                    scenario.parentScenarioId(), scenario.calculationRuleVersion(), scenario.simulationRuleVersion(),
                    Timestamp.from(scenario.createdAt()));
            Map<String,Object> assumptions = new LinkedHashMap<>();
            assumptions.put("financialSnapshotId", scenario.financialSnapshotId());
            assumptions.put("loan", scenario.loanInput());
            assumptions.put("home", scenario.homeInput());
            assumptions.put("shocks", scenario.stressResults().stream().map(SimulationModels.StressCase::shock).toList());
            assumptions.put("comparisonScenarioIds", scenario.comparisonScenarioIds());
            assumptions.put("calculationRuleVersion", scenario.calculationRuleVersion());
            assumptions.put("simulationRuleVersion", scenario.simulationRuleVersion());
            jdbc.update("INSERT INTO loanops.scenario_assumption(scenario_id,payload_ciphertext) VALUES (?,?)",
                    scenario.scenarioId(), cipher.seal("scenario-assumption:" + scenario.scenarioId(), assumptions));
            jdbc.update("INSERT INTO loanops.scenario_result(scenario_id,payload_hash,payload_ciphertext) VALUES (?,?,?)",
                    scenario.scenarioId(), cipher.contentHash(scenario), cipher.seal("scenario-result:" + scenario.scenarioId(), scenario));
            return scenario;
        });
    }
    public Scenario get(String owner, UUID id) {
        if (id == null) throw new IllegalArgumentException("Scenario ID required");
        var found = jdbc.query("""
                SELECT s.id,r.payload_ciphertext FROM loanops.scenario s
                JOIN loanops.scenario_result r ON r.scenario_id=s.id
                JOIN loanops.financial_users u ON u.id=s.financial_user_id
                WHERE u.owner_ref=? AND s.id=?
                """, (row,n) -> decode(row.getObject(1,UUID.class),row.getString(2)), owner,id);
        if (found.isEmpty()) throw new ApiNotFoundException("Scenario not found");
        return found.getFirst();
    }
    /** Bounded newest-first list; full records remain addressable by immutable ID. */
    public List<Scenario> list(String owner) {
        return jdbc.query("""
                SELECT s.id,r.payload_ciphertext FROM loanops.scenario s
                JOIN loanops.scenario_result r ON r.scenario_id=s.id
                JOIN loanops.financial_users u ON u.id=s.financial_user_id
                WHERE u.owner_ref=? ORDER BY s.created_at DESC,s.id DESC LIMIT 100
                """, (row,n) -> decode(row.getObject(1,UUID.class),row.getString(2)),owner);
    }
    private Scenario decode(UUID id, String payload) { return cipher.open("scenario-result:" + id,payload,Scenario.class); }
}
