package com.loanops.source.service;

import com.loanops.source.client.RawSourceResponse;
import com.loanops.source.domain.DataSource;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.RawSnapshot;
import com.loanops.source.domain.SourceRun;
import com.loanops.source.repository.DataSourceRepository;
import com.loanops.source.repository.RawSnapshotRepository;
import com.loanops.source.repository.SourceRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class SourceRunLifecycleService {

    private final DataSourceRepository dataSourceRepository;
    private final SourceRunRepository sourceRunRepository;
    private final RawSnapshotRepository rawSnapshotRepository;
    private final PayloadHasher payloadHasher;

    public SourceRunLifecycleService(
            DataSourceRepository dataSourceRepository,
            SourceRunRepository sourceRunRepository,
            RawSnapshotRepository rawSnapshotRepository,
            PayloadHasher payloadHasher
    ) {
        this.dataSourceRepository = dataSourceRepository;
        this.sourceRunRepository = sourceRunRepository;
        this.rawSnapshotRepository = rawSnapshotRepository;
        this.payloadHasher = payloadHasher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(DataSourceCode sourceCode) {
        DataSource dataSource = dataSourceRepository.findByCodeAndActiveTrue(sourceCode)
                .orElseThrow(() -> new IllegalStateException("Active data source not found: " + sourceCode));
        return sourceRunRepository.saveAndFlush(SourceRun.start(dataSource, Instant.now())).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String preserveRawSnapshot(long sourceRunId, RawSourceResponse response) {
        SourceRun sourceRun = findRun(sourceRunId);
        Instant fetchedAt = Instant.now();
        String payloadHash = payloadHasher.sha256(response.rawPayload());

        rawSnapshotRepository.saveAndFlush(RawSnapshot.create(
                sourceRun,
                response.contentType(),
                response.rawPayload(),
                payloadHash,
                fetchedAt,
                fetchedAt
        ));
        return payloadHash;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeSuccess(long sourceRunId, int httpStatus) {
        SourceRun sourceRun = findRun(sourceRunId);
        sourceRun.markSuccess(httpStatus, Instant.now());
        sourceRunRepository.saveAndFlush(sourceRun);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeFailure(long sourceRunId, SourceFailure failure) {
        SourceRun sourceRun = findRun(sourceRunId);
        sourceRun.markFailed(
                failure.errorType(),
                failure.errorMessage(),
                failure.httpStatus(),
                Instant.now()
        );
        sourceRunRepository.saveAndFlush(sourceRun);
    }

    private SourceRun findRun(long sourceRunId) {
        return sourceRunRepository.findById(sourceRunId)
                .orElseThrow(() -> new IllegalStateException("Source run not found: " + sourceRunId));
    }

}
