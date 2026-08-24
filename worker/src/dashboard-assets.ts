const html = `<!doctype html>
<html lang="zh-Hant">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="robots" content="noindex,nofollow">
  <title>StallPOS 活動分析</title>
  <link rel="stylesheet" href="/dashboard/dashboard.css">
  <script src="https://accounts.google.com/gsi/client" defer></script>
  <script src="/dashboard/dashboard.js" defer></script>
</head>
<body>
  <main>
    <header>
      <div><p class="eyebrow">STALLPOS CLOUD</p><h1>活動分析</h1><p class="muted">唯讀報表 · 金額為新台幣</p></div>
      <button id="logout" class="ghost" hidden>登出</button>
    </header>
    <section id="login" class="login card">
      <div><h2>查看你的市集成果</h2><p>使用與 StallPOS App 相同的 Google 帳號登入。</p></div>
      <div id="google-button"></div><p id="login-error" class="error" role="alert"></p>
    </section>
    <section id="dashboard" hidden>
      <div class="toolbar card"><label for="event-select">活動</label><select id="event-select"></select><span id="event-meta" class="muted"></span></div>
      <p id="status" class="status" role="status">讀取中…</p>
      <div id="content" hidden>
        <section id="summary" class="metrics" aria-label="活動摘要"></section>
        <div id="cost-warning" class="warning" hidden>部分商品成本未知，毛利不完整；未知成本沒有當作 0。</div>
        <div class="grid">
          <section class="card wide"><h2>營收趨勢</h2><div id="trends" class="bars"></div></section>
          <section class="card"><h2>熱門時段</h2><div id="hourly" class="bars"></div></section>
          <section class="card"><h2>付款方式</h2><div id="payments" class="bars"></div></section>
          <section class="card wide"><h2>商品排行</h2><div id="products" class="table-wrap"></div></section>
          <section class="card"><h2>套組表現</h2><div id="bundles" class="table-wrap"></div></section>
          <section class="card"><h2>一起購買</h2><div id="pairs" class="table-wrap"></div></section>
          <section class="card wide"><h2>活動庫存去化</h2><div id="inventory" class="table-wrap"></div></section>
        </div>
      </div>
    </section>
  </main>
</body>
</html>`;

const css = `:root{color-scheme:light;--ink:#1f1b16;--muted:#746b61;--paper:#f6f1e8;--card:#fffdf8;--line:#ded4c7;--accent:#d95f2b;--accent-soft:#f7ddcf;--good:#216e57;--warn:#8a4b14;font-family:Inter,"Noto Sans TC",system-ui,sans-serif}*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink)}main{max-width:1180px;margin:auto;padding:36px 24px 64px}header{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:28px}h1{font-size:clamp(2rem,5vw,3.5rem);line-height:1;margin:.2rem 0 .65rem;letter-spacing:-.05em}h2{font-size:1rem;margin:0 0 18px}.eyebrow{font-size:.72rem;font-weight:800;letter-spacing:.18em;color:var(--accent);margin:0}.muted{color:var(--muted)}.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:20px;box-shadow:0 8px 28px rgba(66,49,31,.05)}.login{min-height:260px;display:grid;place-content:center;gap:24px;text-align:center}.login h2{font-size:1.5rem;margin-bottom:8px}.error,.warning{color:var(--warn)}.ghost{border:1px solid var(--line);background:transparent;border-radius:10px;padding:10px 16px;min-height:44px}.toolbar{display:flex;align-items:center;gap:14px;margin-bottom:18px}.toolbar label{font-weight:800}.toolbar select{min-height:44px;min-width:240px;max-width:100%;border:1px solid var(--line);border-radius:10px;background:#fff;padding:0 12px;font:inherit}.status{text-align:center;padding:32px}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin:18px 0}.metric{background:var(--ink);color:#fff;border-radius:16px;padding:18px}.metric span{display:block;color:#d9cfc3;font-size:.8rem;margin-bottom:8px}.metric strong{font-size:1.55rem}.warning{background:#fff1df;border:1px solid #e7bf8e;border-radius:12px;padding:12px 16px;margin-bottom:14px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.wide{grid-column:1/-1}.bars{display:grid;gap:10px}.bar-row{display:grid;grid-template-columns:minmax(72px,120px) 1fr auto;align-items:center;gap:10px;font-size:.84rem}.bar-track{height:10px;background:#eee5da;border-radius:99px;overflow:hidden}.bar-fill{height:100%;background:var(--accent);border-radius:99px}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;font-size:.86rem;min-width:500px}th,td{text-align:right;padding:11px 9px;border-bottom:1px solid var(--line);white-space:nowrap}th:first-child,td:first-child{text-align:left}th{color:var(--muted);font-size:.72rem;letter-spacing:.05em}.empty{color:var(--muted);padding:18px 0;margin:0}@media(max-width:720px){main{padding:24px 14px 48px}.metrics{grid-template-columns:1fr 1fr}.grid{grid-template-columns:1fr}.wide{grid-column:auto}.toolbar{align-items:flex-start;flex-direction:column}.toolbar select{width:100%}.bar-row{grid-template-columns:72px 1fr auto}.metric strong{font-size:1.2rem}}@media(max-width:390px){.metrics{grid-template-columns:1fr}.card{border-radius:14px;padding:16px}header{align-items:center}}`;

const js = `const $=id=>document.getElementById(id);const money=new Intl.NumberFormat('zh-TW',{style:'currency',currency:'TWD',maximumFractionDigits:0});
async function api(path,options={}){const r=await fetch(path,{...options,headers:{'content-type':'application/json',...(options.headers||{})}});const data=await r.json();if(!r.ok)throw Object.assign(new Error(data.message||'讀取失敗'),{status:r.status,code:data.code});return data}
async function boot(){try{const config=await api('/v2/dashboard/config');if(!config.googleClientId)throw new Error('Dashboard Google Client ID 尚未設定');google.accounts.id.initialize({client_id:config.googleClientId,callback:login});google.accounts.id.renderButton($('google-button'),{theme:'outline',size:'large',text:'signin_with',shape:'rectangular'});await loadEvents()}catch(e){if(e.status!==401)$('login-error').textContent=e.message}}
async function login(result){try{await api('/v2/auth/dashboard',{method:'POST',body:JSON.stringify({idToken:result.credential})});$('login-error').textContent='';await loadEvents()}catch(e){$('login-error').textContent=e.message}}
async function loadEvents(){const data=await api('/v2/reports/events');$('login').hidden=true;$('dashboard').hidden=false;$('logout').hidden=false;const select=$('event-select');select.replaceChildren(...data.events.map(e=>new Option(e.name+' · '+e.code,e.id)));if(!data.events.length){$('status').textContent='尚無活動資料。';return}select.onchange=()=>loadReport(select.value);await loadReport(select.value)}
async function loadReport(id){$('status').hidden=false;$('content').hidden=true;try{const d=await api('/v2/reports/events/'+encodeURIComponent(id));$('event-meta').textContent=d.event.status+' · '+d.event.timezone;render(d);$('status').hidden=true;$('content').hidden=false}catch(e){$('status').textContent=e.message}}
function render(d){$('summary').replaceChildren(metric('營收',money.format(d.summary.revenue)),metric('交易筆數',d.summary.transactionCount),metric('平均客單',money.format(d.summary.averageOrderValue)),metric('毛利',d.summary.grossProfit==null?'不完整':money.format(d.summary.grossProfit)));$('cost-warning').hidden=d.summary.costComplete;bars('trends',d.trends,x=>x.date,x=>x.revenue,money.format.bind(money));bars('hourly',d.hourly,x=>String(x.hour).padStart(2,'0')+':00',x=>x.revenue,money.format.bind(money));bars('payments',d.payments,x=>x.method,x=>x.revenue,money.format.bind(money));table('products',['商品','數量','營收','毛利'],d.products.map(x=>[x.name,x.quantity,money.format(x.revenue),x.grossProfit==null?'不完整':money.format(x.grossProfit)]));table('bundles',['套組','數量','營收'],d.bundles.map(x=>[x.name,x.quantity,money.format(x.revenue)]));table('pairs',['商品組合','共同交易'],d.coPurchases.map(x=>[x.firstName+' ＋ '+x.secondName,x.saleCount]));table('inventory',['商品','供應','售出','損壞','退回','結餘','去化率'],d.inventory.map(x=>[x.name,x.supplied,x.sold,x.damaged,x.returned,x.ending,x.sellThroughRate==null?'—':Math.round(x.sellThroughRate*100)+'%']))}
function metric(label,value){const el=document.createElement('div');el.className='metric';const l=document.createElement('span');l.textContent=label;const v=document.createElement('strong');v.textContent=value;el.append(l,v);return el}
function bars(id,rows,label,value,format){const root=$(id);root.replaceChildren();if(!rows.length){root.append(empty());return}const max=Math.max(...rows.map(value),1);rows.forEach(row=>{const el=document.createElement('div');el.className='bar-row';const name=document.createElement('span');name.textContent=label(row);const track=document.createElement('div');track.className='bar-track';const fill=document.createElement('div');fill.className='bar-fill';fill.style.width=(value(row)/max*100)+'%';track.append(fill);const amount=document.createElement('strong');amount.textContent=format(value(row));el.append(name,track,amount);root.append(el)})}
function table(id,headers,rows){const root=$(id);root.replaceChildren();if(!rows.length){root.append(empty());return}const table=document.createElement('table');const head=document.createElement('thead');const hr=document.createElement('tr');headers.forEach(x=>{const th=document.createElement('th');th.textContent=x;hr.append(th)});head.append(hr);const body=document.createElement('tbody');rows.forEach(row=>{const tr=document.createElement('tr');row.forEach(x=>{const td=document.createElement('td');td.textContent=x;tr.append(td)});body.append(tr)});table.append(head,body);root.append(table)}
function empty(){const p=document.createElement('p');p.className='empty';p.textContent='目前沒有可顯示的資料。';return p}
$('logout').onclick=async()=>{await api('/v2/auth/dashboard',{method:'DELETE'});location.reload()};boot();`;

export function dashboardAsset(path: string): Response | null {
  const assets: Record<string, [string, string]> = {
    "/dashboard": [html, "text/html; charset=utf-8"],
    "/dashboard/": [html, "text/html; charset=utf-8"],
    "/dashboard/dashboard.css": [css, "text/css; charset=utf-8"],
    "/dashboard/dashboard.js": [js, "text/javascript; charset=utf-8"],
  };
  const asset = assets[path];
  if (!asset) return null;
  return new Response(asset[0], { headers: {
    "content-type": asset[1],
    "cache-control": path.endsWith(".html") || path.endsWith("dashboard") || path.endsWith("dashboard/") ? "no-store" : "public, max-age=3600",
    "content-security-policy": "default-src 'self'; script-src 'self' https://accounts.google.com; frame-src https://accounts.google.com; connect-src 'self' https://accounts.google.com; style-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
  } });
}
