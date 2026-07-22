import axios, { type AxiosResponse } from 'axios';
import type { BaseResponse } from '@/types';

const request = axios.create({
  baseURL: '', // 走 Next.js rewrite 代理到 localhost:8080
  timeout: 10000,
});

// 请求拦截:自动加 token
request.interceptors.request.use((config) => {
  if (typeof window !== 'undefined') {
    const token = localStorage.getItem('accessToken');
    if (token) {
      config.headers.Authorization = token;
    }
  }
  return config;
});

// 响应拦截:统一处理 code + 401 跳登录
request.interceptors.response.use(
  ((response: AxiosResponse) => {
    const data = response.data as BaseResponse<unknown>;
    if (data.code === 0) {
      return data;
    }
    // 业务错误
    if (data.code === 40100) {
      // 未登录,跳登录页
      if (typeof window !== 'undefined') {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        window.location.href = '/login';
      }
    }
    return Promise.reject(new Error(data.message || '请求失败'));
  }) as unknown as Parameters<typeof request.interceptors.response.use>[0],
  (error) => Promise.reject(error)
);

export default request;
