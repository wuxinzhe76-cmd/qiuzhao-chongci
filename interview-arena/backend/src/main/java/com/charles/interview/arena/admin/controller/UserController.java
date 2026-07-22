package com.charles.interview.arena.admin.controller;

import org.springframework.web.bind.annotation.RestController;

import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.model.dto.UserLoginDTO;
import com.charles.interview.arena.model.dto.UserRegisterDTO;
import com.charles.interview.arena.model.vo.LoginVO;
import com.charles.interview.arena.model.vo.UserVO;
import com.charles.interview.arena.admin.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public BaseResponse<UserVO> register(@Valid @RequestBody UserRegisterDTO registerDTO) {
        UserVO userVO = userService.userRegister(registerDTO);
        return ResultUtils.success(userVO);
    }

    @PostMapping("/login")
    public BaseResponse<LoginVO> login(@Valid @RequestBody UserLoginDTO loginDTO) {
        LoginVO loginVO = userService.login(loginDTO);
        return ResultUtils.success(loginVO);
    }

    @PostMapping("/logout")
    public BaseResponse<Void> logout(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        userService.logout(userId);
        return ResultUtils.success(null);
    }

    @GetMapping("/me")
    public BaseResponse<UserVO> me(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        UserVO userVO = userService.getLoginUser(userId);
        return ResultUtils.success(userVO);
    }

    @PostMapping("/refresh")
    public BaseResponse<LoginVO> refresh(@RequestParam String refreshToken) {
        LoginVO loginVO = userService.refreshToken(refreshToken);
        return ResultUtils.success(loginVO);
    }
}