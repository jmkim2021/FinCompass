package com.loanops.publication.repository;

import com.loanops.publication.domain.QualityRecordType;
import com.loanops.publication.domain.QuarantineRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuarantineRecordRepository extends JpaRepository<QuarantineRecord, Long> {

    boolean existsBySourceRunIdAndRecordTypeAndRecordId(
            Long sourceRunId,
            QualityRecordType recordType,
            long recordId
    );

    long countBySourceRunId(Long sourceRunId);
}
