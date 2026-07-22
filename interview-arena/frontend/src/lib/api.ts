import request from './request';
import type {
  BaseResponse,
  LoginVO,
  UserVO,
  QuestionVO,
  Page,
  QuestionQueryDTO,
  SubmissionVO,
  QuestionSubmitDTO,
  RagChatResponse,
  InterviewStartDTO,
  InterviewAnswerDTO,
  InterviewStartVO,
  InterviewAnswerVO,
  QuickAskDTO,
  QuickAskResponse,
  SaveToKbDTO,
} from '@/types';

// ========== 用户 ==========
export const userApi = {
  register: (data: { username: string; password: string }) =>
    request.post<any, BaseResponse<UserVO>>('/api/user/register', data),
  login: (data: { username: string; password: string }) =>
    request.post<any, BaseResponse<LoginVO>>('/api/user/login', data),
  logout: () => request.post<any, BaseResponse<void>>('/api/user/logout'),
  me: () => request.get<any, BaseResponse<UserVO>>('/api/user/me'),
};

// ========== 题目 ==========
export const questionApi = {
  list: (data: QuestionQueryDTO) =>
    request.post<any, BaseResponse<Page<QuestionVO>>>('/api/question/list/page/vo', data),
  get: (id: number) =>
    request.get<any, BaseResponse<QuestionVO>>(`/api/question/get/vo/${id}`),
};

// ========== 判题 ==========
export const judgeApi = {
  submit: (data: QuestionSubmitDTO) =>
    request.post<any, BaseResponse<number>>('/api/judge/submit', data),
  status: (submissionId: number) =>
    request.get<any, BaseResponse<SubmissionVO>>(`/api/judge/status/${submissionId}`),
  result: (submissionId: number) =>
    request.get<any, BaseResponse<any>>(`/api/judge/result/${submissionId}`),
  // 本地测试用:同步判题
  testSync: (submissionId: number) =>
    request.post<any, BaseResponse<string>>(`/api/judge/test-sync/${submissionId}`),
};

// ========== RAG ==========
export const ragApi = {
  chat: (message: string) =>
    request.post<any, BaseResponse<RagChatResponse>>('/api/rag/chat', { message }),
  suggest: (prefix: string, limit = 10) =>
    request.get<any, BaseResponse<string[]>>('/api/rag/suggest', { params: { prefix, limit } }),
  importQuestions: () =>
    request.post<any, BaseResponse<number>>('/api/rag/import'),
  quickAsk: (data: QuickAskDTO) =>
    request.post<any, BaseResponse<QuickAskResponse>>('/api/rag/quick-ask', data),
  saveToKb: (data: SaveToKbDTO) =>
    request.post<any, BaseResponse<boolean>>('/api/rag/save-to-kb', data),
};

// ========== AI 面试 ==========
export const interviewApi = {
  start: (data: InterviewStartDTO) =>
    request.post<any, BaseResponse<InterviewStartVO>>('/api/interview/start', data),
  answer: (data: InterviewAnswerDTO) =>
    request.post<any, BaseResponse<InterviewAnswerVO>>('/api/interview/answer', data),
  end: (sessionId: number) =>
    request.post<any, BaseResponse<boolean>>(`/api/interview/end/${sessionId}`),
};
