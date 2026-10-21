package com.delivery.payment.dto;

import com.delivery.payment.entity.JournalEntry;
import com.delivery.payment.entity.LedgerAccount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentTransactionResponse(
        UUID transactionId,
        UUID orderId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        JournalEntry.EntryType entryType,
        JournalEntry.EntryStatus status,
        BigDecimal totalBalance,
        BigDecimal heldBalance,
        BigDecimal availableBalance,
        Instant createdAt,
        String message
) {
    public static PaymentTransactionResponse success(
            JournalEntry entry,
            LedgerAccount account,
            String message
    ) {
        return new PaymentTransactionResponse(
                entry.getId(),
                entry.getOrderId(),
                entry.getSourceAccountId(),
                entry.getDestinationAccountId(),
                entry.getAmount(),
                entry.getEntryType(),
                entry.getStatus(),
                account != null ? account.getBalance() : null,
                account != null ? account.getHeldBalance() : null,
                account != null ? account.getAvailableBalance() : null,
                entry.getCreatedAt(),
                message
        );
    }
}
