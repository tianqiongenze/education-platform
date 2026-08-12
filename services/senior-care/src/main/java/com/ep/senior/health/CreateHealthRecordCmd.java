package com.ep.senior.health;

import java.io.Serializable;

public class CreateHealthRecordCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long elderId;
    private Long facilityId;
    private Long tenantId;
    private Integer systolicBp;
    private Integer diastolicBp;
    private Integer heartRate;
    private Double bloodSugar;
    private Double temperature;
    private Integer spo2;
    private String notes;

    public CreateHealthRecordCmd() {
    }

    public Long getElderId() { return elderId; }
    public void setElderId(Long elderId) { this.elderId = elderId; }
    public Long getFacilityId() { return facilityId; }
    public void setFacilityId(Long facilityId) { this.facilityId = facilityId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Integer getSystolicBp() { return systolicBp; }
    public void setSystolicBp(Integer systolicBp) { this.systolicBp = systolicBp; }
    public Integer getDiastolicBp() { return diastolicBp; }
    public void setDiastolicBp(Integer diastolicBp) { this.diastolicBp = diastolicBp; }
    public Integer getHeartRate() { return heartRate; }
    public void setHeartRate(Integer heartRate) { this.heartRate = heartRate; }
    public Double getBloodSugar() { return bloodSugar; }
    public void setBloodSugar(Double bloodSugar) { this.bloodSugar = bloodSugar; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getSpo2() { return spo2; }
    public void setSpo2(Integer spo2) { this.spo2 = spo2; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
