"""会話翻訳のカードの区切りを、正解の区切りに照らして採点する（docs/設計/会話翻訳.md「動作確認」）。

使い方:
    python tools/score-conv.py <正解.truth.txt> <ログ.txt> [<ログ.txt> ...]

- 正解：1行が1つの単位（カードにしてよい単位）。「話した人<TAB>文」。# で始まる行と「（?）」は無視する。
  単位の中の句点・疑問符は文の終わりとして扱う（単位の中で文の終わりで切るのは許す）。
- ログ：カードを確定した行「確定（理由）始め秒〜終わり秒: [言語] 文 ／ 直前の途中 …」を読む（`SpeechSegmenter` が書く）。
  端末の logcat（タグ PlamoTranslate。「詳しい記録を残す」がオンのとき、頭に「会話の認識: 」が付く）と、
  PC でのリプレイ（`ConversationReplayTest.replay` の結果の「# 区切りの経過」）のどちらでもよい。

カードの文と正解をつないだ文字列どうしを、文字（文字と数字だけ）で並べ、カードの切れ目が正解のどこに当たるかを求める。
正解に区切りを合わせ込むのが目標ではない。目標は細切れにしないこと：自動の判断（話し手の交代・長い発話）で文の途中を
切らないことと、断片のカードを作らないこと。話した人が間を置いて（話し終わり）切れたのは許し、単位が1枚になるのも
読める長さに収まれば許す。
数えるもの：
- 切れ目：単位の切れ目で切った／単位の中の文の終わりで切った／文の途中で切った
- 単位の切れ目のうち、切らなかったもの（2つの単位が1枚になった）
- 長すぎるカード（[MAX_CHARS] 文字より多い）、断片のカード（[MIN_CHARS] 文字より少ない）
- 文字の誤りの割合（正解に対する編集距離）、切れ目の近くで消えた文字

ログと正解は会話の中身を含むので、Git に入れない置き場（tools/testdata/conversation/）に置く。
"""
import re
import sys

# Windows のコンソールでも日本語が化けないよう、出力を UTF-8 にする
sys.stdout.reconfigure(encoding="utf-8")

#: 対面の面でスクロールせずに読める長さ（メインの文26spで6〜7行）。文字と数字だけで数える。
MAX_CHARS = 90
#: これより短いカードは断片（1〜2語）とみなす。
MIN_CHARS = 5
#: 切れ目が正解の切れ目に一致したとみなすずれ（文字）。認識の揺れで数文字ずれるため。
TOLERANCE = 3

#: 話した人の操作や間による区切りの理由（`SpeechSegmenter` の close・cutAt に渡す理由）。これ以外は自動の判断。
PERSON_REASONS = ("話し終わり", "手で区切り", "停止")
#: カードを確定した行。書くのは `SpeechSegmenter.finishIfReady` の trace で、この正規表現が行の形の正本。
#: Kotlin の単体試験（`SpeechSegmenterTest` の「確定の行は採点の道具が読める形で書く」）がこの行を読んで実際の行に当て、
#: 形が食い違えば落ちる。Java の正規表現でもそのまま使えるよう、名前付きの組は使わない（組：理由・始め・終わり・文）。
#: 理由は1段の括弧を含みうる（「話し手の交代（文：ja→en）」）。
CARD_CLOSED = r"確定（((?:[^（）]|（[^（）]*）)*)）([\d.]+)秒〜([\d.]+)秒: \[[^\]]*\] (.*?) ／ 直前の途中"
CUT = re.compile(CARD_CLOSED)
SENTENCE_END = set("。？?！!")
CLAUSE_END = set("、，,")


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
        reason, start, end, text = m.group(1), float(m.group(2)), float(m.group(3)), m.group(4).strip()
        if not text:
            continue
        cards.append({"reason": reason, "start": start, "end": end, "text": text})
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
    t_chars, unit_ends, sentence_ends, clause_ends = [], [], [], []
    for u in units:
        chars = norm_chars(u)
        base = len(t_chars)
        t_chars += [c for c, _ in chars]
        for k, (_, orig) in enumerate(chars):
            nxt = chars[k + 1][1] if k + 1 < len(chars) else len(u)
            between = u[orig + 1:nxt]
            if any(ch in SENTENCE_END for ch in between) and k + 1 < len(chars):
                sentence_ends.append(base + k + 1)
            elif any(ch in CLAUSE_END for ch in between) and k + 1 < len(chars):
                clause_ends.append(base + k + 1)
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
    kinds = {"単位の切れ目": 0, "単位の中の文の終わり": 0, "読点（息継ぎ）": 0, "文の途中": 0}
    mid = []
    for k, p in enumerate(boundaries):
        if near(p, unit_ends[:-1]):
            kinds["単位の切れ目"] += 1
        elif near(p, sentence_ends):
            kinds["単位の中の文の終わり"] += 1
        elif near(p, clause_ends):
            kinds["読点（息継ぎ）"] += 1
        else:
            kinds["文の途中"] += 1
            t = "".join(t_chars)
            mid.append(f"{cards[k]['reason']}：…{t[max(0, p - 10):p]}｜{t[p:p + 10]}…")
    missed = [e for e in unit_ends[:-1] if not near(e, boundaries)]
    lost_near = sum(1 for p in boundaries for j in range(max(0, p - TOLERANCE), min(len(kept), p + TOLERANCE)) if not kept[j])
    too_long = [c for c in cards if c["len"] > MAX_CHARS]
    # カードが正解のいくつの単位にまたがるか（3つ以上は、1〜2文の目標を超える）
    spans = [0] + boundaries + [len(t_chars)]
    wide = 0
    for k in range(len(spans) - 1):
        inside = sum(1 for e in unit_ends[:-1] if spans[k] + TOLERANCE < e < spans[k + 1] - TOLERANCE)
        if inside >= 2:
            wide += 1
    fragments = [c for c in cards if c["len"] < MIN_CHARS]
    reasons = {}
    for c in cards:
        r = re.sub(r"（.*", "", c["reason"])
        reasons[r] = reasons.get(r, 0) + 1

    print(f"== {log_path}")
    print(f"カード {len(cards)} 枚（正解の単位 {len(units)}）、区切りの理由 {reasons}")
    print(f"切れ目 {len(boundaries)}：" + "、".join(f"{k} {v}" for k, v in kinds.items()))
    # 話した人が無音で間を置いた（話し終わり）・ボタンで区切った・止めたのは、話した人の区切りとして許す。目標は、
    # 自動の判断（話し手の交代・長い発話）で文の途中を切らないことと、断片を作らないこと（正解に合わせ込むことではない）
    own = sum(1 for line in mid if not line.startswith(PERSON_REASONS))
    print(f"自動の判断で文の途中を切った {own}（話した人の間・ボタンによる {len(mid) - own} は許す）")
    print(f"単位の切れ目で切らなかった {len(missed)}（2つの単位が1枚になった）")
    print(f"長すぎるカード（{MAX_CHARS}文字超） {len(too_long)}、3つ以上の単位にまたがるカード {wide}、断片のカード（{MIN_CHARS}文字未満） {len(fragments)}")
    lens = sorted(c["len"] for c in cards)
    print(f"カードの長さ（文字） 中央 {lens[len(lens) // 2] if lens else 0}、最大 {lens[-1] if lens else 0}")
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
