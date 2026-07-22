package com.charles.interview.arena.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.charles.interview.arena.model.dto.UserLoginDTO;
import com.charles.interview.arena.model.dto.UserRegisterDTO;
import com.charles.interview.arena.model.entity.User;
import com.charles.interview.arena.model.vo.LoginVO;
import com.charles.interview.arena.model.vo.UserVO;

public interface UserService extends IService<User> {

    UserVO userRegister(UserRegisterDTO registerDTO);

    LoginVO login(UserLoginDTO loginDTO);

    LoginVO refreshToken(String refreshToken);

    UserVO getLoginUser(Long userId);

    void logout(Long userId);
}