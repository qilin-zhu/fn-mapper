/* fnOS Scanner front-end logic */
"use strict";

const $ = (id) => document.getElementById(id);

const els = {
  form: $("scanForm"),
  host: $("fHost"),
  port: $("fPort"),
  scheme: $("fScheme"),
  mainPort: $("fMainPort"),
  user: $("fUser"),
  pass: $("fPass"),
  insecure: $("fInsecure"),
  btn: $("scanBtn"),
  err: $("errBox"),
  health: $("healthText"),
  empty: $("emptyState"),
  scanning: $("scanningState"),
  scanningTitle: $("scanningTitle"),
  meta: $("resultMeta"),
  appBlock: $("appBlock"),
  appBadge: $("appBadge"),
  appRows: $("appRows"),
  dockerBlock: $("dockerBlock"),
  dockerBadge: $("dockerBadge"),
  dockerRows: $("dockerRows"),
  exportBlock: $("exportBlock"),
  exportUrl: $("exportUrl"),
  footMsg: $("footMsg"),
};

const LS_KEY = "fnos-scanner-prefs-v1";
const PASS_KEY = "fnos-scanner-pass-v1";

/* ---------- 配置持久化（密码仅本地可选记忆） ---------- */
function loadPrefs() {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (!raw) return;
    const p = JSON.parse(raw);
    els.host.value = p.host || "";
    els.port.value = p.port || 5666;
    els.scheme.value = p.scheme || "ws";
    els.mainPort.value = p.mainPort || 5666;
    els.user.value = p.user || "";
    els.insecure.checked = !!p.insecure;
    if (p.rememberPass) {
      const pass = localStorage.getItem(PASS_KEY);
      if (pass) els.pass.value = pass;
    }
  } catch (_) { /* ignore corrupted prefs */ }
}

function savePrefs() {
  let rememberPass = false;
  if (els.pass.value.length > 0) {
    const savedPass = localStorage.getItem(PASS_KEY) || "";
    rememberPass = savedPass === els.pass.value;
    if (!rememberPass) {
      rememberPass = window.confirm("记住密码？密码将明文保存在本机浏览器中。");
    }
  }
  const prefs = {
    host: els.host.value.trim(),
    port: els.port.value || 5666,
    scheme: els.scheme.value,
    mainPort: els.mainPort.value || 5666,
    user: els.user.value.trim(),
    insecure: els.insecure.checked,
    rememberPass,
  };
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(prefs));
    if (rememberPass) {
      localStorage.setItem(PASS_KEY, els.pass.value);
    } else {
      localStorage.removeItem(PASS_KEY);
    }
  } catch (_) { /* storage may be unavailable */ }
}

/* ---------- 工具 ---------- */
function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function statusTag(st) {
  const v = String(st || "").toLowerCase();
  const cls = ["running", "start", "healthy"].includes(v) ? "running" : "";
  return `<span class="status-tag ${cls}">${esc(st || "—")}</span>`;
}

function fmtTime(ms) {
  return ms < 1000 ? ms + " ms" : (ms / 1000).toFixed(1) + " s";
}

/* ---------- 视图状态 ---------- */
function setState(mode) {
  els.empty.hidden = mode !== "empty";
  els.scanning.hidden = mode !== "scan";
  const dataVisible = mode === "done";
  els.appBlock.hidden = !dataVisible;
  els.dockerBlock.hidden = !dataVisible;
  els.exportBlock.hidden = !dataVisible;
  if (mode === "empty") els.meta.textContent = "";
  els.btn.disabled = mode === "scan";
  els.btn.classList.toggle("busy", mode === "scan");
}

function showErr(msg) {
  els.err.hidden = !msg;
  els.err.textContent = msg || "";
}

/* ---------- 渲染 ---------- */
function renderApps(apps) {
  els.appBadge.textContent = apps.length;
  if (apps.length === 0) {
    els.appRows.innerHTML = `<div class="cell-empty">未发现独立端口应用</div>`;
    return;
  }
  els.appRows.innerHTML = apps.map((a) => `
    <div class="row app-cols">
      <div class="cell-name" title="${esc(a.name)}">${esc(a.name)}
        <div class="cell-sub">${esc(a.appName || "")}</div>
      </div>
      <div class="cell-port">${a.port}</div>
      <div class="cell-url"><a href="${esc(a.url)}" target="_blank" rel="noopener">${esc(a.url)}</a></div>
      ${statusTag(a.status)}
    </div>`).join("");
}

function renderDocker(list) {
  const totalPorts = list.reduce((n, d) => n + d.ports.length, 0);
  els.dockerBadge.textContent = `${list.length} 容器 · ${totalPorts} 端口`;
  if (list.length === 0) {
    els.dockerRows.innerHTML = `<div class="cell-empty">未发现映射宿主端口的容器</div>`;
    return;
  }
  els.dockerRows.innerHTML = list.map((d) => {
    const chips = d.ports.map((p) =>
      `<span class="port-chip"><span class="pnum">${p.port}</span> <span class="pproto">/${esc(p.proto)}</span></span>`
    ).join("");
    return `
    <div class="row docker-cols">
      <div class="cell-name" title="${esc(d.name)}">${esc(d.name)}</div>
      <div class="cell-name docker-img" title="${esc(d.image)}">${esc(shortImg(d.image))}</div>
      <div class="docker-ports">${chips}</div>
      ${statusTag(d.state)}
    </div>`;
  }).join("");
}

function shortImg(img) {
  if (!img) return "";
  const parts = img.split("/");
  return parts[parts.length - 1];
}

/* ---------- 扫描 ---------- */
async function doScan() {
  showErr("");
  if (!els.host.value.trim()) {
    showErr("请填写 fnOS 地址，例如 192.168.31.18");
    els.host.focus();
    return;
  }
  const payload = {
    host: els.host.value.trim(),
    port: parseInt(els.port.value || "5666", 10),
    scheme: els.scheme.value,
    main_port: parseInt(els.mainPort.value || "5666", 10),
    username: els.user.value.trim(),
    password: els.pass.value,
    insecure: els.insecure.checked,
  };

  setState("scan");
  els.scanningTitle.textContent = "正在连接 NAS…";
  els.footMsg.textContent = "";
  try {
    const resp = await fetch("/api/scan", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const data = await resp.json();
    if (!data.ok) throw new Error(data.error || "未知错误");

    els.scanningTitle.textContent = "解析完成";
    renderApps(data.apps || []);
    renderDocker(data.docker || []);
    els.meta.textContent =
      `${data.host} · 主端口 ${data.mainPort} · ${fmtTime(data.elapsedMs || 0)}`;
    els.footMsg.textContent =
      `应用 ${(data.meta && data.meta.appCount) || 0} 项 · Docker 端口 ${(data.meta && data.meta.dockerPortTotal) || 0} 个`;
    els.exportUrl.textContent = location.origin + "/ports.json";
    setState("done");
    savePrefs();
  } catch (e) {
    setState("empty");
    showErr("扫描失败：" + e.message);
    els.footMsg.textContent = "";
  }
}

/* ---------- 健康检查 ---------- */
async function health() {
  try {
    const r = await fetch("/api/health");
    const d = await r.json();
    if (d.ok) els.health.textContent = "服务就绪";
  } catch (_) {
    els.health.textContent = "服务异常";
  }
}

/* ---------- 启动 ---------- */
els.form.addEventListener("submit", (e) => { e.preventDefault(); doScan(); });

loadPrefs();
setState("empty");
health();
setInterval(health, 15000);
