package com.delivery.order.repository;

import com.delivery.order.entity.OutboxEvent;
import com.delivery.order.entity.OutboxEventId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, OutboxEventId> {

    List<OutboxEvent> findByProcessedFalseOrderByCreatedAtAsc(Pageable pageable);

    @Query(value = "SELECT o.* FROM outbox_events o WHERE o.aggregate_id = :aggregateId", nativeQuery = true)
    List<OutboxEvent> findByAggregateId(@Param("aggregateId") UUID aggregateId);

    @Query(value = "SELECT count(*) FROM outbox_events", nativeQuery = true)
    long countAllEvents();
}
