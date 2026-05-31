package com.bitejiuyeke.user.service;

import com.bitejiuyeke.user.dto.request.AuthRequest;
import com.bitejiuyeke.user.dto.request.ChangePasswordRequest;
import com.bitejiuyeke.user.dto.response.AuthResponse;
import com.bitejiuyeke.user.dto.response.ChangePasswordResponse;
import com.bitejiuyeke.user.dto.response.UserInfoResponse;

/**
 * 用户服务相关接口
 */
public interface UserService {

    AuthResponse auth(AuthRequest authRequest);

    UserInfoResponse getUserInfo(String authorization);

    ChangePasswordResponse changePassword(ChangePasswordRequest request, String token);

    void logout(String authorization);
}
