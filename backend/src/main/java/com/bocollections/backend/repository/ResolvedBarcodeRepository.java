package com.bocollections.backend.repository;

import com.bocollections.backend.entity.ResolvedBarcode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResolvedBarcodeRepository extends JpaRepository<ResolvedBarcode, Long> {

    Optional<ResolvedBarcode> findByBarcode(String barcode);
}
