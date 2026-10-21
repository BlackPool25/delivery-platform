package com.delivery.payment;

import com.delivery.payment.dto.AccountResponse;
import com.delivery.payment.dto.CapturePaymentRequest;
import com.delivery.payment.dto.CreateAccountRequest;
import com.delivery.payment.dto.HoldPaymentRequest;
import com.delivery.payment.dto.PaymentTransactionResponse;
import com.delivery.payment.dto.RefundPaymentRequest;
import com.delivery.payment.entity.JournalEntry;
import com.delivery.payment.exception.InsufficientFundsException;
import com.delivery.payment.filter.ChaosSimulationFilter;
import com.delivery.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaymentVerificationGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentService paymentService;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static UUID destinationAccountId;

    @Test
    @Order(1)
    @DisplayName("Verification Gate Step 1: Create a ledger account with $100.00 initial balance")
    void testCreateLedgerAccountWithInitialBalance() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                USER_ID,
                new BigDecimal("100.00"),
                "USD"
        );

        mockMvc.perform(post("/v1/payments/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.balance").value(100.00))
                .andExpect(jsonPath("$.heldBalance").value(0.00))
                .andExpect(jsonPath("$.availableBalance").value(100.00))
                .andExpect(jsonPath("$.currency").value("USD"));

        // Also create a merchant account to test destination settlement during capture
        CreateAccountRequest merchantReq = new CreateAccountRequest(
                MERCHANT_USER_ID,
                new BigDecimal("0.00"),
                "USD"
        );
        String merchantResp = mockMvc.perform(post("/v1/payments/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(merchantReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        AccountResponse merchantAcc = objectMapper.readValue(merchantResp, AccountResponse.class);
        destinationAccountId = merchantAcc.id();
        assertThat(destinationAccountId).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Verification Gate Step 2: Execute a $25.00 HOLD against ledger account")
    void testExecuteHoldPayment() throws Exception {
        HoldPaymentRequest holdReq = new HoldPaymentRequest(
                ORDER_ID,
                USER_ID,
                new BigDecimal("25.00"),
                "USD",
                destinationAccountId,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/v1/payments/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(holdReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.entryType").value("HOLD"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.totalBalance").value(100.00))
                .andExpect(jsonPath("$.heldBalance").value(25.00))
                .andExpect(jsonPath("$.availableBalance").value(75.00));

        AccountResponse account = paymentService.getAccountByUserId(USER_ID);
        assertThat(account.balance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(account.heldBalance()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("75.00"));
    }

    @Test
    @Order(3)
    @DisplayName("Verification Gate Step 3: Test overdraw failure (attempting to hold more than available balance)")
    void testOverdrawFailure() throws Exception {
        // Available balance is $75.00. Attempting to hold $80.00 must fail with 402 PAYMENT_REQUIRED.
        HoldPaymentRequest overdrawReq = new HoldPaymentRequest(
                UUID.randomUUID(),
                USER_ID,
                new BigDecimal("80.00"),
                "USD",
                destinationAccountId,
                null
        );

        mockMvc.perform(post("/v1/payments/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overdrawReq)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        // Account balances must remain strictly untouched
        AccountResponse account = paymentService.getAccountByUserId(USER_ID);
        assertThat(account.balance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(account.heldBalance()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("75.00"));

        // Direct Service layer assertion
        assertThatThrownBy(() -> paymentService.holdPayment(overdrawReq))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    @Order(4)
    @DisplayName("Verification Gate Step 4: Capture funds ($25.00 CAPTURE upon delivery completion)")
    void testCaptureFunds() throws Exception {
        CapturePaymentRequest captureReq = new CapturePaymentRequest(
                ORDER_ID,
                USER_ID,
                new BigDecimal("25.00"),
                destinationAccountId,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/v1/payments/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(captureReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.entryType").value("CAPTURE"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.totalBalance").value(75.00))
                .andExpect(jsonPath("$.heldBalance").value(0.00))
                .andExpect(jsonPath("$.availableBalance").value(75.00));

        // Customer account check
        AccountResponse customerAcc = paymentService.getAccountByUserId(USER_ID);
        assertThat(customerAcc.balance()).isEqualByComparingTo(new BigDecimal("75.00"));
        assertThat(customerAcc.heldBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(customerAcc.availableBalance()).isEqualByComparingTo(new BigDecimal("75.00"));

        // Merchant account check (credited $25.00)
        AccountResponse merchantAcc = paymentService.getAccountById(destinationAccountId);
        assertThat(merchantAcc.balance()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    @Order(5)
    @DisplayName("Verification Gate Step 5: Test Chaos Header X-Simulate-Failure: INSUFFICIENT_FUNDS")
    void testChaosHeaderSimulateFailure() throws Exception {
        UUID newOrderId = UUID.randomUUID();
        HoldPaymentRequest holdReq = new HoldPaymentRequest(
                newOrderId,
                USER_ID,
                new BigDecimal("10.00"),
                "USD",
                destinationAccountId,
                null
        );

        mockMvc.perform(post("/v1/payments/hold")
                        .header(ChaosSimulationFilter.HEADER_SIMULATE_FAILURE, "INSUFFICIENT_FUNDS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(holdReq)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("Simulated failure: Insufficient funds (triggered via X-Simulate-Failure)"));
    }

    @Test
    @Order(6)
    @DisplayName("Verification Gate Step 6: Test Chaos Header X-Simulate-Delay-Ms")
    void testChaosHeaderSimulateDelay() throws Exception {
        UUID newOrderId = UUID.randomUUID();
        HoldPaymentRequest holdReq = new HoldPaymentRequest(
                newOrderId,
                USER_ID,
                new BigDecimal("5.00"),
                "USD",
                destinationAccountId,
                null
        );

        long start = System.currentTimeMillis();
        mockMvc.perform(post("/v1/payments/hold")
                        .header(ChaosSimulationFilter.HEADER_SIMULATE_DELAY_MS, "150")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(holdReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(140);
    }

    @Test
    @Order(7)
    @DisplayName("Verification Gate Step 7: Test refund of captured order restores customer balance")
    void testRefundPayment() throws Exception {
        RefundPaymentRequest refundReq = new RefundPaymentRequest(
                ORDER_ID,
                USER_ID,
                new BigDecimal("25.00"),
                "Customer cancellation refund",
                UUID.randomUUID()
        );

        mockMvc.perform(post("/v1/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refundReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.entryType").value("REFUND"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // Customer balance restored: original $100.00 minus $5.00 hold from step 6 = $95.00 available, $100.00 total
        AccountResponse customerAcc = paymentService.getAccountByUserId(USER_ID);
        assertThat(customerAcc.balance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(customerAcc.availableBalance()).isEqualByComparingTo(new BigDecimal("95.00"));

        // Merchant balance debited back to $0.00
        AccountResponse merchantAcc = paymentService.getAccountById(destinationAccountId);
        assertThat(merchantAcc.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @Order(8)
    @DisplayName("Verification Gate Step 8: Test dual ingress paths /v1/payments and /api/v1/payments")
    void testDualIngressPathSupport() throws Exception {
        mockMvc.perform(get("/api/v1/payments/accounts/" + USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()));
    }
}
