# Olla Nest Architecture

Olla Nest should use polyglot persistence for company and production deployments.

The default production architecture is:

- PostgreSQL for the structural source of truth
- MongoDB for the cognitive archive
- Redis for the real-time nerve system

The local MVP can run without Docker by using SQLite, a JSON document store, and in-memory realtime state. That fallback is for developer convenience only.

## Database Choice

### PostgreSQL vs SQLite vs MySQL

PostgreSQL is the best default SQL database for Olla Nest.

SQLite is excellent for local development and desktop-first prototypes because it is embedded, simple, and has no server. It is not the best company default for multi-user collaboration, access control, audit trails, billing, and future RAG.

MySQL is stable and widely used, but PostgreSQL is a better strategic choice here because it has stronger support for complex relational workloads, JSONB, extensions, and pgvector.

PostgreSQL also lets us keep RAG embeddings close to user, workspace, permission, and document metadata.

## Production Persistence Roles

### PostgreSQL: Structural Core

PostgreSQL is the source of truth.

Use it for:

- Users
- Groups
- Departments
- Workspaces
- Permissions
- Model registry
- Agent state machine
- Billing and quota records
- RAG metadata and embeddings with pgvector

### MongoDB: Cognitive Archive

MongoDB stores flexible AI interaction data.

Use it for:

- Chat history
- Thought traces
- Tool outputs
- Raw JSON artifacts
- Multi-modal response metadata
- Unpredictable AI output shapes

### Redis: Real-Time Nerve System

Redis handles fast, temporary, real-time state.

Use it for:

- Token streaming buffers
- WebSocket fanout
- User session presence
- Typing status
- Rate limiting
- Job queues
- Pub/Sub events

## Request Flow

1. User sends a request.
2. PostgreSQL verifies the user, workspace, permissions, and model access.
3. The router selects the best approved model for the request.
4. Redis tracks the live task state and token stream.
5. MongoDB stores thought traces, tool outputs, and the final chat message.
6. PostgreSQL stores final task status and any billing or audit-critical state.

## Application Stack Direction

The current MVP is a simple Express and browser app. It is intentionally small.

The recommended product stack direction is:

- Backend: Node.js TypeScript API
- Web: React + Vite
- Desktop: Tauri wrapping the web app for macOS, Windows, and Linux
- Mobile: React Native or Expo using shared API contracts
- Local AI: Ollama connector
- Production SQL: PostgreSQL with pgvector
- Production NoSQL document store: MongoDB
- Production realtime/cache: Redis

Why this direction:

- The same web UI can power browser and desktop.
- Tauri is lighter than Electron for a local-first app.
- Node/TypeScript keeps backend and frontend types shareable.
- React Native or Expo gives a practical mobile path later.
- PostgreSQL + MongoDB + Redis scales better than forcing all AI data into one database.
