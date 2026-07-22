package com.charles.interview.arena.judge.codesandbox.impl;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.judge.codesandbox.CodeSandbox;
import com.charles.interview.arena.judge.codesandbox.model.ExecuteResponse;

/**
 * Mock 沙箱:本地无 Docker 时,模拟代码执行结果,用于跑通 JudgeService 判题逻辑。
 */
@Component
@Primary
public class MockCodeSandbox implements CodeSandbox {
    @Override
    public ExecuteResponse execute(String languageCode, String code,
                            String input, int timeLimit, int memoryLimit) {
        // 模拟:假设用户代码把 stdin 原样输出
        return ExecuteResponse.builder()
                .stdout(input != null ? input.trim() : "")
                .stderr("")
                .exitCode(0)
                .executionTime(10)
                .executionMemory(1024)
                .errorMessage(null)
                .build();
    }
}
