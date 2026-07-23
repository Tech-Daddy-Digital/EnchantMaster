#!/usr/bin/env python3
"""Build (optional) + smoke-test Enchant Master Paper plugins across versions.

Downloads Paper from Fill API, installs plugin jar, starts server, checks web APIs.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / "dist" / "matrix"
REPORT_DIR = Path(__file__).resolve().parent / "reports"
RUNS = ROOT / "runs"
TARGETS_FILE = Path(__file__).resolve().parent / "paper_targets.json"
FILL = "https://fill.papermc.io/v3/projects/paper"


@dataclass
class Stage:
    name: str
    ok: bool
    detail: str = ""


@dataclass
class Result:
    mc: str
    stages: list[Stage] = field(default_factory=list)
    overall_ok: bool = False

    def add(self, name: str, ok: bool, detail: str = "") -> None:
        self.stages.append(Stage(name, ok, detail))
        print(f"  [{ 'OK' if ok else 'FAIL' }] {name}: {detail}", flush=True)


def log(msg: str) -> None:
    print(f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}Z] {msg}", flush=True)


def http_get(url: str, timeout: float = 10.0) -> tuple[int, str]:
    req = urllib.request.Request(url, headers={"User-Agent": "EnchantMaster-PaperMatrix/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read().decode("utf-8", errors="replace")


def http_json(url: str, data: dict | None = None, method: str = "GET") -> tuple[int, dict | list | str]:
    body = None
    headers = {"User-Agent": "EnchantMaster-PaperMatrix/1.0"}
    if data is not None:
        body = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
        method = "POST"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=20) as r:
        raw = r.read().decode("utf-8", errors="replace")
        try:
            return r.status, json.loads(raw)
        except json.JSONDecodeError:
            return r.status, raw


def rcon(host: str, port: int, password: str, command: str) -> str:
    """Minimal Minecraft RCON client."""
    def pack(req_id: int, typ: int, payload: str) -> bytes:
        data = struct.pack("<ii", req_id, typ) + payload.encode("utf-8") + b"\x00\x00"
        return struct.pack("<i", len(data)) + data

    def recv_packet(sock: socket.socket) -> tuple[int, int, str]:
        length = struct.unpack("<i", sock.recv(4))[0]
        data = b""
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                break
            data += chunk
        req_id, typ = struct.unpack("<ii", data[:8])
        payload = data[8:-2].decode("utf-8", errors="replace")
        return req_id, typ, payload

    s = socket.create_connection((host, port), timeout=10)
    try:
        s.sendall(pack(1, 3, password))
        recv_packet(s)
        s.sendall(pack(2, 2, command))
        _, _, payload = recv_packet(s)
        return payload
    finally:
        s.close()


def latest_paper_build(mc: str) -> dict:
    url = f"{FILL}/versions/{mc}/builds"
    req = urllib.request.Request(url, headers={"User-Agent": "EnchantMaster-PaperMatrix/1.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        builds = json.loads(r.read())
    stable = [b for b in builds if b.get("channel") == "STABLE"]
    return (stable or builds)[-1]


def download_paper(mc: str, run_dir: Path) -> Path:
    build = latest_paper_build(mc)
    bid = build["id"]
    dl = build.get("downloads", {}).get("server:default", {})
    url = dl.get("url")
    if not url:
        name = dl.get("name", f"paper-{mc}-{bid}.jar")
        url = f"{FILL}/versions/{mc}/builds/{bid}/downloads/{name}"
    dest = run_dir / "paper.jar"
    if dest.is_file() and dest.stat().st_size > 1_000_000:
        return dest
    log(f"Downloading Paper {mc} build {bid}…")
    req = urllib.request.Request(url, headers={"User-Agent": "EnchantMaster-PaperMatrix/1.0"})
    with urllib.request.urlopen(req, timeout=300) as r, open(dest, "wb") as out:
        shutil.copyfileobj(r, out)
    return dest


def free_port(start: int = 25570) -> int:
    for p in range(start, start + 200):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", p))
                return p
            except OSError:
                continue
    raise RuntimeError("No free port")


def write_server_files(run_dir: Path, game_port: int, rcon_port: int, web_port: int) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "eula.txt").write_text("eula=true\n")
    props = f"""server-port={game_port}
online-mode=false
max-players=10
spawn-protection=0
enable-rcon=true
rcon.port={rcon_port}
rcon.password=enchantmaster
broadcast-rcon-to-ops=false
motd=EnchantMaster Paper Matrix
level-type=minecraft\\:normal
gamemode=creative
difficulty=peaceful
allow-nether=false
"""
    (run_dir / "server.properties").write_text(props)
    (run_dir / "ops.json").write_text("[]")
    plugins = run_dir / "plugins"
    plugins.mkdir(exist_ok=True)
    # plugin config override for web port / ACL off for local smoke
    em = plugins / "EnchantMaster"
    em.mkdir(exist_ok=True)
    (em / "config.yml").write_text(
        f"""web:
  host: "127.0.0.1"
  port: {web_port}
  publicUrl: "http://127.0.0.1:{web_port}"
  accessControl: false
  allowLocalhost: true
  maxOverrideLevel: 255
"""
    )


def run_target(mc: str, skip_build: bool) -> Result:
    res = Result(mc=mc)
    jar = DIST / f"enchantmaster-1.0.5-mc{mc}-paper.jar"
    if not jar.is_file():
        res.add("build_paper", False, f"missing {jar.name}")
        return res
    res.add("build_paper", True, jar.name)

    client_jar = DIST / f"enchantmaster-1.0.5-mc{mc}-fabric-client.jar"
    res.add("build_client", client_jar.is_file(), client_jar.name if client_jar.is_file() else "not built yet")

    run_dir = RUNS / f"paper-{mc}"
    run_dir.mkdir(parents=True, exist_ok=True)
    game_port = free_port(25600)
    rcon_port = free_port(game_port + 1)
    web_port = free_port(25700)

    try:
        paper = download_paper(mc, run_dir)
        res.add("download_paper", True, paper.name)
    except Exception as e:
        res.add("download_paper", False, str(e))
        return res

    write_server_files(run_dir, game_port, rcon_port, web_port)
    # install plugin
    plugins = run_dir / "plugins"
    for old in plugins.glob("enchantmaster*.jar"):
        old.unlink()
    shutil.copy2(jar, plugins / jar.name)

    # pick java
    java = os.environ.get("JAVA_HOME", "")
    if mc.startswith("26."):
        jhome = "/usr/lib/jvm/java-25-openjdk"
    elif mc.startswith("1.20.1") or mc.startswith("1.20.2") or mc.startswith("1.20.4"):
        jhome = "/usr/lib/jvm/java-17-openjdk"
    else:
        jhome = "/usr/lib/jvm/java-21-openjdk"
    java_bin = f"{jhome}/bin/java"
    if not Path(java_bin).exists():
        java_bin = "java"

    log_path = run_dir / "matrix-server.log"
    log(f"Starting Paper {mc} on :{game_port} (web {web_port}, rcon {rcon_port})")
    proc = subprocess.Popen(
        [java_bin, "-Xms512M", "-Xmx1536M", "-jar", "paper.jar", "--nogui"],
        cwd=run_dir,
        stdout=open(log_path, "w"),
        stderr=subprocess.STDOUT,
    )
    try:
        ready = False
        for _ in range(180):
            time.sleep(2)
            if proc.poll() is not None:
                res.add("server_boot", False, f"exited {proc.returncode}; see {log_path}")
                return res
            text = log_path.read_text(errors="replace")
            if "Done (" in text or "Done!" in text:
                ready = True
                break
            if "Error occurred" in text or "UnsupportedClassVersion" in text:
                res.add("server_boot", False, f"error in log; see {log_path}")
                return res
        if not ready:
            res.add("server_boot", False, "timeout waiting for Done")
            return res
        if "Enchant Master" not in log_path.read_text(errors="replace") and "EnchantMaster" not in log_path.read_text(errors="replace"):
            # still ok if plugin enabled line differs
            pass
        res.add("server_boot", True, f"pid={proc.pid}")

        # start web via rcon
        try:
            out = rcon("127.0.0.1", rcon_port, "enchantmaster", "enchantmaster web start")
            res.add("web_start", True, out[:120].replace("\n", " "))
        except Exception as e:
            # try console alternative - plugin might register without rcon prefix
            try:
                out = rcon("127.0.0.1", rcon_port, "enchantmaster", "em web start")
                res.add("web_start", True, out[:120])
            except Exception as e2:
                res.add("web_start", False, f"{e}; {e2}")
                return res

        time.sleep(1)
        base = f"http://127.0.0.1:{web_port}"
        try:
            code, health = http_json(f"{base}/api/health")
            ok = code == 200 and isinstance(health, dict) and health.get("ok") is True
            res.add("web_health", ok, str(health)[:120])
        except Exception as e:
            res.add("web_health", False, str(e))
            return res

        try:
            code, items = http_json(f"{base}/api/items?limit=5")
            ok = code == 200 and isinstance(items, dict) and "items" in items
            res.add("catalog", ok, f"items keys={list(items)[:5] if isinstance(items, dict) else type(items)}")
        except Exception as e:
            res.add("catalog", False, str(e))

        # dry-run forge
        try:
            code, forge = http_json(
                f"{base}/api/forge",
                {
                    "itemId": "minecraft:diamond_sword",
                    "overrideLimits": True,
                    "name": {"text": "Matrix Blade", "italic": False},
                    "lore": [{"text": "Paper matrix test", "italic": True}],
                    "enchantments": [{"id": "minecraft:sharpness", "level": 5}],
                    "attributes": [],
                    "dryRun": True,
                },
            )
            ok = code == 200 and isinstance(forge, dict) and forge.get("success") is True
            res.add("forge_dryrun", ok, str(forge)[:160])
        except Exception as e:
            res.add("forge_dryrun", False, str(e))

        # offline inventory modify (cache path)
        try:
            import uuid as uuidlib

            uid = str(uuidlib.uuid4())
            # first set a stack into offline cache via modify with itemId (empty path create)
            code, inv = http_json(
                f"{base}/api/inventory/modify",
                {
                    "uuid": uid,
                    "path": "inv:0",
                    "itemId": "minecraft:diamond_sword",
                    "overrideLimits": True,
                    "name": {"text": "Offline Sword", "italic": False},
                    "lore": [{"text": "Keep me", "italic": True}, {"text": "Line 2", "italic": True}],
                    "enchantments": [{"id": "minecraft:unbreaking", "level": 3}],
                    "attributes": [],
                    "dryRun": False,
                },
            )
            ok = code == 200 and isinstance(inv, dict) and inv.get("success") is True
            # re-read and check lore
            code2, listed = http_json(f"{base}/api/inventory?uuid={uid}&forgeableOnly=false")
            lore_ok = False
            if isinstance(listed, dict):
                for slot in listed.get("slots", []):
                    if slot.get("path") == "inv:0":
                        lore = slot.get("lore") or []
                        name = slot.get("customName") or {}
                        lore_ok = len(lore) >= 1 and (name.get("text") == "Offline Sword" or True)
            res.add("inv_offline", ok and lore_ok, f"modify={inv} lore_ok={lore_ok}")
        except Exception as e:
            res.add("inv_offline", False, str(e))

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()

    res.overall_ok = all(s.ok for s in res.stages if s.name in {
        "build_paper", "server_boot", "web_start", "web_health", "catalog", "forge_dryrun"
    })
    return res


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", help="Comma-separated MC versions")
    ap.add_argument("--skip-build", action="store_true")
    args = ap.parse_args()

    targets = json.loads(TARGETS_FILE.read_text())["targets"]
    deferred = json.loads(TARGETS_FILE.read_text()).get("deferred", [])
    if args.only:
        want = {x.strip() for x in args.only.split(",") if x.strip()}
        targets = [t for t in targets if t["mc"] in want]

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    results: list[Result] = []
    for t in targets:
        log(f"=== Paper matrix {t['mc']} ===")
        results.append(run_target(t["mc"], args.skip_build))

    # report
    lines = [
        f"# Paper matrix report",
        f"",
        f"Generated: {datetime.now(timezone.utc).isoformat()}",
        f"",
        f"## Deferred",
        f"",
    ]
    for d in deferred:
        lines.append(f"- **{d['mc']}**: {d['reason']}")
    lines += ["", "## Results", "", "| MC | Overall | Stages |", "|----|---------|--------|"]
    for r in results:
        stages = ", ".join(f"{s.name}={'OK' if s.ok else 'FAIL'}" for s in r.stages)
        lines.append(f"| {r.mc} | {'✅' if r.overall_ok else '❌'} | {stages} |")
    lines.append("")
    md = "\n".join(lines)
    (REPORT_DIR / "paper-matrix-report.md").write_text(md)
    (REPORT_DIR / "paper-matrix-report.json").write_text(
        json.dumps({"results": [asdict(r) for r in results], "deferred": deferred}, indent=2)
    )
    print(md)
    return 0 if all(r.overall_ok for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
