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
      hint.innerHTML = `First-boot admin: <strong>${boot.adminEmail}</strong> — password: <strong>${boot.adminPassword || "see above"}</strong>`;
      hint.style.display = "block";
      document.getElementById("email").value = boot.adminEmail;
      if (boot.adminPassword) document.getElementById("password").value = boot.adminPassword;
    }
  } catch {}
}

// Show/hide password toggle
document.getElementById("togglePassword").addEventListener("click", function () {
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
  btn.querySelector("span").textContent = "Signing in…";
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
    btn.querySelector("span").textContent = "Sign in";
  }
});

init();
