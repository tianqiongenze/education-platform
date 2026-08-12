package com.ep.senior.health;

import java.util.List;

public interface HealthRecordService {

    HealthRecord createRecord(CreateHealthRecordCmd cmd);

    HealthRecord getRecordById(Long recordId);

    List<HealthRecord> listByElder(Long elderId);

    List<HealthRecord> listAbnormalByElder(Long elderId);
}
