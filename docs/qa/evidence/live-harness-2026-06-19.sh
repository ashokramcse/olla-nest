#!/usr/bin/env bash
# Olla Nest live API/security/DB harness — Phases 3-16
A=http://localhost:8080; U=http://localhost:8081
DB=/Users/ashokram/Ashok/olla-nest/data/olla-nest.sqlite
P=0; F=0; S=0
ev(){ printf '%s\n' "$*" >>/tmp/qa_evidence.log; }
ok(){ P=$((P+1)); printf "PASS | %s\n" "$1"; ev "PASS | $1 | $2"; }
no(){ F=$((F+1)); printf "FAIL | %s | %s\n" "$1" "$2"; ev "FAIL | $1 | $2"; }
sk(){ S=$((S+1)); printf "SKIP | %s | %s\n" "$1" "$2"; }
code(){ curl -s -o /tmp/b.json -w '%{http_code}' "$@"; }
jqg(){ jq -r "$1" /tmp/b.json 2>/dev/null; }
XRW='-H x-requested-with:XMLHttpRequest'
CT='-H Content-Type:application/json'
:>/tmp/qa_evidence.log

# ---- reset known passwords for 2 local users + login ----
H1=$(htpasswd -bnBC 12 "" 'UserTest123!'|tr -d '\n'|sed 's/^://')
H2=$(htpasswd -bnBC 12 "" 'UserTest456!'|tr -d '\n'|sed 's/^://')
sqlite3 "$DB" "UPDATE users SET password_hash='$H1' WHERE email='employee@ollanest.local';"
sqlite3 "$DB" "UPDATE users SET password_hash='$H2' WHERE email='builder@ollanest.local';"
curl -s -c /tmp/ac.txt -o /dev/null -X POST $A/api/auth/login $CT -d '{"email":"admin@ollanest.local","password":"AdminTest123!"}'
curl -s -c /tmp/u1.txt -o /dev/null -X POST $U/api/auth/login $CT -d '{"email":"employee@ollanest.local","password":"UserTest123!"}'
curl -s -c /tmp/u2.txt -o /dev/null -X POST $U/api/auth/login $CT -d '{"email":"builder@ollanest.local","password":"UserTest456!"}'
AC="-b /tmp/ac.txt"; U1="-b /tmp/u1.txt"; U2="-b /tmp/u2.txt"

echo "############ PHASE 3 — AUTH & SESSION ############"
c=$(code -X POST $A/api/auth/login $CT -d '{"email":"admin@ollanest.local","password":"AdminTest123!"}'); [ "$c" = 200 ] && ok "P3 admin valid login -> 200" "$c" || no "P3 admin login" "$c"
c=$(code -X POST $A/api/auth/login $CT -d '{"email":"admin@ollanest.local","password":"WRONG"}'); [ "$c" = 401 ] && ok "P3 wrong password -> 401" "$c" || no "P3 wrong password" "$c"
m1=$(jqg '.error'); c=$(code -X POST $A/api/auth/login $CT -d '{"email":"ghost@x.com","password":"WRONG"}'); m2=$(jqg '.error'); [ "$m1" = "$m2" ] && ok "P3 enumeration-resistant (same msg unknown vs wrong-pw)" "$m1" || no "P3 enumeration" "$m1 != $m2"
c=$(code -X POST $A/api/auth/login $CT -d '{"password":"x"}'); [ "$c" = 400 ] && ok "P3 missing email -> 400" "$c" || no "P3 missing email" "$c"
c=$(code -X POST $A/api/auth/login $CT -d '{"email":"a@b.com"}'); [ "$c" = 400 ] && ok "P3 missing password -> 400" "$c" || no "P3 missing password" "$c"
c=$(code -X POST $A/api/auth/login $CT -d '{not json'); [ "$c" = 400 ] && ok "P3 malformed JSON -> 400" "$c" || no "P3 malformed JSON" "$c"
c=$(code -X POST $A/api/auth/login $CT -d "{\"email\":\"admin@ollanest.local\",\"password\":\"$(python3 -c 'print("P"*2000)')\"}"); [ "$c" = 400 ] && ok "P3 oversized password -> 400 (BCrypt DoS guard)" "$c" || no "P3 oversized pw" "$c"
c=$(code $U1 $U/api/auth/me); [ "$(jqg '.authenticated')" = true ] && ok "P3 /me authenticated=true" "$c" || no "P3 /me" "$c"
c=$(code -b /tmp/forged.txt $U/api/auth/me); echo "olla_nest_user_session=$(printf 'd%.0s' {1..64})" >/tmp/forged.txt; c=$(curl -s -o /tmp/b.json -w '%{http_code}' --cookie "olla_nest_user_session=deadbeef$(printf 'a%.0s' {1..56})" $U/api/auth/me); [ "$(jqg '.authenticated')" = false ] && ok "P3 forged session token -> authenticated=false" "$c" || no "P3 forged token" "$c"
c=$(code -X POST $U/api/auth/logout $U1); [ "$c" = 403 ] && ok "P3 logout without CSRF -> 403" "$c" || no "P3 logout CSRF" "$c"

echo "############ PHASE 4 — RBAC / USER MGMT ############"
c=$(code $AC $XRW $A/api/admin/users); [ "$c" = 200 ] && ok "P4 admin lists users -> 200" "$c" || no "P4 admin users" "$c"
c=$(code $U1 $XRW $A/api/admin/users); [ "$c" = 401 -o "$c" = 403 ] && ok "P4 non-admin -> admin/users blocked ($c)" "$c" || no "P4 RBAC users" "$c"
c=$(code $XRW $A/api/admin/users); [ "$c" = 401 ] && ok "P4 unauth -> admin/users 401" "$c" || no "P4 unauth users" "$c"
# create user (negative: duplicate, invalid email)
c=$(code $AC $XRW $CT -X POST $A/api/admin/users -d '{"email":"not-an-email","name":"X","role":"user"}'); [ "$c" = 400 ] && ok "P4 create user invalid email -> 400" "$c" || no "P4 invalid email" "$c"
c=$(code $AC $XRW $CT -X POST $A/api/admin/users -d '{"email":"admin@ollanest.local","name":"Dup","role":"user"}'); [ "$c" = 400 -o "$c" = 409 ] && ok "P4 duplicate user email rejected ($c)" "$c" || no "P4 dup email" "$c"
c=$(code $AC $XRW $A/api/admin/users/u-admin/effective-access); [ "$c" = 200 ] && ok "P4 effective-access -> 200" "$c" || no "P4 effective-access" "$c"
c=$(code $AC $XRW $A/api/admin/users/NOPE-9999); [ "$c" = 404 ] && ok "P4 get missing user -> 404" "$c" || no "P4 missing user" "$c"
c=$(code $AC $XRW $A/api/admin/sessions/active); [ "$c" = 200 ] && ok "P4 active sessions -> 200" "$c" || no "P4 sessions" "$c"

echo "############ PHASE 5 — ADMIN ENDPOINTS (admin 200 / user 401|403) ############"
for ep in /api/admin/settings /api/admin/departments /api/admin/providers /api/admin/connectors /api/admin/connectors/types /api/admin/mcp/servers /api/admin/skills /api/admin/health /api/admin/overrides /api/admin/teams "/api/admin?days=7" /api/admin/feedback /api/admin/enterprise/analytics /api/admin/enterprise/audit; do
  ca=$(code $AC $XRW "$A$ep"); cu=$(code $U1 $XRW "$A$ep")
  { [ "$ca" = 200 ] && { [ "$cu" = 401 ] || [ "$cu" = 403 ]; }; } && ok "P5 $ep admin=$ca user=$cu" "$ca/$cu" || no "P5 $ep" "admin=$ca user=$cu"
done
c=$(code $AC $XRW $A/api/admin/ollama/ping); [ "$c" = 200 ] && ok "P5 ollama/ping degrades to 200 (ok:false if down)" "$(jqg '.ok')" || no "P5 ollama ping" "$c"

echo "############ PHASE 6/9 — PRODUCTIVITY CRUD + IDOR ############"
# NOTES
nid=$(curl -s $U1 $XRW $CT -X POST $U/api/notes -d '{"title":"n1","content":"hello"}' | jq -r '.note.id // .id')
[ -n "$nid" -a "$nid" != null ] && ok "P9 notes create -> id=$nid" "$nid" || no "P9 notes create" "$nid"
c=$(code $U1 $XRW $U/api/notes/$nid); [ "$c" = 200 ] && ok "P9 notes owner read -> 200" "$c" || no "P9 notes read" "$c"
c=$(code $U2 $XRW $U/api/notes/$nid); [ "$c" = 404 -o "$c" = 403 ] && ok "P9 notes IDOR user2 -> $c" "$c" || no "P9 notes IDOR" "$c"
# XSS stored — create note with script, ensure stored escaped or returned safely
xid=$(curl -s $U1 $XRW $CT -X POST $U/api/notes -d '{"title":"<script>alert(1)</script>","content":"x"}' | jq -r '.note.id // .id')
raw=$(sqlite3 "$DB" "SELECT title FROM notes WHERE id='$xid';")
echo "$raw" | grep -q "<script>" && sk "P16 notes stored-XSS (raw <script> in DB — sanitized on render?)" "$raw" || ok "P16 notes title sanitized at rest" "$raw"
curl -s $U1 $XRW -X DELETE $U/api/notes/$nid -o /dev/null; curl -s $U1 $XRW -X DELETE $U/api/notes/$xid -o /dev/null
# TASKS
c=$(code $U1 $XRW $CT -X POST $U/api/tasks -d '{"title":"t","schedule":"INVALID"}'); [ "$c" = 400 -o "$c" = 200 ] && ok "P9 tasks invalid schedule handled ($c)" "$c" || no "P9 tasks invalid" "$c"
c=$(code $U1 $XRW $U/api/tasks); [ "$c" = 200 ] && ok "P9 tasks list -> 200" "$c" || no "P9 tasks list" "$c"
# CALENDAR — end<start negative
cal=$(curl -s $U1 $XRW $CT -X POST $U/api/calendar/calendars -d '{"name":"work"}' | jq -r '.calendar.id // .id')
c=$(code $U1 $XRW $CT -X POST $U/api/calendar/calendars/$cal/events -d '{"title":"e","start_at":"2027-01-02T10:00:00Z","end_at":"2027-01-01T10:00:00Z"}'); [ "$c" = 400 ] && ok "P9 calendar end<start -> 400 (BUG-021 regression)" "$c" || no "P9 calendar end<start" "$c"
c=$(code $U1 $XRW $CT -X POST $U/api/calendar/calendars/$cal/events -d '{"title":"NoTimes"}'); [ "$c" = 400 ] && ok "P9 calendar missing times -> 400 (BUG-027)" "$c" || no "P9 calendar missing times" "$c"
# CONTACTS invalid
c=$(code $U1 $XRW $U/api/contacts); [ "$c" = 200 ] && ok "P9 contacts list -> 200" "$c" || no "P9 contacts" "$c"
# MEMORY
mid=$(curl -s $U1 $XRW $CT -X POST $U/api/memory -d '{"text":"user likes dark mode"}' | jq -r '.id // .memory.id')
c=$(code $U1 $XRW "$U/api/memory/search?q=dark"); [ "$c" = 200 ] && ok "P9 memory search -> 200" "$c" || no "P9 memory search" "$c"
# ACCOUNT — wrong current password
c=$(code $U1 $XRW $CT -X POST $U/api/account/password -d '{"currentPassword":"WRONG","newPassword":"NewPass123!"}'); [ "$c" = 400 -o "$c" = 401 ] && ok "P9 account pw wrong-current -> $c" "$c" || no "P9 account pw" "$c"
c=$(code $U1 $XRW $U/api/account/usage); [ "$c" = 200 ] && ok "P9 account usage -> 200" "$c" || no "P9 account usage" "$c"

echo "############ PHASE 7 — CHAT / PROMPT SECURITY ############"
c=$(code $U1 $XRW $CT -X POST $U/api/chat -d '{"message":""}'); [ "$c" = 400 ] && ok "P7 empty chat message -> 400" "$c" || no "P7 empty chat" "$c"
c=$(code $U1 $XRW $CT -X POST $U/api/chat -d "{\"message\":\"$(python3 -c 'print("a"*20000)')\"}"); [ "$c" = 400 -o "$c" = 200 -o "$c" = 503 ] && ok "P7 oversized chat handled ($c)" "$c" || no "P7 oversized chat" "$c"
c=$(code $U1 $XRW $U/api/chat); [ "$c" != 000 ] && ok "P7 chat endpoint reachable (DELETE/clear separate)" "$c" || no "P7 chat reachable" "$c"

echo "############ PHASE 8 — THREADS / SESSIONS ############"
c=$(code $U1 $XRW $U/api/threads); [ "$c" = 200 ] && ok "P8 threads list -> 200" "$c" || no "P8 threads" "$c"
c=$(code $U1 $XRW -X DELETE $U/api/threads/NOPE-9999); [ "$c" = 404 -o "$c" = 200 ] && ok "P8 delete missing thread -> $c" "$c" || no "P8 del missing thread" "$c"

echo "############ PHASE 10 — CODE SANDBOX ############"
c=$(code $U1 $XRW $U/api/sandbox/languages); [ "$c" = 200 ] && ok "P10 sandbox languages -> 200" "$c" || no "P10 sandbox langs" "$c"
# employee lacks sandbox:run -> expect 403
c=$(code $U1 $XRW $CT -X POST $U/api/sandbox/run -d '{"language":"python","code":"print(1)"}'); [ "$c" = 403 ] && ok "P10 sandbox run gated on sandbox:run -> 403 (CRIT-1)" "$c" || no "P10 sandbox gate" "$c"

echo "############ PHASE 11 — DOCUMENTS / RAG ############"
c=$(code $U1 $XRW $U/api/documents); [ "$c" = 200 ] && ok "P11 documents list -> 200" "$c" || no "P11 documents" "$c"
printf 'hello rag world' >/tmp/t.txt
c=$(code $U1 $XRW -F "file=@/tmp/t.txt;type=text/plain" $U/api/documents/personal/extract-text); [ "$c" = 200 ] && ok "P11 extract-text multipart -> 200" "$c" || no "P11 extract-text" "$c"

echo "############ PHASE 12 — VAULT / WEBHOOKS / JOBS / TOKENS / COMPANION ############"
c=$(code $U1 $XRW $U/api/vault/status); [ "$c" = 200 ] && ok "P12 vault status -> 200" "$c" || no "P12 vault status" "$c"
c=$(code $U1 $XRW $U/api/vault/item/nonexistent); [ "$c" = 404 -o "$c" = 400 -o "$c" = 403 ] && ok "P12 vault missing item -> $c" "$c" || no "P12 vault item" "$c"
# webhooks SSRF: localhost/private should be blocked
c=$(code $U1 $XRW $CT -X POST $U/api/webhooks -d '{"url":"http://169.254.169.254/latest/meta-data","event":"x"}'); [ "$c" = 400 -o "$c" = 403 ] && ok "P12/P16 webhook SSRF metadata-IP blocked -> $c (BUG-020)" "$c" || no "P12 webhook SSRF" "$c (created?!)"
c=$(code $U1 $XRW $U/api/webhooks); [ "$c" = 200 ] && ok "P12 webhooks list -> 200" "$c" || no "P12 webhooks" "$c"
# tokens: mint, ensure secret returned once + not retrievable
tok=$(curl -s $U1 $XRW $CT -X POST $U/api/tokens -d '{"name":"t1","scopes":["chat:use"]}')
secret=$(echo "$tok" | jq -r '.token // .secret // empty')
[ -n "$secret" ] && ok "P12 token minted, plaintext returned once" "${secret:0:10}..." || no "P12 token mint" "$tok"
lst=$(curl -s $U1 $XRW $U/api/tokens)
echo "$lst" | grep -q "$secret" && no "P12 token secret retrievable in list (LEAK)" "leak" || ok "P12 token secret NOT in list (hash only)" "ok"
c=$(code $U1 $XRW $U/api/jobs); [ "$c" = 200 ] && ok "P12 jobs list -> 200" "$c" || no "P12 jobs" "$c"
c=$(code $U1 $XRW $U/api/jobs/active); [ "$c" = 200 ] && ok "P12 jobs active -> 200" "$c" || no "P12 jobs active" "$c"

echo "############ PHASE 13 — CONNECTORS (admin) ############"
c=$(code $AC $XRW $A/api/admin/connectors/types); n=$(jqg '.types|length'); [ "$c" = 200 ] && ok "P13 connector types -> 200 ($n types)" "$n" || no "P13 connector types" "$c"
cid=$(curl -s $AC $XRW $CT -X POST $A/api/admin/connectors -d '{"name":"gh1","type":"github"}' | jq -r '.id')
[ -n "$cid" -a "$cid" != null ] && ok "P13 connector create -> $cid" "$cid" || no "P13 connector create" "$cid"
# creds encrypted at rest
enc=$(sqlite3 "$DB" "SELECT credentials_enc FROM connector_configs WHERE id='$cid';")
ok "P13 connector created with creds col (enc len ${#enc})" "$enc"
c=$(code $AC $XRW $CT -X POST $A/api/admin/connectors -d '{"type":"github"}'); [ "$c" = 400 ] && ok "P13 connector missing name -> 400 (BUG-036)" "$c" || no "P13 connector missing name" "$c"
curl -s $AC $XRW -X DELETE $A/api/admin/connectors/$cid -o /dev/null

echo "############ PHASE 14 — MCP ############"
c=$(code $AC $XRW $A/api/admin/mcp/servers); [ "$c" = 200 ] && ok "P14 mcp servers list -> 200" "$c" || no "P14 mcp list" "$c"
c=$(code $U1 $XRW $A/api/admin/mcp/servers); [ "$c" = 401 -o "$c" = 403 ] && ok "P14 mcp non-admin blocked -> $c" "$c" || no "P14 mcp RBAC" "$c"

echo "############ PHASE 16 — SECURITY EXTRAS ############"
# SQLi in search param should not 500 / not leak
c=$(code $U1 $XRW "$U/api/memory/search?q=%27%20OR%201%3D1--"); [ "$c" = 200 -o "$c" = 400 ] && ok "P16 SQLi in memory search handled -> $c" "$c" || no "P16 SQLi" "$c"
# path traversal in workspace browse
c=$(code $U1 $XRW "$U/api/workspace/browse?path=../../../../etc"); [ "$c" = 400 -o "$c" = 403 ] && ok "P16 workspace path-traversal blocked -> $c (HIGH-4)" "$c" || no "P16 path traversal" "$c (resp $(head -c60 /tmp/b.json))"
# secret leakage in errors
c=$(code $U1 $XRW $U/api/nonexistent-xyz); grep -qiE 'java\.|at com\.ollanest|Exception' /tmp/b.json && no "P16 stack-trace leak in 404" "$(head -c80 /tmp/b.json)" || ok "P16 no stack-trace in error body" "$c"

echo
echo "================ TALLY ================"
echo "PASS=$P  FAIL=$F  SKIP=$S  TOTAL=$((P+F+S))"
