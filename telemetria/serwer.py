#!/usr/bin/env python3
"""
Serwer telemetrii dla SRT Kamera.

Przyjmuje pakiety stanu z telefonow i serwuje strone podgladu dla realizatora.
Bez zadnych zewnetrznych bibliotek - dziala na czystym Pythonie 3.

Uruchomienie:
    python3 serwer.py            # port 8080
    python3 serwer.py 9000       # inny port

Strona:      http://ADRES_SERWERA:8080/
Dane z apki: POST http://ADRES_SERWERA:8080/t
"""

import json
import sys
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
HISTORY = 120          # ile probek trzymac na kamere (120 x 5s = 10 minut)

cameras = {}           # id -> deque probek


def store(sample):
    cam_id = str(sample.get("id", "?"))
    if cam_id not in cameras:
        cameras[cam_id] = deque(maxlen=HISTORY)
    sample["recv"] = time.time()
    cameras[cam_id].append(sample)


def snapshot():
    """Ostatni stan kazdej kamery plus dane potrzebne do wykrycia trendow."""
    out = []
    now = time.time()
    for cam_id, hist in sorted(cameras.items()):
        last = dict(hist[-1])
        last["age"] = round(now - last["recv"], 1)

        # bateria sprzed minuty - do wykrycia rozladowywania mimo ladowania
        old = None
        for s in hist:
            if last["recv"] - s["recv"] >= 60:
                old = s
            else:
                break
        last["batteryBefore"] = old.get("battery") if old else None
        last["reconnectsBefore"] = old.get("reconnects") if old else None
        out.append(last)
    return out


PAGE = r"""<!doctype html>
<html lang="pl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Punkty kamerowe</title>
<style>
  :root {
    --bg:#0d1117; --panel:#161b22; --line:#30363d;
    --txt:#e6edf3; --dim:#8b949e;
    --ok:#2ea043; --warn:#d29922; --bad:#f85149;
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--txt);
         font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
  header { padding:16px 20px; border-bottom:1px solid var(--line);
           display:flex; align-items:baseline; gap:16px; flex-wrap:wrap; }
  h1 { margin:0; font-size:19px; font-weight:600; }
  .sub { color:var(--dim); font-size:13px; }
  .wrap { padding:16px 20px; }
  .grid { display:grid; gap:12px;
          grid-template-columns:repeat(auto-fill,minmax(330px,1fr)); }

  .card { background:var(--panel); border:1px solid var(--line);
          border-radius:10px; padding:14px 16px; border-left:5px solid var(--ok); }
  .card.warn { border-left-color:var(--warn); }
  .card.bad  { border-left-color:var(--bad); animation:pulse 1.1s infinite; }
  @keyframes pulse {
    0%,100% { background:var(--panel); }
    50%     { background:#3d1418; }
  }

  .head { display:flex; align-items:center; justify-content:space-between; gap:10px; }
  .name { font-size:22px; font-weight:700; letter-spacing:.5px; }
  .state { font-size:13px; font-weight:600; padding:3px 10px; border-radius:20px;
           background:#21262d; color:var(--dim); white-space:nowrap; }
  .state.ok  { background:#12331d; color:var(--ok); }
  .state.bad { background:#3d1418; color:var(--bad); }

  .rows { margin-top:12px; display:grid; grid-template-columns:1fr 1fr; gap:6px 14px; }
  .row { display:flex; justify-content:space-between; font-size:13px;
         border-bottom:1px dotted #21262d; padding-bottom:3px; }
  .row span:first-child { color:var(--dim); }
  .row b { font-weight:600; }
  .v-warn { color:var(--warn); }
  .v-bad  { color:var(--bad); }

  .alarms { margin-top:12px; display:flex; flex-wrap:wrap; gap:6px; }
  .alarm { font-size:12px; font-weight:600; padding:3px 9px; border-radius:5px;
           background:#3d1418; color:#ffb3ad; }
  .alarm.warn { background:#3d2f10; color:#f0d58a; }

  .empty { color:var(--dim); padding:40px; text-align:center; }
  footer { color:var(--dim); font-size:12px; padding:0 20px 24px; }
</style>
</head>
<body>
<header>
  <h1>Punkty kamerowe</h1>
  <span class="sub" id="summary">łączenie…</span>
  <span class="sub" id="clock" style="margin-left:auto"></span>
</header>
<div class="wrap"><div class="grid" id="grid"></div>
  <div class="empty" id="empty">Brak danych. Uruchom aplikację na telefonie i włącz nadawanie.</div>
</div>
<footer>
  Alarmy: brak danych &gt;15&nbsp;s · bateria spada mimo ładowania · bateria &lt;20% ·
  temperatura &gt;45°C · zasięg &lt;−110&nbsp;dBm · zapchane łącze · wznowienia w ostatniej minucie
</footer>

<script>
const T = { staleSec:15, batteryLow:20, tempHigh:45, rsrpBad:-110, rsrpWarn:-100 };

function fmtUptime(s){
  if(!s) return "—";
  const h=Math.floor(s/3600), m=Math.floor(s%3600/60), sec=s%60;
  return h>0 ? `${h}:${String(m).padStart(2,"0")}:${String(sec).padStart(2,"0")}`
             : `${m}:${String(sec).padStart(2,"0")}`;
}

function analyse(c){
  const bad=[], warn=[];
  if(c.age > T.staleSec) bad.push("BRAK DANYCH " + Math.round(c.age) + "s");
  if(!c.streaming && c.age <= T.staleSec) bad.push("NIE NADAJE");

  if(c.charging && c.batteryBefore != null && c.battery < c.batteryBefore)
    bad.push("ROZŁADOWUJE SIĘ MIMO ŁADOWANIA");
  if(!c.charging) warn.push("BEZ ZASILANIA");
  if(c.battery >= 0 && c.battery < T.batteryLow) bad.push("BATERIA " + c.battery + "%");

  if(c.temp > T.tempHigh) bad.push("TEMPERATURA " + Math.round(c.temp) + "°C");
  else if(c.temp > T.tempHigh - 5) warn.push("ciepło " + Math.round(c.temp) + "°C");

  if(c.rsrp && c.rsrp < T.rsrpBad) bad.push("ZASIĘG " + c.rsrp + " dBm");
  else if(c.rsrp && c.rsrp < T.rsrpWarn) warn.push("słaby zasięg");

  if(c.congested) bad.push("ŁĄCZE ZAPCHANE");
  if(c.reconnectsBefore != null && c.reconnects > c.reconnectsBefore)
    warn.push("wznowień w minucie: " + (c.reconnects - c.reconnectsBefore));

  return {bad, warn};
}

function row(label, value, cls){
  return `<div class="row"><span>${label}</span><b class="${cls||""}">${value}</b></div>`;
}

function card(c){
  const a = analyse(c);
  const level = a.bad.length ? "bad" : (a.warn.length ? "warn" : "");
  const stale = c.age > T.staleSec;

  const battCls = c.battery < T.batteryLow ? "v-bad" : (c.battery < 40 ? "v-warn" : "");
  const tempCls = c.temp > T.tempHigh ? "v-bad" : (c.temp > T.tempHigh-5 ? "v-warn" : "");
  const rsrpCls = c.rsrp < T.rsrpBad ? "v-bad" : (c.rsrp < T.rsrpWarn ? "v-warn" : "");

  const bars = c.bars >= 0 ? "▮".repeat(c.bars) + "▯".repeat(Math.max(0,4-c.bars)) : "—";

  return `<div class="card ${level}">
    <div class="head">
      <div class="name">${c.name || c.id}</div>
      <div class="state ${stale ? "bad" : (c.streaming ? "ok":"")}">${stale ? "OFFLINE" : c.status}</div>
    </div>
    <div class="rows">
      ${row("Bitrate", c.streaming ? c.bitrate+" kbps" : "—")}
      ${row("Czas nadawania", fmtUptime(c.uptime))}
      ${row("Bateria", (c.battery>=0? c.battery+"%":"—") + (c.charging? " ⚡":""), battCls)}
      ${row("Temperatura", c.temp ? Math.round(c.temp)+"°C" : "—", tempCls)}
      ${row("Zasięg", (c.rsrp? c.rsrp+" dBm":"—") + " " + bars, rsrpCls)}
      ${row("Sieć", c.net || "—")}
      ${row("Szczyt sesji", c.peak ? c.peak+" kbps" : "—")}
      ${row("Szacunek łącza", c.upEstimate ? c.upEstimate+" kbps" : "—")}
      ${row("Kolejka", c.queue ?? 0)}
      ${row("Zgubione klatki", c.dropped ?? 0)}
      ${row("Wznowienia", c.reconnects ?? 0)}
      ${row("Obraz", (c.res||"") + " " + (c.codec||""))}
      ${row("Źródło", c.src || "—")}
      ${row("Bufor", (c.latency||0) + " ms")}
    </div>
    ${(a.bad.length||a.warn.length) ? `<div class="alarms">
      ${a.bad.map(x=>`<span class="alarm">${x}</span>`).join("")}
      ${a.warn.map(x=>`<span class="alarm warn">${x}</span>`).join("")}
    </div>` : ""}
  </div>`;
}

async function tick(){
  try{
    const r = await fetch("/api", {cache:"no-store"});
    const data = await r.json();
    const grid = document.getElementById("grid");
    const empty = document.getElementById("empty");

    empty.style.display = data.length ? "none" : "block";
    grid.innerHTML = data.map(card).join("");

    const live = data.filter(c => c.age <= T.staleSec && c.streaming).length;
    const problems = data.filter(c => analyse(c).bad.length).length;
    document.getElementById("summary").textContent =
      `${live} z ${data.length} nadaje` + (problems ? ` · ${problems} z problemem` : " · wszystko w porządku");
  }catch(e){
    document.getElementById("summary").textContent = "brak połączenia z serwerem";
  }
  document.getElementById("clock").textContent = new Date().toLocaleTimeString("pl-PL");
}
tick();
setInterval(tick, 2000);
</script>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):

    def log_message(self, *args):
        pass  # cisza w konsoli

    def _send(self, code, body, ctype="text/html; charset=utf-8"):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/api"):
            self._send(200, json.dumps(snapshot()), "application/json; charset=utf-8")
        elif self.path in ("/", "/index.html"):
            self._send(200, PAGE)
        else:
            self._send(404, "nie ma takiej strony")

    def do_POST(self):
        if not self.path.startswith("/t"):
            self._send(404, "nie ma takiego adresu")
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length)
            store(json.loads(raw.decode("utf-8")))
            self._send(200, "ok", "text/plain")
        except Exception as e:
            self._send(400, "blad: %s" % e, "text/plain")


if __name__ == "__main__":
    print("Telemetria SRT Kamera")
    print("  strona realizatora : http://<adres-serwera>:%d/" % PORT)
    print("  telefony wysylaja  : POST http://<adres-serwera>:%d/t" % PORT)
    print("  zatrzymanie        : Ctrl+C")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
