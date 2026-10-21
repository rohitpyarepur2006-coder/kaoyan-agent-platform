"use strict";
// Frontend-only check with intercepted API fixtures, never connects to a live database.
const fs = require("node:fs"), path = require("node:path"), assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto"), { chromium } = require("playwright");
async function main() {
  const output = process.argv[2];
  if (!output) throw new Error("Usage: node scripts/question-review-browser-check.cjs new-report.json");
  for (const file of [output, output+".desktop.png", output+".mobile.png"]) if (fs.existsSync(file)) throw new Error("Report exists: "+file);
  fs.mkdirSync(path.dirname(output), { recursive:true });
  const checks = [], check = (name, condition=true) => { assert(condition,name); checks.push(name); };
  const browser = await chromium.launch({ headless:true, ...(process.env.CHROME_PATH ? { executablePath:process.env.CHROME_PATH } : {}) });
  let release = () => {};
  try {
    const context = await browser.newContext({ viewport:{ width:1280,height:900 } });
    await context.addInitScript(() => sessionStorage.setItem("kaoyan_token","fixture-admin"));
    const page = await context.newPage(), errors = []; page.on("pageerror", e => errors.push(e.message));
    page.setDefaultTimeout(10000);
    const point = randomUUID(), views = [], histories = [], reviews = [];
    let stale=false, delay=false, adminCalls=0, captured=null;
    const user = { id:randomUUID(),username:"fixture-admin",role:"admin" };
    const student = { id:randomUUID(),username:"fixture-student",role:"student" };
    await page.route("http://127.0.0.1:18796/**",async route => {
      const req=route.request(), url=new URL(req.url()), p=url.pathname, method=req.method();
      const send = (data,status=200) => route.fulfill({status,contentType:"application/json",body:JSON.stringify(data)});
      if (!p.startsWith("/api/")) {
        const assets = new Set(["/","/index.html","/style.css","/question-review.css","/app.js","/js/api.js","/js/question-review.js"]);
        if (!assets.has(p)) return route.fulfill({status:404,body:"Not found"});
        return route.fulfill({status:200,contentType:p.endsWith(".js")?"text/javascript":p.endsWith(".css")?"text/css":"text/html",
          body:fs.readFileSync(path.join(__dirname,"../frontend",p==="/"?"index.html":p.slice(1)))});
      }
      if (p==="/api/auth/me") return send(user);
      if (p==="/api/auth/logout") return send({});
      if (p==="/api/auth/login") return send({token:"fixture-student",user:student});
      if (p==="/api/tasks") return send({items:[],totalPages:0,totalItems:0});
      if (p==="/api/knowledge/points") return send([{id:point,name:"栈",subject:"data_structure"}]);
      if (p.startsWith("/api/admin/questions")) {
        adminCalls++;
        if (delay && method==="GET") { delay=false; const data={items:views,totalPages:1,totalItems:views.length}; await new Promise(resolve=>{ release=resolve; }); return send(data); }
        if (method==="GET") {
          if (p.endsWith("/versions")) return send(histories);
          if (p.endsWith("/reviews")) return send(reviews);
          return send(p==="/api/admin/questions"?{items:views,totalPages:views.length?1:0,totalItems:views.length}:views[0]);
        }
        const body=req.postDataJSON(); captured=body;
        if (stale) { stale=false; return send({code:"STALE_QUESTION_VERSION",message:"版本已更新，请重新加载"},409); }
        if (p.endsWith("/review")) {
          assert.equal(body.questionVersionId,views[0].questionVersionId);
          Object.assign(views[0],{status:body.status,reviewedBy:user.id,reviewedAt:new Date().toISOString(),reviewComment:body.comment});
          Object.assign(histories.at(-1),views[0]);
          reviews.push({questionVersionId:body.questionVersionId,status:body.status,reviewerId:user.id,comment:body.comment,createdAt:new Date().toISOString()});
        } else {
          if (method==="PUT") assert.equal(body.expectedVersionId,views[0].questionVersionId);
          views[0]={questionId:views[0]?.questionId||randomUUID(),questionVersionId:randomUUID(),status:"draft",content:body,createdBy:user.id,createdAt:new Date().toISOString()};
          histories.push(structuredClone(views[0]));
        }
        return send(views[0]);
      }
      throw new Error("Unexpected fixture request: "+method+" "+p);
    });
    await page.goto("http://127.0.0.1:18796/#admin");
    await page.waitForFunction(()=>document.querySelector("#qa-point option") && !document.querySelector("#qa-save").disabled);
    check("管理员进入录入页",await page.locator("#question-admin-panel").isVisible());
    for (const [key,value] of Object.entries({stem:"<img src=x onerror=alert(1)> 测试题",A:"甲",B:"乙",C:"丙",D:"丁",explanation:"测试解析",sourceTitle:"自编题",sourceUrl:"/references/demo-408.html",sourceLocator:"第1题",license:"自编许可"})) await page.locator("#qa-"+key).fill(value);
    await page.locator("#qa-save").click();
    await page.waitForFunction(()=>document.querySelector("#message").textContent.includes("草稿已保存"));
    check("录入完整题目并读取详情版本",histories.length===1 && captured.knowledgePointId===point);
    check("题干按文本展示而不执行HTML",await page.locator("#qa-list img").count()===0 && (await page.locator("#qa-list").textContent()).includes("<img"));
    await page.locator("#qa-stem").fill("修订后的题干");
    check("未保存修订禁止审核",await page.locator("#qa-approve").isDisabled());
    await page.locator("#qa-save").click();
    await page.waitForFunction(()=>document.querySelectorAll("#qa-versions details").length===2 && !document.querySelector("#qa-save").disabled);
    check("修订携带旧版本并形成新草稿",histories.length===2 && Boolean(captured.expectedVersionId));
    await page.locator("#qa-comment").fill("测试批准"); await page.locator("#qa-approve").click();
    await page.waitForFunction(()=>document.querySelector("#message").textContent.includes("审核已通过"));
    check("批准结果明确尚未发布且显示审计",(await page.locator("#qa-state").textContent()).includes("尚未发布") && (await page.locator("#qa-reviews").textContent()).includes(user.id));
    await page.locator("#qa-save").click();
    await page.waitForFunction(()=>document.querySelectorAll("#qa-versions details").length===3 && !document.querySelector("#qa-save").disabled);
    await page.locator("#qa-comment").fill("需要补充说明"); await page.locator("#qa-reject").click();
    await page.waitForFunction(()=>document.querySelector("#message").textContent.includes("已驳回"));
    check("驳回保留既有审核记录",reviews.length===2 && await page.locator("#qa-reviews p").count()===2);
    stale=true; await page.locator("#qa-save").click();
    await page.waitForFunction(()=>document.querySelector("#message").textContent.includes("版本已更新"));
    check("409显示冲突且不伪造保存成功",histories.length===3);
    await page.locator("#qa-reload").click();
    await page.waitForFunction(()=>!document.querySelector("#qa-reload").disabled);
    await page.screenshot({path:output+".desktop.png",fullPage:true});
    await page.setViewportSize({width:390,height:844});
    check("390像素管理页无横向溢出",await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.screenshot({path:output+".mobile.png",fullPage:true});
    // Trigger a delayed list request, then switch from admin to student before releasing it.
    await page.locator("#qa-new").click(); await page.waitForFunction(()=>!document.querySelector("#qa-new").disabled);
    delay=true; const pending=page.waitForRequest(r=>r.url().endsWith("/api/admin/questions?page=0"));
    await page.locator("#qa-reload").click(); await pending;
    await page.locator("#logout").click();
    await page.locator("#auth-name").fill("fixture-student"); await page.locator("#auth-password").fill("test-password"); await page.locator("#login").click();
    await page.waitForFunction(()=>document.querySelector("#username").textContent==="fixture-student");
    const responded=page.waitForResponse(r=>r.url().endsWith("/api/admin/questions?page=0")); release(); await responded;
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    check("退出和迟到响应不向学生暴露管理内容",await page.locator("#question-admin-panel").isHidden() && await page.locator("#qa-list").textContent()==="" && await page.locator("#qa-versions").textContent()==="");
    const count=adminCalls; await page.evaluate(()=>{location.hash="home";}); await page.evaluate(()=>{location.hash="admin";});
    await page.waitForFunction(()=>document.querySelector("#feature-description").textContent.includes("仅供管理员"));
    check("学生手动进入管理路由不发管理请求",adminCalls===count && await page.locator("#admin-link").isHidden());
    check("浏览器无脚本异常",errors.length===0);
    fs.writeFileSync(output,JSON.stringify({scope:"frontend with mocked API; not real backend E2E",checks},null,2));
    console.log(`通过 ${checks.length} 项管理员页面检查（模拟API，未连接实际后端）。`);
  } finally { release(); await browser.close(); }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
