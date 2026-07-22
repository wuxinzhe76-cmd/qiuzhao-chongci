"""
阶段1 - AI Agent 与可观测系统概述

示例：一个简单的 AI Agent，没有可观测性埋点
- 用户输入 → LLM → 输出
- 增加一个 Tool（计算器）
- 打印执行流程日志

问题：日志难以定位、无法统计、无法回放
"""

import time
import json
from datetime import datetime


def print_log(step: str, data: dict):
    """简单的日志打印"""
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{timestamp}] {step}: {json.dumps(data, ensure_ascii=False)}")


def calculator_tool(expression: str) -> float:
    """简单的计算器 Tool"""
    print_log("TOOL_CALL", {"tool": "calculator", "input": expression})
    try:
        result = eval(expression)  # 简化示例，生产环境应使用安全的解析器
        print_log("TOOL_RESULT", {"tool": "calculator", "output": result})
        return result
    except Exception as e:
        print_log("TOOL_ERROR", {"tool": "calculator", "error": str(e)})
        return 0


def simple_agent(user_input: str):
    """
    简单的 Agent 流程：
    1. 接收用户输入
    2. 调用 LLM（这里用模拟代替）
    3. 如果需要计算，调用计算器 Tool
    4. 返回结果
    """
    print("=" * 60)
    print("Agent 开始执行")
    print("=" * 60)

    start_time = time.time()

    # 步骤1：记录用户输入
    print_log("USER_INPUT", {"input": user_input})

    # 步骤2：模拟 LLM 调用（实际应调用 OpenAI API）
    print_log("LLM_CALL", {
        "model": "gpt-5.4",
        "prompt": user_input,
        "status": "simulated"
    })

    # 模拟 LLM 响应
    time.sleep(0.5)  # 模拟网络延迟

    # 步骤3：判断是否需要调用 Tool
    if "计算" in user_input or "算" in user_input:
        print_log("LLM_RESPONSE", {
            "content": "我需要调用计算器工具来计算这个表达式",
            "need_tool": True,
            "tool": "calculator"
        })

        # 提取表达式（简化示例）
        expression = user_input.replace("计算", "").replace("算一下", "").strip()
        if not expression:
            expression = "2 + 3 * 4"

        # 调用 Tool
        tool_result = calculator_tool(expression)

        # 步骤4：将 Tool 结果返回给 LLM
        print_log("LLM_CALL", {
            "model": "gpt-5.4",
            "prompt": f"计算结果是 {tool_result}，请组织语言回复用户",
            "status": "simulated"
        })
        time.sleep(0.3)

        final_response = f"计算结果是 {tool_result}"
    else:
        print_log("LLM_RESPONSE", {
            "content": "这是一个普通对话，不需要调用工具",
            "need_tool": False
        })
        final_response = "你好！我是 AI Agent，有什么可以帮助你的吗？"

    # 步骤5：返回最终结果
    end_time = time.time()
    duration = end_time - start_time

    print_log("AGENT_COMPLETE", {
        "response": final_response,
        "duration_seconds": round(duration, 2)
    })

    print("=" * 60)
    print(f"Agent 执行完成，耗时 {duration:.2f} 秒")
    print("=" * 60)

    return final_response


if __name__ == "__main__":
    # 测试1：普通对话
    print("\n【测试1】普通对话")
    simple_agent("你好")

    # 测试2：需要计算的对话
    print("\n【测试2】需要计算的对话")
    simple_agent("帮我计算 2 + 3 * 4")

    print("\n" + "=" * 60)
    print("问题分析：")
    print("=" * 60)
    print("1. 日志分散在终端，难以持久化和查询")
    print("2. 无法统计 LLM 调用次数、Token 消耗、平均延迟")
    print("3. 无法回放完整的执行流程（缺少 TraceId 关联）")
    print("4. 无法对比不同模型的性能指标")
    print("5. 无法可视化展示调用链路")
    print("\n=> 这就是为什么我们需要可观测性系统！")
