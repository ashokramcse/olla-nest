async function api(path, options = {}) {
    const res = await fetch(path, { headers: { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest" }, ...options });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "Request failed");
    return data;
}

async function init() {
    try {
        const me = await api("/api/auth/me");
        if (me.authenticated) {
            window.location.href = me.user.role === "admin" ? "/admin" : "/app";
            return;
        }
        const boot = await api("/api/bootstrap");
        if (boot.adminEmail) {
            const hint = document.getElementById("bootstrapHint");
            hint.innerHTML = `First-boot admin: <strong>${boot.adminEmail}</strong> — check server logs for the initial password.`;
            hint.style.display = "block";
            document.getElementById("email").value = boot.adminEmail;
        }
    } catch {}
}

// Show/hide password toggle
document.getElementById("togglePassword").addEventListener("click", function() {
    const pw = document.getElementById("password");
    const isHidden = pw.type === "password";
    pw.type = isHidden ? "text" : "password";
    this.textContent = isHidden ? "Hide" : "Show";
});

document.getElementById("loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const err = document.getElementById("loginError");
    const btn = document.getElementById("submitBtn");
    err.classList.remove("show");
    btn.disabled = true;
    btn.querySelector("span:first-child").textContent = "Signing in…";
    try {
        const result = await api("/api/auth/login", {
            method: "POST",
            body: JSON.stringify({
                email: document.getElementById("email").value.trim(),
                password: document.getElementById("password").value,
            }),
        });
        window.location.href = result.redirectTo;
    } catch (error) {
        err.textContent = error.message;
        err.classList.add("show");
        btn.disabled = false;
        btn.querySelector("span:first-child").textContent = "Sign in";
    }
});

init();

// ── SSO Providers ────────────────────────────────────────────────────────────
async function loadSsoProviders() {
    try {
        const data = await fetch("/api/auth/sso/providers").then(r => r.json());
        if (!data.providers || data.providers.length === 0) return;
        const section = document.getElementById("ssoProviders");
        const list = document.getElementById("ssoProviderList");
        section.style.display = "block";
        data.providers.forEach(provider => {
            const btn = document.createElement("a");
            btn.href = `/api/auth/sso/authorize/${provider.id}`;
            btn.style.cssText = "display:flex;align-items:center;justify-content:center;gap:10px;padding:12px 16px;border:1.5px solid var(--border);border-radius:12px;font-size:14px;font-weight:600;color:var(--body-text);text-decoration:none;background:var(--bg);transition:border-color .15s,background .15s;";
            btn.onmouseenter = () => { btn.style.borderColor = "var(--ac)"; btn.style.background = "var(--ac-pale)"; };
            btn.onmouseleave = () => { btn.style.borderColor = "var(--border)"; btn.style.background = "var(--bg)"; };
            const icon = provider.type === "google"
                ? `<svg width="17" height="17" viewBox="0 0 24 24"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l3.66-2.84z" fill="#FBBC05"/><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/></svg>`
                : `<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`;
            btn.innerHTML = `${icon}<span>Continue with ${provider.name}</span>`;
            list.appendChild(btn);
        });
    } catch {}
}
loadSsoProviders();
