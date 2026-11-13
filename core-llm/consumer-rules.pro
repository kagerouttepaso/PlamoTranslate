# ネイティブ（plamo_llm.cpp）から名前で呼ばれる受け口。縮小を有効にしても消さない
# （onNativeLog が消えるとネイティブのログが logcat だけに戻り、JobCallback が消えると翻訳が止まる）
-keep class app.plamotranslate.llm.LlamaNative { *; }
-keep class app.plamotranslate.llm.LlamaNative$JobCallback { *; }
-keep class * implements app.plamotranslate.llm.LlamaNative$JobCallback { *; }
