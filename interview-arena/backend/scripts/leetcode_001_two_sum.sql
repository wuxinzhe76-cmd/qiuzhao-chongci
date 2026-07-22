-- LeetCode 1 - 两数之和:补充题目内容 + 测试用例
-- 注意:本系统是 stdin/stdout 判题,需约定输入输出格式
-- 执行方式:通过后端管理接口或临时测试类执行,不走 Flyway

-- 1. 更新题目内容(按 LeetCode 原文 + 本系统输入输出格式说明)
UPDATE question SET content = '## 题目描述

给定一个整数数组 nums 和一个整数目标值 target，请你在该数组中找出和为目标值 target 的那两个整数，并返回它们的数组下标。

你可以假设每种输入只会对应一个答案。但是，数组中同一个元素在答案里不能重复出现。

你可以按任意顺序返回答案。

## 示例

**示例 1：**

输入：nums = [2,7,11,15], target = 9
输出：[0,1]
解释：因为 nums[0] + nums[1] == 9 ，返回 [0, 1] 。

**示例 2：**

输入：nums = [3,2,4], target = 6
输出：[1,2]

**示例 3：**

输入：nums = [3,3], target = 6
输出：[0,1]

## 约束条件

- 2 <= nums.length <= 10^4
- -10^9 <= nums[i] <= 10^9
- -10^9 <= target <= 10^9
- 只会存在一个有效答案

## 本系统输入输出格式

**输入格式：**
- 第一行：数组元素，空格分隔
- 第二行：目标值 target

**输出格式：**
- 一行：两个下标，空格分隔（小的下标在前，保证唯一答案）

**示例输入：**
2 7 11 15
9

**示例输出：**
0 1' WHERE title = 'LeetCode 1 - 两数之和';

-- 2. 插入测试用例(3个)
-- 用例1: LeetCode 示例1
INSERT INTO test_case (question_id, input, output, is_example, score, user_id) VALUES
(1, '2 7 11 15\n9', '0 1', 1, 30, 1);

-- 用例2: LeetCode 示例2
INSERT INTO test_case (question_id, input, output, is_example, score, user_id) VALUES
(1, '3 2 4\n6', '1 2', 1, 30, 1);

-- 用例3: LeetCode 示例3(相同元素)
INSERT INTO test_case (question_id, input, output, is_example, score, user_id) VALUES
(1, '3 3\n6', '0 1', 1, 40, 1);
