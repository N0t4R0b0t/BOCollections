package com.bocollections.backend.repository;

import com.bocollections.backend.entity.ThriftSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ThriftSessionRepository extends JpaRepository<ThriftSession, Long> {
    List<ThriftSession> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<ThriftSession> findByIdAndUserId(Long id, Long userId);
}
