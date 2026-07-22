-- 编程语言初始化数据
-- 后续沙箱优化时,从这张表读取编译/运行命令,不再写死在代码里

INSERT INTO programming_language (language_name, language_code, version, compile_command, run_command, icon, is_active, user_id) VALUES
('Java', 'java', '17', 'javac Solution.java', 'java Solution', NULL, 1, 1),
('Python 3', 'python3', '3.11', NULL, 'python3 solution.py', NULL, 1, 1);
