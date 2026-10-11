```
企业级 AI / RAG 知识库与 Agent 调度引擎 (Enterprise AI & RAG Engine)
适合展现：最新技术潮（Spring AI）、大模型落地、数据处理管道
为什么适合德国/欧洲市场？ 欧洲企业目前非常渴望将 AI 引入内部业务，但极度害怕商业敏感数据泄漏给 OpenAI。使用本地/欧洲合规模型（如 Ollama 部署本地 LLM 或 Azure OpenAI EU Region）搭建立即落地的 RAG 系统是绝对的加分项。
业务场景： 企业内部 PDF / Markdown 技术文档导入 -> 文本切片（Chunking）与向量化（Embedding）-> 存储至向量数据库 -> 结合上下文调用大模型回答员工提问。
硬核技术栈：
Core: Java 21, Spring AI (取代 Python 框架，展示纯正的 Java AI 生态能力)
Vector DB: PostgreSQL + pgvector 或 Qdrant
LLM Engine: Ollama (本地运行 Llama 3 / DeepSeek) 或 Azure OpenAI (EU region)
Document Pipeline: Apache Tika (解析 PDF/Word/DOCX)
面试谈资与亮点：
“使用 Spring AI 搭建了全链路合规的 RAG 管道，结合 pgvector 实现了混合搜索（Hybrid Search: 关键词 + 语义向量）。”
“为了保护商业机密，数据处理全流程在本地 Docker (Ollama) 中完成，避免了任何敏感数据出境的问题。”

```

