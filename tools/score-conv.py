"""会話翻訳のカードの区切りを、正解の区切りに照らして採点する（docs/設計/会話翻訳.md「動作確認」）。

使い方:
    python tools/score-conv.py <正解.truth.txt> <ログ.txt> [<ログ.txt> ...]

- 正解：1行が1つのまとまり（カードにしてよい単位）。「話した人<TAB>文」。# で始まる行と「（?）」は無視する。
  まとまりの中の句点・疑問符は文の終わりとして扱う（まとまりの中で文の終わりで切るのは許す）。
- ログ：デバッグ版の logcat（タグ PlamoTranslate）。「会話の認識: 区切り（理由）始め〜終わり秒: 確定 [言語] 文」の行を読む。
  録音を `--es wav` で流し直したときのもの。

カードの文と正解をつないだ文字列どうしを、文字（文字と数字だけ）で並べ、カードの切れ目が正解のどこに当たるかを求める。
数えるもの：
- 切れ目：まとまりの切れ目で切った／まとまりの中の文の終わりで切った／文の途中で切った
- まとまりの切れ目のうち、切らなかったもの（2つのまとまりが1枚になった）
- 長すぎるカード（[MAX_CHARS] 文字より多い）、断片のカード（[MIN_CHARS] 文字より少ない）
- 文字の誤りの割合（正解に対する編集距離）、切れ目の近くで消えた文字

ログと正解は会話の中身を含むので、Git に入れない置き場（tools/testdata/conversation/）に置く。
"""
import re
import sys

#: 対面の面でスクロールせずに読める長さ（主の文26spで6〜7行）。文字と数字だけで数える。
MAX_CHARS = 90
#: これより短いカードは断片（1〜2語）とみなす。
MIN_CHARS = 5
#: 切れ目が正解の切れ目に一致したとみなすずれ（文字）。認識の揺れで数文字ずれるため。
TOLERANCE = 3

CUT = re.compile(r"会話の認識: 区切り（(?P<reason>[^）]*(?:）[^）]*)?)）(?P<start>[\d.]+)〜(?P<end>[\d.]+)秒: 確定 \[[^\]]*\] (?P<text>.*?) ／ 直前の途中")
SENTENCE_END = set("。？?！!")


def norm_chars(text):
    """文字と数字だけを残し、元の文の何文字目かも返す。"""
    out = []
    for i, c in enumerate(text):
        if c.isalnum():
            out.append((c.lower(), i))
    return out


def read_truth(path):
    units = []
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        text = line.split("\t", 1)[-1].replace("（?）", "").replace("(?)", "")
        units.append(text)
    return units


def read_cards(path):
    cards = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = CUT.search(line)
        if not m:
            continue
        text = m.group("text").strip()
        if not text:
            continue
        cards.append({"reason": m.group("reason"), "start": float(m.group("start")), "end": float(m.group("end")), "text": text})
    return cards


def align(s, t):
    """s の各位置が t のどこに当たるかと、t の各文字が s に残ったか（消えていないか）と、編集距離を返す。"""
    n, m = len(s), len(t)
    prev = list(range(m + 1))
    back = [bytearray(m + 1) for _ in range(n + 1)]  # 0=対応 1=s だけ 2=t だけ
    for j in range(1, m + 1):
        back[0][j] = 2
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        back[i][0] = 1
        si = s[i - 1]
        for j in range(1, m + 1):
            sub = prev[j - 1] + (0 if si == t[j - 1] else 1)
            dele = prev[j] + 1
            ins = cur[j - 1] + 1
            if sub <= dele and sub <= ins:
                cur[j], back[i][j] = sub, 0
            elif dele <= ins:
                cur[j], back[i][j] = dele, 1
            else:
                cur[j], back[i][j] = ins, 2
        prev = cur
    distance = prev[m]
    s_to_t = [0] * (n + 1)
    kept = [False] * m
    i, j = n, m
    s_to_t[n] = m
    while i > 0 or j > 0:
        b = back[i][j]
        if i > 0 and j > 0 and b == 0:
            if s[i - 1] == t[j - 1]:
                kept[j - 1] = True
            i, j = i - 1, j - 1
        elif i > 0 and (j == 0 or b == 1):
            i -= 1
        else:
            j -= 1
        s_to_t[i] = j if s_to_t[i] == 0 else s_to_t[i]
    # s の位置 i（i 文字目の前）を、t の位置に写す：前の文字の対応の直後
    pos = [0] * (n + 1)
    i, j = n, m
    pos[n] = m
    while i > 0 or j > 0:
        b = back[i][j]
        if i > 0 and j > 0 and b == 0:
            i, j = i - 1, j - 1
            pos[i] = j
        elif i > 0 and (j == 0 or b == 1):
            i -= 1
            pos[i] = j
        else:
            j -= 1
    return pos, kept, distance


def score(truth_path, log_path):
    units = read_truth(truth_path)
    cards = read_cards(log_path)
    t_chars, unit_ends, sentence_ends = [], [], []
    for u in units:
        chars = norm_chars(u)
        base = len(t_chars)
        t_chars += [c for c, _ in chars]
        for k, (_, orig) in enumerate(chars):
            nxt = chars[k + 1][1] if k + 1 < len(chars) else len(u)
            if any(ch in SENTENCE_END for ch in u[orig + 1:nxt]) and k + 1 < len(chars):
                sentence_ends.append(base + k + 1)
        unit_ends.append(len(t_chars))
    s_chars, card_ends = [], []
    for c in cards:
        c["len"] = len(norm_chars(c["text"]))
        s_chars += [ch for ch, _ in norm_chars(c["text"])]
        card_ends.append(len(s_chars))
    pos, kept, distance = align(s_chars, t_chars)

    def near(p, ends):
        return min((abs(p - e) for e in ends), default=10 ** 9) <= TOLERANCE

    boundaries = [pos[e] for e in card_ends[:-1]]
    kinds = {"まとまりの切れ目": 0, "まとまりの中の文の終わり": 0, "文の途中": 0}
    mid = []
    for k, p in enumerate(boundaries):
        if near(p, unit_ends[:-1]):
            kinds["まとまりの切れ目"] += 1
        elif near(p, sentence_ends):
            kinds["まとまりの中の文の終わり"] += 1
        else:
            kinds["文の途中"] += 1
            t = "".join(t_chars)
            mid.append(f"{cards[k]['reason']}：…{t[max(0, p - 10):p]}｜{t[p:p + 10]}…")
    missed = [e for e in unit_ends[:-1] if not near(e, boundaries)]
    lost_near = sum(1 for p in boundaries for j in range(max(0, p - TOLERANCE), min(len(kept), p + TOLERANCE)) if not kept[j])
    too_long = [c for c in cards if c["len"] > MAX_CHARS]
    fragments = [c for c in cards if c["len"] < MIN_CHARS]
    reasons = {}
    for c in cards:
        r = re.sub(r"（.*", "", c["reason"])
        reasons[r] = reasons.get(r, 0) + 1

    print(f"== {log_path}")
    print(f"カード {len(cards)} 枚（正解のまとまり {len(units)}）、区切りの理由 {reasons}")
    print(f"切れ目 {len(boundaries)}：" + "、".join(f"{k} {v}" for k, v in kinds.items()))
    print(f"まとまりの切れ目で切らなかった {len(missed)}（2つのまとまりが1枚になった）")
    print(f"長すぎるカード（{MAX_CHARS}文字超） {len(too_long)}、断片のカード（{MIN_CHARS}文字未満） {len(fragments)}")
    print(f"文字の誤りの割合 {distance / max(1, len(t_chars)):.1%}、切れ目の近くで消えた文字 {lost_near}")
    for line in mid:
        print(f"  文の途中で切った：{line}")
    for c in too_long:
        print(f"  長すぎる：{c['len']}文字 {c['text'][:20]}…")
    for c in fragments:
        print(f"  断片：{c['text']}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    for log in sys.argv[2:]:
        score(sys.argv[1], log)
