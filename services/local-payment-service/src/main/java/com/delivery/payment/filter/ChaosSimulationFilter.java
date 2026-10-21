package com.delivery.payment.filter;

import com.delivery.payment.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChaosSimulationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChaosSimulationFilter.class);

    public static final String HEADER_SIMULATE_DELAY_MS = "X-Simulate-Delay-Ms";
    public static final String HEADER_SIMULATE_FAILURE = "X-Simulate-Failure";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Process simulated processing delay
        String delayHeader = request.getHeader(HEADER_SIMULATE_DELAY_MS);
        if (delayHeader != null && !delayHeader.isBlank()) {
            try {
                long delayMs = Long.parseLong(delayHeader.trim());
                if (delayMs > 0) {
                    log.info("Chaos simulation: Injecting {}ms delay for request {}", delayMs, request.getRequestURI());
                    Thread.sleep(delayMs);
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid X-Simulate-Delay-Ms value: {}", delayHeader);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServletException("Simulated delay interrupted", e);
            }
        }

        // 2. Process simulated failure scenario
        String failureHeader = request.getHeader(HEADER_SIMULATE_FAILURE);
        if (failureHeader != null && !failureHeader.isBlank()) {
            String failureType = failureHeader.trim().toUpperCase();
            log.info("Chaos simulation: Triggering simulated failure '{}' for request {}", failureType, request.getRequestURI());

            HttpStatus status;
            String errorCode;
            String message;

            switch (failureType) {
                case "INSUFFICIENT_FUNDS":
                    status = HttpStatus.PAYMENT_REQUIRED;
                    errorCode = "INSUFFICIENT_FUNDS";
                    message = "Simulated failure: Insufficient funds (triggered via X-Simulate-Failure)";
                    break;
                case "GATEWAY_TIMEOUT":
                case "TIMEOUT":
                    status = HttpStatus.GATEWAY_TIMEOUT;
                    errorCode = "SIMULATED_TIMEOUT";
                    message = "Simulated failure: Gateway timeout (triggered via X-Simulate-Failure)";
                    break;
                case "SERVICE_UNAVAILABLE":
                    status = HttpStatus.SERVICE_UNAVAILABLE;
                    errorCode = "SIMULATED_SERVICE_UNAVAILABLE";
                    message = "Simulated failure: Service unavailable (triggered via X-Simulate-Failure)";
                    break;
                case "BAD_REQUEST":
                    status = HttpStatus.BAD_REQUEST;
                    errorCode = "SIMULATED_BAD_REQUEST";
                    message = "Simulated failure: Bad request (triggered via X-Simulate-Failure)";
                    break;
                case "SYSTEM_ERROR":
                case "NETWORK_ERROR":
                default:
                    status = HttpStatus.INTERNAL_SERVER_ERROR;
                    errorCode = "SIMULATED_SYSTEM_ERROR";
                    message = "Simulated failure: Internal system error (triggered via X-Simulate-Failure)";
                    break;
            }

            ErrorResponse error = new ErrorResponse(
                    status.value(),
                    errorCode,
                    message,
                    request.getRequestURI()
            );

            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(error));
            response.getWriter().flush();
            return;
        }

        filterChain.doFilter(request, response);
    }
}
