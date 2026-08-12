package com.ep.business.user;

import jakarta.validation.constraints.Email;

import java.io.Serializable;

public class UpdateUserCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String nickname;
    private String avatar;
    @Email
    private String email;

    public UpdateUserCmd() {
    }

    public UpdateUserCmd(Long userId, String nickname, String avatar, String email) {
        this.userId = userId;
        this.nickname = nickname;
        this.avatar = avatar;
        this.email = email;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
