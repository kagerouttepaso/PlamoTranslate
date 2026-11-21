package app.plamotranslate.desktop

import app.plamotranslate.pipeline.conversation.ConversationSession

internal data class SubtitleLine(val original: String, val translation: String)

/** 途中・確定のどちらでも、同じ発話の原文と訳を組にして最も新しい1件を出す。 */
internal fun playbackSubtitle(session: ConversationSession): SubtitleLine? =
    session.listeningCards.lastOrNull { it.text.isNotBlank() }?.let { SubtitleLine(it.text, it.translation) }
        ?: session.turns.lastOrNull()?.let { SubtitleLine(it.original, it.translation) }
