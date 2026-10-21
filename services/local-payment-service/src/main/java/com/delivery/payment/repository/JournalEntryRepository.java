package com.delivery.payment.repository;

import com.delivery.payment.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    Optional<JournalEntry> findByIdempotencyKey(UUID idempotencyKey);

    Optional<JournalEntry> findFirstByOrderIdAndEntryTypeAndStatus(
            UUID orderId,
            JournalEntry.EntryType entryType,
            JournalEntry.EntryStatus status
    );
}
