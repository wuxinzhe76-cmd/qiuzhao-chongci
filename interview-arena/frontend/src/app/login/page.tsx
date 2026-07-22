'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useUserStore } from '@/store/user';
import { userApi } from '@/lib/api';

export default function LoginPage() {
  const router = useRouter();
  const { setLogin, accessToken, loadFromStorage } = useUserStore();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // 已登录用户自动跳转,不显示登录表单
  useEffect(() => {
    loadFromStorage();
  }, [loadFromStorage]);

  useEffect(() => {
    if (accessToken) {
      router.replace('/problems');
    }
  }, [accessToken, router]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');
    try {
      if (mode === 'login') {
        const res = await userApi.login({ username, password });
        setLogin(res.data.userInfo, res.data.accessToken, res.data.refreshToken);
        router.push('/problems');
      } else {
        await userApi.register({ username, password });
        // 注册成功自动登录
        const res = await userApi.login({ username, password });
        setLogin(res.data.userInfo, res.data.accessToken, res.data.refreshToken);
        router.push('/problems');
      }
    } catch (err: any) {
      setError(err.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-[calc(100vh-4rem)] flex items-center justify-center px-4">
      <div className="w-full max-w-md animate-slide-up">
        {/* 装饰 */}
        <div className="text-center mb-8">
          <div className="inline-block w-14 h-14 rounded-2xl bg-ink flex items-center justify-center mb-4">
            <span className="font-display text-2xl font-bold text-cream">IA</span>
          </div>
          <h1 className="font-display text-3xl font-bold">
            {mode === 'login' ? '欢迎回来' : '创建账号'}
          </h1>
          <p className="text-ink/50 mt-2">
            {mode === 'login' ? '登录开始刷题' : '注册开启你的面试备战之旅'}
          </p>
        </div>

        <div className="bg-white rounded-2xl border border-surface-border p-8 shadow-sm">
          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label className="block text-sm font-medium text-ink/70 mb-1.5">
                用户名
              </label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="w-full px-4 py-2.5 rounded-xl border border-surface-border bg-surface-subtle/50 focus:bg-white focus:border-accent focus:outline-none transition-colors"
                placeholder="输入用户名"
                required
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-ink/70 mb-1.5">
                密码
              </label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full px-4 py-2.5 rounded-xl border border-surface-border bg-surface-subtle/50 focus:bg-white focus:border-accent focus:outline-none transition-colors"
                placeholder="输入密码"
                required
              />
            </div>

            {error && (
              <div className="px-4 py-2.5 rounded-xl bg-red-50 border border-red-200 text-sm text-red-700">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full py-2.5 rounded-xl bg-ink text-cream font-medium hover:bg-ink/90 transition-colors disabled:opacity-50"
            >
              {loading ? '处理中...' : mode === 'login' ? '登录' : '注册'}
            </button>
          </form>

          <div className="mt-6 text-center text-sm text-ink/50">
            {mode === 'login' ? '没有账号?' : '已有账号?'}
            <button
              onClick={() => {
                setMode(mode === 'login' ? 'register' : 'login');
                setError('');
              }}
              className="ml-1 text-accent hover:underline font-medium"
            >
              {mode === 'login' ? '去注册' : '去登录'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
