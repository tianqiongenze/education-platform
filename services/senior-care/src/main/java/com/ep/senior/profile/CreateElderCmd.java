package com.ep.senior.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

public class CreateElderCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    private Long facilityId;
    private Long tenantId;
    @NotBlank
    private String name;
    private String gender;
    private String birthDate;
    private String idCard;
    private String phone;
    private String emergencyContact;
    private String address;
    private String notes;
    /** D-221: optional business idempotency key for create dedup. */
    private String idempotencyKey;

    public CreateElderCmd() {
    }

    public CreateElderCmd(Long facilityId, Long tenantId, String name, String gender,
                          String birthDate, String phone) {
        this.facilityId = facilityId;
        this.tenantId = tenantId;
        this.name = name;
        this.gender = gender;
        this.birthDate = birthDate;
        this.phone = phone;
    }

    public Long getFacilityId() { return facilityId; }
    public void setFacilityId(Long facilityId) { this.facilityId = facilityId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getBirthDate() { return birthDate; }
    public void setBirthDate(String birthDate) { this.birthDate = birthDate; }
    public String getIdCard() { return idCard; }
    public void setIdCard(String idCard) { this.idCard = idCard; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmergencyContact() { return emergencyContact; }
    public void setEmergencyContact(String emergencyContact) { this.emergencyContact = emergencyContact; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
