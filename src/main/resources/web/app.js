'use strict';

const API = '';
let overview = null;

// ------------------------------------------------------------------ helpers

async function api(path, options = {}) {
  const res = await fetch(API + path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (_) { body = { raw: text }; }
  if (!res.ok) {
    const err = body && body.error ? body.error : { code: 'http_' + res.status, message: text };
    const e = new Error(err.message || 'request failed');
    e.code = err.code;
    e.category = err.category;
    e.status = res.status;
    throw e;
  }
  return body;
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined) node.setAttribute(k, v);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
}

function toast(message, kind = '') {
  const t = document.getElementById('toast');
  t.textContent = message;
  t.className = 'toast ' + kind;
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => t.classList.add('hidden'), 4000);
}

function shortHash(h) { return h ? h.slice(0, 12) : '—'; }
function esc(s) { return String(s ?? ''); }

// ----------------------------------------------------------------- overview

async function loadOverview() {
  overview = await api('/api/overview');
  document.getElementById('server-status').textContent =
    `在线 · ${overview.families.length} 族 · ${overview.packages.length} 签名包 · ${overview.devices.length} 设备`;
  fillFamilySelects();
  renderMatrix();
  renderRolloutSelect();
  renderNewRolloutOptions();
  renderPlanDeviceSelect();
}

function families() { return overview.families; }
function familyPackages(family) {
  return overview.packages.filter(p => p.family === family);
}
function familyObj(id) { return overview.families.find(f => f.family === id); }

function fillFamilySelects() {
  for (const id of ['matrix-family', 'plan-family']) {
    const sel = document.getElementById(id);
    sel.innerHTML = '';
    families().forEach(f => sel.append(el('option', { value: f.family }, f.name || f.family)));
  }
  document.getElementById('matrix-family').onchange = renderMatrix;
  const pf = document.getElementById('plan-family');
  pf.onchange = () => { fillRevisionSelect(); renderPlanDeviceSelect(); renderCurrentAndTargets(); };
  fillRevisionSelect();
}

function fillRevisionSelect() {
  const fam = familyObj(document.getElementById('plan-family').value);
  const sel = document.getElementById('plan-revision');
  sel.innerHTML = '';
  (fam?.revisions || []).forEach(r =>
    sel.append(el('option', { value: r.id }, `${r.id} (下限 ${r.bootloader_min})`)));
}

// ------------------------------------------------------------------- matrix

function renderMatrix() {
  const familyId = document.getElementById('matrix-family').value;
  const fam = familyObj(familyId);
  const container = document.getElementById('matrix-container');
  container.innerHTML = '';
  if (!fam) return;
  api(`/api/matrix/${encodeURIComponent(familyId)}`).then(matrix => {
    const revisions = fam.revisions.map(r => r.id);
    const table = el('table');
    table.append(el('thead', {}, el('tr', {},
        el('th', {}, '组件'), el('th', {}, '包'), el('th', {}, '版本'),
        el('th', {}, '格式'), el('th', {}, 'BL 下限'), el('th', {}, '不可跨越'),
        el('th', {}, '可回滚'),
        ...revisions.map(r => el('th', {}, 'HW ' + r)),
        el('th', {}, '配套窗口'))));
    const tbody = el('tbody');
    for (const row of matrix.packages) {
      tbody.append(el('tr', {},
          el('td', {}, row.component),
          el('td', { class: 'mono' }, row.package_id),
          el('td', { class: 'mono' }, row.version),
          el('td', {}, String(row.format_version)),
          el('td', { class: 'mono' }, row.bootloader_min || '—'),
          el('td', { class: 'mono' }, row.max_from_version || '—'),
          el('td', {}, el('span', { class: 'badge ' + (row.safe_rollback ? 'safe' : 'red') },
              row.safe_rollback ? '是' : '否')),
          ...revisions.map(r => el('td', {},
              el('span', { class: 'badge ' + (row.hardware[r] === 'yes' ? 'yes' : 'no') },
                  row.hardware[r] === 'yes' ? '✓' : '✗'))),
          el('td', { class: 'small mono' },
              Object.entries(row.requires).map(([k, v]) => `${k} ${v}`).join('；') || '—')));
    }
    table.append(tbody);
    container.append(
        el('div', { class: 'card' },
            el('h2', {}, `${fam.name || fam.family} — 兼容矩阵（${matrix.packages.length} 个签名包）`),
            table),
        deviceTable(fam));
  }).catch(e => toast(e.message, 'error'));
}

function deviceTable(fam) {
  const devs = overview.devices.filter(d => d.family === fam.family);
  const table = el('table');
  table.append(el('thead', {}, el('tr', {},
      el('th', {}, '设备'), el('th', {}, '修订'), el('th', {}, 'cohort'),
      el('th', {}, '冻结'), ...fam.components.map(c => el('th', {}, c.label || c.id)))));
  const tbody = el('tbody');
  for (const d of devs) {
    tbody.append(el('tr', {},
        el('td', { class: 'mono' }, d.device_id),
        el('td', {}, d.revision),
        el('td', {}, el('span', { class: 'badge info' }, d.cohort)),
        el('td', {}, freezeToggle(d)),
        ...fam.components.map(c => {
          const ref = d.components[c.id];
          const pkg = ref && overview.packages.find(p =>
              p.family === fam.family &&
              `${p.package_id}__${p.version}__${p.sha256.slice(0, 12)}` === ref);
          return el('td', { class: 'mono' }, pkg ? pkg.version : '—');
        })));
  }
  table.append(tbody);
  return el('div', { class: 'card' }, el('h2', {}, `设备现状（${devs.length}）`), table);
}

function freezeToggle(device) {
  const btn = el('button', {
    class: 'ghost ' + (device.frozen ? '' : ''),
    onclick: async () => {
      btn.disabled = true;
      // device freeze is tracked via upsert; send full record
      await api('/api/devices', { method: 'POST', body: JSON.stringify({ ...device, frozen: !device.frozen }) });
      await loadOverview();
      toast(`设备 ${device.device_id} 已${device.frozen ? '解冻' : '冻结'}`, 'success');
    },
  }, device.frozen ? '已冻结 ✓' : '运行中');
  return btn;
}

// --------------------------------------------------------------------- plan

function renderPlanDeviceSelect() {
  const familyId = document.getElementById('plan-family').value;
  const sel = document.getElementById('plan-device');
  sel.innerHTML = '';
  sel.append(el('option', { value: '' }, '— 自定义（用当前选择）—'));
  overview.devices.filter(d => d.family === familyId).forEach(d =>
    sel.append(el('option', { value: d.device_id },
        `${d.device_id} · ${d.cohort} · ${d.revision}${d.frozen ? ' 🔒' : ''}`)));
  sel.onchange = () => {
    const d = overview.devices.find(x => x.device_id === sel.value);
    if (d) {
      document.getElementById('plan-revision').value = d.revision;
    }
    renderCurrentAndTargets();
    // Convenience: preselect the newest compatible target per component so the
    // solver's non-newest-first behaviour is visible against a "latest" intent.
    if (d) {
      document.querySelectorAll('#plan-targets select').forEach(targetSel => {
        const comp = targetSel.dataset.component;
        const options = [...targetSel.options].filter(o => o.value);
        if (!options.length) return;
        // newest option for the device revision (hw compatibility is enforced
        // server-side anyway; a bad choice surfaces as a minimal conflict set)
        targetSel.value = options[options.length - 1].value;
      });
    }
  };
}

function packageByRef(family, component, ref) {
  if (!ref) return null;
  return overview.packages.find(p =>
      p.family === family &&
      `${p.package_id}__${p.version}__${p.sha256.slice(0, 12)}` === ref)
      || overview.packages.find(p =>
      p.family === family && p.component === component &&
      (p.package_id + '#' + p.version) === ref) || null;
}

function refOption(family, component, currentRef) {
  const select = el('select', {});
  select.append(el('option', { value: '' }, '（不变）'));
  familyPackages(family)
      .filter(p => p.component === component)
      .sort((a, b) => a.version.localeCompare(b.version, undefined, { numeric: true }))
      .forEach(p => {
        const ref = `${p.package_id}__${p.version}__${p.sha256.slice(0, 12)}`;
        const opt = el('option', { value: ref },
            `${p.version} (fmt${p.format_version}${p.safe_rollback ? '' : ', 不可回滚'}) ${shortHash(p.sha256)}`);
        if (ref === currentRef) opt.selected = true;
        select.append(opt);
      });
  return select;
}

function renderCurrentAndTargets() {
  const familyId = document.getElementById('plan-family').value;
  const fam = familyObj(familyId);
  const deviceId = document.getElementById('plan-device').value;
  const device = overview.devices.find(d => d.device_id === deviceId);

  const curBox = document.getElementById('plan-current');
  const tgtBox = document.getElementById('plan-targets');
  curBox.innerHTML = '';
  tgtBox.innerHTML = '';
  for (const c of fam.components) {
    const ref = device ? device.components[c.id] : null;
    const pkg = packageByRef(familyId, c.id, ref);
    curBox.append(el('div', { class: 'kv' },
        el('span', { class: 'k' }, c.label || c.id),
        el('span', { class: 'mono' }, pkg ? `${pkg.version} · ${shortHash(pkg.sha256)}` : '未安装')));
    const wrap = el('label', {}, `${c.label || c.id} → `);
    const select = refOption(familyId, c.id, null);
    select.dataset.component = c.id;
    wrap.append(select);
    tgtBox.append(wrap);
  }
  if (device) document.getElementById('plan-revision').value = device.revision;
}

document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('plan-evaluate').onclick = evaluatePlan;
});

async function evaluatePlan() {
  const family = document.getElementById('plan-family').value;
  const revision = document.getElementById('plan-revision').value;
  const deviceId = document.getElementById('plan-device').value;
  const device = overview.devices.find(d => d.device_id === deviceId);
  const current = device ? { ...device.components } : {};
  const targets = {};
  document.querySelectorAll('#plan-targets select').forEach(sel => {
    if (sel.value) targets[sel.dataset.component] = sel.value;
  });
  const resultBox = document.getElementById('plan-result');
  resultBox.innerHTML = '求解中（BFS 枚举合法刷写顺序，非最新版优先）…';
  try {
    const plan = await api('/api/plans/evaluate', {
      method: 'POST',
      body: JSON.stringify({ family, revision, current, targets }),
    });
    resultBox.innerHTML = '';
    resultBox.append(renderPlan(plan));
  } catch (e) {
    resultBox.innerHTML = '';
    resultBox.append(el('div', { class: 'verdict infeasible' },
        `输入错误 [${e.code}]：${e.message}`));
  }
}

function renderPlan(plan) {
  const frag = document.createDocumentFragment();
  if (plan.feasible) {
    const derived = Object.keys(plan.derived_targets || {});
    frag.append(el('div', { class: 'verdict feasible' },
        `✓ 可行：${plan.steps.length} 步` +
        (derived.length ? `（系统按配套窗口自动派生 ${derived.join('、')} 的目标版本，非最新版硬选）` : '')));
    const steps = el('div', { class: 'steps' });
    plan.steps.forEach((s, i) => {
      const red = !s.safe_rollback;
      const card = el('div', { class: 'step' + (red ? ' red-flag' : '') });
      card.append(
          el('div', { class: 'order' }, String(s.order)),
          el('div', {},
              el('div', { style: 'font-weight:700' },
                  `${s.component}：${s.from_version || '∅'} → ${s.to_version}`,
                  red ? el('span', { class: 'badge red', style: 'margin-left:10px' }, '无安全回滚 · 标红')
                      : el('span', { class: 'badge safe', style: 'margin-left:10px' }, '可安全回滚')),
              el('div', { class: 'meta' },
                  '探针 ', el('code', {}, s.pre_flash_probe),
                  ' ｜ 动作 ', el('code', {}, s.update_action),
                  ' ｜ 健康 ', el('code', {}, s.health_check)),
              el('div', { class: 'rollback-reason' },
                  `回滚目标：${s.rollback_target.version || '无'} — ${s.rollback_target.reason}`),
              renderFaults(plan.simulation[i])),
          el('div', { class: 'side' },
              el('span', { class: 'badge info' }, 'fmt ' + s.format_version),
              el('span', { class: 'mono small' }, shortHash(s.sha256))));
      steps.append(card);
    });
    frag.append(steps);
  } else {
    frag.append(el('div', { class: 'verdict infeasible' },
        `✗ 不可行：${kindLabel(plan.kind)}`));
    if (plan.reasons && Object.keys(plan.reasons).length) {
      Object.entries(plan.reasons).forEach(([k, v]) =>
          frag.append(el('div', { class: 'conflict' },
              el('div', { class: 'gate' }, k), el('div', { class: 'desc' }, v))));
    }
    const conflicts = plan.conflict_set || [];
    if (conflicts.length) {
      frag.append(el('h2', {}, `最小冲突集合（${conflicts.length} 条，不可再约简）`));
      conflicts.forEach(c => frag.append(el('div', { class: 'conflict' },
          el('div', { class: 'gate' }, gateLabel(c.gate)),
          el('div', { class: 'desc' }, c.description),
          el('details', {}, el('summary', {}, '判定依据 / 证据'),
              el('pre', {}, JSON.stringify(c.evidence, null, 2))))));
    }
    if (plan.all_blocking_facts && plan.all_blocking_facts.length > conflicts.length) {
      frag.append(el('details', {},
          el('summary', {}, `查看全部 ${plan.all_blocking_facts.length} 条阻塞事实`),
          el('div', {}, plan.all_blocking_facts.map(f =>
              el('div', { class: 'small' }, `${f.gate}: ${f.description}`)))));
    }
  }
  return frag;
}

function renderFaults(sim) {
  if (!sim) return null;
  const faults = el('div', { class: 'faults' });
  sim.fault_points.forEach(fp => {
    const node = el('div', { class: 'fault' + (fp.recoverable ? ' recoverable' : '') });
    node.append(
        el('h4', {}, faultLabel(fp.fault)),
        el('p', {}, '恢复状态：', el('span', { class: 'state' }, fp.recovery_state)),
        el('p', {}, fp.recoverable ? '可自动恢复' : '需人工介入'),
        el('p', {}, fp.explanation));
    if (fp.rollback_to) node.append(el('p', {}, '回滚到：' + fp.rollback_to));
    faults.append(node);
  });
  return faults;
}

function kindLabel(kind) {
  return ({
    dependency_cycle: '依赖环',
    no_common_version: '没有共同版本',
    storage_format_break: '降级会破坏存储格式',
    bootloader_below_floor: '目标低于 bootloader 下限',
    no_skip_violation: '跨越了不可跨越版本',
    invalid_request: '请求无效',
    unreachable: '目标不可达',
  })[kind] || kind;
}
function gateLabel(g) {
  return ({
    hardware_revision: '硬件修订限制',
    bootloader_floor: 'bootloader 下限',
    package_requires: '配套组件区间',
    max_from_version: '不可跨越版本',
    format_downgrade: '存储格式屏障',
    target_floor: '硬件 bootloader 下限',
  })[g] || g;
}
function faultLabel(f) {
  return ({
    power_loss_after_write_commit: '写入后断电',
    pre_flash_probe_failed: '前置探针失败',
    post_flash_health_failed: '健康判据失败',
  })[f] || f;
}

// ----------------------------------------------------------------- rollout

function renderRolloutSelect() {
  const sel = document.getElementById('rollout-select');
  const current = sel.value;
  sel.innerHTML = '';
  overview.rollouts.forEach(r => sel.append(el('option', { value: r.id },
      `${r.name} · ${r.status} · [${r.cohorts.join(',')}]`)));
  if (current) sel.value = current;
  sel.onchange = renderRolloutDetail;
}

function renderNewRolloutOptions() {
  const fam = familyObj('ctrl-a') || families()[0];
  for (const [component, selectId] of
      [['main', 'new-main'], ['radio', 'new-radio'], ['sensor', 'new-sensor']]) {
    const sel = document.getElementById(selectId);
    sel.innerHTML = '';
    sel.append(el('option', { value: '' }, '不变'));
    familyPackages(fam.family).filter(p => p.component === component)
        .sort((a, b) => a.version.localeCompare(b.version, undefined, { numeric: true }))
        .forEach(p => sel.append(el('option', {
          value: `${p.package_id}__${p.version}__${p.sha256.slice(0, 12)}`,
        }, p.version)));
    if (component !== 'sensor') sel.selectedIndex = sel.options.length - 1;
    else {
      const v3 = [...sel.options].find(o => o.textContent.startsWith('3.0.0'));
      if (v3) v3.selected = true;
    }
  }
}

document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('rollout-reload').onclick = async () => {
    await loadOverview(); renderRolloutDetail();
  };
  document.getElementById('new-create').onclick = createRollout;
  document.getElementById('rollout-approve').onclick = approveRollout;
  document.getElementById('rollout-recompute').onclick = recomputeRollout;
  document.getElementById('rollout-export').onclick = exportRollout;
});

async function createRollout() {
  const fam = familyObj('ctrl-a') || families()[0];
  const targets = {};
  for (const id of ['new-main', 'new-radio', 'new-sensor']) {
    const sel = document.getElementById(id);
    if (sel.value) targets[sel.dataset.component || sel.id.replace('new-', '')] = sel.value;
  }
  const cohorts = document.getElementById('new-cohorts').value.split(',')
      .map(s => s.trim()).filter(Boolean);
  try {
    const created = await api('/api/rollouts', {
      method: 'POST',
      body: JSON.stringify({
        name: document.getElementById('new-name').value,
        cohorts, targets,
      }),
    });
    toast(`已创建草稿 ${created.id}`, 'success');
    overview.rollouts.push(stripInternal(created));
    renderRolloutSelect();
    document.getElementById('rollout-select').value = created.id;
    renderRolloutDetail();
  } catch (e) { toast(e.message, 'error'); }
}

function stripInternal(r) {
  const copy = { ...r };
  delete copy._replayed; delete copy._advanced; delete copy._note;
  return copy;
}

async function selectedRollout() {
  const id = document.getElementById('rollout-select').value;
  if (!id) throw new Error('没有选择批次');
  return api('/api/rollouts/' + id);
}

async function approveRollout() {
  try {
    const r = await selectedRollout();
    const updated = await api(`/api/rollouts/${r.id}/approve`, { method: 'POST', body: '{}' });
    toast('已批准，清单快照已绑定', 'success');
    mergeRollout(updated);
  } catch (e) { toast(e.message, 'error'); }
}

async function recomputeRollout() {
  try {
    const r = await selectedRollout();
    const updated = await api(`/api/rollouts/${r.id}/recompute`, { method: 'POST', body: '{}' });
    toast('已重算未冻结批次', 'success');
    mergeRollout(updated);
  } catch (e) { toast(e.message, 'error'); }
}

async function exportRollout() {
  try {
    const r = await selectedRollout();
    const exp = await api(`/api/rollouts/${r.id}/export`);
    const blob = new Blob([JSON.stringify(exp, null, 2)], { type: 'application/json' });
    const a = el('a', {
      href: URL.createObjectURL(blob),
      download: `rollout-${r.id}-decision-pack.json`,
    });
    a.click();
    toast(`已导出：${exp.failure_branches.length} 个失败分支，快照 ${exp.decision_basis.snapshot_id}`, 'success');
  } catch (e) { toast(e.message, 'error'); }
}

function mergeRollout(updated) {
  const idx = overview.rollouts.findIndex(x => x.id === updated.id);
  if (idx >= 0) overview.rollouts[idx] = stripInternal(updated);
  renderRolloutSelect();
  renderRolloutDetail();
}

async function renderRolloutDetail() {
  const box = document.getElementById('rollout-detail');
  const id = document.getElementById('rollout-select').value;
  if (!id) { box.innerHTML = ''; return; }
  let r;
  try { r = await api('/api/rollouts/' + id); }
  catch (e) { box.textContent = e.message; return; }

  box.innerHTML = '';
  const draft = r.draft || {};
  const head = el('div', { class: 'card' },
      el('h2', {}, `${r.name} — ${r.id}`),
      el('div', { class: 'kv' },
          el('span', { class: 'k' }, '状态'), el('span', {}, el('span', {
            class: 'badge ' + (r.status === 'complete' ? 'safe'
                : r.status === 'approved' ? 'info' : 'warn'),
          }, r.status)),
          el('span', { class: 'k' }, 'cohort'), el('span', {}, r.cohorts.join(', ')),
          el('span', { class: 'k' }, '冻结 cohort'), el('span', {}, (r.frozen_cohorts || []).join(', ') || '无'),
          el('span', { class: 'k' }, '快照'), el('span', { class: 'mono' }, r.snapshot_id || '未绑定（草稿可重算）'),
          el('span', { class: 'k' }, '整体可行'), el('span', {}, String(draft.all_feasible)),
          el('span', { class: 'k' }, '已排除冻结设备'), el('span', {},
              (draft.frozen_devices || draft.pinned_by_freeze || []).join(', ') || '无')));
  box.append(head);

  // cohort freeze controls (draft only)
  if (r.status === 'draft') {
    const controls = el('div', { class: 'card' }, el('h2', {}, '冻结 / 解冻 cohort 后重算其他批次'));
    const input = el('input', { placeholder: 'cohort 名称', value: r.cohorts[0] || '' });
    controls.append(
        el('div', { class: 'receipt-row' }, input,
            el('button', { class: 'danger', onclick: async () => {
              await api(`/api/rollouts/${id}/freeze`, { method: 'POST',
                body: JSON.stringify({ cohort: input.value, frozen: true }) });
              toast(`已冻结 ${input.value}`, 'success'); await renderRolloutDetail();
            } }, '冻结该 cohort'),
            el('button', { class: 'ghost', onclick: async () => {
              await api(`/api/rollouts/${id}/freeze`, { method: 'POST',
                body: JSON.stringify({ cohort: input.value, frozen: false }) });
              toast(`已解冻 ${input.value}`, 'success'); await renderRolloutDetail();
            } }, '解冻')));
    box.append(controls);
  }

  // device plan outcomes
  (draft.device_plans || []).forEach(p => {
    const card = el('div', { class: 'card' });
    card.append(el('h2', {},
        `${p.device_id} (${p.cohort}) — `,
        el('span', { class: 'badge ' + (p.feasible ? 'safe' : 'red') },
            p.feasible ? '可行' : kindLabel(p.kind))));
    if (!p.feasible) {
      (p.conflict_set || []).forEach(c => card.append(el('div', { class: 'conflict' },
          el('div', { class: 'gate' }, gateLabel(c.gate)),
          el('div', { class: 'desc' }, c.description))));
    }
    box.append(card);
  });

  // stages
  const stagesBox = el('div', { class: 'card' },
      el('h2', {}, `阶段（${(r.stages || []).length}） · 当前第 ${r.stage_index + 1}/${(r.stages || []).length} 阶段`));
  (r.stages || []).forEach((s, i) => {
    const acked = s.acked_devices || [];
    const expected = s.expected_devices || [];
    const pct = expected.length ? Math.round(acked.length / expected.length * 100) : 0;
    const done = i < r.stage_index || s.status === 'complete';
    const current = i === r.stage_index && r.status === 'approved';
    const stage = el('div', { class: 'stage' + (done ? ' complete' : '') + (current ? ' current' : '') });
    stage.append(
        el('div', { class: 'stage-head' },
            el('span', { class: 'badge ' + (done ? 'safe' : current ? 'info' : 'purple') },
                `阶段 ${s.order}`),
            el('strong', {}, `${s.component} → ${s.to_version}`),
            el('span', { class: 'mono small' }, shortHash(s.sha256)),
            s.safe_rollback ? el('span', { class: 'badge safe' }, '可回滚')
                : el('span', { class: 'badge red' }, '无安全回滚 · 标红'),
            el('span', { class: 'small' }, `${acked.length}/${expected.length} 回执`)),
        el('div', { class: 'meta small', style: 'margin-top:6px' },
            `探针 ${s.pre_flash_probe} ｜ 健康 ${s.health_check} ｜ 回滚 ${s.rollback_target?.version || '无'}（${s.rollback_target?.reason}）`),
        el('div', { class: 'progress' }, el('div', { style: `width:${pct}%` })));
    if (current) {
      const receiptId = el('input', { placeholder: 'receipt_id', value: 'rcpt-' + Date.now() });
      expected.forEach(dev => {
        const row = el('div', { class: 'receipt-row' },
            el('span', { class: 'mono small' }, dev),
            el('button', { onclick: async () => {
              try {
                const body = { receipt_id: receiptId.value + '-' + dev, device_id: dev,
                  sha256: s.sha256, version: s.to_version };
                const result = await api(`/api/rollouts/${id}/receipts`,
                    { method: 'POST', body: JSON.stringify(body) });
                toast(result._replayed ? '重复回执已忽略，阶段未推进' : '回执已记录',
                    result._replayed ? '' : 'success');
                mergeRollout(result);
              } catch (e) { toast(e.message, 'error'); }
            } }, '上报回执'));
        stage.append(row);
      });
      stage.prepend(receiptId);
    }
    stagesBox.append(stage);
  });
  box.append(stagesBox);
}

// ------------------------------------------------------------------ imports

document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('family-import').onclick = importFamily;
  document.getElementById('package-import').onclick = importPackage;
  document.getElementById('matrix-refresh').onclick = loadOverview;
  document.getElementById('events-refresh').onclick = loadEvents;
  document.getElementById('package-json').value = JSON.stringify({
    package_id: 'ctrl-main', component: 'main', version: '4.2.0', family: 'ctrl-a',
    format_version: 3, sha256: '<填入二进制 sha256>', size: 4096,
    signer: 'release-robot@example.com', signed_at: '2026-09-20T00:00:00Z',
    safe_rollback: false, hw_compatibility: { 'rev-c': '*' },
    requires: { bootloader: '2.1.0..2.1.0', radio: '2.0.0..2.0.0' },
    bootloader_min: '2.1.0', max_from_version: '4.1.0',
    signature: 'demo-2026:<HMAC base64url>'
  }, null, 2);
  document.getElementById('family-json').value = JSON.stringify({
    family: 'sensor-x', name: 'Sensor Node X',
    revisions: [{ id: 'r1', label: 'R1', bootloader_min: '1.0.0' }],
    components: [{ id: 'bootloader', label: 'Bootloader',
      probe: 'probe:bl', health_check: 'health:bl' },
      { id: 'main', label: 'Main', probe: 'probe:main', health_check: 'health:main' }]
  }, null, 2);
});

async function importFamily() {
  const box = document.getElementById('family-import-result');
  try {
    const body = JSON.parse(document.getElementById('family-json').value);
    const result = await api('/api/families', { method: 'POST', body: JSON.stringify(body) });
    box.className = 'import-result ok';
    box.textContent = `已导入设备族 ${result.family}（${result.revisions.length} 修订，${result.components.length} 组件）`;
    await loadOverview();
  } catch (e) {
    box.className = 'import-result err';
    box.textContent = `[${e.code || e.status}] ${e.message}`;
  }
}

async function importPackage() {
  const box = document.getElementById('package-import-result');
  try {
    const body = JSON.parse(document.getElementById('package-json').value);
    const result = await api('/api/packages', { method: 'POST', body: JSON.stringify(body) });
    box.className = 'import-result ok';
    box.textContent = `签名校验通过（${result.signature.scheme} / ${result.signature.signer}）；`
        + (result.status === 'added' ? '新包已入库，按 sha256 识别。' : '相同记录已存在（幂等）。');
    await loadOverview();
  } catch (e) {
    box.className = 'import-result err';
    box.textContent = `[${e.code || e.status} / ${e.category || ''}] ${e.message}`;
  }
}

// ------------------------------------------------------------------- events

async function loadEvents() {
  const { events } = await api('/api/events');
  const body = document.getElementById('events-body');
  body.innerHTML = '';
  events.slice().reverse().forEach(e => {
    body.append(el('tr', {},
        el('td', { class: 'mono small' }, e.ts),
        el('td', {}, el('span', { class: 'badge purple' }, e.type)),
        el('td', { class: 'mono small' }, JSON.stringify(e.detail))));
  });
}

// -------------------------------------------------------------------- tabs

document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('.tabs button').forEach(btn => {
    btn.onclick = () => {
      document.querySelectorAll('.tabs button').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
      if (btn.dataset.tab === 'events') loadEvents();
      if (btn.dataset.tab === 'rollout') renderRolloutDetail();
      if (btn.dataset.tab === 'plan') renderCurrentAndTargets();
    };
  });
  loadOverview().then(async () => {
    renderCurrentAndTargets();
    renderRolloutDetail();
    // Deep links: #/plan/dev-charlie-03 auto-selects and evaluates; #/rollout etc.
    const hash = location.hash.replace(/^#\//, '');
    const parts = hash.split('/');
    if (parts[0]) {
      const btn = document.querySelector(`.tabs button[data-tab="${parts[0]}"]`);
      if (btn) btn.click();
      if (parts[0] === 'plan' && parts[1]) {
        await new Promise(r => setTimeout(r, 60));
        const sel = document.getElementById('plan-device');
        sel.value = parts[1];
        sel.dispatchEvent(new Event('change'));
        await new Promise(r => setTimeout(r, 60));
        evaluatePlan();
      }
      if (parts[0] === 'rollout' && parts[1]) {
        await new Promise(r => setTimeout(r, 60));
        document.getElementById('rollout-select').value = parts[1];
        renderRolloutDetail();
      }
    }
  }).catch(e => {
    document.getElementById('server-status').textContent = '服务不可用：' + e.message;
  });
});
