/**
 * @file features.js
 * @version v2026.2.0
 * @description Olla Nest — Feature panels client-side logic.
 * Handles: Memory, Skills, Notes, Tasks, Email, Calendar, Compare, Cookbook, Assistant, Research
 * All API calls go through the existing api() helper from app.js.
 * Branding and design tokens from styles.css are preserved throughout.
 */

/* ─────────────────────────────────────────────
   PANEL MANAGEMENT
   ───────────────────────────────────────────── */

let _currentPanel = null;

function openFeaturePanel(name) {
  closeFeaturePanel(false);
  const panel = document.getElementById(`fp-${name}`);
  const backdrop = document.getElementById('featurePanelBackdrop');
  if (!panel) return;
  backdrop.classList.add('open');
  panel.classList.add('open');
  _currentPanel = name;

  // Mark nav item active
  document.querySelectorAll('.feature-nav-item').forEach(el => {
    el.classList.toggle('active', el.dataset.panel === name);
  });

  // Load panel data
  switch (name) {
    case 'memory':   loadMemories(); break;
    case 'skills':   loadSkills(); break;
    case 'notes':    loadNotes(); break;
    case 'tasks':    loadTasks(); break;
    case 'email':    loadEmailAccounts(); break;
    case 'calendar': initCalendar(); break;
    case 'compare':  loadCompareHistory(); loadCompareModels(); break;
    case 'cookbook': loadCookbookCatalog(); break;
    case 'assistant': loadAssistant(); break;
    case 'research': loadResearchTasks(); break;
  }
}

function closeFeaturePanel(clearActive = true) {
  const backdrop = document.getElementById('featurePanelBackdrop');
  backdrop.classList.remove('open');
  document.querySelectorAll('.feature-panel.open').forEach(p => p.classList.remove('open'));
  if (clearActive) {
    document.querySelectorAll('.feature-nav-item').forEach(el => el.classList.remove('active'));
    _currentPanel = null;
  }
}

// Close on Escape
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && _currentPanel) closeFeaturePanel();
});

/* ─────────────────────────────────────────────
   UTILITY
   ───────────────────────────────────────────── */

function debounce(fn, delay) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), delay); };
}

function relativeTime(isoStr) {
  if (!isoStr) return '';
  const diff = Date.now() - new Date(isoStr).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

function showToast(msg, type = 'ok') {
  const el = document.createElement('div');
  el.style.cssText = `position:fixed;bottom:24px;right:24px;z-index:9999;padding:10px 18px;border-radius:10px;font-size:13px;font-weight:600;box-shadow:0 4px 20px rgba(0,0,0,0.15);background:${type === 'ok' ? 'var(--success)' : 'var(--danger)'};color:#fff;animation:slideUp .2s;`;
  el.textContent = msg;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 3000);
}

/* ─────────────────────────────────────────────
   MEMORY
   ───────────────────────────────────────────── */

let _memories = [];

async function loadMemories() {
  const q = document.getElementById('memorySearchInput')?.value?.trim();
  try {
    const data = q
      ? await api(`/api/memory/search?q=${encodeURIComponent(q)}&top_k=20`)
      : await api('/api/memory?limit=100');
    _memories = Array.isArray(data) ? data : (data?.memories || []);
    renderMemories(_memories);
  } catch (e) {
    document.getElementById('memoryList').innerHTML = `<div class="fp-empty">Failed to load memories</div>`;
  }
}

function memorySearch() { loadMemories(); }

function renderMemories(memories) {
  const el = document.getElementById('memoryList');
  if (!memories.length) {
    el.innerHTML = `<div class="fp-empty">No memories yet. Add one below to get started.</div>`;
    return;
  }
  el.innerHTML = memories.map(m => `
    <div class="memory-item">
      <div style="flex:1;">
        <div class="memory-item-text">${esc(m.text)}</div>
        <div class="memory-item-meta">
          ${m.source ? `<span style="background:var(--ac-pale);padding:1px 6px;border-radius:4px;font-size:9px;margin-right:6px;">${esc(m.source)}</span>` : ''}
          ${relativeTime(m.created_at)}
        </div>
      </div>
      <button class="memory-item-del" onclick="deleteMemory('${esc(m.id)}')" title="Forget">×</button>
    </div>
  `).join('');
}

function showAddMemory() {
  const form = document.getElementById('memoryAddForm');
  if (form) { form.style.display = 'block'; document.getElementById('memoryAddText')?.focus(); }
}

function hideAddMemory() {
  const form = document.getElementById('memoryAddForm');
  if (form) form.style.display = 'none';
}

async function addMemory() {
  const text = document.getElementById('memoryAddText')?.value?.trim();
  if (!text) return;
  try {
    await api('/api/memory', { method: 'POST', body: JSON.stringify({ text }) });
    document.getElementById('memoryAddText').value = '';
    hideAddMemory();
    await loadMemories();
    showToast('Memory saved');
  } catch (e) {
    showToast('Failed to save memory', 'error');
  }
}

async function deleteMemory(id) {
  if (!confirm('Forget this memory?')) return;
  try {
    await api(`/api/memory/${id}`, { method: 'DELETE' });
    await loadMemories();
    showToast('Memory forgotten');
  } catch (e) {
    showToast('Failed to delete', 'error');
  }
}

/* ─────────────────────────────────────────────
   SKILLS
   ───────────────────────────────────────────── */

async function loadSkills() {
  const category = document.getElementById('skillsCategoryFilter')?.value;
  const params = new URLSearchParams({ status: 'active', limit: '100' });
  if (category) params.set('category', category);
  try {
    const skills = await api(`/api/skills?${params}`);
    renderSkills(Array.isArray(skills) ? skills : []);
  } catch (e) {
    document.getElementById('skillsList').innerHTML = `<div class="fp-empty">Failed to load skills</div>`;
  }
}

async function searchSkills() {
  const q = document.getElementById('skillsSearchInput')?.value?.trim();
  if (!q) { loadSkills(); return; }
  try {
    const skills = await api(`/api/skills/search?q=${encodeURIComponent(q)}&top_k=20`);
    renderSkills(Array.isArray(skills) ? skills : []);
  } catch (e) {}
}

function renderSkills(skills) {
  const el = document.getElementById('skillsList');
  if (!skills.length) {
    el.innerHTML = `<div class="fp-empty">No skills yet. Add skills to supercharge your agent.</div>`;
    return;
  }
  el.innerHTML = skills.map(s => `
    <div class="skill-item">
      <div class="skill-item-header">
        <span class="skill-item-name">${esc(s.name)}</span>
        <span class="skill-item-cat">${esc(s.category || 'general')}</span>
      </div>
      <div class="skill-item-desc">${esc(s.description || '')}</div>
      ${s.when_to_use ? `<div class="skill-item-when">${esc(s.when_to_use)}</div>` : ''}
      <div style="display:flex;gap:6px;margin-top:8px;">
        <button class="fp-btn" style="font-size:11px;padding:3px 8px;" onclick="deleteSkill('${esc(s.id)}')">Delete</button>
        <span style="margin-left:auto;font-size:10px;color:var(--muted2);font-family:var(--font-mono);">Used ${s.use_count || 0}×</span>
      </div>
    </div>
  `).join('');
}

function showAddSkill() {
  const name = prompt('Skill name:');
  if (!name) return;
  const description = prompt('Description (what does it do?):') || '';
  const when = prompt('When to use this skill:') || '';
  const category = prompt('Category (general/coding/writing/research):') || 'general';
  api('/api/skills', {
    method: 'POST',
    body: JSON.stringify({ name, description, when_to_use: when, category })
  }).then(() => { loadSkills(); showToast('Skill added'); }).catch(() => showToast('Failed', 'error'));
}

async function deleteSkill(id) {
  if (!confirm('Delete this skill?')) return;
  try {
    await api(`/api/skills/${id}`, { method: 'DELETE' });
    loadSkills();
    showToast('Skill deleted');
  } catch (e) {
    showToast('Failed to delete', 'error');
  }
}

/* ─────────────────────────────────────────────
   NOTES
   ───────────────────────────────────────────── */

const NOTE_COLORS = ['default', 'yellow', 'green', 'blue', 'pink', 'purple', 'orange'];

async function loadNotes() {
  const label = document.getElementById('notesLabelFilter')?.value;
  const params = new URLSearchParams({ archived: 'false' });
  if (label) params.set('label', label);
  try {
    const notes = await api(`/api/notes?${params}`);
    renderNotes(Array.isArray(notes) ? notes : []);
  } catch (e) {
    document.getElementById('notesList').innerHTML = `<div class="fp-empty">Failed to load notes</div>`;
  }
}

function renderNotes(notes) {
  const el = document.getElementById('notesList');
  if (!notes.length) {
    el.innerHTML = `<div class="fp-empty">No notes yet. Click "+ Note" to create one.</div>`;
    return;
  }
  el.innerHTML = notes.map(n => `
    <div class="note-card color-${n.color || 'default'}" onclick="editNote('${n.id}')">
      ${n.pinned ? `<div class="note-card-pin">📌</div>` : ''}
      ${n.title ? `<div class="note-card-title">${esc(n.title)}</div>` : ''}
      <div class="note-card-body">${esc((n.content || '').slice(0, 200))}</div>
      ${n.due_date ? `<div style="font-size:10px;color:var(--muted2);margin-top:6px;font-family:var(--font-mono);">⏰ ${new Date(n.due_date).toLocaleDateString()}</div>` : ''}
      <div class="note-card-actions">
        <button class="note-card-action" onclick="event.stopPropagation();pinNote('${n.id}', ${!n.pinned})">${n.pinned ? 'Unpin' : 'Pin'}</button>
        <button class="note-card-action" onclick="event.stopPropagation();archiveNote('${n.id}')">Archive</button>
        <button class="note-card-action" onclick="event.stopPropagation();deleteNote('${n.id}')">Delete</button>
      </div>
    </div>
  `).join('');
}

function createNote() {
  const title = prompt('Note title (optional):') || '';
  const content = prompt('Note content:') || '';
  if (!content && !title) return;
  const color = NOTE_COLORS[Math.floor(Math.random() * NOTE_COLORS.length)];
  api('/api/notes', { method: 'POST', body: JSON.stringify({ title, content, color }) })
    .then(() => { loadNotes(); showToast('Note created'); })
    .catch(() => showToast('Failed', 'error'));
}

function createChecklist() {
  const title = prompt('Checklist title:') || '';
  const itemsStr = prompt('Items (comma-separated):') || '';
  const items = itemsStr.split(',').map(i => ({ text: i.trim(), checked: false })).filter(i => i.text);
  api('/api/notes', { method: 'POST', body: JSON.stringify({ title, note_type: 'checklist', items }) })
    .then(() => { loadNotes(); showToast('Checklist created'); })
    .catch(() => showToast('Failed', 'error'));
}

function editNote(id) {
  // Simple inline edit via prompts for now
  const title = prompt('Edit title:');
  if (title === null) return;
  const content = prompt('Edit content:');
  if (content === null) return;
  api(`/api/notes/${id}`, { method: 'PUT', body: JSON.stringify({ title, content }) })
    .then(() => loadNotes()).catch(() => showToast('Failed', 'error'));
}

function pinNote(id, pinned) {
  api(`/api/notes/${id}/pin`, { method: 'POST', body: JSON.stringify({ pinned }) })
    .then(() => loadNotes());
}

function archiveNote(id) {
  api(`/api/notes/${id}/archive`, { method: 'POST', body: '{}' })
    .then(() => { loadNotes(); showToast('Note archived'); });
}

function deleteNote(id) {
  if (!confirm('Delete this note?')) return;
  api(`/api/notes/${id}`, { method: 'DELETE' })
    .then(() => { loadNotes(); showToast('Note deleted'); })
    .catch(() => showToast('Failed', 'error'));
}

/* ─────────────────────────────────────────────
   TASKS
   ───────────────────────────────────────────── */

async function loadTasks() {
  try {
    const tasks = await api('/api/tasks');
    renderTasks(Array.isArray(tasks) ? tasks : []);
  } catch (e) {
    document.getElementById('tasksList').innerHTML = `<div class="fp-empty">Failed to load tasks</div>`;
  }
}

const TASK_ICONS = { llm: '🤖', action: '⚡', research: '🔍' };

function renderTasks(tasks) {
  const el = document.getElementById('tasksList');
  if (!tasks.length) {
    el.innerHTML = `<div class="fp-empty">No scheduled tasks. Click "+ Task" to create one.</div>`;
    return;
  }
  el.innerHTML = tasks.map(t => `
    <div class="task-item">
      <div class="task-item-icon">${TASK_ICONS[t.task_type] || '📋'}</div>
      <div class="task-item-body">
        <div class="task-item-name">${esc(t.name)}</div>
        <div class="task-item-meta">
          ${t.schedule || 'daily'} at ${t.scheduled_time || '09:00'}
          ${t.run_count ? ` · ran ${t.run_count}×` : ''}
          ${t.last_run ? ` · last ${relativeTime(t.last_run)}` : ''}
        </div>
      </div>
      <div style="display:flex;flex-direction:column;gap:4px;align-items:flex-end;">
        <span class="task-item-status ${t.status || 'active'}">${t.status || 'active'}</span>
        <div style="display:flex;gap:4px;">
          ${t.status === 'active'
            ? `<button class="fp-btn" style="font-size:10px;padding:2px 8px;" onclick="pauseTask('${t.id}')">Pause</button>`
            : `<button class="fp-btn" style="font-size:10px;padding:2px 8px;" onclick="resumeTask('${t.id}')">Resume</button>`}
          <button class="fp-btn danger" style="font-size:10px;padding:2px 8px;" onclick="deleteTask('${t.id}')">×</button>
        </div>
      </div>
    </div>
  `).join('');
}

function showCreateTask() {
  const name = prompt('Task name:');
  if (!name) return;
  const prompt_ = prompt('Prompt (what should the AI do?):') || '';
  const time = prompt('Run time (HH:MM, e.g. 09:00):') || '09:00';
  api('/api/tasks', { method: 'POST', body: JSON.stringify({ name, prompt: prompt_, scheduled_time: time, schedule: 'daily', task_type: 'llm' }) })
    .then(() => { loadTasks(); showToast('Task created'); })
    .catch(() => showToast('Failed', 'error'));
}

function pauseTask(id) {
  api(`/api/tasks/${id}/pause`, { method: 'POST', body: '{}' }).then(() => loadTasks());
}
function resumeTask(id) {
  api(`/api/tasks/${id}/resume`, { method: 'POST', body: '{}' }).then(() => loadTasks());
}
function deleteTask(id) {
  if (!confirm('Delete this task?')) return;
  api(`/api/tasks/${id}`, { method: 'DELETE' }).then(() => { loadTasks(); showToast('Task deleted'); });
}

/* ─────────────────────────────────────────────
   EMAIL
   ───────────────────────────────────────────── */

let _activeAccountId = null;

async function loadEmailAccounts() {
  try {
    const accounts = await api('/api/email/accounts');
    renderEmailAccounts(Array.isArray(accounts) ? accounts : []);
  } catch (e) {
    document.getElementById('emailAccountsList').innerHTML = `<div style="font-size:12px;color:var(--muted2);padding:8px;">No accounts</div>`;
  }
}

function renderEmailAccounts(accounts) {
  const el = document.getElementById('emailAccountsList');
  if (!accounts.length) {
    el.innerHTML = `<button class="email-account-btn" onclick="showAddEmailAccount()">+ Add account</button>`;
    return;
  }
  el.innerHTML = accounts.map(a => `
    <button class="email-account-btn ${a.id === _activeAccountId ? 'active' : ''}"
      onclick="selectEmailAccount('${a.id}')">
      📧 ${esc(a.name || a.username)}
    </button>
  `).join('') + `<button class="email-account-btn" onclick="showAddEmailAccount()">+ Add account</button>`;

  if (!_activeAccountId && accounts.length > 0) {
    selectEmailAccount(accounts[0].id);
  }
}

async function selectEmailAccount(accountId) {
  _activeAccountId = accountId;
  document.querySelectorAll('.email-account-btn').forEach(b => {
    b.classList.toggle('active', b.getAttribute('onclick')?.includes(accountId));
  });
  loadEmailMessages(accountId);
}

async function loadEmailMessages(accountId) {
  const el = document.getElementById('emailMessagesList');
  el.innerHTML = `<div class="fp-empty">Loading…</div>`;
  try {
    const msgs = await api(`/api/email/accounts/${accountId}/messages?folder=INBOX&page=1&pageSize=30`);
    renderEmailMessages(Array.isArray(msgs) ? msgs : []);
  } catch (e) {
    el.innerHTML = `<div class="fp-empty">Failed to load messages</div>`;
  }
}

function renderEmailMessages(msgs) {
  const el = document.getElementById('emailMessagesList');
  if (!msgs.length) {
    el.innerHTML = `<div class="fp-empty">No messages in this folder</div>`;
    return;
  }
  el.innerHTML = msgs.map(m => {
    const urgency = m.urgency_score >= 4 ? 'high' : m.urgency_score >= 3 ? 'medium' : 'low';
    return `
      <div class="email-row ${!m.is_read ? 'unread' : ''}" onclick="openEmail('${m.id}')">
        <div style="display:flex;align-items:center;gap:6px;">
          <span class="email-urgency ${urgency}"></span>
          <div style="flex:1;min-width:0;">
            <div class="email-row-subject">${esc(m.subject || '(no subject)')}</div>
            <div class="email-row-from">${esc(m.from_addr || '')}</div>
          </div>
          <div class="email-row-date">${relativeTime(m.date_sent)}</div>
        </div>
        ${m.ai_summary ? `<div style="font-size:11px;color:var(--muted2);margin-top:3px;padding-left:16px;">${esc(m.ai_summary)}</div>` : ''}
      </div>`;
  }).join('');
}

async function openEmail(msgId) {
  const preview = document.getElementById('emailPreview');
  preview.style.display = 'block';
  preview.innerHTML = `<div style="padding:20px;color:var(--muted2);font-size:12px;">Loading…</div>`;

  try {
    const msg = await api(`/api/email/accounts/${_activeAccountId}/messages/${msgId}`);
    preview.innerHTML = `
      <div style="padding:0 0 12px;border-bottom:1px solid var(--border);margin-bottom:12px;">
        <div style="font-size:15px;font-weight:600;margin-bottom:4px;">${esc(msg.subject || '(no subject)')}</div>
        <div style="font-size:12px;color:var(--muted1);">From: ${esc(msg.from_addr)}</div>
        <div style="font-size:11px;color:var(--muted2);">${msg.date_sent ? new Date(msg.date_sent).toLocaleString() : ''}</div>
      </div>
      ${msg.ai_summary ? `<div style="background:var(--ac-pale);border:1px solid var(--ac-mid);border-radius:8px;padding:8px 12px;font-size:12px;margin-bottom:12px;"><strong>AI Summary:</strong> ${esc(msg.ai_summary)}</div>` : ''}
      <div style="font-size:13px;line-height:1.6;white-space:pre-wrap;">${esc(msg.body_text || '(no body)')}</div>
      <div style="display:flex;gap:8px;margin-top:16px;border-top:1px solid var(--border);padding-top:12px;">
        <button class="fp-btn primary" onclick="replyToEmail('${msg.id}')">↩ Reply</button>
        <button class="fp-btn" onclick="replyDraft('${msg.id}')">✨ AI Draft</button>
        <button class="fp-btn danger" onclick="deleteEmail('${msg.id}')">Delete</button>
      </div>`;
  } catch (e) {
    preview.innerHTML = `<div style="padding:20px;color:var(--danger);">Failed to load message</div>`;
  }
}

function replyToEmail(msgId) {
  const body = prompt('Reply:') || '';
  if (!body) return;
  api(`/api/email/accounts/${_activeAccountId}/send`, {
    method: 'POST',
    body: JSON.stringify({ reply_to_id: msgId, body, subject: 'Re: (reply)' })
  }).then(() => showToast('Reply sent')).catch(() => showToast('Failed', 'error'));
}

async function replyDraft(msgId) {
  const res = await api(`/api/email/accounts/${_activeAccountId}/messages/${msgId}/reply-draft`, { method: 'POST', body: '{}' });
  if (res?.draft) {
    alert('AI Draft:\n\n' + res.draft);
  }
}

function deleteEmail(msgId) {
  if (!confirm('Delete this message?')) return;
  api(`/api/email/accounts/${_activeAccountId}/messages/${msgId}`, { method: 'DELETE' })
    .then(() => { loadEmailMessages(_activeAccountId); document.getElementById('emailPreview').style.display = 'none'; showToast('Deleted'); });
}

function showAddEmailAccount() {
  const host = prompt('IMAP host (e.g. imap.gmail.com):');
  if (!host) return;
  const username = prompt('Email address:');
  if (!username) return;
  const password = prompt('Password / App password:');
  if (!password) return;
  const smtpHost = prompt('SMTP host (e.g. smtp.gmail.com):') || host.replace('imap.', 'smtp.');
  api('/api/email/accounts', {
    method: 'POST',
    body: JSON.stringify({
      name: username, imap_host: host, imap_port: 993, smtp_host: smtpHost, smtp_port: 587,
      username, password, display_name: username, imap_security: 'SSL', smtp_security: 'STARTTLS'
    })
  }).then(() => { loadEmailAccounts(); showToast('Account added'); })
    .catch(e => showToast('Failed: ' + e.message, 'error'));
}

function showEmailAccounts() {
  loadEmailAccounts();
}

function showCompose() {
  if (!_activeAccountId) { alert('Select an email account first'); return; }
  const to = prompt('To:');
  if (!to) return;
  const subject = prompt('Subject:') || '';
  const body = prompt('Message body:') || '';
  api(`/api/email/accounts/${_activeAccountId}/send`, {
    method: 'POST',
    body: JSON.stringify({ to, subject, body })
  }).then(() => showToast('Email sent')).catch(e => showToast('Failed: ' + e.message, 'error'));
}

/* ─────────────────────────────────────────────
   CALENDAR
   ───────────────────────────────────────────── */

let _calYear = new Date().getFullYear();
let _calMonth = new Date().getMonth();
let _calEvents = [];

function initCalendar() {
  renderCalendar();
  loadCalEvents();
}

function calNav(dir) {
  _calMonth += dir;
  if (_calMonth < 0) { _calMonth = 11; _calYear--; }
  if (_calMonth > 11) { _calMonth = 0; _calYear++; }
  renderCalendar();
  loadCalEvents();
}

async function loadCalEvents() {
  const from = new Date(_calYear, _calMonth, 1).toISOString();
  const to = new Date(_calYear, _calMonth + 1, 0, 23, 59, 59).toISOString();
  try {
    const events = await api(`/api/calendar/events?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
    _calEvents = Array.isArray(events) ? events : [];
    renderCalendar();
  } catch (e) {}
}

const MONTHS = ['January','February','March','April','May','June','July','August','September','October','November','December'];
const DAYS = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];

function renderCalendar() {
  const label = document.getElementById('calMonthLabel');
  const grid = document.getElementById('calGrid');
  if (!label || !grid) return;

  label.textContent = `${MONTHS[_calMonth]} ${_calYear}`;

  const firstDay = new Date(_calYear, _calMonth, 1).getDay();
  const daysInMonth = new Date(_calYear, _calMonth + 1, 0).getDate();
  const today = new Date();

  let html = DAYS.map(d => `<div class="cal-day-header">${d}</div>`).join('');

  // Padding
  for (let i = 0; i < firstDay; i++) {
    const prevDate = new Date(_calYear, _calMonth, -firstDay + i + 1);
    html += `<div class="cal-day other-month"><div class="cal-day-num">${prevDate.getDate()}</div></div>`;
  }

  for (let d = 1; d <= daysInMonth; d++) {
    const date = new Date(_calYear, _calMonth, d);
    const isToday = date.toDateString() === today.toDateString();
    const dayEvents = _calEvents.filter(e => {
      const start = new Date(e.start_at);
      return start.getFullYear() === _calYear && start.getMonth() === _calMonth && start.getDate() === d;
    });

    html += `<div class="cal-day ${isToday ? 'today' : ''}" onclick="calDayClick(${_calYear},${_calMonth},${d})">
      <div class="cal-day-num">${d}</div>
      ${dayEvents.slice(0, 3).map(() => `<div class="cal-event-dot"></div>`).join('')}
    </div>`;
  }

  grid.innerHTML = html;
}

function calDayClick(y, m, d) {
  const dateStr = `${y}-${String(m + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  // Show events for this day
  const dayEvents = _calEvents.filter(e => e.start_at?.startsWith(dateStr));
  if (dayEvents.length) {
    alert(dayEvents.map(e => `• ${e.title} @ ${new Date(e.start_at).toLocaleTimeString()}`).join('\n'));
  } else {
    if (confirm(`Create event on ${dateStr}?`)) {
      showCreateEventOn(dateStr);
    }
  }
}

function showCreateEvent() {
  const today = new Date().toISOString().slice(0, 10);
  showCreateEventOn(today);
}

async function showCreateEventOn(dateStr) {
  const title = prompt('Event title:');
  if (!title) return;
  const time = prompt('Time (HH:MM, 24h):') || '09:00';

  // Get or create default calendar
  let calendarId;
  try {
    const cals = await api('/api/calendar/calendars');
    if (cals?.length) {
      calendarId = cals[0].id;
    } else {
      const cal = await api('/api/calendar/calendars', { method: 'POST', body: JSON.stringify({ name: 'My Calendar' }) });
      calendarId = cal.id;
    }
    await api(`/api/calendar/calendars/${calendarId}/events`, {
      method: 'POST',
      body: JSON.stringify({
        title,
        start_at: `${dateStr}T${time}:00Z`,
        end_at: `${dateStr}T${String(parseInt(time) + 1).padStart(2, '0')}:00:00Z`
      })
    });
    loadCalEvents();
    showToast('Event created');
  } catch (e) {
    showToast('Failed to create event', 'error');
  }
}

/* ─────────────────────────────────────────────
   COMPARE
   ───────────────────────────────────────────── */

let _compareId = null;
let _compareBlind = true;

async function loadCompareModels() {
  try {
    // Use state from main app.js if available
    const models = typeof state !== 'undefined' && state?.models ? state.models : [];
    const selA = document.getElementById('compareModelA');
    const selB = document.getElementById('compareModelB');
    if (!selA || !selB) return;
    const opts = models.map(m => `<option value="${esc(m.model_ref || m.name)}">${esc(m.name)}</option>`).join('');
    selA.innerHTML = opts || '<option>No models</option>';
    selB.innerHTML = opts || '<option>No models</option>';
    if (models.length > 1) selB.selectedIndex = 1;
  } catch (e) {}
}

async function loadCompareHistory() {
  try {
    const comparisons = await api('/api/compare/history?limit=10');
    renderCompareHistory(Array.isArray(comparisons) ? comparisons : []);
  } catch (e) {}
}

function renderCompareHistory(comparisons) {
  const el = document.getElementById('compareHistoryList');
  if (!el) return;
  if (!comparisons.length) {
    el.innerHTML = `<div style="font-size:12px;color:var(--muted2);">No comparisons yet.</div>`;
    return;
  }
  el.innerHTML = comparisons.map(c => `
    <div style="border:1px solid var(--border);border-radius:8px;padding:10px 12px;margin-bottom:8px;font-size:12px;">
      <div style="font-weight:600;margin-bottom:4px;">${esc(c.prompt?.slice(0, 80) || '')}</div>
      <div style="color:var(--muted2);">${esc(c.model_a)} vs ${esc(c.model_b)}
        ${c.winner ? `· Winner: <strong>${esc(c.winner)}</strong>` : '· Pending'}
      </div>
    </div>
  `).join('');
}

async function startCompare() {
  const prompt = document.getElementById('comparePrompt')?.value?.trim();
  const modelA = document.getElementById('compareModelA')?.value;
  const modelB = document.getElementById('compareModelB')?.value;
  if (!prompt || !modelA || !modelB) { showToast('Fill in all fields', 'error'); return; }
  if (modelA === modelB) { showToast('Choose different models', 'error'); return; }

  // Get endpoints from state
  const models = typeof state !== 'undefined' && state?.models ? state.models : [];
  const mA = models.find(m => m.model_ref === modelA || m.name === modelA);
  const mB = models.find(m => m.model_ref === modelB || m.name === modelB);

  try {
    const cmp = await api('/api/compare/start', {
      method: 'POST',
      body: JSON.stringify({
        prompt, model_a: modelA, model_b: modelB,
        endpoint_a: mA?.provider || 'ollama', endpoint_b: mB?.provider || 'ollama',
        is_blind: true
      })
    });
    _compareId = cmp.id;
    showCompareArena(prompt, cmp);
  } catch (e) {
    showToast('Failed to start comparison', 'error');
  }
}

function showCompareArena(prompt, cmp) {
  document.getElementById('compareSetup').style.display = 'none';
  const arena = document.getElementById('compareArena');
  arena.style.display = 'grid';
  document.getElementById('compareColA').querySelector('.compare-col-label').textContent = cmp.label_a || 'Model A';
  document.getElementById('compareColB').querySelector('.compare-col-label').textContent = cmp.label_b || 'Model B';
  document.getElementById('compareContentA').innerHTML = `<div style="color:var(--muted2);font-size:12px;">Generating…</div>`;
  document.getElementById('compareContentB').innerHTML = `<div style="color:var(--muted2);font-size:12px;">Generating…</div>`;

  // Simulate response generation (real implementation would use SSE)
  setTimeout(() => {
    document.getElementById('compareContentA').innerHTML = `<div style="font-size:13px;line-height:1.6;color:var(--body-text);">Response from Model A will appear here.<br><br>In a full implementation, this would stream via SSE from <code>/api/chat/stream</code> using session IDs ${cmp.session_id_a}.</div>`;
    document.getElementById('compareContentB').innerHTML = `<div style="font-size:13px;line-height:1.6;color:var(--body-text);">Response from Model B will appear here.<br><br>In a full implementation, this would stream via SSE from <code>/api/chat/stream</code> using session IDs ${cmp.session_id_b}.</div>`;
    document.getElementById('compareVoteRow').style.display = 'block';
  }, 1500);
}

async function castVote(side) {
  if (!_compareId) return;
  const winner = side === 'tie' ? 'tie' : (side === 'a' ? 'Model A' : 'Model B');
  try {
    await api(`/api/compare/${_compareId}/vote`, { method: 'POST', body: JSON.stringify({ winner }) });
    showToast(`Voted: ${winner}`);
    // Reset UI
    document.getElementById('compareSetup').style.display = 'block';
    document.getElementById('compareArena').style.display = 'none';
    document.getElementById('compareVoteRow').style.display = 'none';
    document.getElementById('comparePrompt').value = '';
    _compareId = null;
    loadCompareHistory();
  } catch (e) {
    showToast('Failed to record vote', 'error');
  }
}

/* ─────────────────────────────────────────────
   COOKBOOK
   ───────────────────────────────────────────── */

async function detectHardware() {
  const el = document.getElementById('cookbookHw');
  el.innerHTML = `<div class="fp-empty">Detecting hardware…</div>`;
  try {
    const hw = await api('/api/cookbook/hardware');
    el.innerHTML = `
      <div class="cookbook-hw-row">
        <span class="cookbook-hw-label">GPU</span>
        <span class="cookbook-hw-val">${esc(hw.gpu_name || 'None detected')}</span>
      </div>
      <div class="cookbook-hw-row">
        <span class="cookbook-hw-label">VRAM</span>
        <span class="cookbook-hw-val">${hw.gpu_vram_gb ? hw.gpu_vram_gb + ' GB' : '—'}</span>
      </div>
      <div class="cookbook-hw-row">
        <span class="cookbook-hw-label">RAM</span>
        <span class="cookbook-hw-val">${hw.available_ram_gb ? hw.available_ram_gb + ' GB' : '—'}</span>
      </div>
      <div class="cookbook-hw-row">
        <span class="cookbook-hw-label">Backend</span>
        <span class="cookbook-hw-val">${esc(hw.backend || 'cpu')}</span>
      </div>
      <div class="cookbook-hw-row">
        <span class="cookbook-hw-label">Bandwidth</span>
        <span class="cookbook-hw-val">${hw.gpu_bandwidth_gb_s ? hw.gpu_bandwidth_gb_s + ' GB/s' : '—'}</span>
      </div>`;
    loadCookbookCatalog();
  } catch (e) {
    el.innerHTML = `<div class="fp-empty">Hardware detection failed. Try again.</div>`;
  }
}

async function loadCookbookCatalog() {
  try {
    const catalog = await api('/api/cookbook/catalog');
    renderCookbookCatalog(Array.isArray(catalog) ? catalog : []);
  } catch (e) {}
}

function renderCookbookCatalog(models) {
  const el = document.getElementById('cookbookModelGrid');
  if (!el) return;
  el.className = 'cookbook-model-grid';
  el.innerHTML = models.map(m => `
    <div class="cookbook-model-card ${m.fits ? 'fits' : 'no-fit'}">
      <div class="cookbook-fit-badge ${m.fits ? 'fits' : 'no-fit'}">${m.fits ? '✓ Fits' : '✗ Too large'}</div>
      <div class="cookbook-model-name">${esc(m.name)}</div>
      <div class="cookbook-model-meta">${m.params_b}B · ${m.quantization} · ${m.size_gb}GB · ${m.use_case}</div>
      <div class="cookbook-model-actions">
        ${m.is_downloaded
          ? `<button class="fp-btn primary" style="font-size:11px;" onclick="serveModel('${esc(m.hf_repo)}')">▶ Serve</button>`
          : `<button class="fp-btn primary" style="font-size:11px;" onclick="downloadModel('${esc(m.hf_repo)}', '${esc(m.hf_filename || '')}')">⬇ Download</button>`}
        <span style="font-size:10px;color:var(--muted2);align-self:center;">${esc(m.hf_repo.split('/').pop())}</span>
      </div>
    </div>
  `).join('');
}

function downloadModel(hfRepo, hfFile) {
  showToast(`Downloading ${hfRepo.split('/').pop()}…`);
  const es = new EventSource(`/api/cookbook/download`);
  // In practice would use fetch + SSE; simplified here
  fetch('/api/cookbook/download', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
    body: JSON.stringify({ hf_repo: hfRepo, hf_filename: hfFile || null })
  }).then(() => { showToast('Download started — check Admin panel for progress'); })
    .catch(e => showToast('Failed: ' + e.message, 'error'));
}

function serveModel(hfRepo) {
  showToast(`${hfRepo.split('/').pop()} — configure in settings to serve`);
}

/* ─────────────────────────────────────────────
   ASSISTANT
   ───────────────────────────────────────────── */

let _assistant = null;

async function loadAssistant() {
  const el = document.getElementById('assistantBody');
  try {
    _assistant = await api('/api/assistant');
    renderAssistant(_assistant);
  } catch (e) {
    el.innerHTML = `<div class="fp-empty">Failed to load assistant</div>`;
  }
}

function renderAssistant(a) {
  if (!a) return;
  const el = document.getElementById('assistantBody');
  el.innerHTML = `
    <div style="text-align:center;margin-bottom:20px;">
      <div class="assistant-avatar">${esc(a.avatar || '🤖')}</div>
      <div style="font-size:16px;font-weight:600;">${esc(a.name || 'Assistant')}</div>
      <div style="font-size:12px;color:var(--muted2);margin-top:2px;">Your personal AI assistant</div>
    </div>

    <div class="fp-field">
      <label class="fp-label">Name</label>
      <input class="fp-input" id="aName" value="${esc(a.name || 'Assistant')}">
    </div>
    <div class="fp-field">
      <label class="fp-label">Avatar emoji</label>
      <input class="fp-input" id="aAvatar" value="${esc(a.avatar || '🤖')}" style="width:80px;">
    </div>
    <div class="fp-field">
      <label class="fp-label">Personality</label>
      <textarea class="fp-textarea" id="aPersonality" style="min-height:60px;">${esc(a.personality || '')}</textarea>
    </div>
    <div class="fp-field">
      <label class="fp-label">Timezone</label>
      <input class="fp-input" id="aTimezone" value="${esc(a.timezone || 'UTC')}">
    </div>

    <div style="margin-top:16px;border-top:1px solid var(--border);padding-top:16px;">
      <div class="fp-label" style="margin-bottom:10px;">Daily Check-ins</div>
      ${(a.check_ins || []).map(ci => `
        <div class="assistant-checkin">
          <span class="assistant-checkin-time">${esc(ci.scheduled_time || '09:00')}</span>
          <div>
            <div class="assistant-checkin-name">${esc(ci.name || 'Check-in')}</div>
          </div>
          <button class="assistant-checkin-toggle ${ci.status === 'active' ? 'on' : ''}"
            onclick="toggleCheckin('${ci.id}', ${ci.status === 'paused'})">
            ${ci.status === 'active' ? 'On' : 'Off'}
          </button>
        </div>
      `).join('')}
    </div>`;
}

async function saveAssistant() {
  try {
    const data = {
      name: document.getElementById('aName')?.value,
      avatar: document.getElementById('aAvatar')?.value,
      personality: document.getElementById('aPersonality')?.value,
      timezone: document.getElementById('aTimezone')?.value
    };
    _assistant = await api('/api/assistant', { method: 'PUT', body: JSON.stringify(data) });
    renderAssistant(_assistant);
    showToast('Assistant saved');
  } catch (e) {
    showToast('Failed to save', 'error');
  }
}

function toggleCheckin(taskId, activate) {
  const endpoint = activate ? `/api/tasks/${taskId}/resume` : `/api/tasks/${taskId}/pause`;
  api(endpoint, { method: 'POST', body: '{}' })
    .then(() => loadAssistant())
    .catch(() => showToast('Failed', 'error'));
}

/* ─────────────────────────────────────────────
   RESEARCH TASKS
   ───────────────────────────────────────────── */

async function loadResearchTasks() {
  try {
    const tasks = await api('/api/research/tasks');
    renderResearchTasks(Array.isArray(tasks) ? tasks : []);
  } catch (e) {
    document.getElementById('researchTasksList').innerHTML = `<div class="fp-empty">Failed to load research tasks</div>`;
  }
}

function renderResearchTasks(tasks) {
  const el = document.getElementById('researchTasksList');
  if (!tasks.length) {
    el.innerHTML = `<div class="fp-empty">No research tasks yet.<br>Use the <strong>Research</strong> button in chat to start a deep research run.</div>`;
    return;
  }
  el.innerHTML = tasks.map(t => `
    <div class="research-task-item">
      <div class="research-task-query">${esc(t.query)}</div>
      <div class="research-task-meta">
        <span class="research-status ${t.status}">${t.status}</span>
        <span>${relativeTime(t.started_at)}</span>
        ${t.duration_ms ? `<span>${(t.duration_ms / 1000).toFixed(1)}s</span>` : ''}
      </div>
      <div class="research-task-actions">
        ${t.status === 'completed'
          ? `<a href="/api/research/tasks/${t.id}/report" target="_blank" class="fp-btn primary" style="font-size:11px;">📄 View Report</a>`
          : ''}
        ${t.status === 'running'
          ? `<button class="fp-btn danger" style="font-size:11px;" onclick="cancelResearch('${t.id}')">Cancel</button>`
          : ''}
      </div>
    </div>
  `).join('');
}

async function cancelResearch(id) {
  try {
    await api(`/api/research/tasks/${id}`, { method: 'DELETE' });
    loadResearchTasks();
    showToast('Research cancelled');
  } catch (e) {
    showToast('Failed to cancel', 'error');
  }
}

/* ─────────────────────────────────────────────
   KEYBOARD SHORTCUT: Ctrl+Shift+M = Memory
   ───────────────────────────────────────────── */
document.addEventListener('keydown', e => {
  if (e.ctrlKey && e.shiftKey) {
    switch (e.key) {
      case 'M': e.preventDefault(); openFeaturePanel('memory'); break;
      case 'N': e.preventDefault(); openFeaturePanel('notes'); break;
      case 'T': e.preventDefault(); openFeaturePanel('tasks'); break;
      case 'E': e.preventDefault(); openFeaturePanel('email'); break;
    }
  }
});

console.log('[Olla Nest] Features v2026.2.0 loaded — Memory, Skills, Notes, Tasks, Email, Calendar, Compare, Cookbook, Assistant, Research');
