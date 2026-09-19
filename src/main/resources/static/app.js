'use strict';

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const res = await fetch(path, {
    method: options.method || 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; }
  catch (e) { throw new Error('响应不是 JSON: ' + text.slice(0, 200)); }
  if (!res.ok) {
    const err = new Error((data.error || 'ERROR') + ': ' + (data.message || res.statusText));
    err.kind = data.error;
    throw err;
  }
  return data;
}

document.querySelectorAll('.tabs button').forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll('.tabs button').forEach((b) => b.classList.remove('active'));
    document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
    btn.classList.add('active');
    $('tab-' + btn.dataset.tab).classList.add('active');
    if (btn.dataset.tab === 'cohorts') loadCohorts();
    if (btn.dataset.tab === 'releases') loadReleases();
  };
});

async function refresh() {
  const data = await api('/api/overview');
  window.__overview = data;
  $('overview').textContent = JSON.stringify({
    trustedSigners: data.trustedSigners,
    families: data.families.map((f) => `${f.familyId} [${f.hwRevisions.join('/')}] 组件:${f.components.join(',')}`),
    firmwareCount: data.firmware.length,
    firmware: data.firmware.map((f) => `${f.component}@${f.version} ${f.sha256.slice(0, 10)}… hw:${f.hwRevisions.join('/')} epoch${f.storageEpoch}`),
    cohorts: data.cohorts.map((c) => `${c.cohortId}${c.frozen ? ' [冻结]' : ''}`),
    releases: data.releases.map((r) => `${r.releaseId}:${r.status}`),
  }, null, 2);
  fillFamilySelects(data);
}

function fillFamilySelects(data) {
  for (const selId of ['matrixFamily', 'cFamily', 'aFamily']) {
    const sel = $(selId);
    const prev = sel.value;
    sel.innerHTML = '';
    data.families.forEach((f) => {
      const opt = document.createElement('option');
      opt.value = f.familyId;
      opt.textContent = f.familyId;
      sel.appendChild(opt);
    });
    if (prev) sel.value = prev;
  }
  syncHw('matrixFamily', 'matrixHw');
  syncHw('cFamily', 'cHw');
  syncHw('aFamily', 'aHw');
}

function syncHw(familySelId, hwSelId) {
  const family = $(familySelId).value;
  const fam = (window.__overview?.families || []).find((f) => f.familyId === family);
  const sel = $(hwSelId);
  const prev = sel.value;
  sel.innerHTML = '';
  (fam?.hwRevisions || []).forEach((hw) => {
    const opt = document.createElement('option');
    opt.value = hw; opt.textContent = hw;
    sel.appendChild(opt);
  });
  if (prev) sel.value = prev;
}

document.addEventListener('change', (e) => {
  if (['matrixFamily', 'cFamily', 'aFamily'].includes(e.target.id)) {
    syncHw('matrixFamily', 'matrixHw');
    syncHw('cFamily', 'cHw');
    syncHw('aFamily', 'aHw');
  }
});

async function importManifest() {
  try {
    const body = JSON.parse($('manifestInput').value);
    const r = await api('/api/import/manifest', { method: 'POST', body });
    $('manifestResult').textContent = '已导入:\n' + JSON.stringify(r, null, 2);
    await refresh();
  } catch (e) { $('manifestResult').textContent = showErr(e); }
}

async function importFirmware() {
  try {
    const body = JSON.parse($('firmwareInput').value);
    const r = await api('/api/import/firmware', { method: 'POST', body });
    $('firmwareResult').textContent = '已导入（签名校验通过）:\n' + JSON.stringify(r, null, 2);
    await refresh();
  } catch (e) { $('firmwareResult').textContent = showErr(e); }
}

function showErr(e) {
  const tag = e.kind === 'INPUT_FORMAT' ? '[输入格式 400]'
    : e.kind === 'STATE_CONFLICT' ? '[状态冲突 409]' : '[内部故障 500]';
  return tag + ' ' + e.message;
}

// ---------- 兼容矩阵 ----------
async function loadMatrix() {
  try {
    const f = $('matrixFamily').value, h = $('matrixHw').value;
    const m = await api(`/api/matrix/${encodeURIComponent(f)}/${encodeURIComponent(h)}`);
    let html = '<table><tr><th>组件</th><th>版本/工件</th><th>bootloader 下限</th><th>存储 epoch</th><th>配套要求（含是否存在共同版本）</th></tr>';
    for (const row of m.components) {
      html += `<tr><td rowspan="${Math.max(1, row.versions.length)}"><b>${row.component}</b></td>`;
      row.versions.forEach((v, i) => {
        if (i > 0) html += '<tr>';
        const comps = v.companions.map((c) => {
          const cls = c.commonVersionExists ? 'ok' : 'bad';
          return `<span class="${cls}">${c.component} ∈ ${c.range} → 共同版本: [${c.satisfyingVersions.join(', ') || '无'}]</span>`;
        }).join('<br>') || '<span class="warnc">—</span>';
        html += `<td>${v.version}<br><small>${v.firmwareId}</small></td><td>${v.minBootloader || '—'}</td><td>${v.storageEpoch}</td><td>${comps}</td></tr>`;
      });
      if (row.versions.length === 0) html += '<td colspan="4" class="warnc">该硬件上无签名版本</td></tr>';
    }
    html += '</table>';
    html += `<div style="margin-top:8px">不可跨越版本: ${m.gates.map((g) => `${g.component}≥${g.fromVersion} 不可向下跨越`).join('；') || '无'}</div>`;
    $('matrixBody').innerHTML = html;
  } catch (e) { $('matrixBody').innerHTML = errHtml(e); }
}

// ---------- cohort / 步骤图 / 故障点 ----------
function errHtml(e) { return `<div class="conflictbox">${showErr(e).replace(/</g, '&lt;')}</div>`; }

function conflictsHtml(conflicts) {
  if (!conflicts || conflicts.length === 0) return '';
  return conflicts.map((c) =>
    `<div class="conflictbox"><span class="red">● ${c.code}</span> ${c.message}
       <br><small>涉及: ${c.involved.join(', ')}</small></div>`).join('');
}

function stepGraphHtml(sim) {
  if (!sim || !sim.steps || sim.steps.length === 0) return '<div class="warnc">无步骤</div>';
  let html = '<div class="stepgraph">';
  for (const s of sim.steps) {
    const cls = s.safeRollback ? (s.step.bundle ? 'stepnode bundle' : 'stepnode') : 'stepnode unsafe';
    const flag = s.safeRollback ? '<span class="pill green">可回滚</span>'
      : '<span class="pill red">无安全回滚</span>';
    const bundle = s.step.bundle ? ' <span class="pill" style="background:#3d3413;color:#ffce6e">BUNDLE</span>' : '';
    html += `<div class="${cls}">
      <b>#${s.step.index}</b> ${s.step.components.join(' + ')}${bundle}<br>
      <small>${s.step.components.map((c) => `${c}: ${s.step.fromVersions[c]}→${s.step.toVersions[c]}`).join('；')}</small><br>${flag}
    </div>`;
  }
  html += '</div>';
  html += sim.allSafe ? '<div class="ok">全部阶段都有安全回滚。</div>'
    : '<div class="red">存在无安全回滚的阶段（红色节点），发布时必须显式确认。</div>';

  html += '<details><summary>展开前置探针 / 健康判据 / 故障点（任意组件写入后断电）</summary>';
  for (const s of sim.steps) {
    html += `<div class="failurebox"><b>步骤 #${s.step.index}</b>
      <div>前置探针：<ul>${s.probe.map((p) => `<li>${p}</li>`).join('')}</ul></div>
      <div>健康判据：<ul>${s.health.map((p) => `<li class="${p.includes('失败') ? 'bad' : 'ok'}">${p}</li>`).join('')}</ul></div>
      <div>回滚目标：${s.rollbackTarget ? JSON.stringify(s.rollbackTarget) : '<span class="red">无</span>'}
        ｜ <span class="${s.safeRollback ? 'ok' : 'red'}">${s.rollbackReason}</span></div>`;
    for (const fp of s.failurePoints) {
      html += `<div class="${fp.recovers ? 'ok' : 'red'}">⚡ 写入 ${fp.component}@${fp.partialVersion} 后断电 → ${fp.recovers ? '可恢复' : '砖化/无定义恢复'}<br>
        <small>${fp.diagnosis}</small></div>`;
    }
    html += '</div>';
  }
  html += '</details>';
  return html;
}

function cohortCard(c, analysis) {
  const plan = analysis.plan;
  let body;
  if (plan.feasible) {
    body = `<div class="ok">可行：${plan.steps.length} 步${plan.rationale.bundleUsed ? '（含 bundle 同刷步骤）' : ''}</div>`;
    body += stepGraphHtml(analysis.simulation);
  } else {
    body = `<div class="red">不可行：最小冲突集合 ${plan.conflicts.length} 条（未做最新版优先硬选）</div>`;
    body += conflictsHtml(plan.conflicts);
  }
  const state = c.frozen ? '<span class="pill gray">已冻结</span>' : '<span class="pill green">活动</span>';
  return `<div class="card" style="margin-bottom:12px">
    <h2>${c.label} <small>(${c.cohortId}, ${c.familyId}/${c.hwRevision})</small> ${state}</h2>
    <div><small>现状: ${JSON.stringify(c.current)} → 目标: ${JSON.stringify(c.target)}</small></div>
    ${body}
    <div style="margin-top:8px">
      <button class="ghost" onclick="freeze('${c.cohortId}', ${!c.frozen})">${c.frozen ? '解冻' : '冻结'}</button>
    </div>
  </div>`;
}

async function loadCohorts() {
  try {
    const data = await api('/api/overview');
    let html = '';
    for (const c of data.cohorts) {
      const analysis = await api('/api/cohorts/' + encodeURIComponent(c.cohortId));
      html += cohortCard(c, analysis);
    }
    $('cohortList').innerHTML = html || '<div class="warnc">还没有 cohort</div>';
  } catch (e) { $('cohortList').innerHTML = errHtml(e); }
}

async function freeze(id, frozen) {
  await api(`/api/cohorts/${encodeURIComponent(id)}/${frozen ? 'freeze' : 'unfreeze'}`, { method: 'POST' });
  await refresh();
  await loadCohorts();
}

async function recompute() {
  try {
    const r = await api('/api/recompute', { method: 'POST', body: {} });
    alert(`重算完成：冻结保留 ${r.frozenKept.length} 批，重算 ${r.recomputed.length} 批`);
    await loadCohorts();
  } catch (e) { alert(showErr(e)); }
}

async function upsertCohort() {
  try {
    const body = {
      cohortId: $('cId').value.trim() || undefined,
      label: $('cLabel').value.trim() || '未命名批次',
      familyId: $('cFamily').value,
      hwRevision: $('cHw').value,
      current: JSON.parse($('cCurrent').value || '{}'),
      target: JSON.parse($('cTarget').value || '{}'),
      frozen: $('cFrozen').checked,
    };
    const r = await api('/api/cohorts', { method: 'POST', body });
    $('cohortSaveResult').textContent = '已保存: ' + JSON.stringify(r, null, 2);
    await refresh();
    await loadCohorts();
  } catch (e) { $('cohortSaveResult').textContent = showErr(e); }
}

// ---------- 发布方案 / 回执 ----------
async function loadReleases() {
  try {
    const data = await api('/api/overview');
    let html = '';
    for (const r of data.releases) {
      const unsafe = r.phases.filter((p) => !p.safeRollback);
      html += `<div class="card" style="margin-bottom:10px">
        <h2>${r.displayName} (${r.releaseId})
          <span class="pill ${r.status === 'approved' ? 'green' : 'gray'}">${r.status}</span>
          ${unsafe.length ? `<span class="pill red">${unsafe.length} 个阶段无安全回滚</span>` : ''}
        </h2>
        <div>阶段：
          <ol>${r.phases.map((p) => `<li>${p.title} - cohort ${p.cohortId}
            ${p.safeRollback ? '<span class="ok">可回滚</span>' : '<span class="red">无安全回滚（标红）</span>'}</li>`).join('')}</ol>
        </div>
        ${r.status === 'draft'
          ? `<button onclick="approve('${r.releaseId}')">批准并绑定清单快照</button>
             <span class="warnc" style="margin-left:10px">批准后新上传的元数据不再影响该方案</span>`
          : `<a href="/api/releases/${encodeURIComponent(r.releaseId)}/export" target="_blank">
               <button class="ghost">下载导出包（决定依据 + 签名校验 + 全部失败分支）</button></a>`}
      </div>`;
    }
    $('releaseList').innerHTML = html || '<div class="warnc">还没有发布方案</div>';
  } catch (e) { $('releaseList').innerHTML = errHtml(e); }
}

async function createRelease() {
  try {
    const body = {
      releaseId: $('rName').value.trim() || undefined,
      displayName: $('rDisplay').value.trim() || $('rName').value.trim() || '发布方案',
      cohortIds: $('rCohorts').value.split(',').map((s) => s.trim()).filter(Boolean),
    };
    const r = await api('/api/releases/create', { method: 'POST', body });
    alert('已创建 draft 方案 ' + r.releaseId);
    await refresh();
    await loadReleases();
  } catch (e) { alert(showErr(e)); }
}

async function approve(id) {
  try {
    await api(`/api/releases/${encodeURIComponent(id)}/approve`, { method: 'POST' });
    alert('方案已批准，清单快照已绑定');
    await refresh();
    await loadReleases();
  } catch (e) { alert(showErr(e)); }
}

async function advanceDevice() {
  try {
    const body = {
      requestId: $('advRequest').value.trim(),
      releaseId: $('advRelease').value.trim(),
      cohortId: $('advCohort').value.trim(),
      deviceId: $('advDevice').value.trim(),
    };
    const r = await api('/api/device/advance', { method: 'POST', body });
    $('advanceResult').textContent = (r.replay ? '[重放] 同一 requestId，未重复推进\n' : '[已推进]\n')
      + JSON.stringify(r, null, 2);
  } catch (e) { $('advanceResult').textContent = showErr(e); }
}

// ---------- 临时分析 ----------
async function adHocAnalyze() {
  try {
    const body = {
      familyId: $('aFamily').value,
      hwRevision: $('aHw').value,
      current: JSON.parse($('aCurrent').value || '{}'),
      target: JSON.parse($('aTarget').value || '{}'),
    };
    const r = await api('/api/analyze', { method: 'POST', body });
    let html = '';
    if (r.plan.feasible) {
      html += `<div class="ok">可行：${r.plan.steps.length} 步${r.plan.rationale.bundleUsed ? '，含 bundle 同刷' : ''}</div>`;
      html += stepGraphHtml(r.simulation);
    } else {
      html += `<div class="red">不可行</div>${conflictsHtml(r.plan.conflicts)}`;
    }
    $('analyzeBody').innerHTML = html;
  } catch (e) { $('analyzeBody').innerHTML = errHtml(e); }
}

// 初始化
refresh().then(() => {
  const f = $('matrixFamily');
  if (f.value) loadMatrix();
});
