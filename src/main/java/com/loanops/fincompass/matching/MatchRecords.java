package com.loanops.fincompass.matching;
import java.time.Instant;
import java.util.*;
import static com.loanops.fincompass.matching.MatchingModels.*;
public final class MatchRecords {
 private MatchRecords(){}
 public record MatchRequest(UUID scenarioId,java.math.BigDecimal requiredLoanAmount,java.math.BigDecimal maxMonthlyPayment,
                            Integer preferredTermYears,List<String> repaymentPreferences,Integer topK){
  public MatchRequest{repaymentPreferences=repaymentPreferences==null?List.of():List.copyOf(repaymentPreferences);}
 }
 public record Run(UUID matchRunId,UUID financialSnapshotId,UUID scenarioId,Instant createdAt,
                   String calculationRuleVersion,String simulationRuleVersion,String scoreRuleVersion,String ratePolicyVersion,
                   Request request,Request effectiveRequest,List<Evaluation> candidates,List<String>warnings,List<String>limitations){
  public Run{candidates=List.copyOf(candidates);warnings=List.copyOf(warnings);limitations=List.copyOf(limitations);}
  @Override public String toString(){return "ProductMatchRun[financial data redacted]";}
 }
}
