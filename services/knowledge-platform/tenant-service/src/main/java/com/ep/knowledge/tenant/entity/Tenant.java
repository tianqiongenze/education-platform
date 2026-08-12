package com.ep.knowledge.tenant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

@TableName("tenant")
public class Tenant {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @NotBlank
    private String name;

    /** FREE | PRO | ENTERPRISE */
    private String plan;

    private Long quota;

    @TableField("brand_config")
    private String brandConfig;

    private String domain;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }
    public Long getQuota() { return quota; }
    public void setQuota(Long quota) { this.quota = quota; }
    public String getBrandConfig() { return brandConfig; }
    public void setBrandConfig(String brandConfig) { this.brandConfig = brandConfig; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
