# 前端修改笔记

## 一、当前前端状态

| 页面 | 路径 | 功能 | 状态 |
|------|------|------|------|
| 首页 | `/` | Hero + RAG 搜索(Quick Ask) | 已有 |
| 登录 | `/login` | 登录注册 | 已有 |
| 题目列表 | `/problems` | 题库浏览 | 已有 |
| 题目详情 | `/problems/[id]` | 题目+代码编辑+判题 | 已有 |
| 面试 | `/interview` | AI 模拟面试 | 已有 |
| 算法 | `/algorithms` | 算法题 | 已有 |
| 题库 | `/banks` | 题库管理 | 已有 |

API层已完整对接(interviewApi + ragApi),前端无需大改。

## 二、需要优化的地方

### 面试页面优化
- 掌握度实时显示:每轮回答后显示 currentTopicMastery 进度条(红<60/黄60-80/绿>80)
- 面试报告展示:结束后展示AI评估报告(优势/不足/建议)+学习路径
- 面试历史:历史面试列表+详情回看

### Quick Ask 优化
- 引用来源展示:显示命中的题库题目(questionId+title)
- 联网来源标注:显示webSearch的URL,标注"参考"
- 保存到知识库:答案下方加"存入我的知识库"按钮
- 缓存命中提示:显示cacheHit标识

### 新增页面(可选)
- 面试报告页 `/interview/report/[id]`
- 知识库管理页 `/knowledge-base`

## 三、实施计划

Phase 1:面试页面优化(与后端重构同步)
- 增加掌握度进度条
- 面试结束后展示报告

Phase 2:Quick Ask 优化
- RagSearch组件增强(展示sourceQuestions + webSources)
- 加"存入知识库"按钮

Phase 3:新增页面
- 面试报告页
- 知识库管理页

## 四、前端代码规范

- 组件:PascalCase(InterviewPage)
- 状态管理:Zustand(全局) + useState(局部)
- 数据获取:SWR或直接axios
- 样式:Tailwind CSS,无自定义CSS
- 类型:TypeScript strict mode
- API调用:统一走lib/api.ts,不直接axios
