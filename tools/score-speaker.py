"""会話翻訳のカードの話し手を、録音から作った言語の時系列に照らして採点する（docs/手順/開発環境.md「会話の録音をリプレイする」）。

使い方:
    python tools/score-speaker.py <基準.tsv> <リプレイの結果.txt> [<リプレイの結果.txt> ...]

- 基準：`ConversationReplayTest.reference` が書く「始まり<TAB>終わり<TAB>言語」（0.25秒ごとの声の言語。声が無ければ `-`）。
  録音の全体を見通した Whisper tiny の判定で、正解そのものではないが、区切りの判断の前と後を同じ基準で比べられる。
- 会話の記録：カードを確定した行を読む（読み方は `tools/conversation_log.py`）。PC のリプレイ（`ConversationReplayTest.replay`
  の結果）と、端末で録音を流したときの logcat のどちらでもよい。

数えるもの（1枚のカード＝1人の話し手の1言語が守れているか）:
- 言語の違う声：カードの中の声のうち、基準の言語がカードの言語と違う時間（混ざったカードと、言語を誤ったカード）
- 交代：基準で言語が代わった位置（前後とも [MIN_RUN] 秒以上続く声）に、カードの切れ目があるか。
  間（声の無いところ）にあれば遅れ0、声の中なら基準の交代からのずれ。[MAX_LATE] 秒より遅いか無ければ取りこぼし
- 幻：音声認識モデルが作り出す言葉（「system」など）の数
"""
import re
import sys

from conversation_log import read_cards

#: 交代とみなす、前後の言語が続く最短の声の長さ（秒）。基準の揺れを交代と数えない。
MIN_RUN = 1.5
#: 交代からこれより遅い切れ目は、取りこぼしと数える（秒）。
MAX_LATE = 3.0
#: 交代より前に切った切れ目も、この範囲なら同じ交代の切れ目とみなす（秒）。
MAX_EARLY = 1.0
PHANTOM = re.compile(r"(?i)\bsystem\b|システム|시스템")


def read_reference(path):
    runs = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            start, end, lang = line.rstrip("\n").split("\t")
            runs.append((float(start), float(end), lang))
    return runs


def overlap(a0, a1, b0, b1):
    return max(0.0, min(a1, b1) - max(a0, b0))


def switches(runs):
    """基準の言語の交代：声の言語の並び（間は飛ばす）で、前後とも MIN_RUN 秒以上続く言語が代わる位置と、その前の間。"""
    voiced = []
    for start, end, lang in runs:
        if lang == "-":
            continue
        if voiced and voiced[-1][2] == lang:
            voiced[-1] = (voiced[-1][0], end, lang, voiced[-1][3] + end - start)
        else:
            voiced.append((start, end, lang, end - start))
    # 短い揺れを前の言語に吸わせる
    merged = []
    for v in voiced:
        if merged and v[3] < MIN_RUN:
            p = merged[-1]
            merged[-1] = (p[0], v[1], p[2], p[3])
        elif merged and merged[-1][2] == v[2]:
            p = merged[-1]
            merged[-1] = (p[0], v[1], p[2], p[3] + v[3])
        else:
            merged.append(v)
    out = []
    for prev, cur in zip(merged, merged[1:]):
        if prev[3] >= MIN_RUN and cur[3] >= MIN_RUN:
            out.append((prev[1], cur[0], prev[2], cur[2]))
    return out


def score(runs, cards):
    voiced = [(s, e, lang) for s, e, lang in runs if lang != "-"]
    speech = 0.0
    wrong = 0.0
    for c0, c1, clang in ((c["start"], c["end"], c["lang"]) for c in cards):
        for s, e, lang in voiced:
            o = overlap(s, e, c0, c1)
            speech += o
            if lang != clang:
                wrong += o
    edges = sorted({c["start"] for c in cards} | {c["end"] for c in cards})
    found = 0
    delays = []
    missed = []
    sw = switches(runs)
    for gap_start, at, before, after in sw:
        inside_gap = [b for b in edges if gap_start - 0.05 <= b <= at + 0.05]
        if inside_gap:
            found += 1
            delays.append(0.0)
            continue
        near = [b - at for b in edges if -MAX_EARLY <= b - at <= MAX_LATE]
        if near:
            found += 1
            delays.append(min(near, key=abs))
        else:
            missed.append((at, before, after))
    phantoms = sum(len(PHANTOM.findall(c["text"])) for c in cards)
    return {
        "cards": len(cards),
        "speech": speech,
        "wrong": wrong,
        "switches": len(sw),
        "found": found,
        "late": sum(1 for d in delays if d > 0.5),
        "mean_delay": sum(max(d, 0) for d in delays) / len(delays) if delays else 0.0,
        "missed": missed,
        "phantoms": phantoms,
    }


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    runs = read_reference(sys.argv[1])
    for path in sys.argv[2:]:
        r = score(runs, read_cards(path))
        print(f"== {path}")
        print(f"カード {r['cards']}枚、声 {r['speech']:.1f}秒のうち言語の違う声 {r['wrong']:.1f}秒（{r['wrong'] / max(r['speech'], 1e-9):.0%}）")
        print(f"交代 {r['switches']}回：切れ目あり {r['found']}回（0.5秒より遅い {r['late']}回、平均の遅れ {r['mean_delay']:.2f}秒）、取りこぼし {len(r['missed'])}回")
        for at, before, after in r["missed"]:
            print(f"  取りこぼし {at:.2f}秒 {before}→{after}")
        print(f"幻（system など）{r['phantoms']}個")


if __name__ == "__main__":
    main()
