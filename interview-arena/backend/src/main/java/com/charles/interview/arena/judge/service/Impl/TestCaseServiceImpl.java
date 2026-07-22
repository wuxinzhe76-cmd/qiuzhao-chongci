package com.charles.interview.arena.judge.service.Impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.charles.interview.arena.judge.service.TestCaseService;
import com.charles.interview.arena.mapper.TestCaseMapper;
import com.charles.interview.arena.model.entity.TestCase;

/**
 * 测试用例 ServiceImpl
 * CRUD 全靠 ServiceImpl 继承,无需自定义方法
 */
@Service
public class TestCaseServiceImpl extends ServiceImpl<TestCaseMapper, TestCase>
        implements TestCaseService {
}
