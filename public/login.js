async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Request failed");
  return data;
}

async function init() {
  const me = await api("/api/auth/me");
  if (me.authenticated) {
    window.location.href = me.user.role === "admin" ? "/admin" : "/app";
    return;
  }
  const boot = await api("/api/bootstrap");
  document.querySelector("#email").value = boot.adminEmail;
  document.querySelector("#bootstrapHint").textContent = `First boot admin: ${boot.adminEmail}`;
}

document.querySelector("#loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const error = document.querySelector("#loginError");
  error.textContent = "";
  try {
    const result = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        email: document.querySelector("#email").value.trim(),
        password: document.querySelector("#password").value,
      }),
    });
    window.location.href = result.redirectTo;
  } catch (err) {
    error.textContent = err.message;
  }
});

init();
