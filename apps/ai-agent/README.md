# QualityOps AI Agent

Python FastAPI service for AI-powered QA features. Phase 6.

## Stack
- Python 3.12
- FastAPI (async API framework)
- LangChain / LangGraph (LLM orchestration)
- OpenAI / Anthropic (LLM providers)
- Pinecone or pgvector (vector database)
- Redis (conversation memory, caching)

## Features (planned)
- RAG over test results and codebase
- Tool-use agent (queries API, reads code, runs tests, files bugs)
- Failure analysis and triage
- Test generation from OpenAPI specs and PR diffs
- Streaming chat UI in the React dashboard

## Run locally
```bash
cd apps/ai-agent
pip install -e ".[dev]"
uvicorn app.main:app --reload --port 8000
```

Requires `OPENAI_API_KEY` or `ANTHROPIC_API_KEY` environment variable.
