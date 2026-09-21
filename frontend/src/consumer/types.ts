export type Availability = 'AVAILABLE' | 'INSUFFICIENT_DATA' | 'DEGRADED'

export interface Metric {
  value: number | null
  availability: Availability
  unit: string
  warnings: string[]
}

export interface SourceRef {
  recordId: string
  provenanceId: string
  validationRunId: string
  sourceType: string
  sourceMode: string
  asOf: string
  contentHash: string
  purpose: string
}

export interface FinancialSnapshot {
  financialSnapshotId: string
  asOf: string
  status: string
  calculationRuleVersion: string
  inputFingerprint: string
  input: { sourceRefs: SourceRef[]; warnings: string[] }
  result: { metrics: Record<string, Metric>; warnings: string[]; limitations: string[]; verifiedAnnualIncome: number | null }
  currentAssessment: { freshness: string; warnings: string[] }
}

export interface HomeResult {
  before: Record<string, Metric>
  after: Record<string, Metric>
  mortgage: { regularMonthlyPayment: number; totalInterest: number; postLoanDebtBurden: number }
  warnings: string[]
  limitations: string[]
}

export interface Scenario {
  scenarioId: string
  financialSnapshotId: string
  kind: 'HOME' | 'STRESS' | 'COMPARISON' | 'LOAN'
  createdAt: string
  simulationRuleVersion: string
  calculationRuleVersion: string
  homeResult: HomeResult | null
  stressResults: Array<{ shock: { label: string }; result: HomeResult }>
  comparisonResults: HomeResult[]
  warnings: string[]
}

export interface ProductEvaluation {
  status: 'MATCHED_FOR_REVIEW' | 'FILTERED_OUT' | 'INSUFFICIENT_DATA'
  rank: number | null
  simulationAnnualRate: number | null
  score: { total: number; components: Array<{ name: string; weightedScore: number }> } | null
  product: { institution: string; productName: string; minRatePercent: number; maxRatePercent: number; sourceObservedAt: string | null; sourceType: string; sourceMode: string }
  loanResult: { regularMonthlyPayment: number; totalInterest: number } | null
  homeResult: HomeResult | null
  warnings: string[]
  limitations: string[]
}

export interface MatchRun {
  matchRunId: string
  financialSnapshotId: string
  scenarioId: string | null
  createdAt: string
  scoreRuleVersion: string
  candidates: ProductEvaluation[]
  warnings: string[]
  limitations: string[]
}

export interface Opportunity {
  opportunityEventId: string
  type: string
  detectedAt: string
  scenarioId: string | null
  productMatchRunId: string | null
  ruleVersion: string
  status: string
  evidence: { metric: string; actual: number | null; threshold: number | null; sourceId: string }
  warnings: string[]
}

export interface AgentResponse {
  agentRunId: string
  status: string
  answer: string
  financialSnapshotId: string | null
  scenarioId: string | null
  productMatchRunId: string | null
  missingInputs: string[]
  assumptions: string[]
  warnings: string[]
  limitations: string[]
}

export interface AgentTrace {
  agentRunId: string
  status: string
  modelProvider: string
  modelVersion: string
  financialSnapshotId: string | null
  scenarioId: string | null
  productMatchRunId: string | null
  ruleVersions: Record<string, string>
  assumptions: string[]
  warnings: string[]
  limitations: string[]
  toolCalls: Array<{ ordinal: number; toolName: string; status: string; paramsHash: string; resultHash: string | null }>
  finalExplanation: string
}
