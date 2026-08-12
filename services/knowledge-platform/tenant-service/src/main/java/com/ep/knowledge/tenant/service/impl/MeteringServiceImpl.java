package com.ep.knowledge.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ep.knowledge.tenant.entity.UsageRecord;
import com.ep.knowledge.tenant.mapper.UsageRecordMapper;
import com.ep.knowledge.tenant.service.MeteringService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D-179: Metering service implementation.
 *
 * <p>{@code record()} inserts a new {@link UsageRecord} with the current
 * timestamp. {@code summarize()} queries the usage records for a tenant within
 * a billing period (YYYY-MM) and groups them by resource type, summing the
 * quantities.
 *
 * <p>This service writes to {@code tenant_usage_record} which is NOT registered
 * as a tenant-scoped table in {@code MybatisPlusConfig.TENANT_TABLES}, so the
 * MyBatis-Plus tenant-line interceptor does not auto-append a tenant_id filter.
 * The explicit {@code tenantId} passed to each method is therefore used in
 * manual query wrappers.
 *
 * <p>Uses string-column {@link QueryWrapper} rather than {@code LambdaQueryWrapper}
 * because the lambda variant requires MyBatis-Plus's entity metadata cache to be
 * initialised (it normally is, by the auto-configuration), which is not
 * available in pure-Mockito unit tests that bypass the Spring context.
 */
@Service
public class MeteringServiceImpl implements MeteringService {

    private final UsageRecordMapper usageRecordMapper;

    public MeteringServiceImpl(UsageRecordMapper usageRecordMapper) {
        this.usageRecordMapper = usageRecordMapper;
    }

    @Override
    public void record(String tenantId, String resourceType, long quantity) {
        UsageRecord record = new UsageRecord();
        record.setTenantId(tenantId);
        record.setResourceType(resourceType);
        record.setQuantity(quantity);
        record.setRecordedAt(LocalDateTime.now());
        usageRecordMapper.insert(record);
    }

    @Override
    public Map<String, Long> summarize(String tenantId, String period) {
        // period is YYYY-MM; compute the inclusive [start, end) LocalDateTime range.
        YearMonth ym = YearMonth.parse(period);
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();

        List<UsageRecord> records = usageRecordMapper.selectList(
                new QueryWrapper<UsageRecord>()
                        .eq("tenant_id", tenantId)
                        .ge("recorded_at", start)
                        .lt("recorded_at", end));

        Map<String, Long> summary = new LinkedHashMap<>();
        for (UsageRecord r : records) {
            summary.merge(r.getResourceType(), r.getQuantity(), Long::sum);
        }
        return summary;
    }
}
