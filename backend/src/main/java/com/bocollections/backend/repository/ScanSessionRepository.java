package com.bocollections.backend.repository;

import com.bocollections.backend.entity.ScanSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScanSessionRepository extends JpaRepository<ScanSession, Long> {
    List<ScanSession> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<ScanSession> findByIdAndUserId(Long id, Long userId);
}
