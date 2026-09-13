package com.loanops.publication.repository;

import com.loanops.publication.domain.DataQualityIssue;
import com.loanops.publication.domain.QualityRecordType;
import com.loanops.publication.domain.QualityRuleCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataQualityIssueRepository extends JpaRepository<DataQualityIssue, Long> {

    boolean existsBySourceRunIdAndRecordTypeAndRecordIdAndRuleCodeAndFieldName(
            Long sourceRunId,
            QualityRecordType recordType,
            long recordId,
            QualityRuleCode ruleCode,
            String fieldName
    );

    long countBySourceRunId(Long sourceRunId);

    List<DataQualityIssue> findAllBySourceRunIdOrderByIdAsc(Long sourceRunId);
}
