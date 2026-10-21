/** Java enforces authorization; only static markup is generated here. */
export function createQuestionAdmin({ api, getUser, getEpoch, onError, message }) {
  const panel = document.getElementById("question-admin-panel");
  const fields = [["stem", "题干", 2000], ["A", "选项 A", 1000], ["B", "选项 B", 1000], ["C", "选项 C", 1000], ["D", "选项 D", 1000],
    ["explanation", "解析", 4000], ["hintConcept", "概念提示（可选）", 2000, true], ["hintApproach", "思路提示（可选）", 2000, true],
    ["sourceTitle", "来源标题", 200], ["sourceUrl", "来源链接（HTTP(S)或 /references/ 路径）", 500],
    ["sourceLocator", "原文定位（页码或题号）", 100], ["license", "使用权限或授权依据", 1000]];
  panel.innerHTML = `
    <div class="section-head"><h2>题目录入与审核</h2><div class="actions compact"><button id="qa-new" type="button">录入新题</button><button id="qa-reload" class="secondary" type="button">重新加载</button></div></div>
    <p class="notice">审核通过后仍需发布才可进入正式练习。当前提供草稿与审核，发布功能尚未开放。</p>
    <div class="grid admin-question-layout"><section><h3>题目与最新编辑版本</h3><div id="qa-list"></div>
    <div class="pagination"><button id="qa-prev" class="secondary" type="button">上一页</button><span id="qa-page"></span><button id="qa-next" class="secondary" type="button">下一页</button></div></section>
    <section><h3 id="qa-title">录入新题</h3><p id="qa-state" role="status" class="hint"></p>
    <form id="qa-form"><fieldset id="qa-fields">
      <label for="qa-subject">科目</label><select id="qa-subject"><option value="data_structure">数据结构</option><option value="computer_organization">计算机组成原理</option><option value="operating_system">操作系统</option><option value="computer_network">计算机网络</option></select>
      <label for="qa-point">知识点</label><select id="qa-point" required></select>
      <label for="qa-difficulty">难度</label><select id="qa-difficulty"><option value="easy">简单</option><option value="medium">中等</option><option value="hard">困难</option></select>
      ${fields.map(([id, label, max, optional]) => `<label for="qa-${id}">${label}</label><textarea id="qa-${id}" rows="2" maxlength="${max}" ${optional ? "" : "required"}></textarea>`).join("")}
      <label for="qa-answer">标准答案</label><select id="qa-answer"><option>A</option><option>B</option><option>C</option><option>D</option></select>
      <label for="qa-origin">题目来源类别</label><select id="qa-origin"><option value="self_authored">自编题</option><option value="authorized_past">授权真题</option><option value="simulated">模拟题</option></select>
      <label for="qa-year">考试年份（仅授权真题）</label><input id="qa-year" type="number" min="2000" max="2100" disabled>
      <label for="qa-retrievedAt">资料获取日期</label><input id="qa-retrievedAt" type="date" required>
    </fieldset><button id="qa-save" type="submit">保存草稿</button></form>
    <form id="qa-review-form"><label for="qa-comment">审核意见（必填）</label><textarea id="qa-comment" rows="3" maxlength="2000" required></textarea>
      <div class="actions"><button id="qa-approve" type="submit" value="approved" disabled>审核通过</button><button id="qa-reject" class="secondary" type="submit" value="rejected" disabled>驳回</button></div></form>
    </section></div><h3>版本历史</h3><div id="qa-versions"></div><h3>审核记录</h3><div id="qa-reviews"></div>`;
  const $ = id => document.getElementById(`qa-${id}`);
  const names = { unreviewed: "尚未登记审核", draft: "待审核", approved: "已通过，尚未发布", rejected: "已驳回" };
  let selected = null, page = 0, totalPages = 0, busy = false, dirty = false, sequence = 0;
  function controls() {
    for (const button of panel.querySelectorAll("button")) button.disabled = busy;
    $("fields").disabled = busy;
    $("prev").disabled = busy || page === 0; $("next").disabled = busy || page + 1 >= totalPages;
    $("approve").disabled = $("reject").disabled = busy || dirty || selected?.status !== "draft";
    $("save").textContent = selected ? "保存为新版本" : "保存草稿";
  }
  function yearState() {
    const past = $("origin").value === "authorized_past";
    $("year").disabled = !past; $("year").required = past; if (!past) $("year").value = "";
  }
  async function run(action) {
    if (busy || getUser()?.role !== "admin") return;
    const serial = ++sequence, epoch = getEpoch();
    const valid = () => serial === sequence && epoch === getEpoch() && getUser()?.role === "admin" && !panel.hidden;
    busy = true; controls(); message();
    try { await action(valid); } catch (error) { if (valid()) onError(error); }
    finally { if (valid()) { busy = false; controls(); } }
  }
  async function points(valid, content = null) {
    const choices = await api(`/api/knowledge/points?subject=${$("subject").value}`); if (!valid()) return;
    $("point").replaceChildren();
    if (content && !choices.some(p => p.id === content.knowledgePointId)) choices.push({ id: content.knowledgePointId, name: content.knowledgePointName });
    for (const point of choices) { const option = document.createElement("option"); option.value = point.id; option.textContent = point.name; $("point").append(option); }
    if (content) $("point").value = content.knowledgePointId;
  }
  function newForm() {
    selected = null; dirty = false; $("form").reset(); $("review-form").reset(); yearState();
    $("retrievedAt").value = new Date().toISOString().slice(0, 10);
    $("title").textContent = "录入新题"; $("state").textContent = "保存后进入待审核状态。";
    $("versions").replaceChildren(); $("reviews").replaceChildren();
  }
  async function list(valid) {
    const data = await api(`/api/admin/questions?page=${page}`); if (!valid()) return;
    totalPages = data.totalPages; $("list").replaceChildren(); $("page").textContent = totalPages ? `${page + 1} / ${totalPages}` : "暂无题目";
    for (const view of data.items) {
      const row = document.createElement("div"); row.className = "admin-question-row";
      const title = document.createElement("p"); title.textContent = view.content.stem;
      const state = document.createElement("small"); state.textContent = names[view.status] || view.status;
      const button = document.createElement("button"); button.type = "button"; button.className = "secondary"; button.textContent = "查看与修订";
      button.addEventListener("click", () => run(v => open(view.questionId, v))); row.append(title, state, button); $("list").append(row);
    }
  }
  async function open(id, valid) {
    const [view, versions, reviews] = await Promise.all([api(`/api/admin/questions/${id}`), api(`/api/admin/questions/${id}/versions`), api(`/api/admin/questions/${id}/reviews`)]);
    if (!valid()) return;
    selected = view; dirty = false; const c = view.content;
    $("title").textContent = "题目详情与修订"; $("state").textContent = `${names[view.status]} · 版本 ${view.questionVersionId}`;
    $("subject").value = c.subject; $("difficulty").value = c.difficulty; $("answer").value = c.correctOption;
    $("origin").value = c.questionOrigin || "self_authored"; yearState(); $("year").value = c.examYear || ""; $("retrievedAt").value = c.retrievedAt || "";
    for (const [id] of fields) $(id).value = c.options[id] ?? c[id] ?? "";
    $("comment").value = ""; $("versions").replaceChildren();
    for (const version of versions) {
      const details = document.createElement("details"), summary = document.createElement("summary"), body = document.createElement("pre");
      summary.textContent = `${names[version.status]} · ${version.questionVersionId}`; body.textContent = JSON.stringify(version.content, null, 2);
      details.append(summary, body); $("versions").append(details);
    }
    $("reviews").replaceChildren(); if (!reviews.length) $("reviews").textContent = "尚无审核记录。";
    for (const event of reviews) { const line = document.createElement("p"); line.textContent = `${names[event.status]} · 审核人 ${event.reviewerId} · ${new Date(event.createdAt).toLocaleString()}\n${event.comment}`; $("reviews").append(line); }
    await points(valid, c);
  }
  function input() {
    const point = $("point").selectedOptions[0];
    const data = { subject: $("subject").value, difficulty: $("difficulty").value, knowledgePointId: point.value, knowledgePointName: point.textContent,
      options: Object.fromEntries(["A", "B", "C", "D"].map(k => [k, $(k).value])), correctOption: $("answer").value,
      questionOrigin: $("origin").value, examYear: $("year").value ? Number($("year").value) : null, retrievedAt: $("retrievedAt").value };
    for (const [id] of fields) if (!["A", "B", "C", "D"].includes(id)) data[id] = $(id).value;
    if (selected) data.expectedVersionId = selected.questionVersionId; return data;
  }
  $("form").addEventListener("input", () => { dirty = true; controls(); });
  $("origin").addEventListener("change", yearState); $("subject").addEventListener("change", () => run(v => points(v)));
  $("form").addEventListener("submit", event => {
    event.preventDefault(); if (!$("form").reportValidity()) return;
    run(async valid => {
      const saved = await api(selected ? `/api/admin/questions/${selected.questionId}` : "/api/admin/questions", { method: selected ? "PUT" : "POST", body: JSON.stringify(input()) });
      if (!valid()) return; await open(saved.questionId, valid); if (!valid()) return; await list(valid);
      if (valid()) message("草稿已保存，等待审核；尚未发布到练习题库。");
    });
  });
  $("review-form").addEventListener("submit", event => {
    event.preventDefault(); if (dirty || selected?.status !== "draft" || !$("review-form").reportValidity()) return;
    const status = event.submitter?.value; if (!["approved", "rejected"].includes(status)) return;
    run(async valid => {
      const id = selected.questionId;
      await api(`/api/admin/questions/${id}/review`, { method: "POST", body: JSON.stringify({ questionVersionId: selected.questionVersionId, status, comment: $("comment").value }) });
      if (!valid()) return; await open(id, valid); if (!valid()) return; await list(valid);
      if (valid()) message(status === "approved" ? "审核已通过，等待后续发布。" : "已驳回，可修订为新版本后再次审核。");
    });
  });
  $("new").addEventListener("click", () => run(async valid => { newForm(); await points(valid); }));
  $("reload").addEventListener("click", () => run(async valid => { if (selected) await open(selected.questionId, valid); if (valid()) await list(valid); }));
  for (const [id, delta] of [["prev", -1], ["next", 1]]) $(id).addEventListener("click", () => run(v => { page += delta; return list(v); }));
  return {
    show() { panel.hidden = false; return run(async valid => { newForm(); await points(valid); if (valid()) await list(valid); }); },
    hide() { panel.hidden = true; sequence++; busy = false; },
    reset() { this.hide(); page = 0; totalPages = 0; newForm(); $("point").replaceChildren(); $("list").replaceChildren(); $("page").textContent = ""; controls(); }
  };
}
