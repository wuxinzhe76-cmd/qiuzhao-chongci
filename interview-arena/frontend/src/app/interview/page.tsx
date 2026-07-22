'use client';

import { useState, useRef, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useUserStore } from '@/store/user';
import { interviewApi } from '@/lib/api';
import {
  Brain, Send, Square, Loader2, MessageCircle, User,
  TrendingUp, CheckCircle2, AlertCircle, ArrowLeft,
} from 'lucide-react';

interface ChatMessage {
  role: 'interviewer' | 'user';
  content: string;
  mastery?: number;
  directive?: string;
}

export default function InterviewPage() {
  const router = useRouter();
  const { accessToken } = useUserStore();
  const [phase, setPhase] = useState<'config' | 'interview' | 'ended'>('config');
  const [mode, setMode] = useState<number>(2);
  const [bankId, setBankId] = useState<number | undefined>(undefined);
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [answer, setAnswer] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [mastery, setMastery] = useState<number | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!accessToken) {
      router.push('/login');
    }
  }, [accessToken, router]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  const handleStart = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await interviewApi.start({ mode, bankId });
      if (res.code === 0 && res.data) {
        setSessionId(res.data.sessionId);
        setMessages([{ role: 'interviewer', content: res.data.openingQuestion }]);
        setPhase('interview');
      } else {
        setError(res.message || '启动面试失败');
      }
    } catch (e: any) {
      setError(e.message || '网络错误');
    } finally {
      setLoading(false);
    }
  };

  const handleAnswer = async () => {
    if (!answer.trim() || !sessionId) return;
    const userAnswer = answer.trim();
    setAnswer('');
    setMessages(prev => [...prev, { role: 'user', content: userAnswer }]);
    setLoading(true);
    setError('');
    try {
      const res = await interviewApi.answer({ sessionId, answer: userAnswer });
      if (res.code === 0 && res.data) {
        const data = res.data;
        setMastery(data.currentTopicMastery);
        setMessages(prev => [...prev, {
          role: 'interviewer',
          content: data.replyToUser,
          mastery: data.currentTopicMastery,
          directive: data.actionDirective,
        }]);
        if (data.isEnded) {
          setPhase('ended');
        }
      } else {
        setError(res.message || '提交回答失败');
      }
    } catch (e: any) {
      setError(e.message || '网络错误');
    } finally {
      setLoading(false);
    }
  };

  const handleEnd = async () => {
    if (!sessionId) return;
    setLoading(true);
    try {
      await interviewApi.end(sessionId);
      setPhase('ended');
    } catch (e: any) {
      setError(e.message || '结束面试失败');
    } finally {
      setLoading(false);
    }
  };

  const directiveLabel = (d?: string) => {
    switch (d) {
      case 'DEEP_DIVE': return '深度追问';
      case 'NEXT_QUESTION': return '下一题';
      case 'END_INTERVIEW': return '面试结束';
      default: return '';
    }
  };

  // ========== 配置阶段 ==========
  if (phase === 'config') {
    return (
      <div className="animate-fade-in min-h-[calc(100vh-4rem)] flex items-center justify-center px-6">
        <div className="max-w-lg w-full">
          <div className="text-center mb-8">
            <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full bg-accent-light mb-4">
              <Brain className="w-4 h-4 text-accent" />
              <span className="text-sm text-accent-dark">AI 模拟面试</span>
            </div>
            <h1 className="font-display text-3xl font-bold mb-3">开始你的模拟面试</h1>
            <p className="text-ink/60">AI 面试官将根据你的水平智能出题、深度追问</p>
          </div>

          <div className="bg-white border border-surface-border rounded-2xl p-6 space-y-4">
            <div>
              <label className="block text-sm font-medium text-ink/80 mb-3">选择面试模式</label>
              <div className="grid grid-cols-2 gap-3">
                <button
                  onClick={() => setMode(2)}
                  className={`p-4 rounded-xl border-2 text-left transition-all ${
                    mode === 2
                      ? 'border-accent bg-accent-light'
                      : 'border-surface-border hover:border-ink/20'
                  }`}
                >
                  <div className="font-medium text-sm mb-1">大厂随机</div>
                  <div className="text-xs text-ink/50">全题库随机出题</div>
                </button>
                <button
                  onClick={() => setMode(1)}
                  className={`p-4 rounded-xl border-2 text-left transition-all ${
                    mode === 1
                      ? 'border-accent bg-accent-light'
                      : 'border-surface-border hover:border-ink/20'
                  }`}
                >
                  <div className="font-medium text-sm mb-1">指定题库</div>
                  <div className="text-xs text-ink/50">选择特定题库</div>
                </button>
              </div>
            </div>

            {mode === 1 && (
              <div>
                <label className="block text-sm font-medium text-ink/80 mb-2">题库 ID</label>
                <input
                  type="number"
                  value={bankId ?? ''}
                  onChange={e => setBankId(e.target.value ? Number(e.target.value) : undefined)}
                  placeholder="输入题库 ID"
                  className="w-full px-4 py-2.5 rounded-xl border border-surface-border bg-surface-subtle focus:outline-none focus:border-accent transition-colors"
                />
              </div>
            )}

            {error && (
              <div className="flex items-center gap-2 text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">
                <AlertCircle className="w-4 h-4 flex-shrink-0" />
                <span>{error}</span>
              </div>
            )}

            <button
              onClick={handleStart}
              disabled={loading}
              className="w-full py-3 rounded-xl bg-ink text-cream font-medium hover:bg-ink/90 transition-all disabled:opacity-50 flex items-center justify-center gap-2"
            >
              {loading ? (
                <><Loader2 className="w-4 h-4 animate-spin" /> 正在启动...</>
              ) : (
                <>开始面试</>
              )}
            </button>
          </div>
        </div>
      </div>
    );
  }

  // ========== 面试进行中 / 已结束 ==========
  return (
    <div className="animate-fade-in flex flex-col h-[calc(100vh-4rem)]">
      {/* 顶部状态栏 */}
      <div className="border-b border-surface-border bg-white px-6 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => router.push('/')}
            className="p-1.5 rounded-lg hover:bg-surface-subtle transition-colors"
          >
            <ArrowLeft className="w-4 h-4 text-ink/60" />
          </button>
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-accent-light flex items-center justify-center">
              <Brain className="w-4 h-4 text-accent" />
            </div>
            <div>
              <div className="text-sm font-medium">AI 模拟面试</div>
              <div className="text-xs text-ink/50">
                {phase === 'ended' ? '已结束' : `会话 #${sessionId}`}
              </div>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-4">
          {mastery !== null && (
            <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-surface-subtle">
              <TrendingUp className="w-4 h-4 text-accent" />
              <span className="text-sm font-medium">掌握度 {mastery}</span>
            </div>
          )}
          {phase === 'interview' && (
            <button
              onClick={handleEnd}
              disabled={loading}
              className="flex items-center gap-1.5 px-4 py-1.5 rounded-lg border border-red-200 text-red-600 text-sm font-medium hover:bg-red-50 transition-colors disabled:opacity-50"
            >
              <Square className="w-3.5 h-3.5" /> 结束面试
            </button>
          )}
        </div>
      </div>

      {/* 对话区域 */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto px-6 py-6 bg-cream">
        <div className="max-w-3xl mx-auto space-y-4">
          {messages.map((msg, i) => (
            <div
              key={i}
              className={`flex gap-3 ${msg.role === 'user' ? 'flex-row-reverse' : ''}`}
            >
              <div className={`flex-shrink-0 w-9 h-9 rounded-lg flex items-center justify-center ${
                msg.role === 'interviewer'
                  ? 'bg-accent-light'
                  : 'bg-ink'
              }`}>
                {msg.role === 'interviewer'
                  ? <Brain className="w-5 h-5 text-accent" />
                  : <User className="w-5 h-5 text-cream" />
                }
              </div>
              <div className={`max-w-[80%] ${msg.role === 'user' ? 'items-end' : 'items-start'} flex flex-col gap-1`}>
                {msg.directive && (
                  <div className="flex items-center gap-1.5">
                    {msg.directive === 'END_INTERVIEW'
                      ? <CheckCircle2 className="w-3.5 h-3.5 text-green-600" />
                      : <MessageCircle className="w-3.5 h-3.5 text-accent" />
                    }
                    <span className="text-xs font-medium text-ink/50">
                      {directiveLabel(msg.directive)}
                      {msg.mastery !== undefined && msg.mastery > 0 && ` · 掌握度 ${msg.mastery}`}
                    </span>
                  </div>
                )}
                <div className={`px-4 py-3 rounded-2xl ${
                  msg.role === 'interviewer'
                    ? 'bg-white border border-surface-border rounded-tl-sm'
                    : 'bg-ink text-cream rounded-tr-sm'
                }`}>
                  <div className="text-sm leading-relaxed whitespace-pre-wrap">{msg.content}</div>
                </div>
              </div>
            </div>
          ))}

          {loading && (
            <div className="flex gap-3">
              <div className="flex-shrink-0 w-9 h-9 rounded-lg bg-accent-light flex items-center justify-center">
                <Brain className="w-5 h-5 text-accent" />
              </div>
              <div className="px-4 py-3 rounded-2xl bg-white border border-surface-border rounded-tl-sm">
                <div className="flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full bg-ink/30 animate-bounce" style={{ animationDelay: '0ms' }} />
                  <span className="w-2 h-2 rounded-full bg-ink/30 animate-bounce" style={{ animationDelay: '150ms' }} />
                  <span className="w-2 h-2 rounded-full bg-ink/30 animate-bounce" style={{ animationDelay: '300ms' }} />
                </div>
              </div>
            </div>
          )}

          {phase === 'ended' && (
            <div className="text-center py-8">
              <div className="inline-flex items-center gap-2 px-6 py-3 rounded-full bg-green-50 border border-green-200 mb-4">
                <CheckCircle2 className="w-5 h-5 text-green-600" />
                <span className="font-medium text-green-800">面试已结束</span>
              </div>
              <p className="text-ink/60 mb-4">你的面试记录已保存，AI 正在生成面试报告</p>
              <button
                onClick={() => router.push('/')}
                className="px-6 py-2.5 rounded-xl bg-ink text-cream font-medium hover:bg-ink/90 transition-colors"
              >
                返回首页
              </button>
            </div>
          )}
        </div>
      </div>

      {/* 输入区域 */}
      {phase === 'interview' && (
        <div className="border-t border-surface-border bg-white px-6 py-4">
          <div className="max-w-3xl mx-auto">
            {error && (
              <div className="flex items-center gap-2 text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2 mb-3">
                <AlertCircle className="w-4 h-4 flex-shrink-0" />
                <span>{error}</span>
              </div>
            )}
            <div className="flex gap-3 items-end">
              <textarea
                value={answer}
                onChange={e => setAnswer(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    handleAnswer();
                  }
                }}
                placeholder="输入你的回答... (Enter 发送, Shift+Enter 换行)"
                rows={2}
                disabled={loading}
                className="flex-1 px-4 py-3 rounded-xl border border-surface-border bg-surface-subtle focus:outline-none focus:border-accent transition-colors resize-none disabled:opacity-50"
              />
              <button
                onClick={handleAnswer}
                disabled={loading || !answer.trim()}
                className="flex-shrink-0 w-12 h-12 rounded-xl bg-ink text-cream flex items-center justify-center hover:bg-ink/90 transition-colors disabled:opacity-30"
              >
                {loading
                  ? <Loader2 className="w-5 h-5 animate-spin" />
                  : <Send className="w-5 h-5" />
                }
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
