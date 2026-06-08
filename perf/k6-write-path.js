// k6 write-path load test: notes create -> list -> delete (with cleanup) under
// ramping load against the user service. Run: k6 run perf/k6-write-path.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8081';
const HDR = { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };

export const options = {
  stages: [
    { duration: '10s', target: 10 },
    { duration: '20s', target: 30 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],          // <1% errors
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ email: 'qa.user@test.local', password: 'REDACTED_TEST_CRED' }), { headers: HDR });
  check(res, { 'login ok': (r) => r.status === 200 });
  const c = res.cookies['olla_nest_user_session'];
  return { cookie: c && c[0] ? `olla_nest_user_session=${c[0].value}` : '' };
}

export default function (data) {
  const h = { ...HDR, Cookie: data.cookie };
  const create = http.post(`${BASE}/api/notes`,
    JSON.stringify({ title: `k6-${__VU}-${__ITER}`, content: 'load-test' }), { headers: h });
  check(create, { 'create 2xx': (r) => r.status === 200 || r.status === 201 });

  const list = http.get(`${BASE}/api/notes`, { headers: h });
  check(list, { 'list 200': (r) => r.status === 200 });

  let id;
  try { id = JSON.parse(create.body).id; } catch (e) { /* ignore */ }
  if (id) {
    const del = http.del(`${BASE}/api/notes/${id}`, null, { headers: h });
    check(del, { 'delete 2xx': (r) => r.status >= 200 && r.status < 300 });
  }
  sleep(0.1);
}
