#!/usr/bin/env python3
"""Cosechador + inventario de hosts del robot AIBI.

Vigila el log del sniffer (/tmp/esp-spy.log) y, en tiempo real:
1. INVENTARIO "de donde": cada host que el robot toca -> /tmp/hosts-robot.txt
   (fuente dns = consulta DNS, http = header Host:, tls = SNI en :443).
   Esto responde "de donde consume" incluso sin poder bajar el binario (HTTPS).
2. DESCARGA "que consume": cada GET HTTP (cualquier host, cualquier path, no
   solo api-guigu/dl) -> baja el archivo a /tmp/robot-assets/ y registra
   estado HTTP + tamano + tipo en /tmp/respuestas-robot.txt.

La reconstruccion de host/URL desde el log es heuristica (printable() del
sniffer convierte \\r\\n\\t en espacios y los bytes de longitud DNS en '.'/' ').
ponytail: parser heuristico, revisar si aparece un host con formato raro.

Uso:  python3 harvester.py            (modo watch)
      python3 harvester.py --selftest (verifica el parser)
"""
import os, re, subprocess, time, sys

LOG = "/tmp/esp-spy.log"
OUT = "/tmp/robot-assets"
SCRIPT = "/tmp/descargas-robot.sh"
RESP = "/tmp/respuestas-robot.txt"
HOSTS = "/tmp/hosts-robot.txt"

# Un GET dentro de una linea "TCP:80 | GET /path HTTP/1.1 Host: h User-Agent: ..."
RE_GET = re.compile(r"(?:GET|POST)\s+(/\S*)")
RE_HOST = re.compile(r"Host:\s*([A-Za-z0-9.\-]+)")
# Dominios sueltos (para DNS y SNI): labels separados por '.' o ' ' (byte de longitud)
RE_DOMAIN = re.compile(r"([A-Za-z0-9\-]+(?:[. ][A-Za-z0-9\-]+)*[. ](?:com|cn|net|ai|io|org))")


def norm_host(s):
    """'api aibipocket.com' / 'api.aibipocket.com' -> 'api.aibipocket.com'."""
    return re.sub(r"[ .]+", ".", s).strip(".").lower()


def parse_line(line):
    """-> (hosts:[(host,fuente)], download:{url}|None)."""
    hosts, dl = [], None
    if "UDP:53" in line:                      # consulta DNS
        for m in RE_DOMAIN.finditer(line.split("|", 1)[-1]):
            hosts.append((norm_host(m.group(1)), "dns"))
    elif "TCP:80" in line:                     # HTTP en claro
        g, h = RE_GET.search(line), RE_HOST.search(line)
        if h:
            host = norm_host(h.group(1))
            hosts.append((host, "http"))
            if g:
                dl = "http://" + host + g.group(1)
    elif "TCP:443" in line:                    # SNI en el ClientHello (dump crudo)
        for m in RE_DOMAIN.finditer(line.split("|", 1)[-1]):
            hosts.append((norm_host(m.group(1)), "tls"))
    return [(hh, f) for hh, f in hosts if "." in hh and len(hh) > 4], dl


def selftest():
    samples = [
        ("  UDP:53 | .............api aibipocket.com.....",
         [("api.aibipocket.com", "dns")], None),
        ("  TCP:80 | GET /poweron/dl/abc123 HTTP/1.1 Host: api-guigu.aibipocket.com User-Agent: x",
         [("api-guigu.aibipocket.com", "http")],
         "http://api-guigu.aibipocket.com/poweron/dl/abc123"),
        ("  TCP:443 | ...........res-us-east-1.living.ai....",
         [("res-us-east-1.living.ai", "tls")], None),
        ("ROBOT->192.168.4.1 (proto 17)", [], None),
    ]
    for line, exp_hosts, exp_dl in samples:
        hosts, dl = parse_line(line)
        assert hosts == exp_hosts, f"hosts {hosts!r} != {exp_hosts!r} en {line!r}"
        assert dl == exp_dl, f"dl {dl!r} != {exp_dl!r} en {line!r}"
    print("selftest OK")


def main():
    os.makedirs(OUT, exist_ok=True)
    seen_dl, seen_host, pos = set(), {}, 0
    if os.path.exists(SCRIPT):
        for l in open(SCRIPT):
            m = re.search(r"'(https?://[^']+)'", l)
            if m: seen_dl.add(m.group(1))

    def download(url):
        if url in seen_dl: return
        seen_dl.add(url)
        fname = url.rstrip("/").split("/")[-1] or "index"
        path = f"{OUT}/{fname}"
        with open(SCRIPT, "a") as f:
            f.write(f"curl -sL -o \"{path}\" '{url}'\n")
        r = subprocess.run(["curl", "-sL", "-o", path, "-w", "%{http_code} %{size_download}", url],
                           capture_output=True, text=True)
        size = os.path.getsize(path) if os.path.exists(path) else 0
        ftype = subprocess.run(["file", "-b", path], capture_output=True, text=True).stdout.strip()
        with open(RESP, "a") as f:
            f.write(f"{time.strftime('%H:%M:%S')} | {r.stdout.strip()} | {size}B | {ftype} | {url}\n")
        print(f"[dl] {r.stdout.strip()} | {ftype} | {url}", flush=True)

    def note_host(host, src):
        if host in seen_host: return
        seen_host[host] = src
        with open(HOSTS, "a") as f:
            f.write(f"{time.strftime('%H:%M:%S')} | {src:4} | {host}\n")
        print(f"[host:{src}] {host}", flush=True)

    print("[harvester] mapeando hosts + descargas del robot...", flush=True)
    while True:
        try:
            with open(LOG, "rb") as f:
                f.seek(pos); data = f.read(); pos = f.tell()
            for line in data.decode("utf-8", errors="replace").splitlines():
                hosts, dl = parse_line(line)
                for h, src in hosts: note_host(h, src)
                if dl: download(dl)
        except FileNotFoundError:
            pass
        except Exception as e:
            print("ERR", e, file=sys.stderr, flush=True)
        time.sleep(2)


if __name__ == "__main__":
    if "--selftest" in sys.argv: selftest()
    else: main()
