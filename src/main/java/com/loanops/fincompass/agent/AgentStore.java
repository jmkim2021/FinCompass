package com.loanops.fincompass.agent;

import com.loanops.api.ApiNotFoundException;
import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.personal.canonical.PersonalFinanceCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import static com.loanops.fincompass.agent.AgentModels.*;

/** Encrypted, append-only agent and tool audit history. */
public final class AgentStore {
    private final JdbcTemplate jdbc;private final PersonalFinanceCipher cipher;private final FinancialTwinStore twins;private final TransactionTemplate tx;
    public AgentStore(JdbcTemplate jdbc,PersonalFinanceCipher cipher,FinancialTwinStore twins,PlatformTransactionManager manager){this.jdbc=jdbc;this.cipher=cipher;this.twins=twins;this.tx=new TransactionTemplate(manager);}
    public RunTrace save(String owner,RunTrace run){return tx.execute(status->{
        UUID user=twins.ownerId(owner);
        jdbc.update("INSERT INTO loanops.agent_run(id,financial_user_id,started_at,completed_at,status,model_provider,model_version,financial_snapshot_id,scenario_id,product_match_run_id,query_hash,payload_ciphertext) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                run.agentRunId(),user,Timestamp.from(run.startedAt()),Timestamp.from(run.completedAt()),run.status(),run.modelProvider(),run.modelVersion(),run.financialSnapshotId(),run.scenarioId(),run.productMatchRunId(),cipher.contentHash(run.userQuery()),cipher.seal("agent-run:"+run.agentRunId(),run));
        for(ToolCall call:run.toolCalls())jdbc.update("INSERT INTO loanops.agent_tool_call(id,agent_run_id,ordinal,tool_name,started_at,completed_at,status,params_hash,result_hash,payload_ciphertext) VALUES (?,?,?,?,?,?,?,?,?,?)",
                call.toolCallId(),run.agentRunId(),call.ordinal(),call.toolName(),Timestamp.from(call.startedAt()),Timestamp.from(call.completedAt()),call.status(),call.paramsHash(),call.resultHash(),cipher.seal("agent-tool:"+call.toolCallId(),call));
        return run;
    });}
    public RunTrace get(String owner,UUID id){var rows=jdbc.query("SELECT r.id,r.payload_ciphertext FROM loanops.agent_run r JOIN loanops.financial_users u ON u.id=r.financial_user_id WHERE u.owner_ref=? AND r.id=?",(rs,n)->cipher.open("agent-run:"+rs.getObject(1,UUID.class),rs.getString(2),RunTrace.class),owner,id);if(rows.isEmpty())throw new ApiNotFoundException("Agent run not found");return rows.getFirst();}
}
