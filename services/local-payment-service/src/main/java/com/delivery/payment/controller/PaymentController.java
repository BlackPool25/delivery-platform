package com.delivery.payment.controller;

import com.delivery.payment.dto.AccountResponse;
import com.delivery.payment.dto.CapturePaymentRequest;
import com.delivery.payment.dto.CreateAccountRequest;
import com.delivery.payment.dto.HoldPaymentRequest;
import com.delivery.payment.dto.PaymentTransactionResponse;
import com.delivery.payment.dto.RefundPaymentRequest;
import com.delivery.payment.entity.JournalEntry;
import com.delivery.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/v1/payments", "/api/v1/payments"})
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = paymentService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/accounts/{userId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID userId) {
        AccountResponse response = paymentService.getAccountByUserId(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/hold")
    public ResponseEntity<PaymentTransactionResponse> holdPayment(@Valid @RequestBody HoldPaymentRequest request) {
        PaymentTransactionResponse response = paymentService.holdPayment(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/capture")
    public ResponseEntity<PaymentTransactionResponse> capturePayment(@Valid @RequestBody CapturePaymentRequest request) {
        PaymentTransactionResponse response = paymentService.capturePayment(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refund")
    public ResponseEntity<PaymentTransactionResponse> refundPayment(@Valid @RequestBody RefundPaymentRequest request) {
        PaymentTransactionResponse response = paymentService.refundPayment(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions/{orderId}")
    public ResponseEntity<List<JournalEntry>> getTransactions(@PathVariable UUID orderId) {
        List<JournalEntry> transactions = paymentService.getOrderTransactions(orderId);
        return ResponseEntity.ok(transactions);
    }
}
