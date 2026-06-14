package com.aetherops.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SagaStepRepository extends JpaRepository<SagaStep, UUID> {

    List<SagaStep> findBySagaIdOrderByRecordedAtAsc(UUID sagaId);
}
