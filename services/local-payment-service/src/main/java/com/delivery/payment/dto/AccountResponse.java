package com.delivery.payment.dto;

import com.delivery.payment.entity.LedgerAccount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID userId,
        BigDecimal balance,
        BigDecimal heldBalance,
        BigDecimal availableBalance,
        String currency,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static AccountResponse from(LedgerAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getUserId(),
                account.getBalance(),
                account.getHeldBalance(),
                account.getAvailableBalance(),
                account.getCurrency(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
