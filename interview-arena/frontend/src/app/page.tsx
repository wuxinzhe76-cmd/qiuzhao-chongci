import Link from 'next/link';
import { ArrowRight, Brain, Code2, Trophy } from 'lucide-react';
import RagSearch from '@/components/RagSearch';

export default function HomePage() {
  return (
    <div className="animate-fade-in">
      {/* Hero 区 */}
      <section className="relative overflow-hidden py-20 px-6">
        {/* 装饰圆 */}
        <div className="absolute top-10 right-10 w-64 h-64 rounded-full bg-accent-light opacity-40 blur-3xl" />
        <div className="absolute bottom-10 left-10 w-48 h-48 rounded-full bg-amber-100 opacity-50 blur-2xl" />

        <div className="relative max-w-3xl mx-auto text-center">
          <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full bg-surface-subtle border border-surface-border mb-6">
            <Brain className="w-4 h-4 text-accent" />
            <span className="text-sm text-ink/70">AI 原生面试平台</span>
          </div>

          <h1 className="font-display text-5xl sm:text-6xl font-bold tracking-tight mb-6">
            刷题 · 判题 · <span className="text-accent">AI 面试</span>
          </h1>

          <p className="text-lg text-ink/60 mb-10 leading-relaxed">
            431 道大厂算法题,在线编写代码,Docker 沙箱判题。
            <br />
            AI 模拟面试,深度追问,像真实面试官一样考察你。
          </p>

          {/* RAG AI 搜索 */}
          <RagSearch />

          {/* 行动按钮 */}
          <div className="flex items-center justify-center gap-4 mt-8">
            <Link
              href="/problems"
              className="inline-flex items-center gap-2 px-6 py-3 rounded-xl bg-ink text-cream font-medium hover:bg-ink/90 transition-all hover:scale-105"
            >
              开始刷题 <ArrowRight className="w-4 h-4" />
            </Link>
            <Link
              href="/login"
              className="inline-flex items-center gap-2 px-6 py-3 rounded-xl border border-surface-border bg-white font-medium hover:bg-surface-subtle transition-colors"
            >
              登录
            </Link>
          </div>
        </div>
      </section>

      {/* 特性卡片 */}
      <section className="grid grid-cols-1 md:grid-cols-3 gap-6 px-6 max-w-5xl mx-auto">
        {[
          { icon: Code2, title: '在线编程', desc: 'Monaco 编辑器,支持 Java / Python,实时编译运行' },
          { icon: Trophy, title: '自动判题', desc: 'Docker 沙箱隔离执行,测试用例自动对比' },
          { icon: Brain, title: 'AI 模拟面试', desc: '智能追问,结构化评估,生成面试报告' },
        ].map((f, i) => (
          <div
            key={i}
            className="card-hover p-6 rounded-2xl bg-white border border-surface-border"
            style={{ animationDelay: `${i * 100}ms` }}
          >
            <div className="w-12 h-12 rounded-xl bg-accent-light flex items-center justify-center mb-4">
              <f.icon className="w-6 h-6 text-accent" />
            </div>
            <h3 className="font-display text-lg font-bold mb-2">{f.title}</h3>
            <p className="text-sm text-ink/60 leading-relaxed">{f.desc}</p>
          </div>
        ))}
      </section>
    </div>
  );
}
