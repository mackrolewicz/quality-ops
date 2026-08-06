---
name: ai-engineering
description: Use this skill when building the AI agent, working with RAG pipelines, LangChain, vector databases, embeddings, or LLM integrations. Covers the full AI engineering stack for this project.
---

# AI Engineering patterns

This skill covers how AI/ML features are built in this project. Load it
when working on the `apps/ai-agent/` service or any LLM integration.

## 1. AI service architecture

```
apps/ai-agent/                    # Python FastAPI service
├── pyproject.toml                # dependencies (Poetry or pip)
├── app/
│   ├── main.py                   # FastAPI entry point
│   ├── config.py                 # settings, API keys from env
│   ├── api/
│   │   ├── routes.py             # REST endpoints
│   │   └── websocket.py          # streaming responses
│   ├── agents/
│   │   ├── qa_agent.py           # main QA agent (tool-use)
│   │   ├── triage_agent.py       # failure triage agent
│   │   └── test_gen_agent.py     # test generation agent
│   ├── chains/
│   │   ├── rag_chain.py          # RAG retrieval chain
│   │   ├── analysis_chain.py     # failure analysis chain
│   │   └── generation_chain.py   # test code generation chain
│   ├── tools/
│   │   ├── test_results.py       # query platform API for results
│   │   ├── source_code.py        # read codebase files
│   │   ├── playwright_runner.py  # execute browser tests
│   │   ├── github_issues.py      # create/read GitHub issues
│   │   └── database.py           # read-only Postgres queries
│   ├── embeddings/
│   │   ├── indexer.py            # document ingestion pipeline
│   │   ├── chunker.py            # text chunking strategies
│   │   └── store.py              # vector store abstraction
│   ├── memory/
│   │   ├── conversation.py       # short-term (Redis)
│   │   └── knowledge.py          # long-term (Postgres)
│   └── prompts/
│       ├── system.py             # system prompts
│       ├── templates.py          # prompt templates
│       └── few_shot.py           # few-shot examples
└── tests/
    ├── test_chains.py
    ├── test_tools.py
    └── test_agents.py
```

## 2. RAG pipeline

### What is RAG?

Retrieval-Augmented Generation: instead of asking the LLM to answer from
its training data alone, you first **retrieve** relevant documents from
your own data, then **augment** the prompt with that context, then
**generate** the answer.

```
User: "Why did the login test fail?"
         │
         ▼
┌─────────────────────┐
│ 1. RETRIEVE         │  Query vector DB for similar failures,
│    relevant context  │  test logs, code snippets
└─────────┬───────────┘
          │ top-K results
          ▼
┌─────────────────────┐
│ 2. AUGMENT          │  Build prompt: system + context + question
│    the prompt        │
└─────────┬───────────┘
          │ enriched prompt
          ▼
┌─────────────────────┐
│ 3. GENERATE         │  LLM produces answer grounded in your data
│    the answer        │
└─────────────────────┘
```

### Embedding pipeline

```python
from langchain_openai import OpenAIEmbeddings
from langchain_community.vectorstores import PGVector

embeddings = OpenAIEmbeddings(model="text-embedding-3-small")

vectorstore = PGVector(
    connection_string=settings.DATABASE_URL,
    embedding_function=embeddings,
    collection_name="test_failures",
)
```

### Chunking strategy

Split documents into chunks that are:
- Small enough to embed meaningfully (~500-1000 tokens).
- Large enough to contain useful context.
- Overlapping slightly (50-100 tokens) to avoid losing context at boundaries.

```python
from langchain.text_splitter import RecursiveCharacterTextSplitter

splitter = RecursiveCharacterTextSplitter(
    chunk_size=1000,
    chunk_overlap=100,
    separators=["\n\n", "\n", ". ", " "],
)
```

### What to index

| Data source | What to embed | Why |
|---|---|---|
| Test results | Error messages, stack traces | Find similar past failures |
| Test code | Test case source code | Understand what tests do |
| Application code | Source code with docstrings | Agent understands the system |
| Run logs | Execution logs, timing data | Debug performance issues |
| Bug reports | Issue descriptions, resolutions | Learn from past fixes |

## 3. Tool-use agent pattern

```python
from langchain.agents import AgentExecutor, create_tool_calling_agent
from langchain_openai import ChatOpenAI

llm = ChatOpenAI(model="gpt-4o", temperature=0)

tools = [
    query_test_results,    # call your platform API
    read_source_code,      # read files from the repo
    run_playwright_test,   # execute a browser test
    create_github_issue,   # file a bug report
    search_similar_failures,  # vector search
]

agent = create_tool_calling_agent(llm, tools, prompt)
executor = AgentExecutor(agent=agent, tools=tools, verbose=True)

result = executor.invoke({
    "input": "Analyze the last regression run and create issues for real bugs"
})
```

### Tool definition pattern

```python
from langchain.tools import tool

@tool
def query_test_results(run_id: str) -> str:
    """Query the QualityOps API for test results of a specific run."""
    response = httpx.get(
        f"{settings.API_URL}/api/v1/runs/{run_id}/results",
        headers={"Authorization": f"Bearer {settings.API_TOKEN}"},
    )
    return response.json()
```

## 4. LLM provider abstraction

Never hardcode a specific LLM. Use an abstraction so you can swap providers:

```python
from langchain_openai import ChatOpenAI
from langchain_anthropic import ChatAnthropic

def get_llm(provider: str = "openai"):
    match provider:
        case "openai":
            return ChatOpenAI(model="gpt-4o", temperature=0)
        case "anthropic":
            return ChatAnthropic(model="claude-sonnet-4-20250514", temperature=0)
```

## 5. Prompt management

Store prompts as templates, not hardcoded strings:

```python
FAILURE_ANALYSIS_PROMPT = """You are a QA engineer analyzing test failures.

Given the following test results and context, determine:
1. Is this a real bug, a flaky test, or an infrastructure issue?
2. What is the root cause?
3. What should the team do next?

Test results:
{test_results}

Similar past failures:
{similar_failures}

Source code context:
{source_code}

Provide a structured analysis."""
```

## 6. Cost control

LLM calls are expensive. Control costs:

- **Cache responses** in Redis (same question → same answer).
- **Use smaller models** for simple tasks (classification, extraction).
- **Use larger models** only for complex reasoning (analysis, generation).
- **Rate limit** LLM calls per org per hour.
- **Track usage** per request (tokens in, tokens out, cost).
- **Set hard limits** on tokens per request (max_tokens).

## 7. Evaluation

You can't improve what you can't measure:

```python
test_cases = [
    {"input": "Why did login test fail?", "expected_contains": ["timeout", "auth"]},
    {"input": "Is this flaky?", "expected_label": "flaky"},
]

for case in test_cases:
    result = agent.invoke(case["input"])
    assert case["expected_contains"] in result  # basic check
```

Use LangSmith or Langfuse to trace every chain execution and review quality.

## 8. Security for AI features

- **Never pass secrets to LLM** — strip tokens, passwords from context.
- **Validate tool outputs** — the agent calls tools, but you verify results.
- **Rate limit AI endpoints** — expensive, abuse-prone.
- **Sanitize user input** — prevent prompt injection.
- **Audit AI actions** — log every tool call the agent makes.
- **Read-only DB access** — the agent should NEVER write to Postgres directly.

## 9. Testing AI features

- **Unit test tools** — mock the API, test each tool in isolation.
- **Unit test chains** — mock the LLM, test prompt construction.
- **Integration test** — real LLM call, verify response quality.
- **Evaluation set** — curated set of questions with expected answers.
- **No flaky AI tests** — use `temperature=0` and seed for reproducibility.
