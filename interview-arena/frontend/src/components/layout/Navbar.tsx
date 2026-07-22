'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useUserStore } from '@/store/user';
import { userApi } from '@/lib/api';
import { Brain, Code2, LogOut, User } from 'lucide-react';

export function Navbar() {
  const { user, accessToken, logout, loadFromStorage } = useUserStore();
  const router = useRouter();

  // 首次加载:从 localStorage 恢复登录状态
  useEffect(() => {
    loadFromStorage();
  }, [loadFromStorage]);

  // 同步登出状态到其他标签页
  useEffect(() => {
    const handleStorage = (e: StorageEvent) => {
      if (e.key === 'accessToken' && !e.newValue) {
        useUserStore.setState({ user: null, accessToken: null });
      }
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  const handleLogout = async () => {
    try {
      await userApi.logout();
    } catch {}
    logout();
    router.push('/login');
  };

  return (
    <nav className="sticky top-0 z-50 border-b border-surface-border bg-white/80 backdrop-blur-md">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        {/* Logo */}
        <Link href="/" className="flex items-center gap-2 group">
          <div className="w-9 h-9 rounded-lg bg-ink flex items-center justify-center group-hover:rotate-6 transition-transform">
            <Code2 className="w-5 h-5 text-cream" />
          </div>
          <span className="font-display text-xl font-bold tracking-tight">
            Interview Arena
          </span>
        </Link>

        {/* 导航 */}
        <div className="flex items-center gap-6">
          <Link
            href="/banks"
            className="text-sm font-medium text-ink/70 hover:text-ink transition-colors"
          >
            题库
          </Link>
          <Link
            href="/problems"
            className="text-sm font-medium text-ink/70 hover:text-ink transition-colors"
          >
            题目
          </Link>
          <Link
            href="/algorithms"
            className="text-sm font-medium text-ink/70 hover:text-ink transition-colors"
          >
            算法
          </Link>
          <Link
            href="/interview"
            className="flex items-center gap-1 text-sm font-medium text-accent hover:text-accent-dark transition-colors"
          >
            <Brain className="w-3.5 h-3.5" />
            AI 面试
          </Link>

          {accessToken ? (
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-surface-subtle">
                <User className="w-4 h-4 text-ink/60" />
                <span className="text-sm font-medium">{user?.username || '用户'}</span>
              </div>
              <button
                onClick={handleLogout}
                className="p-2 rounded-lg hover:bg-surface-subtle transition-colors"
                title="退出登录"
              >
                <LogOut className="w-4 h-4 text-ink/60" />
              </button>
            </div>
          ) : (
            <Link
              href="/login"
              className="px-4 py-1.5 rounded-lg bg-ink text-cream text-sm font-medium hover:bg-ink/90 transition-colors"
            >
              登录
            </Link>
          )}
        </div>
      </div>
    </nav>
  );
}
