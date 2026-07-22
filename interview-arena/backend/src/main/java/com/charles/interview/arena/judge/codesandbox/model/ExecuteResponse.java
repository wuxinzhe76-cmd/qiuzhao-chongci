package com.charles.interview.arena.judge.codesandbox.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExecuteResponse {
    private String stdout;           // 程序标准输出(用来对比)
    private String stderr;           // 错误输出(RE 时有值)
    private int exitCode;            // 退出码:0正常,非0异常
    private long executionTime;      // 执行耗时(ms)
    private long executionMemory;    // 内存峰值(KB)
    private String errorMessage;     // 沙箱层面的错误
}