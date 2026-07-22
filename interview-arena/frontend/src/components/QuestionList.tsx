'use client';

import { useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import Link from 'next/link';
import useSWR from 'swr';
import { questionApi } from '@/lib/api';
import type { QuestionVO, QuestionQueryDTO, Page } from '@/types';
import { Search, ChevronLeft, ChevronRight } from 'lucide-react';

const DIFFICULTY_LABEL: Record<string, { text: string; cls: string }> = {
  EASY: { text: '简单', cls: 'badge-easy' },
  MEDIUM: { text: '中等', cls: 'badge-medium' },
  HARD: { text: '困难', cls: 'badge-hard' },
};

const TYPE_LABEL: Record<string, { text: string; icon: string }> = {
  PROGRAMMING: { text: '算法', icon: '💻' },
  FILL_IN: { text: '八股', icon: '📝' },
  CHOICE: { text: '选择', icon: '○' },
};

const ALGO_SUBCATEGORIES = [
  '双指针', '链表', '动态规划', '字符串', '回溯', '栈', '单调栈',
  '二分查找', '贪心', '矩阵', '哈希表', '区间', '滑动窗口',
  '图论', '前缀和', '前缀树', '排序', '位运算', '树', '堆',
];

// 八股:第一层技术分类
const BAGWEN_TECHS = ['Redis', 'MySQL', 'Spring', 'SpringBoot', 'Java基础', 'Java并发', 'Java虚拟机', 'Java集合', 'MyBatis', '消息队列', '操作系统', '计算机网络', '设计模式'];

// 八股:第二层板块(按技术分类)
const BAGWEN_MODULES: Record<string, string[]> = {
  Redis: ['应用场景', '数据类型', '持久化', '内存管理', '事务', '集群', '缓存问题', '性能优化', '原理'],
  MySQL: ['索引', '事务', '锁', 'SQL优化', '架构', '日志'],
  Spring: ['IOC', 'AOP', 'Bean', '事务', 'MVC', '自动配置'],
  SpringBoot: ['自动配置', '启动流程', 'Starter', 'Actuator'],
  Java基础: ['集合', '并发', 'IO', '反射', '注解', '泛型'],
  Java并发: ['线程', '锁', '线程池', 'JMM', '并发工具'],
  Java虚拟机: ['内存区域', '垃圾回收', '类加载', '调优'],
  Java集合: ['List', 'Map', 'Set', 'Queue', '源码'],
  MyBatis: ['缓存', '动态SQL', '映射', '插件', '原理'],
  消息队列: ['Kafka', 'RabbitMQ', 'RocketMQ', '可靠性', '顺序'],
  操作系统: ['进程', '内存', '文件系统', 'IO', '调度'],
  计算机网络: ['TCP', 'HTTP', 'HTTPS', 'DNS', '网络安全'],
  设计模式: ['创建型', '结构型', '行为型', '实战应用'],
};

// SWR fetcher
const fetcher = (query: QuestionQueryDTO) => questionApi.list(query).then((res) => res.data);

interface QuestionListProps {
  title: string;
  subtitle?: string;
  fixedType?: string;
}

export function QuestionList({ title, subtitle, fixedType }: QuestionListProps) {
  const searchParams = useSearchParams();
  const category = searchParams.get('category');

  const [selectedTech, setSelectedTech] = useState<string>('Redis'); // 八股第一层:当前选中的技术
  const [selectedSub, setSelectedSub] = useState<string>('');        // 第二层:板块
  const [query, setQuery] = useState<QuestionQueryDTO>({
    current: 1,
    pageSize: 20,
    title: '',
    difficulty: '',
    type: fixedType || '',
    tags: fixedType === 'FILL_IN' ? ['Redis'] : undefined, // 八股默认查 Redis
  });

  // SWR 缓存:key 是 query 对象,切换回来时秒显示缓存
  const { data, isLoading, isValidating } = useSWR(
    query,
    fetcher,
    {
      keepPreviousData: true,
      revalidateOnFocus: false,
      dedupingInterval: 10000,
    }
  );

  const questions = data?.records || [];
  const total = data?.total || 0;
  const totalPages = Math.ceil(total / query.pageSize);

  const currentType = query.type || fixedType || '';

  // 八股:当前技术对应的板块列表
  const bagwenModules = currentType === 'FILL_IN' ? (BAGWEN_MODULES[selectedTech] || []) : [];

  // 第一层:切换技术(Redis → MySQL)
  const handleTechClick = (tech: string) => {
    setSelectedTech(tech);
    setSelectedSub('');
    setQuery({ ...query, tags: [tech], current: 1 });
  };

  // 第二层:切换板块(Redis → 应用场景)
  const handleSubClick = (sub: string) => {
    const newSub = selectedSub === sub ? '' : sub;
    setSelectedSub(newSub);
    setQuery({ ...query, tags: newSub ? [selectedTech, newSub] : [selectedTech], current: 1 });
  };

  const handleTypeChange = (t: string) => {
    setSelectedSub('');
    setQuery({ ...query, type: t, tags: undefined, current: 1 });
  };

  const showTypeFilter = !fixedType;
  const showLoading = isLoading && !data;

  return (
    <div className="animate-fade-in">
      {/* 标题 */}
      <div className="mb-8">
        <h1 className="font-display text-3xl font-bold mb-2">{title}</h1>
        <p className="text-ink/50">
          {subtitle || `共 ${total} 道题`}
          {isValidating && data && <span className="ml-2 text-xs text-ink/30">更新中...</span>}
        </p>
      </div>

      {/* 筛选栏 */}
      <div className="flex flex-wrap items-center gap-4 mb-3">
        <div className="relative flex-1 min-w-[240px] max-w-md">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-ink/40" />
          <input
            type="text"
            placeholder="搜索题目标题..."
            value={query.title}
            onChange={(e) => setQuery({ ...query, title: e.target.value, current: 1 })}
            className="w-full pl-10 pr-4 py-2 rounded-xl border border-surface-border bg-white focus:border-accent focus:outline-none transition-colors"
          />
        </div>

        {showTypeFilter && (
          <div className="flex gap-2">
            {['', 'PROGRAMMING', 'FILL_IN'].map((t) => (
              <button
                key={t}
                onClick={() => handleTypeChange(t)}
                className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                  query.type === t
                    ? 'bg-ink text-cream'
                    : 'bg-white border border-surface-border text-ink/70 hover:bg-surface-subtle'
                }`}
              >
                {t === '' ? '全部' : TYPE_LABEL[t].text}
              </button>
            ))}
          </div>
        )}

        <div className="flex gap-2">
          {['', 'EASY', 'MEDIUM', 'HARD'].map((d) => (
            <button
              key={d}
              onClick={() => setQuery({ ...query, difficulty: d, current: 1 })}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                query.difficulty === d
                  ? 'bg-ink text-cream'
                  : 'bg-white border border-surface-border text-ink/70 hover:bg-surface-subtle'
              }`}
            >
              {d === '' ? '全部' : DIFFICULTY_LABEL[d].text}
            </button>
          ))}
        </div>
      </div>

      {/* 八股:第一层技术分类 */}
      {currentType === 'FILL_IN' && (
        <div className="flex flex-wrap gap-2 mb-3 pb-3 border-b border-surface-border">
          {BAGWEN_TECHS.map((tech) => (
            <button
              key={tech}
              onClick={() => handleTechClick(tech)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                selectedTech === tech
                  ? 'bg-ink text-cream'
                  : 'bg-white border border-surface-border text-ink/70 hover:bg-surface-subtle'
              }`}
            >
              {tech}
            </button>
          ))}
        </div>
      )}

      {/* 子分类筛选栏(算法:套路 / 八股:板块) */}
      {currentType === 'PROGRAMMING' && (
        <div className="flex flex-wrap gap-2 mb-6 pb-4 border-b border-surface-border">
          {ALGO_SUBCATEGORIES.map((sub) => (
            <button
              key={sub}
              onClick={() => handleSubClick(sub)}
              className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${
                selectedSub === sub
                  ? 'bg-accent text-white'
                  : 'bg-surface-subtle text-ink/60 hover:bg-surface-border'
              }`}
            >
              {sub}
            </button>
          ))}
        </div>
      )}
      {currentType === 'FILL_IN' && bagwenModules.length > 0 && (
        <div className="flex flex-wrap gap-2 mb-6 pb-4 border-b border-surface-border">
          {bagwenModules.map((sub) => (
            <button
              key={sub}
              onClick={() => handleSubClick(sub)}
              className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${
                selectedSub === sub
                  ? 'bg-accent text-white'
                  : 'bg-surface-subtle text-ink/60 hover:bg-surface-border'
              }`}
            >
              {sub}
            </button>
          ))}
        </div>
      )}

      {/* 题目列表 */}
      <div className="bg-white rounded-2xl border border-surface-border overflow-hidden">
        {showLoading ? (
          <div className="p-12 text-center text-ink/40">加载中...</div>
        ) : questions.length === 0 ? (
          <div className="p-12 text-center text-ink/40">暂无题目</div>
        ) : (
          <div className="divide-y divide-surface-border">
            {questions.map((q, idx) => {
              const diff = DIFFICULTY_LABEL[q.difficulty || 'MEDIUM'] || DIFFICULTY_LABEL.MEDIUM;
              const typeInfo = TYPE_LABEL[q.type || 'PROGRAMMING'] || TYPE_LABEL.PROGRAMMING;
              return (
                <Link
                  key={q.id}
                  href={`/problems/${q.id}`}
                  className="flex items-center gap-4 px-6 py-4 hover:bg-surface-subtle/50 transition-colors group"
                >
                  <span className="text-sm text-ink/30 font-mono w-12">
                    {(query.current - 1) * query.pageSize + idx + 1}
                  </span>
                  <span className="text-base" title={typeInfo.text}>{typeInfo.icon}</span>
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-ink group-hover:text-accent transition-colors truncate">
                      {q.title}
                    </div>
                    {q.tags && q.tags.length > 0 && (
                      <div className="flex gap-1.5 mt-1">
                        {q.tags.slice(0, 3).map((tag) => (
                          <span
                            key={tag}
                            className="px-2 py-0.5 rounded text-xs bg-surface-subtle text-ink/60"
                          >
                            {tag}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                  <div className="hidden sm:flex items-center gap-2 text-sm text-ink/40">
                    <span>{q.acceptanceRate?.toFixed(1) || '0.0'}%</span>
                  </div>
                  <span className={`px-2.5 py-0.5 rounded-md text-xs font-medium border ${diff.cls}`}>
                    {diff.text}
                  </span>
                </Link>
              );
            })}
          </div>
        )}
      </div>

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 mt-6">
          <button
            onClick={() => setQuery({ ...query, current: Math.max(1, query.current - 1) })}
            disabled={query.current === 1}
            className="p-2 rounded-lg border border-surface-border bg-white disabled:opacity-40 hover:bg-surface-subtle"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <span className="px-4 py-2 text-sm text-ink/60">
            {query.current} / {totalPages}
          </span>
          <button
            onClick={() => setQuery({ ...query, current: Math.min(totalPages, query.current + 1) })}
            disabled={query.current === totalPages}
            className="p-2 rounded-lg border border-surface-border bg-white disabled:opacity-40 hover:bg-surface-subtle"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>
      )}
    </div>
  );
}
