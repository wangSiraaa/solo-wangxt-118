package com.treasury.clearing.service;

import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class BatchQueryService {

    private final ClearingBatchRepository batchRepo;
    private final ExcludedClaimRepository excludedRepo;

    public BatchQueryService(ClearingBatchRepository batchRepo,
                             ExcludedClaimRepository excludedRepo) {
        this.batchRepo = batchRepo;
        this.excludedRepo = excludedRepo;
    }

    @Transactional(readOnly = true)
    public List<ClearingBatch> list() {
        return batchRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public ClearingBatch get(String id) {
        return batchRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("批次不存在: " + id));
    }
}
