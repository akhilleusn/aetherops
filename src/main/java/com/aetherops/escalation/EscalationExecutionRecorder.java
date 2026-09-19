package com.aetherops.escalation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A separate bean purely so {@code escalate()}'s one DB write goes through a proxy-visible
 * {@code @Transactional} boundary. {@code EscalationService.escalate} is {@code @Async}, and
 * {@code executionRepository.save(execution)} used to be called directly from
 * {@code escalateInternal}, a private method self-invoked from {@code escalate} -- self-invocation
 * bypasses the Spring AOP proxy, so annotating {@code escalateInternal} itself with
 * {@code @Transactional} would not have worked. Same class of bug as {@code AlertDeduplication
 * .linkIncident} before it was fixed; see {@code IncidentSlackMetadataUpdater} for the same
 * pattern applied there.
 */
@Service
@RequiredArgsConstructor
public class EscalationExecutionRecorder {

    private final EscalationExecutionRepository executionRepository;

    @Transactional
    public void save(EscalationExecution execution) {
        executionRepository.save(execution);
    }
}
