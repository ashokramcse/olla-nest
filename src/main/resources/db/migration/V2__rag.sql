-- RAG: document knowledge base
CREATE TABLE IF NOT EXISTS rag_documents (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL,
  size INTEGER NOT NULL DEFAULT 0,
  chunk_count INTEGER NOT NULL DEFAULT 0,
  uploaded_by TEXT NOT NULL,
  scope TEXT NOT NULL DEFAULT 'global',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS rag_chunks (
  id TEXT PRIMARY KEY,
  document_id TEXT NOT NULL,
  chunk_index INTEGER NOT NULL,
  content TEXT NOT NULL,
  embedding_json TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY (document_id) REFERENCES rag_documents(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_rag_chunks_doc ON rag_chunks(document_id);
