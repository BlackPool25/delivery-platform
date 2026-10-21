package com.delivery.sms;

import com.delivery.sms.buffer.CircularSmsBuffer;
import com.delivery.sms.dto.SendSmsRequest;
import com.delivery.sms.dto.SmsResponse;
import com.delivery.sms.model.SmsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SmsGatewayVerificationGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CircularSmsBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer.clear();
    }

    @Test
    @Order(1)
    @DisplayName("Verification Gate: Send SMS and read back OTP from inspection endpoint in < 10ms")
    void testSendSmsAndReadBackOtpUnderTenMilliseconds() throws Exception {
        String testPhone = "+15551234567";
        String testMessage = "Your verification code is 849201. Valid for 5 minutes.";

        SendSmsRequest sendRequest = new SendSmsRequest(testPhone, testMessage);

        // 1. Ingest SMS
        mockMvc.perform(post("/v1/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sendRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value(testPhone))
                .andExpect(jsonPath("$.otp").value("849201"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // 2. Measure inspection endpoint latency
        long startNano = System.nanoTime();

        MvcResult result = mockMvc.perform(get("/v1/sms/latest")
                        .param("phone", testPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(testPhone))
                .andExpect(jsonPath("$.otp").value("849201"))
                .andExpect(jsonPath("$.message").value(testMessage))
                .andReturn();

        long durationNano = System.nanoTime() - startNano;
        double durationMs = durationNano / 1_000_000.0;

        System.out.printf("Verification Gate Gate Result: SMS Inspection Latency = %.3f ms%n", durationMs);

        // Verification Gate strict requirement: Latency < 10ms
        assertThat(durationMs).isLessThan(10.0);

        SmsResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), SmsResponse.class);
        assertThat(response.otp()).isEqualTo("849201");
        assertThat(response.phone()).isEqualTo(testPhone);
    }

    @Test
    @Order(2)
    @DisplayName("Verify Circular Buffer 1,000 Capacity Limit and FIFO Eviction Rollover")
    void testCircularBufferCapacityAndEviction() {
        CircularSmsBuffer customBuffer = new CircularSmsBuffer(1000);

        // Ingest 1,050 messages
        for (int i = 1; i <= 1050; i++) {
            customBuffer.add(new SmsMessage("+1999" + String.format("%04d", i), "OTP: " + i));
        }

        // Buffer size must be capped strictly at 1,000
        assertThat(customBuffer.size()).isEqualTo(1000);
        assertThat(customBuffer.getTotalReceived()).isEqualTo(1050);

        // First 50 messages (+19990001 through +19990050) should have been evicted
        assertThat(customBuffer.getLatest("+19990001")).isEmpty();
        assertThat(customBuffer.getLatest("+19990050")).isEmpty();

        // 51st message onward must still exist in buffer
        assertThat(customBuffer.getLatest("+19990051")).isPresent();
        assertThat(customBuffer.getLatest("+19991050")).isPresent();
        assertThat(customBuffer.getLatest("+19991050").get().getOtp()).isEqualTo("1050");
    }

    @Test
    @Order(3)
    @DisplayName("Verify High-Concurrency Ingestion and Sub-Millisecond Retrieval")
    void testConcurrentIngestionAndRetrieval() throws InterruptedException {
        int threads = 10;
        int messagesPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCounter = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int m = 0; m < messagesPerThread; m++) {
                        String phone = "+1800" + threadId + String.format("%03d", m);
                        buffer.add(new SmsMessage(phone, "Code " + (1000 + m)));
                        if (buffer.getLatest(phone).isPresent()) {
                            successCounter.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCounter.get()).isEqualTo(threads * messagesPerThread);
        assertThat(buffer.size()).isEqualTo(500);
    }

    @Test
    @Order(4)
    @DisplayName("Verify Dual Ingress Routing /api/v1/sms and /v1/sms")
    void testDualIngressPathSupport() throws Exception {
        SendSmsRequest request = new SendSmsRequest("+14155552671", "Dual path test OTP: 541299");

        mockMvc.perform(post("/api/v1/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.otp").value("541299"));

        mockMvc.perform(get("/api/v1/sms/latest")
                        .param("phone", "+14155552671"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otp").value("541299"));
    }

    @Test
    @Order(5)
    @DisplayName("Verify Web Inbox UI endpoint /admin/sms-inbox serves 200 OK")
    void testWebInboxUiEndpoint() throws Exception {
        mockMvc.perform(get("/admin/sms-inbox"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Local SMS Gateway")));

        mockMvc.perform(get("/admin/sms-inbox/"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/ui"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(6)
    @DisplayName("Verify Buffer Clear Endpoint")
    void testClearEndpoint() throws Exception {
        buffer.add(new SmsMessage("+1234", "OTP 9999"));
        assertThat(buffer.size()).isEqualTo(1);

        mockMvc.perform(delete("/v1/sms/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEARED"));

        assertThat(buffer.size()).isEqualTo(0);
    }
}
