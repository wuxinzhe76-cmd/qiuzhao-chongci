package com.charles.interview.arena.judge.codesandbox;

import com.charles.interview.arena.judge.codesandbox.model.ExecuteResponse;

public interface CodeSandbox {
    /**
     * 执行代码
     * @param languageCode  语言代码(java/python3)
     * @param code          用户代码字符串
     * @param input         stdin 输入
     * @param timeLimit     时间限制(ms)
     * @param memoryLimit   内存限制(MB)
     * @return              执行结果
     */
    ExecuteResponse execute(String languageCode, String code, 
                            String input, int timeLimit, int memoryLimit);
}
