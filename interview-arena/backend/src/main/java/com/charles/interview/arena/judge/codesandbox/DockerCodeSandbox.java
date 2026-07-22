package com.charles.interview.arena.judge.codesandbox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.charles.interview.arena.judge.codesandbox.model.ExecuteResponse;

@Component
public class DockerCodeSandbox implements CodeSandbox {
    private static final String JAVA_IMAGE = "judge-java:latest";
    private static final String PYTHON_IMAGE = "judge-python:latest";
    @Override
    public ExecuteResponse execute(String languageCode, String code, String input, int timeLimit, int memoryLimit) {
        Path tempDir = null; 
        try {
            tempDir = Files.createTempDirectory("judge_");
            String tempDirStr = tempDir.toAbsolutePath().toString();
            // 2. 根据语言写代码文件
            //    java → Solution.java
            //    python3 → solution.py
            String fileName = "java".equals(languageCode) ? "Solution.java" : "solution.py";
            Files.writeString(tempDir.resolve(fileName), code);

            // 3. 写 input 文件
            Files.writeString(tempDir.resolve("input.txt"), input != null ? input : "");

            // 4. 拼 docker 命令
            String image = "java".equals(languageCode) ? JAVA_IMAGE : PYTHON_IMAGE;
            String runCmd = "java".equals(languageCode) 
                ? "javac Solution.java && java Solution < input.txt"
                : "python3 solution.py < input.txt";

            List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--cpus=1",
                "--memory=" + memoryLimit + "m",
                "--network=none",
                "-v", tempDirStr + ":/code",
                "-w", "/code",
                image,
                "sh", "-c", runCmd
            ));

            // 5. 执行,计时
            long start = System.currentTimeMillis();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 等待进程结束,最多等 timeLimit 毫秒
            boolean finished = process.waitFor(timeLimit, TimeUnit.MILLISECONDS);
            long executionTime = System.currentTimeMillis() - start;

            if (!finished) {
                // 超时(TLE)
                process.destroyForcibly();
                return ExecuteResponse.builder()
                    .exitCode(-1)
                    .executionTime(executionTime)
                    .errorMessage("Time Limit Exceeded")
                    .build();
            }

            // 正常结束,收集结果
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            int exitCode = process.exitValue();

            return ExecuteResponse.builder()
                .stdout(stdout)              // 程序输出(判题对比用)
                .stderr(stderr)              // 错误信息(RE 时有值)
                .exitCode(exitCode)          // 0=正常,非0=异常
                .executionTime(executionTime)
                .build();

        } catch (Exception e) {
            return ExecuteResponse.builder()
                .errorMessage("沙箱执行失败: " + e.getMessage())
                .exitCode(-1)
                .build();
        } finally {
            // 7. 清理临时文件
            deleteRecursively(tempDir.toFile());
        }
    }
    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
    
}
