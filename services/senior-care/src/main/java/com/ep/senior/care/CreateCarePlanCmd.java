package com.ep.senior.care;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

public class CreateCarePlanCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    private Long elderId;
    private Long facilityId;
    private Long tenantId;
    private Long assignedCaregiverId;
    @NotBlank
    private String title;
    private String description;
    private String careItems;
    private Long startDate;
    private Long endDate;

    public CreateCarePlanCmd() {
    }

    public Long getElderId() { return elderId; }
    public void setElderId(Long elderId) { this.elderId = elderId; }
    public Long getFacilityId() { return facilityId; }
    public void setFacilityId(Long facilityId) { this.facilityId = facilityId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getAssignedCaregiverId() { return assignedCaregiverId; }
    public void setAssignedCaregiverId(Long assignedCaregiverId) { this.assignedCaregiverId = assignedCaregiverId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCareItems() { return careItems; }
    public void setCareItems(String careItems) { this.careItems = careItems; }
    public Long getStartDate() { return startDate; }
    public void setStartDate(Long startDate) { this.startDate = startDate; }
    public Long getEndDate() { return endDate; }
    public void setEndDate(Long endDate) { this.endDate = endDate; }
}
