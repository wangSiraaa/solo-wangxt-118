package com.treasury.clearing.web;

import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.dto.ReceivableView;
import com.treasury.clearing.repo.ReceivableRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 原始债权只读视图（清算不回写金额，只在确认时改状态）。 */
@RestController
@RequestMapping("/api/receivables")
public class ReceivableController {

    private final ReceivableRepository repo;

    public ReceivableController(ReceivableRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<ReceivableView> list() {
        return repo.findAllByOrderByInvoiceDateAsc().stream()
                .map(ReceivableController::toView)
                .toList();
    }

    static ReceivableView toView(Receivable r) {
        return new ReceivableView(r.getId(), r.getInvoiceNo(), r.getCreditorCode(),
                r.getDebtorCode(), r.getCurrency(), r.getAmount(),
                r.getInvoiceDate().toString(), r.getAgreementCode(),
                r.isPledged(), r.isDisputed(), r.getStatus().name());
    }
}
