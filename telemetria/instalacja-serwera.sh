#!/bin/bash
#
# Instalacja serwera SRT + telemetrii na czystej Ubuntu 24.04
# (Google Cloud, Vultr, OVH, Hetzner - dziala wszedzie tak samo)
#
# Uruchomienie:
#   sudo bash instalacja-serwera.sh HASLO_SRT
#
# HASLO_SRT musi miec 10-79 znakow i to samo wpisujesz w telefonach.
#
set -e

HASLO="${1:-ZmienToHaslo123}"
PORT_SRT=8890
PORT_TELEMETRIA=8080

if [ ${#HASLO} -lt 10 ]; then
  echo "BLAD: haslo musi miec co najmniej 10 znakow"
  exit 1
fi

echo "==> Aktualizacja systemu"
apt-get update -qq
apt-get install -y -qq wget curl tar python3 >/dev/null

echo "==> Pobieranie MediaMTX"
cd /opt
URL=$(curl -s https://api.github.com/repos/bluenviron/mediamtx/releases/latest \
      | grep -o 'https://[^"]*linux_amd64\.tar\.gz' | head -1)
if [ -z "$URL" ]; then
  echo "BLAD: nie udalo sie ustalic adresu pobierania MediaMTX"
  exit 1
fi
wget -q "$URL" -O mediamtx.tar.gz
tar -xzf mediamtx.tar.gz
rm -f mediamtx.tar.gz LICENSE
chmod +x /opt/mediamtx

echo "==> Konfiguracja MediaMTX (10 punktow kamerowych)"
cat > /opt/mediamtx.yml <<EOF
logLevel: info

rtmp: no
hls: no
webrtc: no
rtsp: no

srt: yes
srtAddress: :${PORT_SRT}

pathDefaults:
  srtReadPassphrase: ${HASLO}
  srtPublishPassphrase: ${HASLO}

paths:
  kam01:
  kam02:
  kam03:
  kam04:
  kam05:
  kam06:
  kam07:
  kam08:
  kam09:
  kam10:
EOF

echo "==> Usluga MediaMTX"
cat > /etc/systemd/system/mediamtx.service <<'EOF'
[Unit]
Description=MediaMTX - przekaznik SRT
After=network.target

[Service]
ExecStart=/opt/mediamtx /opt/mediamtx.yml
Restart=always
RestartSec=3
User=root

[Install]
WantedBy=multi-user.target
EOF

echo "==> Usluga telemetrii"
if [ -f /opt/srt-telemetria.py ]; then
  cat > /etc/systemd/system/srt-telemetria.service <<EOF
[Unit]
Description=Telemetria SRT Kamera
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/srt-telemetria.py ${PORT_TELEMETRIA}
Restart=always
RestartSec=3
User=root

[Install]
WantedBy=multi-user.target
EOF
  TELEMETRIA=1
else
  echo "    UWAGA: brak /opt/srt-telemetria.py - telemetria pominieta."
  echo "    Wgraj plik serwer.py jako /opt/srt-telemetria.py i uruchom skrypt ponownie."
  TELEMETRIA=0
fi

echo "==> Zapora systemowa"
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp   >/dev/null 2>&1 || true
  ufw allow ${PORT_SRT}/udp >/dev/null 2>&1 || true
  ufw allow ${PORT_TELEMETRIA}/tcp >/dev/null 2>&1 || true
fi

echo "==> Uruchamianie"
systemctl daemon-reload
systemctl enable --now mediamtx >/dev/null 2>&1
[ "$TELEMETRIA" = "1" ] && systemctl enable --now srt-telemetria >/dev/null 2>&1

sleep 2
IP=$(curl -s --max-time 5 ifconfig.me || echo "ADRES_SERWERA")

echo
echo "============================================================"
echo " GOTOWE"
echo "============================================================"
echo " Adres serwera : $IP"
echo " Haslo SRT     : $HASLO"
echo
echo " W telefonie ustaw:"
echo "   Adres serwera : $IP"
echo "   Port          : ${PORT_SRT}"
echo "   Haslo szyfr.  : $HASLO"
echo "   Numer punktu  : 1..10  (kazdy telefon inny)"
echo
echo " W OBS, jako Media Source:"
echo "   srt://$IP:${PORT_SRT}?streamid=read:kam01&passphrase=$HASLO"
echo "   Input Format: mpegts,  Network Buffering: 0"
echo
[ "$TELEMETRIA" = "1" ] && echo " Strona realizatora: http://$IP:${PORT_TELEMETRIA}/"
echo
echo " PAMIETAJ: w Google Cloud trzeba jeszcze otworzyc porty w regule zapory"
echo "           (UDP ${PORT_SRT} i TCP ${PORT_TELEMETRIA}) - sama ufw nie wystarczy."
echo "============================================================"
echo
echo " Sprawdzenie stanu:  systemctl status mediamtx"
echo " Podglad logow:      journalctl -u mediamtx -f"
