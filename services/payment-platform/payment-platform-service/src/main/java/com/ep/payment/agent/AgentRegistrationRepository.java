package com.ep.payment.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRegistrationRepository extends JpaRepository<AgentRegistrationEntity, String> {

    Optional<AgentRegistrationEntity> findByAgentId(String agentId);

    List<AgentRegistrationEntity> findByOwnerUserId(String ownerUserId);
}
