"""アプリに同梱するライセンス文のうち、依存の版から機械で集められるものを作り直す（docs/手順/開発環境.md「ライセンス文を集め直す」）。

作るもの（app/src/main/assets/licenses/ に書く。どれもGitに入れる）：
  maven.txt                         正式版に入る Maven の依存（版つき）と、POM に書かれたライセンス名の一覧
  google-third-party.txt            Google のライブラリ（ML Kit・Play services など）が AAR に同梱する第三者のライセンス文
  onnxruntime-third-party-notices.txt  ONNX Runtime の第三者の告知（gradle/libs.versions.toml の版のタグから取る）

手で書くもの（llama.cpp・KleidiAI・sherpa-onnx に組み込まれた第三者のコード・libc++ など）は同じフォルダーにあり、
この道具は触らない。依存の版を変えたら、この道具を流し、手で書くものも版に合っているかを見る。

使い方（リポジトリの直下で）：
  python tools/collect-licenses.py
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "licenses")
CACHE = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1")


def runtime_dependencies():
    """正式版の実行時の依存（group:name:version）。Gradle が解決した版を使う。"""
    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    out = subprocess.run(
        [gradlew, ":app:dependencies", "--configuration", "releaseRuntimeClasspath", "-q"],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", check=True,
    ).stdout
    deps = set()
    for line in out.splitlines():
        m = re.search(r"[+\\]--- ([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?", line)
        if m:
            group, name, version, resolved = m.groups()
            deps.add((group, name, resolved or version))
        m = re.search(r"[+\\]--- ([\w.\-]+):([\w.\-]+) -> ([\w.\-]+)", line)
        if m:
            deps.add(m.groups())
    return sorted(deps)


def artifact_files(group, name, version):
    base = os.path.join(CACHE, group, name, version)
    files = []
    if os.path.isdir(base):
        for h in os.listdir(base):
            for f in os.listdir(os.path.join(base, h)):
                files.append(os.path.join(base, h, f))
    return files


# POM にライセンスの記載が無く、親の POM で決まっているもの（親の POM を確かめて書いた）
KNOWN = {
    "com.google.auto.value:auto-value-annotations": "Apache-2.0（親の POM の記載）",
    "com.google.guava:listenablefuture": "Apache-2.0（親の POM の記載）",
    "javax.inject:javax.inject": "Apache-2.0（プロジェクトの記載）",
}


def pom_licenses(files):
    for f in files:
        if f.endswith(".pom"):
            try:
                tree = ET.parse(f)
            except ET.ParseError:
                return []
            ns = {"m": "http://maven.apache.org/POM/4.0.0"}
            found = []
            for lic in tree.getroot().findall(".//m:licenses/m:license", ns):
                n = lic.findtext("m:name", default="", namespaces=ns).strip()
                u = lic.findtext("m:url", default="", namespaces=ns).strip()
                found.append(f"{n}（{u}）" if u else n)
            return found
    return []


def google_third_party(files):
    """AAR の third_party_licenses.json（名前 → 文の位置）と .txt から、名前ごとの文を取り出す。"""
    for f in files:
        if not f.endswith(".aar"):
            continue
        with zipfile.ZipFile(f) as z:
            names = z.namelist()
            if "third_party_licenses.json" not in names or "third_party_licenses.txt" not in names:
                return {}
            index = json.loads(z.read("third_party_licenses.json").decode("utf-8"))
            text = z.read("third_party_licenses.txt")
            return {k: text[v["start"]:v["start"] + v["length"]].decode("utf-8", "replace") for k, v in index.items()}
    return {}


def onnxruntime_version():
    with open(os.path.join(ROOT, "gradle", "libs.versions.toml"), encoding="utf-8") as f:
        return re.search(r'^onnxruntime = "([^"]+)"', f.read(), re.M).group(1)


def main():
    os.makedirs(OUT, exist_ok=True)
    deps = runtime_dependencies()
    if not deps:
        sys.exit("依存の一覧を読めませんでした")
    listing = ["正式版に含まれる Maven の依存と、そのライセンス（POM の記載）", "", "この一覧は tools/collect-licenses.py が作る。", ""]
    third = {}
    for group, name, version in deps:
        files = artifact_files(group, name, version)
        lic = pom_licenses(files) or [KNOWN.get(f"{group}:{name}", "")]
        if not lic[0]:
            sys.exit(f"{group}:{name}:{version} のライセンスが分かりません。確かめて KNOWN に足してください")
        listing.append(f"{group}:{name}:{version}")
        listing.append("  " + "／".join(lic))
        third.update(google_third_party(files))
    with open(os.path.join(OUT, "maven.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(listing) + "\n")

    body = [
        "Google のライブラリ（ML Kit・Play services など）が使っている第三者のソフトウェアと、そのライセンス文",
        "",
        "この文は各ライブラリの AAR に同梱された third_party_licenses から tools/collect-licenses.py が集める。",
        "",
    ]
    for key in sorted(third, key=str.lower):
        body += ["=" * 40, key, "=" * 40, third[key].strip(), ""]
    with open(os.path.join(OUT, "google-third-party.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(body) + "\n")

    version = onnxruntime_version()
    url = f"https://raw.githubusercontent.com/microsoft/onnxruntime/v{version}/ThirdPartyNotices.txt"
    with urllib.request.urlopen(url) as r:
        notices = r.read().decode("utf-8")
    with open(os.path.join(OUT, "onnxruntime-third-party-notices.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"ONNX Runtime {version} の第三者の告知（{url}）\n\n" + notices.replace("\r\n", "\n"))

    print(f"Maven の依存 {len(deps)} 件、Google の第三者のライセンス {len(third)} 件、ONNX Runtime {version} の告知を書いた")


if __name__ == "__main__":
    main()
