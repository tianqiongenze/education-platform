package com.ep.payment.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelStatementRepository extends JpaRepository<ChannelStatementEntity, String> {

    List<ChannelStatementEntity> findByBizDate(String bizDate);

    Optional<ChannelStatementEntity> findByChannelTxId(String channelTxId);
}
