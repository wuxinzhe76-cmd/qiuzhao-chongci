# RAG 系统测试结果 V2（改写 Query + questionId 匹配）

> 📅 测试时间：2026-07-23 00:57:59
> 📊 测试集规模：30 条改写 query
> 🤖 模型：Embedding=DashScope text-embedding-v3, Rerank=DashScope gte-rerank-v2
> 🔧 匹配方式：questionId（解决 Milvus UUID vs ES 数字 ID 不匹配）

## 一、检索质量测试结果

| 配置 | Hit@5 | Hit@10 | Hit@20 | MRR@5 | NDCG@5 | P50延迟 | P95延迟 | 平均延迟 |
|------|-------|--------|--------|-------|--------|---------|---------|----------|
| Full (Vec+BM25+RRF+Rerank) | 100.0% | 100.0% | 100.0% | 0.886 | 0.879 | 662ms | 833ms | 694ms |
| Vector-Only | 100.0% | 100.0% | 100.0% | 0.925 | 0.921 | 400ms | 613ms | 486ms |
| BM25-Only | 0.0% | 0.0% | 0.0% | 0.000 | 0.000 | 258ms | 303ms | 229ms |
| No-RRF (Vec+BM25 merge) | 100.0% | 100.0% | 100.0% | 0.886 | 0.879 | 755ms | 838ms | 739ms |
| No-Rerank (Vec+BM25+RRF) | 96.7% | 96.7% | 96.7% | 0.867 | 0.858 | 415ms | 601ms | 492ms |
| Vec+Rerank (no BM25) | 100.0% | 100.0% | 100.0% | 0.886 | 0.879 | 669ms | 887ms | 695ms |

## 二、消融实验对比

**基线（Full Hybrid + Rerank）**：Hit@5=100.0%, MRR=0.886, P95=833ms

| 配置 | Hit@5 | vs基线 | MRR | vs基线 | P95延迟 | 结论 |
|------|-------|--------|-----|--------|---------|------|
| Vector-Only | 100.0% | +0.0% | 0.925 | +0.039 | 613ms | 持平/提升 |
| BM25-Only | 0.0% | -100.0% | 0.000 | -0.886 | 303ms | 下降 |
| No-RRF (Vec+BM25 merge) | 100.0% | +0.0% | 0.886 | +0.000 | 838ms | 持平/提升 |
| No-Rerank (Vec+BM25+RRF) | 96.7% | -3.3% | 0.867 | -0.019 | 601ms | 下降 |
| Vec+Rerank (no BM25) | 100.0% | +0.0% | 0.886 | +0.000 | 887ms | 持平/提升 |

## 三、测试集详情（改写 Query）

| # | 改写Query | 原题关键词 | questionId |
|---|----------|-----------|------------|
| 1 | Redis Pub/Sub 机制 | 订阅发布 | 874 |
| 2 | Redis 分布式锁的坑 | 分布式锁时可能遇到的问题 | 877 |
| 3 | Redis 底层压缩列表和快速列表 | Ziplist 和 Quicklist | 846 |
| 4 | Redis 集群 slot 分配 | 根据键定位到对应的节点 | 852 |
| 5 | Redis 性能调优 | 性能瓶颈时如何处理 | 841 |
| 6 | Redis sentinel 高可用 | 哨兵机制 | 870 |
| 7 | Redis 热点数据高并发 | 热点 key 问题 | 856 |
| 8 | Redis MGET MSET 和 Pipeline 区别 | 批处理命令 | 842 |
| 9 | Redis 大 key 危害 | Big Key 问题 | 866 |
| 10 | Redis 7.0 ListPack | ListPack 数据结构 | 850 |
| 11 | Redis cluster split brain | 脑裂问题 | 873 |
| 12 | Redis string max size 512MB | 字符串类型的最大值 | 835 |
| 13 | Redis sorted set 排行榜 | 快速实现排行榜 | 837 |
| 14 | Redis 主从同步架构 | 主从复制的常见拓扑结构 | 843 |
| 15 | Redis 缓存穿透 击穿 雪崩 | 缓存击穿、缓存穿透和缓存雪崩 | 867 |
| 16 | Redis 分布式锁过期续期 | 未完成逻辑前过期 | 881 |
| 17 | Redis RDB fork bgsave | 生成 RDB 文件时如何处理请求 | 869 |
| 18 | Redis MULTI EXEC 事务 | 支持事务吗 | 861 |
| 19 | Redis 跳表 skip list 为什么不用红黑树 | Zset 用跳表实现 | 854 |
| 20 | Redis Cluster vs Sentinel 对比 | Cluster 模式与 Sentinel 模式 | 849 |
| 21 | Redis replication 原理 | 主从复制的实现原理 | 871 |
| 22 | Redis GEO 地理位置 | Geo 数据结构 | 840 |
| 23 | Redis VM 内存 | 虚拟内存 | 879 |
| 24 | Redis 使用场景和应用 | 通常应用于哪些场景 | 831 |
| 25 | Redis 单线程为什么快 多线程 IO | 单线程 | 841 |
| 26 | Redis SETNX 分布式锁实现 | 如何实现分布式锁 | 875 |
| 27 | Redis 5种数据类型 | 常见的数据类型 | 832 |
| 28 | Redis LPUSH RPUSH LRANGE | List 类型的常见操作命令 | 844 |
| 29 | Redis Redlock 红锁 | Red Lock | 876 |
| 30 | Redis pipeline 批量执行 | Pipeline 功能 | 865 |

## 四、简历可引用数据

- 向量检索单独 Hit@5=100.0%，Hybrid+Rerank 后 Hit@5=100.0%
- BM25 单独 Hit@5=0.0%（验证 BM25 对关键词查询的贡献）
- 无 Rerank 时 Hit@5=96.7%，Rerank 后 Hit@5=100.0%
- RAG 端到端 P95 延迟 833ms，P50 延迟 662ms
