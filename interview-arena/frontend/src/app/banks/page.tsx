'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { questionApi } from '@/lib/api';
import type { QuestionQueryDTO } from '@/types';
import { Code2, Database, BookOpen, Layers, ArrowRight } from 'lucide-react';

// 题库分类配置
const BANK_CATEGORIES = [
  {
    key: 'algorithm',
    title: 'LeetCode 算法题',
    desc: '大厂高频算法题,涵盖双指针、动态规划、回溯、图论等核心套路',
    icon: Code2,
    gradient: 'from-blue-500 to-indigo-600',
    query: { type: 'PROGRAMMING' } as Partial<QuestionQueryDTO>,
    href: '/algorithms',
  },
  {
    key: 'redis',
    title: 'Redis 八股面试题',
    desc: 'Redis 数据结构、持久化、集群、缓存策略等面试必考知识点',
    icon: Database,
    gradient: 'from-red-500 to-rose-600',
    query: { tags: ['Redis'] } as Partial<QuestionQueryDTO>,
    href: '/problems?category=redis',
  },
  {
    key: 'mysql',
    title: 'MySQL 八股面试题',
    desc: '索引原理、事务隔离级别、锁机制、SQL 优化等数据库面试核心',
    icon: Layers,
    gradient: 'from-amber-500 to-orange-600',
    query: { tags: ['MySQL'] } as Partial<QuestionQueryDTO>,
    href: '/problems?category=mysql',
  },
  {
    key: 'spring',
    title: 'Spring 八股面试题',
    desc: 'IOC/AOP 原理、Bean 生命周期、事务管理、SpringBoot 自动配置',
    icon: BookOpen,
    gradient: 'from-green-500 to-emerald-600',
    query: { tags: ['Spring'] } as Partial<QuestionQueryDTO>,
    href: '/problems?category=spring',
  },
];

export default function BanksPage() {
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      const results: Record<string, number> = {};
      await Promise.all(
        BANK_CATEGORIES.map(async (cat) => {
          try {
            const res = await questionApi.list({
              current: 1,
              pageSize: 1,
              ...cat.query,
            } as QuestionQueryDTO);
            results[cat.key] = res.data.total;
          } catch {
            results[cat.key] = 0;
          }
        })
      );
      setCounts(results);
      setLoading(false);
    })();
  }, []);

  return (
    <div className="animate-fade-in">
      {/* 标题 */}
      <div className="mb-10">
        <h1 className="font-display text-3xl font-bold mb-2">题库</h1>
        <p className="text-ink/50">选择题库开始练习,涵盖算法与八股</p>
      </div>

      {/* 分类卡片 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {BANK_CATEGORIES.map((cat) => {
          const Icon = cat.icon;
          const count = counts[cat.key];
          return (
            <Link
              key={cat.key}
              href={cat.href}
              className="group relative bg-white rounded-2xl border border-surface-border p-8 hover:border-accent/40 hover:shadow-lg transition-all duration-300 overflow-hidden"
            >
              {/* 装饰渐变 */}
              <div
                className={`absolute -top-12 -right-12 w-40 h-40 rounded-full bg-gradient-to-br ${cat.gradient} opacity-5 group-hover:opacity-10 transition-opacity blur-2xl`}
              />

              <div className="relative">
                {/* 图标 */}
                <div
                  className={`inline-flex items-center justify-center w-12 h-12 rounded-xl bg-gradient-to-br ${cat.gradient} mb-4`}
                >
                  <Icon className="w-6 h-6 text-white" />
                </div>

                {/* 标题 */}
                <h2 className="font-display text-xl font-bold mb-2 group-hover:text-accent transition-colors">
                  {cat.title}
                </h2>

                {/* 描述 */}
                <p className="text-sm text-ink/50 leading-relaxed mb-4">
                  {cat.desc}
                </p>

                {/* 底部:题目数量 + 箭头 */}
                <div className="flex items-center justify-between">
                  <span className="text-sm text-ink/40">
                    {loading ? (
                      '加载中...'
                    ) : (
                      <>{count > 0 ? `${count} 道题` : '暂无题目'}</>
                    )}
                  </span>
                  <ArrowRight className="w-5 h-5 text-ink/30 group-hover:text-accent group-hover:translate-x-1 transition-all" />
                </div>
              </div>
            </Link>
          );
        })}
      </div>
    </div>
  );
}
