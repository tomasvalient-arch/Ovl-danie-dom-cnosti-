package main

const pageHTML = `<!doctype html>
<html lang="sk">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Domov PC</title>
<style>
  :root { color-scheme: dark; --bg:#14110d; --card:#1f1b15; --line:#3a3328; --text:#f1ebe0; --dim:#a79d8c; --amber:#f2b134; --good:#7bd88f; --bad:#ff7b6b; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:16px/1.5 "Segoe UI", system-ui, sans-serif; }
  main { max-width: 760px; margin: 0 auto; padding: 24px 16px 64px; }
  h1 { font-size: 28px; margin: 0 0 4px; }
  h2 { font-size: 13px; letter-spacing: .08em; text-transform: uppercase; color: var(--dim); margin: 28px 0 8px; }
  .sub { color: var(--dim); margin: 0 0 16px; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 16px; margin-bottom: 12px; }
  .row { display:flex; gap:12px; align-items:center; justify-content:space-between; flex-wrap:wrap; }
  button { background: var(--amber); color:#1b1306; border:0; border-radius: 999px; padding: 10px 18px; font: inherit; font-weight: 600; cursor: pointer; }
  button.ghost { background: transparent; color: var(--text); border: 1px solid var(--line); }
  button.danger { background: transparent; color: var(--bad); border: 1px solid var(--line); }
  button:disabled { opacity:.5; cursor: default; }
  input[type=text] { background:#14110d; color:var(--text); border:1px solid var(--line); border-radius: 12px; padding: 9px 12px; font: inherit; width: 240px; max-width: 100%; }
  .code { font: 700 40px/1.1 Consolas, "Courier New", monospace; letter-spacing: .12em; color: var(--amber); margin: 8px 0; }
  .qr { width: 240px; height: 240px; background:#fff; border-radius: 12px; padding: 0; }
  .dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:8px; background: var(--dim); }
  .dot.on { background: var(--good); } .dot.warn { background: var(--bad); }
  .dim { color: var(--dim); } small { color: var(--dim); }
  ol, ul { margin: 6px 0 0 20px; padding: 0; } li { margin: 3px 0; }
  code { background:#14110d; border:1px solid var(--line); border-radius:6px; padding:1px 6px; font-size: 13px; word-break: break-all; }
  pre { background:#14110d; border:1px solid var(--line); border-radius:10px; padding:10px; white-space: pre-wrap; font-size: 13px; }
  .pairbox { display:flex; gap: 24px; flex-wrap: wrap; align-items: center; }
</style>
</head>
<body>
<main>
  <h1>Domov PC</h1>
  <p class="sub">Ovládanie tohto počítača z telefónu cez domácu WiFi. Táto stránka je vidieť len na tomto PC.</p>

  <div class="card" id="status"></div>

  <h2>Spárovať telefón</h2>
  <div class="card" id="pairing"></div>

  <h2>Spárované telefóny</h2>
  <div class="card" id="devices"></div>

  <h2>Nastavenia</h2>
  <div class="card">
    <div class="row"><span>Názov PC</span>
      <span><input type="text" id="name" maxlength="40"> <button class="ghost" onclick="rename()">Uložiť</button></span></div>
    <div class="row" style="margin-top:12px" id="autostartRow"><label><input type="checkbox" id="autostart" onchange="setAuto(this.checked)"> Spustiť program po prihlásení do Windows</label></div>
    <p class="dim" style="margin:12px 0 0" id="elevNote"></p>
    <div class="row" style="margin-top:12px"><span class="dim">Program môžeš kedykoľvek ukončiť (potom sa na PC nedá pripojiť).</span><button class="danger" onclick="quitApp()">Ukončiť program</button></div>
  </div>

  <h2>Ak sa telefón nevie pripojiť</h2>
  <div class="card">
    <ul>
      <li>Telefón aj PC musia byť na <b>rovnakej WiFi / sieti</b> (nie hosťovská sieť).</li>
      <li>Pri prvom spustení Windows pýta povolenie vo firewalle: zaškrtni <b>Súkromné siete</b> a klikni <b>Povoliť prístup</b>.</li>
      <li>Ak si to zamietol, otvor PowerShell <b>ako správca</b> a spusti:
        <pre>netsh advfirewall firewall add rule name="Domov PC" dir=in action=allow protocol=TCP localport=<span class="port">47810</span> profile=private
netsh advfirewall firewall add rule name="Domov PC hladanie" dir=in action=allow protocol=UDP localport=<span class="port">47810</span> profile=private</pre></li>
      <li>Ak router izoluje zariadenia (AP izolácia), v appke zadaj IP adresu PC ručne.</li>
      <li>Keď je PC zamknutý (Win+L) alebo ukazuje prihlasovaciu obrazovku, obraz sa nedá preniesť. Odomkni ho na PC.</li>
    </ul>
  </div>

  <h2>Diagnostika</h2>
  <div class="card">
    <div class="row"><span class="dim" id="diag"></span><button class="ghost" id="testBtn" onclick="selftest()">Otestovať zachytenie obrazovky</button></div>
    <pre id="testOut" style="display:none"></pre>
  </div>
</main>
<script>
var S = null, busy = false;
function esc(s){ var d=document.createElement('div'); d.textContent=s==null?'':String(s); return d.innerHTML; }
function api(path, body){
  return fetch(path, {method:'POST', headers:{'X-Domov-PC':'1','Content-Type':'application/json'}, body: JSON.stringify(body||{})})
    .then(function(r){ return r.json().then(function(j){ if(!r.ok) throw new Error(j.error||'chyba'); return j; }); });
}
function ago(ts){
  if(!ts) return 'ešte sa nepripojil';
  var s = Math.floor(Date.now()/1000) - ts;
  if (s < 90) return 'práve teraz';
  if (s < 3600) return 'pred ' + Math.floor(s/60) + ' min';
  if (s < 86400) return 'pred ' + Math.floor(s/3600) + ' h';
  return 'pred ' + Math.floor(s/86400) + ' d';
}
function render(){
  if(!S) return;
  var st = '<div class="row"><span><span class="dot ' + (S.sessionName?'on':'') + '"></span><b>' + esc(S.name) + '</b> · program beží · verzia ' + esc(S.version) + '</span></div>';
  st += '<div class="dim" style="margin-top:6px">' + (S.sessionName ? 'Pripojený telefón: <b>' + esc(S.sessionName) + '</b> (' + esc(S.sessionIp) + ')' : 'Žiadny telefón nie je práve pripojený.') + '</div>';
  document.getElementById('status').innerHTML = st;

  var p = S.pairing, h = '';
  if (p.active) {
    h += '<div class="pairbox"><div class="qr">' + p.qr + '</div><div>';
    h += '<div class="dim">Kód na párovanie</div><div class="code">' + esc(p.code) + '</div>';
    h += '<div class="dim">Platí ešte ' + Math.floor(p.remaining/60) + ':' + ('0'+(p.remaining%60)).slice(-2) + '</div>';
    h += '<ol><li>V appke Domov otvor <b>Nastavenia → Ovládanie PC → Pridať PC</b>.</li><li>Naskenuj tento QR kód, alebo napíš kód.</li></ol>';
    h += '<p><button class="ghost" onclick="cancelPair()">Zrušiť párovanie</button></p></div></div>';
  } else {
    h += '<div class="row"><span class="dim">Párovanie je vypnuté. Zapni ho, len keď chceš pridať nový telefón.</span><button onclick="startPair()">Spárovať nový telefón</button></div>';
  }
  document.getElementById('pairing').innerHTML = h;

  var d = '';
  if (!S.devices.length) d = '<span class="dim">Zatiaľ žiadny telefón.</span>';
  S.devices.forEach(function(x){
    d += '<div class="row" style="padding:6px 0"><span><b>' + esc(x.name) + '</b><br><small>' + ago(x.lastSeen) + (x.lastIp ? ' · ' + esc(x.lastIp) : '') + '</small></span><button class="danger" onclick="removeDev(\'' + esc(x.id) + '\',\'' + esc(x.name).replace(/'/g,'') + '\')">Odstrániť</button></div>';
  });
  document.getElementById('devices').innerHTML = d;

  var nm = document.getElementById('name'); if (document.activeElement !== nm && !nm.dataset.dirty) nm.value = S.name;
  var as = document.getElementById('autostart'); as.checked = S.autostart;
  document.getElementById('autostartRow').style.display = S.canAutostart ? '' : 'none';
  document.getElementById('elevNote').textContent = S.elevated
    ? 'Program beží ako správca: ovládať sa dajú aj okná spustené ako správca.'
    : 'Okná spustené ako správca (napr. Správca úloh) sa z telefónu ovládať nedajú. Ak to potrebuješ, spusti program cez pravý klik → Spustiť ako správca.';
  var ports = document.getElementsByClassName('port'); for (var i=0;i<ports.length;i++) ports[i].textContent = S.port;
  document.getElementById('diag').innerHTML = 'IP adresy PC: <code>' + esc(S.ips.join(', ') || 'nenájdené') + '</code> · port <code>' + S.port + '</code> · plocha: ' + (S.desktop ? '<span style="color:var(--good)">odomknutá</span>' : '<span style="color:var(--bad)">zamknutá</span>') + '<br><small>Záznam: ' + esc(S.logPath) + '</small>';
}
function refresh(){ if (busy) return; fetch('/api/state').then(function(r){return r.json();}).then(function(j){ S=j; render(); }).catch(function(){}); }
function act(path, body){ busy = true; return api(path, body).then(function(j){ if (j && j.version) { S=j; } busy=false; render(); }).catch(function(e){ busy=false; alert(e.message); }); }
function startPair(){ act('/api/pair/start'); }
function cancelPair(){ act('/api/pair/cancel'); }
function removeDev(id, name){ if (confirm('Odstrániť telefón „' + name + '“? Už sa nebude môcť pripojiť.')) act('/api/device/remove', {id:id}); }
function rename(){ var n=document.getElementById('name'); act('/api/rename', {name:n.value}).then(function(){ n.dataset.dirty=''; }); }
function setAuto(v){ act('/api/autostart', {enable:v}); }
function quitApp(){ if (confirm('Ukončiť program Domov PC? Telefón sa potom na tento PC nepripojí, kým ho znova nespustíš.')) api('/api/quit').then(function(){ document.body.innerHTML = '<main><h1>Program bol ukončený</h1><p class="sub">Môžeš zavrieť túto kartu.</p></main>'; }); }
function selftest(){
  var b=document.getElementById('testBtn'), o=document.getElementById('testOut'); b.disabled=true; o.style.display='block'; o.textContent='Testujem…';
  api('/api/selftest').then(function(j){ o.textContent=j.text; b.disabled=false; }).catch(function(e){ o.textContent='Chyba: '+e.message; b.disabled=false; });
}
document.getElementById('name').addEventListener('input', function(){ this.dataset.dirty='1'; });
refresh(); setInterval(refresh, 1500);
</script>
</body>
</html>`
