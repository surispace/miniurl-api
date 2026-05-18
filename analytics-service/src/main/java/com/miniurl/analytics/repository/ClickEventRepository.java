package com.miniurl.analytics.repository;

import com.miniurl.analytics.entity.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
}
