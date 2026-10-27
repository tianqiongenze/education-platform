package com.ep.payment.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentIntentLogRepository extends JpaRepository<AgentIntentLogEntity, String> {

    List<AgentIntentLogEntity> findByAuthorizationIdOrderByCreatedAtDesc(String authorizationId);

    List<AgentIntentLogEntity> findByPrincipalUserIdOrderByCreatedAtDesc(String principalUserId);
}
