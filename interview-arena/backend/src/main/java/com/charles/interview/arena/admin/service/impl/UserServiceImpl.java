package com.charles.interview.arena.admin.service.impl;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.common.JwtUtil;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.mapper.UserMapper;
import com.charles.interview.arena.model.dto.UserLoginDTO;
import com.charles.interview.arena.model.dto.UserRegisterDTO;
import com.charles.interview.arena.model.entity.User;
import com.charles.interview.arena.model.vo.LoginVO;
import com.charles.interview.arena.model.vo.UserVO;
import com.charles.interview.arena.admin.service.UserService;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
     // 登录失败限流配置
    private static final String LOGIN_FAIL_PREFIX = "login:fail:";
    private static final int MAX_FAIL_COUNT = 5;
    private static final int FAIL_LOCK_MINUTES = 30;

    // Redis token 白名单 key 前缀
    private static final String ACCESS_TOKEN_PREFIX = "access:";
    private static final String REFRESH_TOKEN_PREFIX = "refresh:";

    private final StringRedisTemplate stringRedisTemplate;
    @Override
    public UserVO userRegister(UserRegisterDTO registerDTO) {
        // 1. 校验用户名是否已存在
        String username = registerDTO.getUsername();
        long count = this.lambdaQuery().eq(User::getUsername, username).count();
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "用户名已存在");

        // 2. 密码加密
        String encryptedPassword = PASSWORD_ENCODER.encode(registerDTO.getPassword());

        // 3. 构建 User 实体(DTO → Entity 快速映射)
        User user = new User();
        BeanUtils.copyProperties(registerDTO, user);
        user.setPassword(encryptedPassword);

        // 4. 插入数据库(id 自动回填)
        boolean saved = this.save(user);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "注册失败");
        // 5. return userVO
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public LoginVO login(UserLoginDTO loginDTO) {
        String username = loginDTO.getUsername();

        // 1. 检查是否被锁定
        String failKey = LOGIN_FAIL_PREFIX + username;
        String failCountStr = stringRedisTemplate.opsForValue().get(failKey);
        int failCount = failCountStr != null ? Integer.parseInt(failCountStr) : 0;
        ThrowUtils.throwIf(failCount >= MAX_FAIL_COUNT,
                ErrorCode.FORBIDDEN_ERROR, "登录失败次数过多，请" + FAIL_LOCK_MINUTES + "分钟后再试");

        // 2. 查用户
        User user = this.lambdaQuery().eq(User::getUsername, username).one();
        ThrowUtils.throwIf(user == null, ErrorCode.PARAMS_ERROR, "用户名或密码错误");

        // 3. 校验密码
        boolean match = PASSWORD_ENCODER.matches(loginDTO.getPassword(), user.getPassword());
        if (!match) {
            // 记录失败次数
            Long newCount = stringRedisTemplate.opsForValue().increment(failKey);
            if (newCount != null && newCount == 1) {
                stringRedisTemplate.expire(failKey, FAIL_LOCK_MINUTES, TimeUnit.MINUTES);
            }
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR, "用户名或密码错误");
        }

        // 4. 登录成功，清除失败记录
        stringRedisTemplate.delete(failKey);

        // 5. 生成双 token
        String accessToken = JwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = JwtUtil.generateRefreshToken(user.getId(), user.getUsername());

        // 6. 存入 Redis 白名单
        stringRedisTemplate.opsForValue().set(ACCESS_TOKEN_PREFIX + user.getId(), accessToken, 2, TimeUnit.HOURS);
        stringRedisTemplate.opsForValue().set(REFRESH_TOKEN_PREFIX + user.getId(), refreshToken, 7, TimeUnit.DAYS);

        // 7. 构建返回
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        return LoginVO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userInfo(userVO)
                .build();
    }

    @Override
    public LoginVO refreshToken(String refreshToken) {
        // 1. 验证 refreshToken 格式
        Claims claims = JwtUtil.parseToken(refreshToken);
        ThrowUtils.throwIf(claims == null, ErrorCode.NOT_LOGIN_ERROR, "refreshToken 无效");
        ThrowUtils.throwIf(!"refresh".equals(claims.get("type")), 
                ErrorCode.NOT_LOGIN_ERROR, "token 类型错误");

        // 2. 获取 userId
        Long userId = Long.parseLong(claims.getSubject());

        // 3. 验证 Redis 中 refreshToken 是否匹配
        String redisKey = REFRESH_TOKEN_PREFIX + userId;
        String redisToken = stringRedisTemplate.opsForValue().get(redisKey);
        ThrowUtils.throwIf(redisToken == null || !redisToken.equals(refreshToken),
                ErrorCode.NOT_LOGIN_ERROR, "refreshToken 已失效，请重新登录");

        // 4. 获取用户信息
        User user = this.getById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        // 5. 生成新的 accessToken（refreshToken 不变）
        String newAccessToken = JwtUtil.generateAccessToken(userId, user.getUsername());
        stringRedisTemplate.opsForValue().set(ACCESS_TOKEN_PREFIX + userId, newAccessToken, 2, TimeUnit.HOURS);

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        return LoginVO.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken)
                .userInfo(userVO)
                .build();
    }

    @Override
    public UserVO getLoginUser(Long userId) {
        User user = this.getById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public void logout(Long userId) {
        // 删除 Redis 中的 token 白名单
        stringRedisTemplate.delete(ACCESS_TOKEN_PREFIX + userId);
        stringRedisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
    }
}