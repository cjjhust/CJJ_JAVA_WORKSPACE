# 🚀 个人专属 Jarvis (Harness RAG 系统) 开发 TODO

> **项目目标**：构建一个完全自主可控的 AI 助手后端（Harness 拦截层）。实现长上下文记忆、多模态文件解析（PDF/MD/图片），并配合高性能前端（虚拟列表、局部流式渲染），打造媲美 DeepSeek 网页版的丝滑体验。
> **核心技术栈**：Spring Boot 3.x + LangChain4j + PostgreSQL (pgvector) + OpenRouter (免费大模型 API) + Vue 3/React 前端。

---

## 🏗️ 系统数据流向设计

1. **用户端 (前端)**：通过 HTTP 发送文字或多模态文件（PDF/MD/图片）上传。
2. **控制层 (Harness 拦截层 / Spring Boot)**：
   - 提取文本（PDFBox/大模型视觉解析）。
   - 调用 Embedding 模型，存入数据库。
   - 接收用户对话，进行向量检索（召回历史知识与文档）。
   - 组装最终的 Prompt。
3. **推理层 (OpenRouter)**：接收请求，流式返回大模型的推理结果。
4. **落盘层 (异步)**：将对话历史、提取的事实存入 PostgreSQL 与 pgvector 中。

---

## 📅 里程碑计划 (Milestones)

- [ ] **M1 (周末突击)**：跑通 OpenRouter 对接，实现基础对话和流式输出（SSE）。
- [ ] **M2 (第 1 周)**：完成多模态文件上传（PDF/MD/图片）与向量化入库（pgvector）。
- [ ] **M3 (第 2 周)**：实现 Harness 核心逻辑，结合向量检索与滑动窗口，实现 RAG 问答。
- [ ] **M4 (第 3 周)**：前端性能优化，实现 DeepSeek 级的虚拟列表与 Markdown 增量渲染。
- [ ] **M5 (第 4 周)**：实现后台定时任务，让系统自动总结长期记忆（自我进化）。

---

## 🛠️ 详细开发任务 (TODO List)

### 🟢 Phase 1: 基础设施与 LLM 对接 (M1)
- [ ] 初始化 Spring Boot 3.x 工程。
- [ ] 集成 `langchain4j-open-ai` 依赖（OpenRouter 完全兼容 OpenAI 接口）。
- [ ] 配置 OpenRouter API Key 和 Base URL (`https://openrouter.ai/api/v1`)。
- [ ] 写一个基础的 `/api/chat` 接口，测试模型响应。
- [ ] 引入 `SseEmitter` 实现流式响应（`stream: true`）。
- [ ] **前端基础**：写一个最简单的输入框和对话框，用 Fetch API 接收 SSE 流并打印到控制台。

### 🟡 Phase 2: RAG 检索引擎 (M2)
- [ ] 安装并配置 PostgreSQL 数据库，启用 `pgvector` 扩展。
- [ ] 设计数据表：
    *   `chat_sessions` (会话表)
    *   `chat_messages` (消息日志表)
    *   `knowledge_embeddings` (向量存储表：id, content, metadata, embedding)
- [ ] 实现**多模态文档解析**：
    *   PDF 解析：集成 `Apache PDFBox` 提取文本。
    *   MD 解析：标准文件读取。
    *   图片解析：调用 OpenRouter 的多模态模型进行 OCR 或图片内容描述。
- [ ] 实现文档分块（Chunking）：按段落或固定字符数（如 500 字）切分文本。
- [ ] 调用 Embedding 模型，将文本块转为向量，存入 pgvector。

### 🔵 Phase 3: Harness 核心拦截逻辑 (M3)
- [ ] 完善 `/api/chat` 接口，引入 `HarnessService`。
- [ ] **上下文组装机制 (Prompt Builder)**：
    *   读取当前 `sessionId` 最近 5 轮对话（滑动窗口）。
    *   将用户当前提问转为向量，去 pgvector 中搜索 Top 3 相关文档/记忆。
    *   组装 System Prompt（人设 + 检索到的知识 + 历史对话 + 当前问题）。
- [ ] **引用溯源**：在返回给前端的流中，附带本次回答引用的文档来源（文件名/段落）。
- [ ] **异步落盘**：使用 `@Async` 将本轮问答存入 `chat_messages` 表。

### 🔴 Phase 4: 高性能前端交互 (M4)
- [ ] **UI 布局**：参照 DeepSeek 网页版，左侧历史会话列表，右侧主对话框。
- [ ] **文件上传交互**：支持拖拽/点击上传 PDF/MD/图片，显示解析进度条。
- [ ] **流式 Markdown 增量渲染**：
    *   对接 Markdown 解析库（如 `marked.js` + `highlight.js`）。
    *   **难点**：SSE 每次吐出一个字，不能触发整个列表重新渲染。必须使用**局部 DOM 更新**或虚拟 DOM 优化，只更新最后一段代码块的内容。
- [ ] **虚拟列表 (Virtual List)**：
    *   引入 `vue-virtual-scroller` 或 `react-window`。
    *   仅渲染视口内的 10-20 条消息，防止 DOM 节点爆炸（解决“聊多了变卡”的问题）。
- [ ] 实现打字机光标效果和自动滚动到底部。

### 🟣 Phase 5: 长期记忆与自我进化 (M5)
- [ ] 编写 Spring `@Scheduled` 定时任务（如每天凌晨）。
- [ ] 提取当天的所有对话，让大模型总结：“用户今天遇到了什么问题？有什么关键结论？”
- [ ] 将总结出的“事实”重新向量化，存入专门的 `long_term_memory` 表。
- [ ] 在后续对话的 Prompt 中，优先注入长期记忆。

---

## ⚠️ 关键技术避坑指南

1. **大模型选择 (OpenRouter)**：OpenRouter 上的 free 模型（如 `google/gemini-flash-1.5-free` 或 `meta-llama/llama-3-8b-instruct:free`）在长上下文或高并发时可能会报 429 (Too Many Requests) 错误。**后端必须写好重试机制（Retry）或配置备用模型池**。
2. **前端流式不卡顿的秘诀**：不要用 `v-html` 去渲染流式输出的 Markdown！最好把消息拆分成“普通文本”和“代码块”组件，流式输出时，只在当前最后一个组件上做**局部文本追加 (append)**，而非整个字符串替换。
3. **图片解析**：对纯本地开源模型来说，图片 OCR 很难。直接利用 OpenRouter 的多模态大模型（Vision）生成一段“图片描述”，然后把这描述当作文本向量化。
4. **数据库索引**：pgvector 检索时，记得给 embedding 字段建 **HNSW 索引**，否则数据量上万后，查询速度会慢到让你怀疑人生。

---

## 💡 项目拓展 (未来可接小车)

- [ ] 在后端开辟 WebSocket 端点，用于给 ESP32 下发指令。
- [ ] 增加语音转录接口（对接阿里/讯飞的 ASR），将小车的麦克风音频直接转为文字进入 Harness。