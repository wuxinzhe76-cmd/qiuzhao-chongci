from setuptools import setup, find_packages

setup(
    name="agent-insight-sdk",
    version="0.3.0",
    description="AI Agent 可观测性探针 SDK — 多厂商 LLM 拦截 + 链路追踪 + 性能监控",
    author="Agent-Insight Team",
    packages=find_packages(),
    install_requires=[
        "httpx>=0.24.0",
    ],
    extras_require={
        "openai": ["openai>=1.0.0"],
        "anthropic": ["anthropic>=0.25.0"],
        "all": ["openai>=1.0.0", "anthropic>=0.25.0", "python-dotenv>=1.0.0"],
    },
    python_requires=">=3.10",
    classifiers=[
        "Development Status :: 4 - Beta",
        "Intended Audience :: Developers",
        "Topic :: Software Development :: Libraries :: Python Modules",
        "Topic :: System :: Monitoring",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
    ],
)
