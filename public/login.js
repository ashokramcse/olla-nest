async function api(path, options = {}) {
  const res = await fetch(path, { headers: { "Content-Type": "application/json" }, ...options });
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
    const hint = document.getElementById("bootstrapHint");
    hint.textContent = `First-boot admin: ${boot.adminEmail} — default password shown above.`;
    hint.style.display = "block";
    document.getElementById("email").value = boot.adminEmail;
  } catch {}
}

document.getElementById("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const err = document.getElementById("loginError");
  const btn = document.getElementById("submitBtn");
  err.classList.remove("show");
  btn.disabled = true;
  btn.textContent = "Signing in…";
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
    btn.textContent = "Sign in";
  }
});

init();
