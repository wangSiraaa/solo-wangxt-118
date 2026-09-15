package com.treasury.clearing.web;

import com.treasury.clearing.domain.FxRate;
import com.treasury.clearing.dto.FxRateUpsertRequest;
import com.treasury.clearing.dto.FxRateView;
import com.treasury.clearing.repo.FxRateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 汇率维护：资金部手工录入的内部记账汇率，带明确时点与来源说明。
 * 不连接任何外部行情或银行系统。
 */
@RestController
@RequestMapping("/api/fx-rates")
public class FxRateController {

    private final FxRateRepository repo;

    public FxRateController(FxRateRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<FxRateView> list() {
        return repo.findAll().stream()
                .map(fx -> new FxRateView(fx.getId(), fx.getFromCurrency(), fx.getToCurrency(),
                        fx.getRate(), fx.getRateTime().toString(), fx.getSource()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<FxRateView> upsert(@RequestBody FxRateUpsertRequest req) {
        if (req.fromCurrency() == null || req.toCurrency() == null || req.rate() == null) {
            throw new IllegalArgumentException("fromCurrency/toCurrency/rate 必填");
        }
        if (req.rate().signum() <= 0) {
            throw new IllegalArgumentException("汇率必须为正数");
        }
        Instant time = req.rateTime() != null ? req.rateTime() : Instant.now();
        FxRate saved = repo.save(new FxRate("FX-" + UUID.randomUUID().toString().substring(0, 8),
                req.fromCurrency(), req.toCurrency(), req.rate(), time,
                req.source() != null ? req.source() : "资金部手工记账汇率"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FxRateView(saved.getId(), saved.getFromCurrency(),
                        saved.getToCurrency(), saved.getRate(),
                        saved.getRateTime().toString(), saved.getSource()));
    }
}
