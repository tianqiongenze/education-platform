package com.ep.payment.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentAuthorizationRepository extends JpaRepository<AgentAuthorizationEntity, String> {

    Optional<AgentAuthorizationEntity> findByAuthorizationId(String authorizationId);

    List<AgentAuthorizationEntity> findByPrincipalUserId(String principalUserId);

    List<AgentAuthorizationEntity> findByAgentId(String agentId);
}
