package com.ep.business.user;

import java.util.List;

public interface UserService {
    User createUser(CreateUserCmd cmd);
    User getUserById(Long userId);
    User getUserByPhone(String phone);
    List<User> listUsers();
    void updateUser(UpdateUserCmd cmd);
    void freezeUser(Long userId, String reason);
}
