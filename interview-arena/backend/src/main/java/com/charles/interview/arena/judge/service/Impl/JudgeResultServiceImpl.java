package com.charles.interview.arena.judge.service.Impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.charles.interview.arena.judge.service.JudgeResultService;
import com.charles.interview.arena.mapper.JudgeResultMapper;
import com.charles.interview.arena.model.entity.JudgeResult;

/**
 * 判题结果 ServiceImpl
 * CRUD 全靠 ServiceImpl 继承
 */
@Service
public class JudgeResultServiceImpl extends ServiceImpl<JudgeResultMapper, JudgeResult>
        implements JudgeResultService {
        
}
