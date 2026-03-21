package com.deepshield.backend.repository;

import com.deepshield.backend.model.entity.ScanJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * JPA repository for ScanJob entities.
 * Spring Data auto-generates the implementation at runtime.
 */
@Repository
public interface ScanJobRepository extends JpaRepository<ScanJob, Long> {

    /**
     * Returns all scan jobs ordered by most recent first.
     */
    List<ScanJob> findAllByOrderByCreatedAtDesc();
}