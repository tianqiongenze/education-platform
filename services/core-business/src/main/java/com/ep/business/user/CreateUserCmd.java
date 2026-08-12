package com.ep.business.user;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

public class CreateUserCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String username;
    private String phone;
    @NotBlank
    private String email;
    @NotBlank
    private String password;
    private String nickname;

    public CreateUserCmd() {
    }

    public CreateUserCmd(String username, String phone, String email, String password, String nickname) {
        this.username = username;
        this.phone = phone;
        this.email = email;
        this.password = password;
        this.nickname = nickname;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
}
