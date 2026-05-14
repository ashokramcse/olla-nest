# Enterprise User Management & Access Control System

**Scope:** User Management, Access Control, Model Governance, and AI Usage Permissions for Olla Nest.

**Positioning:** Olla Nest remains an Ollama-first, local-first enterprise AI dashboard. The existing USP stays unchanged: employees use one simple AI workspace, Olla Nest discovers available local/private models, and the platform routes requests to the best approved model while admins control access, governance, and local work permissions.

**Primary Runtime:** Ollama is the core private AI infrastructure layer for running local models, private models, coding models, reasoning models, embeddings, and offline inference. External providers such as Claude, Codex, OpenAI, Gemini, and DeepSeek API are governed as premium or cloud fallback providers, not the default runtime.

**Official Ollama References:**

- Ollama documentation: https://docs.ollama.com
- Ollama API reference: https://docs.ollama.com/api/introduction
- Ollama model library: https://ollama.com/library

Ollama's API is served locally at `http://localhost:11434/api` by default, and exposes model operations such as generate, chat, embeddings, model tags, running processes, model details, create, copy, pull, push, delete, and version. The official documentation also covers streaming, thinking, structured outputs, vision, embeddings, tool calling, web search, Modelfiles, context length, Docker, importing models, and hardware support.

---

## 1. Complete User Management Architecture

The User Management System is the enterprise identity and AI entitlement layer for Olla Nest. It decides who a user is, what organization they belong to, which department/team they are assigned to, what AI capabilities they can use, and what resource limits apply.

### User Lifecycle

1. Invite or provision user.
2. Assign organization, department, team, manager, and role.
3. Attach default role permissions.
4. Attach department and team permissions.
5. Apply individual overrides.
6. Enforce security requirements such as MFA, device trust, and IP rules.
7. Monitor usage, sessions, model activity, and risk.
8. Suspend, expire, or revoke access when needed.

### User Creation Methods

- Manual user creation
- CSV bulk import
- Email invite
- Google Workspace sync
- Microsoft Entra ID sync
- LDAP / Active Directory integration
- SCIM provisioning

### User Profile Structure

**Identity Information**

- Full name
- Email
- Employee ID
- Designation
- Department
- Team
- Branch
- Manager
- Organization
- Tenant

**AI Access Information**

- Allowed Ollama models
- Allowed external models
- AI access tier
- Coding AI access
- Reasoning AI access
- Agent access
- Workflow access
- File upload access
- Internet access permission
- Tool calling permission
- Local workspace write permission
- API access permission

**Resource Allocation**

- Daily token limit
- Monthly token limit
- GPU quota
- VRAM usage limit
- Concurrent model usage limit
- API rate limit
- Max context size
- Max file upload size
- Max autonomous agent runtime

**Security Information**

- MFA status
- Device sessions
- Login history
- IP history
- Security risk score
- Session activity
- Last password change
- Identity provider source

**Status**

- Active
- Suspended
- Pending invite
- Pending approval
- Temporary access
- Access expired
- Locked by security policy

---

## 2. Ollama-Based AI Infrastructure Design

Ollama is treated as the private inference control plane. Olla Nest sits above Ollama and adds enterprise governance, identity, policy, routing, audit, and usage controls.

### Supported Model Sources

- Local Ollama models
- GGUF models imported into Ollama
- Fine-tuned models
- Private company models
- Imported custom models
- Modelfile-created variants
- Multi-GPU served models
- CPU-only models
- External premium models such as Claude, Codex, OpenAI, Gemini, and DeepSeek API

### Ollama Governance Objects

- Ollama server
- Ollama cluster
- Model registry entry
- Model version/tag
- Modelfile
- Model capability profile
- Hardware requirement profile
- Access policy
- Routing policy
- Usage budget
- Audit trail

### Admin Controls

- Which users can access each Ollama model
- Which departments can use reasoning models
- Which users can use coding models
- Which models are GPU-restricted
- Which models are CPU-only
- Which models are internal/private/premium
- Concurrent model usage
- Context window limits
- Memory and VRAM allocation
- Model pull/import/delete permissions
- Modelfile creation permissions
- Ollama API access permissions
- Local inference governance

### Ollama Server Management

- Local desktop Ollama instance
- Shared team Ollama server
- Remote Ollama instance
- GPU workstation pool
- Multi-node Ollama cluster
- Multi-region private inference
- Model cache location
- Health status
- Running model/process state
- Hardware profile

---

## 3. RBAC Permission Engine

The RBAC engine defines the base permission layer. It should be simple enough for administrators, but expressive enough for enterprise AI governance.

### Roles

- Platform Owner
- AI Infrastructure Admin
- Ollama Admin
- Security Admin
- Department Admin
- Team Lead
- AI Developer
- AI Analyst
- Engineering User
- Research User
- Viewer

### Permission Categories

**User & Organization**

- users:create
- users:read
- users:update
- users:suspend
- users:delete
- users:invite
- users:bulk_import
- groups:manage
- departments:manage
- roles:manage

**Ollama Model Management**

- ollama:servers:read
- ollama:servers:manage
- ollama:models:use
- ollama:models:pull
- ollama:models:delete
- ollama:models:import
- ollama:models:copy
- ollama:models:push
- ollama:modelfile:create
- ollama:modelfile:update
- ollama:api:use

**Model Usage**

- models:local:use
- models:external:use
- models:premium:use
- models:coding:use
- models:reasoning:use
- models:vision:use
- models:embedding:use
- models:offline_only:use

**AI Workflows**

- chat:use
- files:upload
- files:export
- workspace:write
- tools:call
- internet:use
- agents:run
- agents:autonomous
- workflows:create
- workflows:execute
- api_keys:create

**Governance**

- audit:read
- prompts:view
- prompts:redacted_view
- usage:read
- usage:export
- quotas:manage
- security:manage
- dlp:manage
- moderation:manage

### Permission Scope Levels

1. Organization
2. Department
3. Team
4. Individual user
5. Project/workspace
6. Model/provider
7. Ollama server/cluster

### Permission Decision Priority

1. Explicit deny
2. User override
3. Project/workspace policy
4. Team policy
5. Department policy
6. Role permission
7. Organization default

Explicit deny always wins. User override can grant or restrict access unless blocked by a higher compliance policy.

---

## 4. Department Access Logic

Departments receive default AI access profiles. This keeps enterprise setup simple while allowing user-specific exceptions.

### Department Profiles

| Department | Default Access | Restrictions |
|---|---|---|
| Engineering | Coding models, local reasoning, approved Codex/Claude fallback | Sensitive repos require local-only mode |
| Research | Reasoning models, long context, embeddings, RAG | External sharing restricted |
| Finance | Secure local models, summarization, controlled analytics | Cloud AI blocked by default |
| HR | Writing, policy Q&A, internal knowledge | PII redaction enforced |
| Legal | Offline-only private models, secure document review | Export/copy restricted |
| Marketing | Text generation, campaign ideation, approved image/text models | Brand safety moderation |
| Sales | Proposal, CRM assistant, approved external models | Customer data policy enforced |
| Operations | Summaries, SOP assistant, workflow agents | Tool execution requires approval |

### Department Isolation

- Separate model allowlists
- Separate knowledge base permissions
- Separate prompt visibility rules
- Separate audit and reporting views
- Optional department-specific Ollama server pools
- Optional department-specific GPU quota

---

## 5. User Override System

User overrides allow exceptions without changing role or department policy.

### Override Types

- Grant external provider access
- Restrict to local-only models
- Grant GPU-intensive model access
- Increase context window
- Increase token quota
- Enable coding model access
- Enable reasoning model access
- Enable autonomous agents
- Enable internet tools
- Enable workspace file writes
- Temporary project access
- Expiring executive access

### Override Evaluation

```text
final_permission = explicit_deny
                OR user_override
                OR workspace_policy
                OR team_policy
                OR department_policy
                OR role_policy
                OR organization_default
```

### Override Governance

- Reason required
- Expiry date supported
- Approval workflow supported
- Audit log required
- Periodic access review required

---

## 6. Ollama Model Governance Layer

The Model Governance Layer controls model inventory, availability, approvals, risk classification, and usage.

### Model Registry Fields

- Model ID
- Display name
- Provider: Ollama, OpenAI, Anthropic, Gemini, DeepSeek, custom
- Runtime: local, private cloud, external API
- Ollama model tag
- Source server/cluster
- Model family
- Capability tags: coding, reasoning, vision, embedding, medical, OCR, writing, tool calling
- Size/parameter class
- Quantization
- Context length
- Hardware requirement
- CPU/GPU eligibility
- VRAM estimate
- Privacy tier
- Cost profile
- Approval status
- Department allowlist
- User allowlist
- Compliance restrictions

### Model Actions With Permissions

- Use model
- Pull model
- Import model
- Delete model
- Copy model
- Push model
- Show model details
- Create from Modelfile
- Update model metadata
- Mark as approved/restricted/deprecated

### Model Risk Levels

- Public local model
- Approved internal model
- Restricted internal model
- Sensitive/offline-only model
- Premium external model
- Experimental model
- Deprecated model

---

## 7. GPU Resource Allocation Logic

GPU governance ensures local AI does not collapse under uncontrolled use.

### Resource Objects

- GPU node
- GPU device
- VRAM capacity
- Current VRAM usage
- Running model
- Queued jobs
- Tenant allocation
- Department allocation
- User quota
- Priority class

### Allocation Rules

- Platform Owner and AI Infrastructure Admin can reserve capacity.
- Department budgets cap aggregate GPU usage.
- User quotas cap per-user usage.
- High VRAM models require explicit permission.
- Concurrent usage limits prevent one user from monopolizing GPUs.
- Queue priority is decided by role, department, task class, and urgency.
- Sensitive workloads route to private/offline nodes.

### Scheduling Modes

- First available
- Least loaded GPU
- Department reserved pool
- Model affinity
- Cost-aware local vs cloud fallback
- Privacy-aware local-only routing
- Queue with ETA

---

## 8. Hybrid AI Routing Architecture

The router chooses the best allowed model from local Ollama and approved external providers.

### Routing Inputs

- User identity
- Role, department, team
- User overrides
- Prompt sensitivity classification
- Requested mode: ask, build, review, fix, learn, agent, workflow
- Required capabilities
- Model availability
- GPU availability
- Cost budget
- Privacy policy
- Context size requirement
- Tool/internet requirement

### Routing Strategy

- Sensitive prompts -> local/private Ollama models
- Coding tasks -> approved coding models or Codex fallback
- Heavy reasoning -> local reasoning model or approved Claude fallback
- Cheap inference -> local quantized model
- Offline mode -> Ollama only
- Budget exceeded -> local fallback or queued execution
- GPU unavailable -> CPU model, smaller quantized model, queue, or approved cloud fallback

### Routing Output

- Selected model
- Provider
- Reason
- Policy checks passed
- Cost estimate
- Resource estimate
- Audit ID
- Fallback path

---

## 9. Database Schema

The database must support multi-tenant SaaS, enterprise governance, usage accounting, and AI infrastructure mapping.

### Core Tables

```sql
organizations (
  id, name, slug, plan, status, created_at
)

tenants (
  id, organization_id, name, region, data_residency, status
)

users (
  id, organization_id, email, full_name, employee_id, designation,
  department_id, team_id, branch, manager_id, role_id,
  ai_access_tier, status, mfa_enabled, risk_score,
  created_at, updated_at, access_expires_at
)

roles (
  id, organization_id, name, description, system_role, created_at
)

permissions (
  id, key, category, description, risk_level
)

role_permissions (
  role_id, permission_id, scope_type, scope_id, effect
)

departments (
  id, organization_id, name, parent_department_id, default_policy_id
)

teams (
  id, organization_id, department_id, name, default_policy_id
)
```

### Model Governance Tables

```sql
model_providers (
  id, organization_id, name, type, runtime, status, config_ref
)

ollama_servers (
  id, organization_id, name, base_url, region, cluster_id,
  status, health_status, gpu_pool_id, last_seen_at
)

models (
  id, organization_id, provider_id, ollama_server_id,
  name, model_ref, family, capabilities_json, context_size,
  quantization, privacy_tier, cost_profile_json, hardware_profile_json,
  approval_status, status, last_seen_at
)

model_access_policies (
  id, organization_id, model_id, subject_type, subject_id,
  effect, max_context_size, daily_token_limit, monthly_token_limit,
  concurrency_limit, expires_at
)

user_overrides (
  id, organization_id, user_id, permission_key, model_id,
  effect, reason, approved_by, expires_at, created_at
)
```

### Resource Tables

```sql
gpu_pools (
  id, organization_id, name, region, reserved_for_department_id, status
)

gpu_devices (
  id, gpu_pool_id, node_name, device_name, vram_total_mb,
  vram_available_mb, health_status, last_seen_at
)

gpu_allocations (
  id, organization_id, user_id, department_id, model_id,
  gpu_device_id, vram_reserved_mb, status, started_at, ended_at
)

token_usage (
  id, organization_id, user_id, department_id, model_id, provider_id,
  prompt_tokens, completion_tokens, total_tokens, cost_estimate,
  request_id, created_at
)
```

### Security, Session, Audit Tables

```sql
sessions (
  id, organization_id, user_id, device_id, ip_address,
  user_agent, status, created_at, expires_at, last_seen_at
)

device_sessions (
  id, organization_id, user_id, device_fingerprint,
  device_name, trusted, last_ip, last_seen_at
)

api_keys (
  id, organization_id, user_id, name, key_hash,
  scopes_json, last_used_at, expires_at, status
)

audit_logs (
  id, organization_id, actor_user_id, action, resource_type,
  resource_id, metadata_json, ip_address, created_at
)

ai_sessions (
  id, organization_id, user_id, workspace_id, model_id,
  provider_id, route_reason, sensitivity_level, status, created_at
)

workflow_permissions (
  id, organization_id, workflow_id, subject_type, subject_id,
  effect, expires_at
)
```

### Document / NoSQL Collections

Use MongoDB or a document archive for high-volume, variable AI records:

- chat_messages
- thought_traces
- tool_outputs
- prompt_redaction_events
- file_upload_metadata
- workflow_execution_logs
- agent_steps
- model_response_metadata

### Redis Structures

Use Redis for:

- Live token stream buffers
- Active session presence
- Rate-limit counters
- GPU queue state
- Model warm-cache state
- WebSocket pub/sub
- Temporary approval tokens

---

## 10. API Structure

### User APIs

- `GET /api/users`
- `POST /api/users`
- `GET /api/users/:id`
- `PATCH /api/users/:id`
- `POST /api/users/:id/suspend`
- `POST /api/users/:id/activate`
- `POST /api/users/bulk-import`
- `POST /api/users/invite`
- `GET /api/users/:id/activity`

### Role & Permission APIs

- `GET /api/roles`
- `POST /api/roles`
- `PATCH /api/roles/:id`
- `GET /api/permissions`
- `POST /api/roles/:id/permissions`
- `GET /api/access/effective/:userId`
- `POST /api/access/overrides`

### Ollama Governance APIs

- `GET /api/ollama/servers`
- `POST /api/ollama/servers`
- `GET /api/ollama/servers/:id/health`
- `GET /api/ollama/models`
- `POST /api/ollama/models/pull`
- `POST /api/ollama/models/import`
- `DELETE /api/ollama/models/:id`
- `POST /api/ollama/modelfiles`
- `GET /api/ollama/processes`

### Model Access APIs

- `GET /api/models`
- `PATCH /api/models/:id/governance`
- `POST /api/models/:id/access-policy`
- `GET /api/models/:id/users`
- `GET /api/models/:id/departments`
- `GET /api/routing/policies`
- `POST /api/routing/evaluate`

### Resource & Usage APIs

- `GET /api/usage/tokens`
- `GET /api/usage/costs`
- `GET /api/gpu/pools`
- `GET /api/gpu/devices`
- `GET /api/gpu/allocations`
- `POST /api/quotas`
- `PATCH /api/quotas/:id`

### Security & Audit APIs

- `GET /api/sessions`
- `DELETE /api/sessions/:id`
- `GET /api/devices`
- `PATCH /api/devices/:id/trust`
- `GET /api/audit-logs`
- `GET /api/security/incidents`
- `POST /api/security/policies`

### API Standards

- REST APIs for administration
- WebSockets for streaming and live monitoring
- JWT or secure session cookies
- RBAC middleware
- Tenant-aware middleware
- Request audit middleware
- OpenAPI documentation
- Idempotent provisioning endpoints

---

## 11. Dashboard UI Structure

The UI should feel like ChatGPT Enterprise, Microsoft Copilot Admin Center, Open WebUI Enterprise, AWS Bedrock Console, Cursor Team Admin, and Claude Enterprise Workspace.

### Top-Level Admin Navigation

- Overview
- Users
- Groups & Departments
- Roles & Permissions
- Model Governance
- Ollama Infrastructure
- Routing Policies
- Token & GPU Usage
- Security
- Audit Logs
- Settings

### User Management Dashboard Widgets

- Active AI users
- Active Ollama models
- GPU utilization
- Most used models
- Token burn rate
- AI cost analytics
- Security alerts
- Suspicious activity
- Pending access approvals
- Expiring permissions

### User Table Columns

- Name
- Email
- Role
- Department
- Team
- Allowed models
- GPU usage
- Token usage
- Last active
- Session count
- Risk score
- Status

### User Table Features

- Search
- Filters
- Bulk actions
- CSV import
- Invite users
- Suspend/reactivate
- Permission matrix
- Effective access preview
- User activity timeline
- Session/device drawer
- Export audit evidence

### Model Access Visualizer

Show a layered view:

```text
Organization Defaults
        -> Role Permissions
        -> Department Policy
        -> Team Policy
        -> User Override
        -> Effective Access
```

For each model, show:

- Allowed users
- Blocked users
- Department coverage
- GPU restrictions
- Context limit
- Token limit
- Privacy tier
- External fallback allowed or blocked

### Permission Matrix UI

Rows:

- Roles
- Departments
- Teams
- Users

Columns:

- Model categories
- Ollama actions
- External providers
- Tool calling
- File upload
- Agents
- APIs
- Exports
- Internet access

Cell states:

- Allow
- Deny
- Inherit
- Requires approval
- Temporary access

---

## 12. Security Architecture

The system follows zero-trust enterprise security principles.

### Identity Security

- SSO
- MFA/2FA
- SCIM provisioning
- Entra/Google/LDAP sync
- Just-in-time provisioning
- Session expiration
- Password policy
- Device trust

### Access Security

- RBAC
- Attribute-based policies
- Explicit deny controls
- Least privilege defaults
- Temporary access
- Approval workflow
- Periodic access review

### AI Security

- Prompt injection defense
- Prompt leak prevention
- DLP checks
- AI output moderation
- Sensitive prompt classifier
- Local-only enforcement for sensitive departments
- External API blocking
- Tool execution approvals
- Internet agent restrictions

### Data Security

- Tenant isolation
- Encryption at rest
- TLS in transit
- Secrets vault integration
- Audit evidence retention
- Data residency policy
- Prompt redaction
- Export controls

---

## 13. Session Management Flow

1. User signs in through SSO or local login.
2. System validates MFA and device posture.
3. Session is created with tenant, role, department, team, and risk context.
4. Effective permissions are calculated and cached.
5. Every AI request re-checks model access, quotas, and policy.
6. Suspicious behavior increases risk score.
7. High-risk sessions require re-authentication or are blocked.
8. Admin can revoke sessions and device trust.

### Session Controls

- Active session list
- Device fingerprint
- IP address history
- Location anomaly detection
- Session revocation
- Idle timeout
- Absolute timeout
- High-risk re-authentication

---

## 14. Audit Logging System

Audit logs are immutable business evidence for AI governance.

### Events To Track

- User login/logout
- Failed login
- MFA changes
- User creation/suspension
- Role changes
- Permission changes
- Model access changes
- Ollama model pull/import/delete
- Modelfile creation
- AI request routing
- Prompt sensitivity classification
- External AI API usage
- GPU allocation
- VRAM usage
- File uploads
- Local file writes
- Export/copy events
- Agent execution
- Workflow execution
- Security incidents

### Audit Log Fields

- Event ID
- Organization ID
- Actor
- Action
- Resource type
- Resource ID
- Target user
- IP address
- Device ID
- Policy decision
- Before/after metadata
- Timestamp
- Risk level

---

## 15. Token & Resource Governance

Token and resource governance controls cost, GPU pressure, and fair use.

### Track

- Prompt tokens
- Completion tokens
- Total tokens
- Local inference cost estimate
- External API cost
- GPU time
- VRAM usage
- Queue time
- Model-wise cost
- Department-wise usage
- User-wise usage

### Controls

- Daily hard limits
- Monthly hard limits
- Soft warning limits
- Auto throttling
- Queue prioritization
- Resource balancing
- AI budget allocation
- External API cost caps
- Department budgets
- Project budgets

### Enforcement Flow

1. Request enters gateway.
2. Estimate tokens and model cost.
3. Check user quota.
4. Check department quota.
5. Check provider/model budget.
6. Check GPU availability.
7. Allow, throttle, queue, fallback, or deny.

---

## 16. Multi-Tenant SaaS Architecture

Each company is isolated by organization/tenant boundaries.

### Tenant Isolation

- Tenant-aware authentication
- Tenant ID on every relational row
- Separate encryption keys per tenant for secrets
- Separate model policies per tenant
- Separate audit logs per tenant
- Optional dedicated Ollama server pools per tenant
- Optional dedicated database per enterprise tenant

### Deployment Modes

- Single-company local deployment
- Multi-tenant SaaS deployment
- Hybrid deployment with local Ollama and cloud control plane
- Fully private VPC deployment
- Air-gapped enterprise deployment

---

## 17. Enterprise AI Governance Framework

### Governance Pillars

- Identity: who is using AI
- Access: what models/tools they can use
- Privacy: where data can go
- Cost: how much they can spend
- Infrastructure: what compute they can consume
- Audit: what happened and why
- Compliance: what policies were enforced
- Safety: what outputs/tools are allowed

### Policy Types

- Model access policy
- Provider access policy
- Data sensitivity policy
- File upload policy
- Tool calling policy
- Agent execution policy
- Internet access policy
- Export policy
- Token quota policy
- GPU quota policy
- External cost policy

---

## 18. Production-Ready Security Strategy

### Minimum Production Controls

- SSO for enterprise tenants
- MFA enforced for admins
- Strong password policy for local accounts
- RBAC middleware on every admin API
- Tenant isolation middleware on every request
- Audit middleware on every state-changing action
- Rate limiting
- CSRF protection for cookie sessions
- Secure cookies
- Secrets in vault or environment manager
- Encrypted provider credentials
- Input validation
- Prompt/file scanning
- Admin action approval for high-risk changes

### High-Risk Actions Requiring Approval

- Enable external API for restricted department
- Grant autonomous agent access
- Grant GPU-intensive model access
- Pull/import new model into production
- Delete model
- Disable audit logging
- Export prompt history
- Change data residency
- Create organization-wide user override

---

## 19. AI Infrastructure Monitoring System

### Live Monitoring

- Active AI users
- Running Ollama models
- Ollama server health
- GPU health
- VRAM usage
- Request queue depth
- Model latency
- Error rate
- External provider status
- Token burn rate
- Cost burn rate

### Infrastructure Views

- Ollama server list
- Model inventory
- Running model processes
- GPU pool status
- Department usage heatmap
- Provider spend chart
- Routing decision timeline
- Security incident stream

---

## 20. Modern Enterprise Admin UX

### UX Principles

- Minimal but data-rich
- AI-native terminology
- Clear policy inheritance
- Fast search and filtering
- Explainable access decisions
- Visual model governance
- Real-time infrastructure state
- Safe high-risk admin actions
- No hidden local writes
- No unclear cloud fallback

### Key Screens

1. User Directory
2. User Detail Drawer
3. Effective Access Viewer
4. Role Builder
5. Permission Matrix
6. Department Policy Editor
7. Model Governance Console
8. Ollama Server Monitor
9. GPU Resource Dashboard
10. Routing Policy Builder
11. Token & Cost Dashboard
12. Audit Timeline
13. Security Incidents
14. Access Approval Queue

### User Detail Layout

- Identity summary
- AI access tier
- Effective models
- External provider access
- Quotas
- GPU usage
- Sessions/devices
- Login history
- Activity timeline
- Overrides
- Approval history

---

## Implementation Priority For Olla Nest

### Phase 1: Enterprise Access MVP

- Expanded user profile
- Roles and permissions table
- Effective access calculation
- Department policy editor
- User override editor
- Model governance table
- Ollama server/source registry
- Token quota fields
- Audit events for permission changes

### Phase 2: Infrastructure Governance

- GPU pool/device registry
- Ollama process monitoring
- Model pull/import permissions
- API key management
- Session/device manager
- Usage dashboards
- Admin approval workflow

### Phase 3: Enterprise Scale

- SSO/MFA/SCIM
- Multi-tenant isolation
- OpenAPI docs
- WebSockets for live monitoring
- Advanced DLP/moderation
- External provider cost governance
- Air-gapped deployment mode

---

## Final Product Direction

Olla Nest should become the enterprise control plane for Ollama-first AI infrastructure.

The business promise is:

> Give every employee one AI workspace, while the company controls identity, model access, local/private inference, GPU resources, external AI providers, audit, and compliance.

The winning differentiation remains:

- Ollama-first local/private AI
- Auto model routing
- User/group/department model governance
- Local file work with permission control
- Enterprise admin dashboard
- Hybrid AI governance without losing local-first privacy
