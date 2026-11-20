"""公開の音声データ FLEURS（CC BY 4.0）の2言語の文を、2人の会話に見立てて1本の WAV（16kHz・モノラル・16bit）にする
（docs/手順/開発環境.md「見立ての会話を作る」）。作者の声を録らずに、会話翻訳の区切りを変更の前後で比べるための試験音声。

使い方:
    python tools/make-conv.py <FLEURSの置き場> <出力の頭> [--seed 1] [--phrases 20] [--overlap 0.5]
                              [--langs ja_jp ko_kr] [--keep-silence]

- 置き場に `<言語>/dev.tsv` が無ければ、Hugging Face の google/fleurs から dev の音声を先頭の 60 文だけ取り出して置く
  （アーカイブを流し読みし、全部はダウンロードしない）。
- 1つ目の言語を「自分」、2つ目を「相手」とし、多くは交代、4回に1回ほど同じ人が続ける。交代の間は 0.3〜1.2 秒、
  同じ人が続けるときは 0.6〜1.4 秒。
- FLEURS の文は前に平均 1.5 秒・後ろに平均 2 秒の無音を含むので、前後の無音を落としてから並べる（落とさないと、交代の
  間が無音の分だけ延び、実際の会話より間延びする）。--keep-silence で落とさない。
- --overlap（0〜1）を渡すと、交代のときにその割合で、次の人が前の人の文の終わりの 0.3〜1.0 秒に重ねて話し始める
  （2人の声を足す）。重ねるときは2人の声の大きさをそろえる。正解の文は全文のまま（前の人は文を言い切っている）。

出力（音声と、文の中身を含むので Git に入れない置き場 tools/testdata/conversation/ に置く）:
- `<出力の頭>.wav`
- `<出力の頭>.truth.txt`：正解の区切り（`tools/score-conv.py` の形。1文を1単位とする）
- `<出力の頭>.reference.tsv`：0.25 秒ごとの声の言語（`tools/score-speaker.py` の基準の形。声が重なった区間は「-」で採点しない）
"""
import argparse
import array
import csv
import math
import os
import random
import struct
import sys
import tarfile
import urllib.request
import wave

RATE = 16000
FLEURS = "https://huggingface.co/datasets/google/fleurs/resolve/main/data/{lang}/{path}"
FETCH_CLIPS = 60

sys.stdout.reconfigure(encoding="utf-8")


def fetch(root, lang):
    """FLEURS の [lang] の dev の文を、先頭から [FETCH_CLIPS] 個だけ取り出して置く。"""
    out = os.path.join(root, lang)
    os.makedirs(out, exist_ok=True)
    urllib.request.urlretrieve(FLEURS.format(lang=lang, path="dev.tsv"), os.path.join(out, "dev.tsv"))
    taken = 0
    with urllib.request.urlopen(FLEURS.format(lang=lang, path="audio/dev.tar.gz")) as r, tarfile.open(fileobj=r, mode="r|gz") as tf:
        for m in tf:
            if not m.isfile() or not m.name.endswith(".wav"):
                continue
            with open(os.path.join(out, os.path.basename(m.name)), "wb") as f:
                f.write(tf.extractfile(m).read())
            taken += 1
            if taken >= FETCH_CLIPS:
                break
    print(lang, "取り出した文", taken)


def read_wav(path):
    """16kHz・モノラルの WAV（16bit か 32bit float）を -1〜1 の値で読む。"""
    b = open(path, "rb").read()
    assert b[:4] == b"RIFF" and b[8:12] == b"WAVE", path
    i, fmt, data = 12, None, None
    while i < len(b):
        cid, size = b[i:i + 4], struct.unpack("<I", b[i + 4:i + 8])[0]
        body = b[i + 8:i + 8 + size]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", body[:16])
        if cid == b"data":
            data = body
        i += 8 + size + (size & 1)
    tag, ch, rate, _, _, bits = fmt
    assert ch == 1 and rate == RATE, (path, fmt)
    if tag == 3 and bits == 32:
        return array.array("f", data)
    if tag == 1 and bits == 16:
        return array.array("f", (s / 32768 for s in array.array("h", data)))
    raise ValueError(f"{path}: {fmt}")


def clips(root, lang):
    """[lang] の文（音声のパスと文字）。"""
    text = {}
    for row in csv.reader(open(os.path.join(root, lang, "dev.tsv"), encoding="utf-8"), delimiter="\t"):
        text[row[1]] = row[2]
    names = sorted(f for f in os.listdir(os.path.join(root, lang)) if f.endswith(".wav"))
    return [(os.path.join(root, lang, f), text.get(f, "")) for f in names]


def trimmed(a, margin=0.2):
    """文の前後の無音を落とす。0.1 秒ごとの RMS が一番大きいところの 10 分の 1 を超える最初と最後の区間を声とし、前後に [margin] 秒残す。"""
    fr = RATE // 10
    rms = [math.sqrt(sum(x * x for x in a[i:i + fr]) / fr) for i in range(0, len(a) - fr + 1, fr)]
    th = max(rms) * 0.1
    first = next(i for i, r in enumerate(rms) if r > th)
    last = len(rms) - 1 - next(i for i, r in enumerate(reversed(rms)) if r > th)
    m = int(margin * RATE)
    return a[max(0, first * fr - m):min(len(a), (last + 1) * fr + m)]


def leveled(a):
    """声のある部分（絶対値の大きい上位半分）の RMS を -20dBFS にそろえる。"""
    loud = sorted(abs(x) for x in a)[len(a) // 2:]
    rms = math.sqrt(sum(x * x for x in loud) / max(1, len(loud)))
    g = 0.1 / rms if rms > 0 else 1.0
    return array.array("f", (x * g for x in a))


def main():
    p = argparse.ArgumentParser(description="FLEURS の文を2人の会話に見立てた試験音声を作る")
    p.add_argument("root", help="FLEURS の置き場（無ければ取り出す）")
    p.add_argument("out", help="出力の頭（.wav・.truth.txt・.reference.tsv を足す）")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--phrases", type=int, default=20, help="文の数")
    p.add_argument("--overlap", type=float, default=0.0, help="交代のときに重ねて話し始める割合（0〜1）")
    p.add_argument("--langs", nargs=2, default=["ja_jp", "ko_kr"], help="自分と相手の言語（FLEURS の名前）")
    p.add_argument("--keep-silence", action="store_true", help="文の前後の無音を落とさない")
    args = p.parse_args()

    rng = random.Random(args.seed)
    me, partner = args.langs
    for lang in args.langs:
        if not os.path.exists(os.path.join(args.root, lang, "dev.tsv")):
            fetch(args.root, lang)
    pool = {lang: clips(args.root, lang) for lang in args.langs}
    for v in pool.values():
        rng.shuffle(v)

    mix = array.array("f")
    voice = []  # 声の区間 [始まり, 終わり, 言語]
    truth = []

    def put(a, at):
        end = at + len(a)
        if len(mix) < end:
            mix.extend([0.0] * (end - len(mix)))
        for k, x in enumerate(a):
            mix[at + k] += x
        return end

    cursor = RATE
    speaker = me
    used = {lang: 0 for lang in args.langs}
    overlaps = 0
    for i in range(args.phrases):
        path, text = pool[speaker][used[speaker] % len(pool[speaker])]
        used[speaker] += 1
        a = read_wav(path)
        if not args.keep_silence:
            a = trimmed(a)
        if args.overlap > 0:
            a = leveled(a)
        end = put(a, cursor)
        voice.append((cursor, end, speaker.split("_")[0]))
        truth.append(("自分" if speaker == me else "相手", text))
        same = rng.random() < 0.25
        if same:
            cursor = end + int(rng.uniform(0.6, 1.4) * RATE)
        elif i + 1 < args.phrases and rng.random() < args.overlap:
            cursor = end - int(rng.uniform(0.3, 1.0) * RATE)
            overlaps += 1
        else:
            cursor = end + int(rng.uniform(0.3, 1.2) * RATE)
        if not same:
            speaker = partner if speaker == me else me
    put([0.0] * RATE, max(cursor, len(mix)))

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with wave.open(args.out + ".wav", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(array.array("h", (int(max(-1.0, min(1.0, x)) * 32767) for x in mix)).tobytes())
    with open(args.out + ".truth.txt", "w", encoding="utf-8", newline="\n") as f:
        note = f"。交代のうち{overlaps}回は、前の人の文の終わりに重ねて次の人が話し始める" if overlaps else ""
        f.write(f"# FLEURS（CC BY 4.0）の dev の文を会話に見立ててつないだもの。自分＝{me}、相手＝{partner}{note}\n")
        for who, t in truth:
            f.write(f"{who}\t{t}\n")
    with open(args.out + ".reference.tsv", "w", encoding="utf-8", newline="\n") as f:
        step = RATE // 4
        for t in range(0, len(mix), step):
            e = min(len(mix), t + step)
            langs = {lang for a, b, lang in voice if a < e and b > t}
            f.write(f"{t / RATE:.2f}\t{e / RATE:.2f}\t{langs.pop() if len(langs) == 1 else '-'}\n")
    print(args.out, f"{len(mix) / RATE:.0f}秒", len(truth), "文", f"重ねた交代{overlaps}回")


if __name__ == "__main__":
    main()
