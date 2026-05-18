package com.miniurl.url.repository;

import com.miniurl.url.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {
    List<Outbox> findByProcessedFalseOrderByCreatedAtAsc();

    long countByProcessedFalse();

    @Query("SELECT MIN(o.createdAt) FROM Outbox o WHERE o.processed = false")
    LocalDateTime findOldestUnprocessedCreatedAt();
}
