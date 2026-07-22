"""
Provider Adapter 包 — 多厂商 LLM 拦截适配器

当前支持的厂商：
  - OpenAI / DeepSeek / vLLM / Ollama / Groq / Together（OpenAI 兼容协议）
  - Anthropic Claude

扩展方式：继承 BaseProviderAdapter，实现 supports() / _wrap_call() / _unwrap_client() 三个抽象方法（extract() 有默认实现，按需重写）
"""

from .base import BaseProviderAdapter, LLMInterceptor, LLMCallRecord, register_adapter
from .openai_compatible import OpenAICompatibleAdapter
from .anthropic import AnthropicAdapter

# 导入即自动注册 Adapter（每个子模块底部有 register_adapter 调用）

__all__ = [
    "BaseProviderAdapter",
    "LLMInterceptor",
    "LLMCallRecord",
    "OpenAICompatibleAdapter",
    "AnthropicAdapter",
    "register_adapter",
]
