# Olla Nest — Feature Traceability Matrix

Maps every `FEATURES.md` segment → controller/service/tables → existing test asset → status verified **in this audit run**.

**Status legend**
- ✅ **PROVEN** — exercised with captured evidence this run (live probe and/or green unit test confirmed).
- 🟢 **UNIT-COVERED** — a unit/integration test class exists and passes (green) but was not separately re-isolated this run.
- 🟡 **UNIT-RED** — a test exists but is currently failing (see BUG-003/004/005; test-debt, not proven product failure).
- ⚪ **NOT TESTED** — no execution this run (needs E2E/load/dynamic).

---

## Segment 2 — Authentication & Sessions
| Feature | Endpoint | Controller | Service | Tables | Test asset | Status |
|---|---|---|---|---|---|---|
| Local login | `POST /api/auth/login` | AuthController | AuthService, RateLimiterService | users, sessions, login_attempts | AuthControllerTest, AuthServiceTest, Soc2AuditTest | ✅ PROVEN (T1–T5, lockout T14) |
| Logout (CSRF) | `POST /api/auth/logout` | AuthController | AuthService | sessions | AuthControllerTest | ✅ PROVEN (T11/T12) |
| Current user | `GET /api/auth/me` | AuthController | AuthService | sessions | AuthControllerTest | ✅ PROVEN (T6/T7) |
| Session token/cookie | — | — | AuthService | sessions | AuthServiceTest, Soc2AuditTest | ✅ PROVEN (flags, isolation T13) |
| Brute-force lockout | — | AuthController | RateLimiterService | login_attempts | RateLimiterServiceTest | ✅ PROVEN (429 after 10) |
| Per-service cookie name | — | — | AuthService | — | AuthServiceTest | ✅ PROVEN (T13) + **BUG-001 fixed** |
| SSO OAuth/OIDC/SAML | `/api/auth/sso/*` | SsoController | SsoService | sso_providers, oauth_state | SsoServiceTest | 🟢 UNIT / ⚪ live |
| API tokens | `/api/tokens` | ApiTokenController | ApiTokenService | api_tokens | ApiTokenServiceTest | 🟡 UNIT-RED (BUG-003) |
| Companion pairing | `/api/companion/*` | CompanionController | — | companion_tokens | — | ⚪ NOT TESTED |
| First-boot bootstrap | — | BootstrapController | UserService | users | — | ⚪ NOT TESTED |

## Segment 3/4 — RBAC, Users, Governance, Admin
| Feature | Endpoint | Controller | Tables | Test asset | Status |
|---|---|---|---|---|---|
| Admin users CRUD | `/api/admin/users*` | AdminUserController | users, user_groups | — | ✅ PROVEN authz (T8/T9); ⚪ CRUD E2E |
| Effective access / overrides | `/api/admin/users/{id}/effective-access`, `/overrides` | AdminUserController | user_overrides, access_grants | — | ⚪ NOT TESTED |
| Active sessions / force-logout | `/api/admin/sessions/*` | AdminUserController | sessions | — | ⚪ NOT TESTED |
| Settings / departments | `/api/admin/settings`, `/departments` | AdminSettingsController | settings, departments | — | ⚪ NOT TESTED |
| Model governance | `/api/admin/models/{id}/governance` | AdminModelsController | models | ModelServiceTest | 🟢 UNIT / ⚪ live |
| Providers admin | `/api/admin/providers*` | AdminProvidersController | api_providers, api_models | ProviderServiceTest | 🟡 UNIT-RED (BUG-003) |
| Connectors admin | `/api/admin/connectors*` | AdminConnectorController | connector_* | ConnectorRegistryTest, ConnectorSyncSchedulerTest | 🟡 partial (BUG-003) |
| Enterprise analytics/audit | `/api/admin/enterprise/*` | AdminEnterpriseController | audit_events | — | ⚪ NOT TESTED |
| MCP admin | `/api/admin/mcp/*` | AdminMcpController | mcp_servers | McpServerServiceTest | 🟡 partial (BUG-002 fixed, BUG-003) |
| Skills moderation | `/api/admin/skills/*` | AdminSkillsController | skills | SkillsServiceTest | 🟡 UNIT-RED (BUG-003) |
| Health | `/api/admin/health` | AdminHealthController | — | AdminHealthControllerTest | ✅ PROVEN authz (T10) |
| Admin auth gate (all admin APIs) | `/api/admin/**` | BaseController | — | Soc2SecurityIntegrationTest | ✅ PROVEN (401 unauth) |

## Segment 5 — Employee Workspace
| Feature | Endpoint | Controller | Service | Tables | Test asset | Status |
|---|---|---|---|---|---|---|
| Chat | `POST /api/chat` | ChatController | ChatService, RouterService | chat_sessions, chat_messages, router_traces, feedback | ChatServiceTest, RouterServiceTest | 🟢 UNIT / ⚪ live (needs Ollama) |
| Threads/sessions | `/api/threads/*`, `/api/sessions/*` | ThreadController, SessionEnhancementController | ChatService, SessionEnhancementService | chat_sessions | SessionEnhancementServiceTest | 🟡 UNIT-RED (BUG-003) |
| Assistant/Crew | `/api/assistant/*` | AssistantController | PersonalAssistantService | crew_members | PersonalAssistantServiceTest | 🟡 UNIT-RED (BUG-003) |
| Agent loop | `/api/agent/*` | AgentController | AgentLoopService | — | AgentLoopServiceTest | 🟢 UNIT |
| Calendar | `/api/calendar/*` | CalendarController | CalendarService | calendars, calendar_events | CalendarServiceTest | 🟡 UNIT-RED (BUG-003) |
| Contacts | `/api/contacts/*` | ContactsController | ContactsService | contacts | ContactsServiceTest | 🟡 UNIT-RED (BUG-003) |
| Email | `/api/email/*` | EmailController | EmailService | email_accounts, email_messages, email_drafts | EmailServiceTest | 🟡 UNIT-RED (BUG-003) |
| Notes | `/api/notes/*` | NotesController | NotesService | notes | NotesServiceTest | 🟡 UNIT-RED (BUG-003) |
| Tasks | `/api/tasks/*` | TasksController | TaskSchedulerService | scheduled_tasks, task_runs | TaskSchedulerServiceTest | 🟢 UNIT (fixed earlier) |
| Memory | `/api/memory/*` | MemoryController | MemoryService, MemoryExtractorService | memories | MemoryServiceTest | 🟡 UNIT-RED (BUG-005) |
| Skills | `/api/skills/*` | SkillsController | SkillsService | skills | SkillsServiceTest | 🟡 UNIT-RED (BUG-003) |
| Presets/templates | `/api/presets/*` | PresetsController | PresetService, PromptTemplateService | user_templates | PresetServiceTest, PromptTemplateServiceTest | 🟢 UNIT |
| Compare | `/api/compare/*` | CompareController | CompareService | comparisons | CompareServiceTest | 🟡 UNIT-RED (BUG-003) |
| Deep Research | `/api/research/*` | ResearchController | DeepResearchService | research_tasks | DeepResearchServiceTest | 🟢 UNIT / ⚪ live |
| Gallery | `/api/gallery/*` | GalleryController | GalleryService | gallery_albums, gallery_images, editor_drafts | GalleryServiceTest | 🟢 UNIT |
| Cookbook | `/api/cookbook/*` | CookbookController | CookbookService | cookbook_models | CookbookServiceTest | 🟢 UNIT |
| Account | `/api/account/*` | AccountController | UserService | users | UserServiceTest | 🟢 UNIT |
| Workspace/terminal | `/api/workspace/*`, WS | WorkspaceController | WorkspaceService, TerminalService | workspace_prefs | WorkspaceServiceTest, TerminalServiceTest, WebSocketAuthInterceptorTest | 🟡 partial (BUG-003) |
| Code sandbox | `/api/sandbox/*` | CodeSandboxController | CodeSandboxService | — | CodeSandboxServiceTest | 🟢 UNIT |
| Documents/RAG | `/api/documents/*` | DocumentController, PersonalDocumentController | RagService, PersonalDocumentService, EmbeddingService | rag_documents, rag_chunks | PersonalDocumentServiceTest | 🟢 UNIT / ⚪ live |
| Voice | `/api/voice/*` | VoiceController | VoiceService, WhisperServerManager | — | VoiceServiceTest, WhisperServerManagerTest | 🟢 UNIT |
| Image generation | `/api/images/*` | ImageController | ImageGenerationService | image_generation_log | ImageGenerationServiceTest | 🟢 UNIT |
| YouTube | `/api/youtube/*` | YouTubeController | YouTubeService | youtube_transcripts | YouTubeServiceTest | 🟢 UNIT |
| Vault | `/api/vault/*` | VaultController | VaultService, CryptoService | vault_config | VaultServiceTest, CryptoServiceTest | 🟡 partial (BUG-003) |
| Background jobs | `/api/jobs/*` | BackgroundJobController | BackgroundJobService | background_jobs | BackgroundJobServiceTest | 🟡 UNIT-RED (BUG-003) |
| Webhooks | `/api/webhooks/*` | WebhookController | WebhookService | webhooks | WebhookServiceTest | 🟡 UNIT-RED (BUG-004, SSRF guard ✅ in code) |

## Segment 6 — AI/LLM core
| Feature | Service | Tables | Test asset | Status |
|---|---|---|---|---|
| Auto router | RouterService | router_traces | RouterServiceTest | 🟢 UNIT |
| Function/tool calling | FunctionCallService | — | FunctionCallServiceTest | 🟡 UNIT-RED (1, BUG-003) |
| RAG / embeddings | RagService, EmbeddingService, SearchCacheService | rag_*, search_cache_index | SearchCacheServiceTest | 🟢 UNIT (fixed earlier) |
| Web search | WebSearchService | web_search_log | WebSearchServiceTest | 🟢 UNIT |
| Prompt security | PromptSecurityService | prompt_security_log | PromptSecurityServiceTest | 🟢 UNIT |
| Context compaction | ContextCompactorService | — | ContextCompactorServiceTest | 🟢 UNIT |
| Visual reports | VisualReportService | — | VisualReportServiceTest | 🟢 UNIT |

## Segment 7 — Connectors (22)
| Item | Code | Test asset | Status |
|---|---|---|---|
| Framework + registry + scheduler | BaseConnector, ConnectorRegistry, ConnectorSyncScheduler | ConnectorRegistryTest, ConnectorSyncSchedulerTest, ConnectorImplTest | 🟡 partial (BUG-003 on scheduler log) |
| 22 connector impls (GitHub…Zendesk) | connector/impl/* | ConnectorImplTest | 🟢 UNIT / ⚪ live (need provider creds) |

## Segment 9 — Security (cross-cutting)
| Feature | Code | Test asset | Status |
|---|---|---|---|
| Crypto AES-256-GCM | CryptoService | CryptoServiceTest | 🟢 UNIT + ✅ no-leak probes (T15/T16) |
| Security headers | SecurityHeadersFilter | SecurityHeadersFilterTest | ✅ PROVEN (live headers) |
| Session auth filter | SessionAuthFilter | SessionAuthFilterTest | 🟢 UNIT + ✅ live |
| MDC/access logging | MdcLoggingFilter | MdcLoggingFilterTest | 🟢 UNIT + ✅ live (Loki) |
| WebSocket auth | WebSocketAuthInterceptor | WebSocketAuthInterceptorTest, WebSocketConfigTest | 🟢 UNIT / ⚪ live WS |
| SQL safety | DatabaseService | SqlSafetyTest, SecurityHardeningTest | ✅ PROVEN (green) |
| Security config | SecurityConfig | SecurityConfigTest | 🟢 UNIT |

## Segment 10/11/12 — Observability, DB, Config
| Feature | Evidence | Status |
|---|---|---|
| Flyway migrations V1–V12 | 13 rows success=1 | ✅ PROVEN |
| WAL + integrity_check | wal / ok | ✅ PROVEN |
| FK enforcement | per-connection (OBS-001) | 🟡 implicit |
| Loki/Grafana logging | separated views live | ✅ PROVEN (prior session) |
| Health endpoint | authz 401 | ✅ PROVEN (authz); ⚪ payload truthfulness |
| Backup | endpoint exists | ⚪ NOT TESTED |
| Removed admin Logs | `/api/admin/logs` → 404 | ✅ PROVEN |

---

## Coverage rollup (this run)
- **PROVEN (live + green):** auth, RBAC gate, CSRF, lockout, cookie/session isolation, headers, secret non-leak, SQL-safety, migrations/DB integrity, log removal.
- **UNIT-COVERED green:** ~40 service classes.
- **UNIT-RED (test-debt, BUG-003/004/005):** ~32 tests across ~20 classes — not proven product failures.
- **NOT TESTED:** all frontend E2E, responsive/a11y, load/soak, live chat/RAG/AI-injection, connector live syncs, backup restore, dynamic IDOR/role with multi-user fixtures.

> Gap analysis vs `FEATURES.md`: no documented feature was found **missing** from code; no orphan endpoint contradicted the doc. The audit found a documentation-accurate codebase whose **automated test suite is partially red due to test-debt**, plus one fixed Critical regression.
