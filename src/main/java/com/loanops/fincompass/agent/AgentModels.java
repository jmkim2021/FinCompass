package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class AgentModels {
    private AgentModels() {}

    public enum Intent { HOME_PURCHASE, FINANCIAL_OVERVIEW, PRODUCT_REVIEW, OPPORTUNITIES, UNKNOWN }
    public enum ToolName {
        GET_FINANCIAL_SNAPSHOT("get_financial_snapshot"),
        GET_CASHFLOW_ANALYSIS("get_cashflow_analysis"),
        GET_DEBT_ANALYSIS("get_debt_analysis"),
        ESTIMATE_LOAN_CAPACITY("estimate_loan_capacity"),
        SEARCH_LOAN_PRODUCTS("search_loan_products"),
        SIMULATE_HOME_PURCHASE("simulate_home_purchase"),
        RUN_STRESS_TEST("run_stress_test"),
        COMPARE_SCENARIOS("compare_scenarios"),
        MATCH_LOAN_PRODUCTS("match_loan_products"),
        SIMULATE_PRODUCT_CANDIDATE("simulate_product_candidate"),
        COMPARE_PRODUCT_CANDIDATES("compare_product_candidates"),
        GET_OPPORTUNITY_EVENTS("get_opportunity_events"),
        GET_CALCULATION_TRACE("get_calculation_trace"),
        GET_PRODUCT_MATCH_TRACE("get_product_match_trace");
        private final String externalName;
        ToolName(String externalName) { this.externalName=externalName; }
        public String externalName() { return externalName; }
    }

    public record ChatRequest(String question, BigDecimal housePrice, BigDecimal cashContribution,
                              BigDecimal mortgageAmount, BigDecimal annualInterestRate,
                              Integer termYears, BigDecimal maxMonthlyPayment) {}
    public record Plan(Intent intent,List<ToolName> tools) {
        public Plan { tools=List.copyOf(tools); }
    }
    public record DraftContext(String suggestedAnswer,Intent intent,List<String> warnings,List<String> limitations) {
        public DraftContext { warnings=List.copyOf(warnings);limitations=List.copyOf(limitations); }
    }
    public record ToolCall(UUID toolCallId,int ordinal,String toolName,Instant startedAt,Instant completedAt,
                           String status,JsonNode params,JsonNode result,String paramsHash,String resultHash,String errorCode) {}
    public record RunTrace(UUID agentRunId,Instant startedAt,Instant completedAt,String status,
                           String modelProvider,String modelVersion,String userQuery,Intent intent,
                           UUID financialSnapshotId,UUID scenarioId,UUID productMatchRunId,
                           Map<String,String> ruleVersions,List<String> assumptions,List<String> warnings,
                           List<String> limitations,Set<String> allowedNumericEvidence,List<ToolCall> toolCalls,
                           String finalExplanation,boolean groundingFallbackUsed) {
        public RunTrace {
            ruleVersions=Map.copyOf(ruleVersions);assumptions=List.copyOf(assumptions);
            warnings=List.copyOf(warnings);limitations=List.copyOf(limitations);
            allowedNumericEvidence=Set.copyOf(allowedNumericEvidence);toolCalls=List.copyOf(toolCalls);
        }
        @Override public String toString(){return "AgentRunTrace[financial data redacted]";}
    }
    public record ChatResponse(UUID agentRunId,String status,String answer,Intent intent,
                               UUID financialSnapshotId,UUID scenarioId,UUID productMatchRunId,
                               List<String> missingInputs,List<String> assumptions,List<String> warnings,List<String> limitations) {
        public ChatResponse {
            missingInputs=List.copyOf(missingInputs);assumptions=List.copyOf(assumptions);
            warnings=List.copyOf(warnings);limitations=List.copyOf(limitations);
        }
    }
}
