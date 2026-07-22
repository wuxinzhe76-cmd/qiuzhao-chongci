import { create } from 'zustand';
import type { UserVO } from '@/types';
import { userApi } from '@/lib/api';

interface UserState {
  user: UserVO | null;
  accessToken: string | null;
  setLogin: (user: UserVO, accessToken: string, refreshToken: string) => void;
  logout: () => void;
  loadFromStorage: () => Promise<void>;
}

export const useUserStore = create<UserState>((set) => ({
  user: null,
  accessToken: null,
  setLogin: (user, accessToken, refreshToken) => {
    localStorage.setItem('accessToken', accessToken);
    localStorage.setItem('refreshToken', refreshToken);
    set({ user, accessToken });
  },
  logout: () => {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    set({ user: null, accessToken: null });
  },
  loadFromStorage: async () => {
    const token = localStorage.getItem('accessToken');
    if (!token) return;
    // 先同步设置 token,让 UI 立即反映登录状态(避免刷新瞬间显示未登录)
    set({ accessToken: token });
    // 再异步验证 token 是否有效
    try {
      const res = await userApi.me();
      set({ user: res.data, accessToken: token });
    } catch {
      // token 无效,清除
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      set({ user: null, accessToken: null });
    }
  },
}));
