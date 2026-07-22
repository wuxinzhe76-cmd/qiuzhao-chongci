"""
配置管理
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用配置"""
    # Kafka 配置
    kafka_bootstrap_servers: str = "localhost:9093"
    kafka_topic: str = "agent-logs"
    kafka_group_id: str = "agent-insight-consumer"

    # ClickHouse 配置
    clickhouse_host: str = "localhost"
    clickhouse_port: int = 9000
    clickhouse_database: str = "default"
    clickhouse_user: str = "default"
    clickhouse_password: str = ""

    # 服务配置
    backend_host: str = "0.0.0.0"
    backend_port: int = 8000

    class Config:
        env_file = ".env"


settings = Settings()
