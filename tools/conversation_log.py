"""会話翻訳の記録（端末の logcat と、PC のリプレイの結果）から、確定したカードを読む。採点の道具が共有する1か所。

カードを確定した行「確定（理由）始め秒〜終わり秒: [言語] 文 ／ 直前の途中 …」は `SpeechSegmenter.finishIfReady` が書く。
行の形の正本は [CARD_CLOSED] で、Kotlin の単体試験（`SpeechSegmenterTest` の「確定の行は採点の道具が読める形で書く」）が
この正規表現を読んで実際の行に当て、形が食い違えば落ちる。端末の logcat（タグ PlamoTranslate。「詳しい記録を残す」が
オンのとき、頭に「会話の認識: 」が付く）と、PC のリプレイ（`ConversationReplayTest.replay` の結果の「# 区切りの経過」）の
どちらにも同じ行が入る。
"""
import re

#: 話した人の操作や間による区切りの理由（`SpeechSegmenter` の close・cutAt に渡す理由）。これ以外は自動の判断。
PERSON_REASONS = ("話し終わり", "手で区切り", "停止")
#: カードを確定した行。Java の正規表現でもそのまま使えるよう、名前付きの組は使わない（組：理由・始め・終わり・言語・文）。
#: 理由は1段の括弧を含みうる（「話し手の交代（文：ja→en）」）。言語はカードの表示の言語。
CARD_CLOSED = r"確定（((?:[^（）]|（[^（）]*）)*)）([\d.]+)秒〜([\d.]+)秒: \[([^\]]*)\] (.*?) ／ 直前の途中"
_CARD = re.compile(CARD_CLOSED)


def read_cards(path):
    """確定したカード（文の空のカードを除く）を、記録の順に返す。各カードは reason・start・end・lang・text を持つ。"""
    cards = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = _CARD.search(line)
        if not m:
            continue
        reason, start, end, lang, text = m.group(1), float(m.group(2)), float(m.group(3)), m.group(4), m.group(5).strip()
        if not text:
            continue
        cards.append({"reason": reason, "start": start, "end": end, "lang": lang, "text": text})
    return cards
