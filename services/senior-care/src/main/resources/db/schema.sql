-- D-147: sp08-elder 康养养老事业群 DDL (TiDB / MySQL compatible)
-- Run on TiDB cluster: mysql -h <tidb-host> -P 4000 -u root < schema.sql

CREATE DATABASE IF NOT EXISTS ep_elder DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ep_elder;

-- ============================================================
-- ep_elder_profile: 长者档案
-- ============================================================
CREATE TABLE IF NOT EXISTS ep_elder_profile (
    elder_id           BIGINT       NOT NULL COMMENT 'Snowflake ID (D-132: 避免 TiDB 写热点)',
    facility_id        BIGINT       NOT NULL COMMENT '机构ID',
    tenant_id          BIGINT       NOT NULL COMMENT '租户ID',
    name               VARCHAR(64)  NOT NULL COMMENT '姓名',
    gender             VARCHAR(16)  DEFAULT NULL COMMENT '性别',
    birth_date         VARCHAR(16)  DEFAULT NULL COMMENT '出生日期 ISO yyyy-MM-dd',
    id_card            VARCHAR(32)  DEFAULT NULL COMMENT '身份证号(加密存储)',
    phone              VARCHAR(32)  DEFAULT NULL COMMENT '联系电话',
    emergency_contact  VARCHAR(128) DEFAULT NULL COMMENT '紧急联系人',
    address            VARCHAR(256) DEFAULT NULL COMMENT '地址',
    notes              VARCHAR(1024) DEFAULT NULL COMMENT '备注',
    status             INT          NOT NULL DEFAULT 1 COMMENT '1=ACTIVE,2=PAUSED,3=DISCHARGED',
    created_at         BIGINT       NOT NULL COMMENT '创建时间(epoch ms)',
    updated_at         BIGINT       NOT NULL COMMENT '更新时间(epoch ms)',
    lock_version       BIGINT       DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (elder_id),
    KEY idx_facility_status (facility_id, status),
    KEY idx_tenant (tenant_id),
    KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='康养养老-长者档案';

-- ============================================================
-- ep_health_record: 健康体征记录
-- ============================================================
CREATE TABLE IF NOT EXISTS ep_health_record (
    record_id    BIGINT       NOT NULL COMMENT 'Snowflake ID',
    elder_id     BIGINT       NOT NULL COMMENT '长者ID',
    facility_id  BIGINT       NOT NULL COMMENT '机构ID',
    tenant_id    BIGINT       NOT NULL COMMENT '租户ID',
    systolic_bp  INT          DEFAULT NULL COMMENT '收缩压 mmHg',
    diastolic_bp INT          DEFAULT NULL COMMENT '舒张压 mmHg',
    heart_rate   INT          DEFAULT NULL COMMENT '心率 bpm',
    blood_sugar  DOUBLE       DEFAULT NULL COMMENT '血糖 mmol/L',
    temperature  DOUBLE       DEFAULT NULL COMMENT '体温 °C',
    spo2         INT          DEFAULT NULL COMMENT '血氧饱和度 %',
    notes        VARCHAR(1024) DEFAULT NULL COMMENT '备注',
    is_abnormal  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否异常',
    recorded_at  BIGINT       NOT NULL COMMENT '记录时间(epoch ms)',
    created_at   BIGINT       NOT NULL COMMENT '创建时间(epoch ms)',
    lock_version BIGINT       DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (record_id),
    KEY idx_elder_recorded (elder_id, recorded_at),
    KEY idx_elder_abnormal (elder_id, is_abnormal),
    KEY idx_facility (facility_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='康养养老-健康体征记录';

-- ============================================================
-- ep_care_plan: 照护计划
-- ============================================================
CREATE TABLE IF NOT EXISTS ep_care_plan (
    plan_id               BIGINT       NOT NULL COMMENT 'Snowflake ID',
    elder_id              BIGINT       NOT NULL COMMENT '长者ID',
    facility_id           BIGINT       NOT NULL COMMENT '机构ID',
    tenant_id             BIGINT       NOT NULL COMMENT '租户ID',
    assigned_caregiver_id BIGINT       DEFAULT NULL COMMENT '指定护理员ID',
    title                 VARCHAR(128) NOT NULL COMMENT '计划标题',
    description           VARCHAR(2048) DEFAULT NULL COMMENT '计划描述',
    care_items            VARCHAR(4096) DEFAULT NULL COMMENT '照护项 JSON 数组',
    start_date            BIGINT       DEFAULT NULL COMMENT '开始日期(epoch ms)',
    end_date              BIGINT       DEFAULT NULL COMMENT '结束日期(epoch ms)',
    status                INT          NOT NULL DEFAULT 0 COMMENT '0=DRAFT,1=ACTIVE,2=COMPLETED,3=ARCHIVED',
    created_at            BIGINT       NOT NULL COMMENT '创建时间(epoch ms)',
    updated_at            BIGINT       NOT NULL COMMENT '更新时间(epoch ms)',
    lock_version          BIGINT       DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (plan_id),
    KEY idx_elder_status (elder_id, status),
    KEY idx_caregiver (assigned_caregiver_id),
    KEY idx_facility (facility_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='康养养老-照护计划';
