package com.loanops.fincompass.opportunity;
import java.math.BigDecimal;import java.time.Instant;import java.util.*;
public final class OpportunityModels {private OpportunityModels(){}
 public enum Type{RATE_REVIEW,REFINANCING_REVIEW,LIQUIDITY_RISK,DEBT_BURDEN_WARNING}
 public record Policy(BigDecimal emergencyFundMonths,BigDecimal debtBurden,BigDecimal refinancingRateGap,String ruleVersion){public static Policy defaults(){return new Policy(new BigDecimal("3"),new BigDecimal(".40"),new BigDecimal(".01"),"OPPORTUNITY_V1");}}
 public record Evidence(String metric,BigDecimal actual,BigDecimal threshold,String unit,List<Long> productTermIds){public Evidence{productTermIds=List.copyOf(productTermIds);}}
 public record Draft(Type type,UUID financialSnapshotId,UUID scenarioId,UUID productMatchRunId,Evidence evidence,List<String>warnings){public Draft{warnings=List.copyOf(warnings);}}
 public record Event(UUID opportunityEventId,Type type,Instant detectedAt,UUID financialSnapshotId,UUID scenarioId,UUID productMatchRunId,String ruleVersion,String status,Evidence evidence,List<String>warnings){public Event{warnings=List.copyOf(warnings);}}
}
