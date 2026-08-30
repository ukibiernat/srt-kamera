# SRT Kamera — instrukcja

Aplikacja na Androida. Bierze obraz z grabbera HDMI podpietego przez USB-C
i wysyla go protokolem SRT na twoj serwer.

---

## 1. Jak zrobic z tego plik APK

**APK** to plik instalacyjny aplikacji na Androida — odpowiednik `.exe` na Windows.
Kod trzeba raz "skompilowac" do takiego pliku. Nie musisz niczego instalowac na
swoim komputerze — zrobi to za darmo serwer GitHuba.

### Krok po kroku

1. Zaloz darmowe konto na **github.com**
2. Kliknij zielony przycisk **New** (nowe repozytorium)
   - nazwa: `srt-kamera`
   - zaznacz **Private** (prywatne)
   - kliknij **Create repository**
3. Na nastepnej stronie kliknij link **uploading an existing file**
4. Przeciagnij tam **cala zawartosc folderu `srt-kamera`** (wszystkie pliki i podfoldery)
5. Kliknij **Commit changes**
6. Przejdz na zakladke **Actions** u gory. Zobaczysz zadanie „Zbuduj APK", ktore
   wlasnie sie uruchomilo. Trwa 3–6 minut.
7. Gdy pojawi sie zielony znaczek — wejdz w to zadanie i na dole, w sekcji
   **Artifacts**, pobierz **srt-kamera-apk**
8. W pobranym pliku ZIP jest plik `.apk`

> Jesli zadanie skonczy sie czerwonym krzyzykiem — kliknij w nie, skopiuj tresc
> bledu i przeslij mi. To normalny etap, kod nie byl jeszcze kompilowany.

### Instalacja na telefonie

1. Przeslij plik APK na telefon (kabel, Bluetooth, mail, Dysk Google — obojetnie)
2. Otworz go w telefonie
3. Android zapyta o zgode na instalacje z nieznanego zrodla — zezwol
4. Gotowe. Ten sam plik instalujesz na wszystkich 10 telefonach, bez zadnych oplat

---

## 2. Serwer VPS

### Zakup

Polecany: **OVHcloud VPS, lokalizacja Warszawa** (~25–30 zl/mies.).
Wybierz system **Ubuntu 22.04** lub nowszy. Dostaniesz adres IP i haslo do konta root.

### Instalacja MediaMTX

Zaloguj sie na serwer (przez SSH) i wklej:

```bash
wget https://github.com/bluenviron/mediamtx/releases/latest/download/mediamtx_linux_amd64.tar.gz
tar -xzf mediamtx_linux_amd64.tar.gz
```

Wgraj na serwer dolaczony plik `mediamtx.yml` (zamien w nim haslo!), a potem:

```bash
# otworz port SRT
ufw allow 8890/udp

# uruchom
./mediamtx mediamtx.yml
```

Zeby serwer dzialal caly czas, takze po restarcie — zrobimy z tego usluge
systemd, ale to juz kolejny krok.

---

## 3. Ustawienia w telefonie

Otworz aplikacje → **Ustawienia**:

| Pole | Wartosc |
|---|---|
| Nazwa punktu | `KAM01` (na kazdym telefonie inna) |
| Adres serwera | IP twojego VPS |
| Port | `8890` |
| Stream ID | `kam01` (na kazdym telefonie inny!) |
| Bufor | **1000 ms** dla LTE/5G |
| Haslo szyfrowania | to samo, co w `mediamtx.yml` |
| Zrodlo obrazu | Kamera USB (grabber HDMI) |
| Rozdzielczosc | 1920x1080 |
| Bitrate | 5000 kbps |
| Kodek | H264 (H265 tylko na Redmi Note 10 Pro i Samsungu A53) |
| Adaptacyjny bitrate | wlaczony |

**Stream ID musi byc rozny na kazdym telefonie** — inaczej strumienie beda sie
nadpisywac.

---

## 4. Odbior w OBS

W OBS dodaj **Zrodlo → Media Source** (Zrodlo multimediow):

- odznacz **Local File**
- Input: `srt://IP_TWOJEGO_VPS:8890?streamid=read:kam01&passphrase=TwojeHaslo`
- Input Format: `mpegts`

Dla kazdej kamery osobne zrodlo, zmieniajac `kam01` na `kam02` itd.

---

## 5. Czego jeszcze nie sprawdzono

Uczciwie: ten kod nie byl jeszcze skompilowany ani uruchomiony na twoim sprzecie.
Spodziewaj sie, ze przy pierwszym budowaniu wyskocza bledy do poprawienia —
to normalny etap, nie awaria.

Rzeczy do przetestowania na miejscu:

1. **Hub USB-C** — czy obsluguje grabber i ladowanie jednoczesnie
2. **Grabber Unitek** — czy zostanie wykryty przez telefon
3. **MIUI** — Xiaomi agresywnie ubija aplikacje w tle. W ustawieniach telefonu:
   Aplikacje → SRT Kamera → Oszczedzanie baterii → **Bez ograniczen**,
   oraz Autostart → wlaczony
4. **Temperatura** przy dluzszej pracy
