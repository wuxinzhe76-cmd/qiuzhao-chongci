'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import dynamic from 'next/dynamic';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { questionApi, judgeApi } from '@/lib/api';
import type { QuestionVO, SubmissionVO } from '@/types';
import { Play, Loader2, CheckCircle, XCircle, Clock, AlertCircle, Eye, EyeOff } from 'lucide-react';

// Monaco 编辑器动态加载(避免 SSR)
const MonacoEditor = dynamic(() => import('@monaco-editor/react').then((m) => m.default), {
  ssr: false,
  loading: () => <div className="h-full flex items-center justify-center text-ink/40">编辑器加载中...</div>,
});

const DEFAULT_JAVA_CODE = `import java.util.Scanner;

public class Solution {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        // 在这里写你的代码
    }
}`;

const STATUS_CONFIG: Record<string, { text: string; icon: any; cls: string }> = {
  ACCEPTED: { text: '通过', icon: CheckCircle, cls: 'text-green-600 bg-green-50' },
  WRONG_ANSWER: { text: '答案错误', icon: XCircle, cls: 'text-red-600 bg-red-50' },
  TIME_LIMIT_EXCEEDED: { text: '超时', icon: Clock, cls: 'text-amber-600 bg-amber-50' },
  RUNTIME_ERROR: { text: '运行错误', icon: AlertCircle, cls: 'text-purple-600 bg-purple-50' },
  PENDING: { text: '排队中', icon: Loader2, cls: 'text-gray-500 bg-gray-50' },
  JUDGING: { text: '判题中', icon: Loader2, cls: 'text-blue-500 bg-blue-50' },
};

export default function ProblemDetailPage() {
  const { id } = useParams();
  const router = useRouter();
  const questionId = Number(id);

  const [question, setQuestion] = useState<QuestionVO | null>(null);
  const [code, setCode] = useState(DEFAULT_JAVA_CODE);
  const [language, setLanguage] = useState('java');
  const [submitting, setSubmitting] = useState(false);
  const [submission, setSubmission] = useState<SubmissionVO | null>(null);
  const [loading, setLoading] = useState(true);
  const [showAnswer, setShowAnswer] = useState(false); // 八股题:是否显示参考答案

  // 加载题目
  useEffect(() => {
    (async () => {
      try {
        const res = await questionApi.get(questionId);
        setQuestion(res.data);
      } catch (err) {
        console.error(err);
      } finally {
        setLoading(false);
      }
    })();
  }, [questionId]);

  // 提交代码(算法题)
  const handleSubmit = async () => {
    setSubmitting(true);
    setSubmission(null);
    try {
      const res = await judgeApi.submit({ questionId, languageCode: language, code });
      const submissionId = res.data;
      const poll = async () => {
        try {
          const statusRes = await judgeApi.status(submissionId);
          setSubmission(statusRes.data);
          if (statusRes.data.status === 'PENDING' || statusRes.data.status === 'JUDGING') {
            setTimeout(poll, 1000);
          } else {
            setSubmitting(false);
          }
        } catch { setSubmitting(false); }
      };
      poll();
    } catch (err) { setSubmitting(false); }
  };

  if (loading) return <div className="p-12 text-center text-ink/40">加载中...</div>;
  if (!question) return <div className="p-12 text-center text-ink/40">题目不存在</div>;

  const statusInfo = submission ? STATUS_CONFIG[submission.status] : null;
  const isProgramming = question.type === 'PROGRAMMING';

  return (
    <div className="animate-fade-in">
      {/* 顶部标题栏 */}
      <div className="mb-4">
        <div className="flex items-center gap-3">
          <span className={`px-2 py-0.5 rounded text-xs font-medium ${
            isProgramming ? 'bg-blue-50 text-blue-700' : 'bg-amber-50 text-amber-700'
          }`}>
            {isProgramming ? '算法题' : '八股题'}
          </span>
          <h1 className="font-display text-2xl font-bold">{question.title}</h1>
        </div>
        <div className="flex items-center gap-4 mt-2 text-sm text-ink/50">
          {isProgramming && <span>时间限制 {question.timeLimit || 1000}ms</span>}
          {isProgramming && <span>内存限制 {question.memoryLimit || 256}MB</span>}
          {question.tags?.map((tag) => (
            <span key={tag} className="px-2 py-0.5 rounded bg-surface-subtle">{tag}</span>
          ))}
        </div>
      </div>

      {isProgramming ? (
        /* ========== 算法题:左右分栏(大屏) / 上下堆叠(小屏) ========== */
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 lg:h-[calc(100vh-16rem)]">
          {/* 左:题目描述 */}
          <div className="bg-white rounded-2xl border border-surface-border overflow-hidden flex flex-col max-h-[40vh] lg:max-h-none">
            <div className="px-5 py-3 border-b border-surface-border font-medium text-sm text-ink/70">题目描述</div>
            <div className="flex-1 overflow-y-auto px-5 py-4">
              <div className="prose prose-sm max-w-none text-ink/80 leading-relaxed">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {question.content || '题目内容待补充'}
                </ReactMarkdown>
              </div>
            </div>
          </div>

          {/* 右:代码编辑器 + 提交 */}
          <div className="bg-white rounded-2xl border border-surface-border overflow-hidden flex flex-col h-[50vh] lg:h-auto">
            <div className="px-5 py-3 border-b border-surface-border flex items-center justify-between">
              <select value={language} onChange={(e) => setLanguage(e.target.value)}
                className="text-sm bg-surface-subtle border border-surface-border rounded-lg px-3 py-1 focus:outline-none">
                <option value="java">Java</option>
                <option value="python3">Python 3</option>
              </select>
              <button onClick={handleSubmit} disabled={submitting}
                className="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-lg bg-ink text-cream text-sm font-medium hover:bg-ink/90 transition-colors disabled:opacity-50">
                {submitting ? <><Loader2 className="w-4 h-4 animate-spin" /> 判题中</> : <><Play className="w-4 h-4" /> 提交</>}
              </button>
            </div>
            <div className="flex-1 min-h-0">
              <MonacoEditor language={language === 'python3' ? 'python' : 'java'} value={code}
                onChange={(val) => setCode(val || '')} theme="vs"
                options={{ fontSize: 14, minimap: { enabled: false }, scrollBeyondLastLine: false, automaticLayout: true, tabSize: 4 }} />
            </div>
            {submission && statusInfo && (
              <div className={`px-5 py-3 border-t border-surface-border ${statusInfo.cls}`}>
                <div className="flex items-center gap-2">
                  <statusInfo.icon className={`w-4 h-4 ${submission.status === 'JUDGING' || submission.status === 'PENDING' ? 'animate-spin' : ''}`} />
                  <span className="font-medium text-sm">{statusInfo.text}</span>
                  {submission.executionTime && <span className="text-xs opacity-70">· {submission.executionTime}ms</span>}
                  {submission.passedTestCase != null && submission.totalTestCase != null && (
                    <span className="text-xs opacity-70">· {submission.passedTestCase}/{submission.totalTestCase} 用例通过</span>
                  )}
                </div>
                {submission.errorMessage && (
                  <div className="mt-2 text-xs font-mono opacity-70 whitespace-pre-wrap">{submission.errorMessage}</div>
                )}
              </div>
            )}
          </div>
        </div>
      ) : (
        /* ========== 八股题:题目 + 参考答案 ========== */
        <div className="max-w-3xl mx-auto">
          {/* 题目内容 */}
          <div className="bg-white rounded-2xl border border-surface-border overflow-hidden mb-4">
            <div className="px-6 py-3 border-b border-surface-border font-medium text-sm text-ink/70">题目内容</div>
            <div className="px-6 py-5">
              <div className="prose prose-sm max-w-none text-ink/80 leading-relaxed">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {question.content || '题目内容待补充'}
                </ReactMarkdown>
              </div>
            </div>
          </div>

          {/* 参考答案(默认隐藏,点击展开) */}
          <div className="bg-white rounded-2xl border border-surface-border overflow-hidden">
            <button
              onClick={() => setShowAnswer(!showAnswer)}
              className="w-full px-6 py-3 border-b border-surface-border flex items-center justify-between hover:bg-surface-subtle/50 transition-colors"
            >
              <span className="font-medium text-sm text-ink/70 flex items-center gap-2">
                {showAnswer ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                参考答案
              </span>
              <span className="text-xs text-ink/40">{showAnswer ? '点击隐藏' : '点击查看'}</span>
            </button>
            {showAnswer && (
              <div className="px-6 py-5 animate-fade-in">
                <div className="prose prose-sm max-w-none text-ink/80 leading-relaxed">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                    {question.answer || '暂无参考答案'}
                  </ReactMarkdown>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
