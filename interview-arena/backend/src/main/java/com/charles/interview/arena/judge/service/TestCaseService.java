package com.charles.interview.arena.judge.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.charles.interview.arena.model.entity.TestCase;

/**
 * 测试用例 Service
 * CRUD 全靠 IService 继承,无需自定义方法
 */
public interface TestCaseService extends IService<TestCase> {
}
