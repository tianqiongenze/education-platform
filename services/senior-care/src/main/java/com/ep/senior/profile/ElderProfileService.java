package com.ep.senior.profile;

import java.util.List;

public interface ElderProfileService {

    ElderProfile createElder(CreateElderCmd cmd);

    ElderProfile getElderById(Long elderId);

    List<ElderProfile> listElders(Integer page, Integer size);

    List<ElderProfile> searchElders(String keyword);

    void pauseElder(Long elderId);

    void activateElder(Long elderId);

    void dischargeElder(Long elderId);
}
