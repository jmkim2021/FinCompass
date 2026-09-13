export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface SourceOverview {
  sourceCode: string
  latestSourceRunId: number | null
  ingestionStatus: string | null
  latestPublicationSourceRunId: number | null
  publicationStatus: string | null
  qualityPolicyVersion: string | null
  publishedAt: string | null
}

export interface Overview {
  sourceCount: number
  sources: SourceOverview[]
  servingLoanProductCount: number
  servingProductTermCount: number
  servingSeoulObservationCount: number
  currentQuarantineCount: number
  totalQualityIssueCount: number
  recentDataChangeCount: number
}

export interface SourceRun {
  id: number
  sourceCode: string
  status: string
  httpStatus: number | null
  errorType: string | null
  errorMessage: string | null
  startedAt: string
  endedAt: string | null
  rawSnapshotCount: number
}

export interface RawSnapshotMetadata {
  id: number
  contentType: string
  payloadHash: string
  fetchedAt: string
  payloadSize: number
}

export interface Publication {
  id: number
  sourceRunId: number
  sourceCode: string
  status: string
  qualityPolicyVersion: string
  createdAt: string
  completedAt: string
  issueCount: number
  quarantineCount: number
  changeCount: number
}

export interface QualityIssue {
  id: number
  sourceRunId: number
  rawSnapshotId: number
  recordType: string
  recordId: number
  ruleCode: string
  fieldName: string | null
  observedValue: string | null
  message: string
  createdAt: string
}

export interface QuarantineRecord {
  id: number
  sourceRunId: number
  rawSnapshotId: number
  recordType: string
  recordId: number
  createdAt: string
}

export interface DataChange {
  id: number
  sourceRunId: number
  recordType: string
  stableRecordIdentity: string
  changeType: string
  previousRecordId: number | null
  currentRecordId: number
  changedFields: string | null
  createdAt: string
}

export interface SourceRunDetail {
  sourceRun: SourceRun
  dataSource: {
    id: number
    code: string
    name: string
    responseFormat: string
    active: boolean
  }
  rawSnapshots: RawSnapshotMetadata[]
  publication: Publication | null
  qualityIssueCount: number
  quarantineCount: number
  normalizedRecordCounts: {
    loanProducts: number
    productTerms: number
    loanRateObservations: number
  }
}

export interface PublicationDetail {
  publication: Publication
  qualityIssues: QualityIssue[]
  quarantineRecords: QuarantineRecord[]
  dataChanges: DataChange[]
}

export interface ServingLoanProduct {
  id: number
  sourceInstitutionCode: string
  sourceInstitutionName: string
  canonicalInstitutionName: string
  sourceProductId: string
  productName: string
  disclosureMonth: string
  disclosureStartDate: string | null
  disclosureEndDate: string | null
  sourceSubmittedAt: string | null
  currentNormalizedRecordId: number
  sourceRunId: number
  rawSnapshotId: number
  termCount: number
  updatedAt: string
}

export interface ServingProductTerm {
  id: number
  currentNormalizedRecordId: number
  disclosureMonth: string
  mortgageTypeCode: string
  mortgageTypeName: string
  repaymentTypeCode: string
  repaymentTypeName: string
  lendingRateTypeCode: string
  lendingRateTypeName: string
  minimumRate: number
  averageRate: number | null
  maximumRate: number
  sourceRunId: number
  rawSnapshotId: number
  updatedAt: string
}

export interface ServingLoanProductDetail {
  product: ServingLoanProduct
  terms: ServingProductTerm[]
}

export interface ServingLoanRateObservation {
  id: number
  sourceInstitutionCode: string
  sourceInstitutionName: string
  canonicalInstitutionName: string | null
  mappingStatus: string
  referenceStartDate: string
  referenceEndDate: string
  loanCount: number
  minimumLoanRate: number
  averageLoanRate: number
  maximumLoanRate: number
  minimumLoanAleRate: number
  averageLoanAleRate: number
  maximumLoanAleRate: number
  currentNormalizedRecordId: number
  sourceRunId: number
  rawSnapshotId: number
  updatedAt: string
}
