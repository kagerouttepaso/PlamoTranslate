#define NOMINMAX
#include <jni.h>
#include <fpdf_annot.h>
#include <fpdf_doc.h>
#include <fpdf_edit.h>
#include <fpdf_save.h>
#include <fpdf_text.h>
#include <fpdfview.h>
#include <hb-subset.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {
struct Pdfium {
    Pdfium() { FPDF_InitLibrary(); }
    ~Pdfium() { FPDF_DestroyLibrary(); }
};

// 文書ごとのスレッドからの呼び出しも、PDFium 全体では直列にする。
std::mutex pdfium_mutex;

void init_pdfium() {
    static Pdfium library;
}

int read_block(void * handle, unsigned long offset, unsigned char * bytes, unsigned long length) {
    auto * file = static_cast<FILE *>(handle);
    return _fseeki64(file, offset, SEEK_SET) == 0 && std::fread(bytes, 1, length, file) == length;
}

struct Document {
    std::unique_ptr<FILE, decltype(&std::fclose)> file{nullptr, std::fclose};
    FPDF_FILEACCESS access{};
    std::vector<jbyte> bytes;
    std::unique_ptr<fpdf_document_t__, decltype(&FPDF_CloseDocument)> pdf{nullptr, FPDF_CloseDocument};
    std::unique_ptr<fpdf_font_t__, decltype(&FPDFFont_Close)> regular{nullptr, FPDFFont_Close};
    std::unique_ptr<fpdf_font_t__, decltype(&FPDFFont_Close)> bold{nullptr, FPDFFont_Close};
};

using Page = std::unique_ptr<fpdf_page_t__, decltype(&FPDF_ClosePage)>;
using TextPage = std::unique_ptr<fpdf_textpage_t__, decltype(&FPDFText_ClosePage)>;
using Bitmap = std::unique_ptr<fpdf_bitmap_t__, decltype(&FPDFBitmap_Destroy)>;

FPDF_DOCUMENT document(jlong handle) {
    return reinterpret_cast<Document *>(handle)->pdf.get();
}

template <typename T>
void append(std::vector<jbyte>& bytes, T value) {
    const auto* data = reinterpret_cast<const jbyte*>(&value);
    bytes.insert(bytes.end(), data, data + sizeof(value));
}

// JNI ではページ単位の配列にまとめる。数値は Windows x64 のリトルエンディアン。
struct Packet : std::vector<jbyte> {
    void integer(jint value) { append(*this, value); }
    void number(double value) { append(*this, value); }

    template <typename Char>
    void string(const std::vector<Char>& buffer) {
        const auto bytes = buffer.empty() ? 0 : (buffer.size() - 1) * sizeof(Char);
        integer(static_cast<jint>(bytes));
        if (!bytes) return;
        const auto* start = reinterpret_cast<const jbyte*>(buffer.data());
        insert(end(), start, start + bytes);
    }
};

jbyteArray byte_array(JNIEnv* env, const void* bytes, size_t size) {
    if (size > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    auto out = env->NewByteArray(static_cast<jsize>(size));
    if (out && size) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), static_cast<const jbyte*>(bytes));
    return out;
}

jbyteArray packet_array(JNIEnv* env, const Packet& packet) {
    return byte_array(env, packet.data(), packet.size());
}

std::vector<jbyte> read_bytes(JNIEnv* env, jbyteArray array) {
    std::vector<jbyte> bytes(env->GetArrayLength(array));
    if (!bytes.empty()) env->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), bytes.data());
    return bytes;
}

std::vector<FPDF_WCHAR> wide_string(JNIEnv* env, jstring value) {
    const auto size = env->GetStringLength(value);
    std::vector<FPDF_WCHAR> chars(static_cast<size_t>(size) + 1);
    if (size) env->GetStringRegion(value, 0, size, chars.data());
    return chars;
}

struct TextOnPage {
    Page page;
    TextPage text;
    TextOnPage(jlong handle, int index)
        : page(FPDF_LoadPage(document(handle), index), FPDF_ClosePage),
          text(page ? FPDFText_LoadPage(page.get()) : nullptr, FPDFText_ClosePage) {}
};

struct Box {
    double left{}, bottom{}, right{}, top{};

    void pack(Packet& out) const {
        out.number(left);
        out.number(bottom);
        out.number(right);
        out.number(top);
    }

    FS_RECTF pdf_rect() const {
        return {static_cast<float>(left), static_cast<float>(top),
                static_cast<float>(right), static_cast<float>(bottom)};
    }
};

struct Color {
    unsigned int red{}, green{}, blue{}, alpha{255};

    jint argb() const {
        return static_cast<jint>((alpha << 24) | (red << 16) | (green << 8) | blue);
    }

    bool apply(FPDF_PAGEOBJECT object) const {
        return FPDFPageObj_SetFillColor(object, red, green, blue, alpha);
    }
};

using Annotation = std::unique_ptr<fpdf_annotation_t__, decltype(&FPDFPage_CloseAnnot)>;

struct Link {
    int index;
    Box box;
    FPDF_DEST dest;
    FPDF_ACTION action;
    std::vector<Box> quads;
};

std::vector<Link> page_links(FPDF_DOCUMENT doc, FPDF_PAGE page) {
    std::vector<Link> links;
    const int count = FPDFPage_GetAnnotCount(page);
    for (int index = 0; index < count; ++index) {
        Annotation annot(FPDFPage_GetAnnot(page, index), FPDFPage_CloseAnnot);
        FS_RECTF rect{};
        if (!annot || FPDFAnnot_GetSubtype(annot.get()) != FPDF_ANNOT_LINK ||
            !FPDFAnnot_GetRect(annot.get(), &rect)) continue;
        const auto link = FPDFAnnot_GetLink(annot.get());
        const auto action = link ? FPDFLink_GetAction(link) : nullptr;
        auto dest = link ? FPDFLink_GetDest(doc, link) : nullptr;
        if (!dest && action && FPDFAction_GetType(action) == PDFACTION_GOTO) {
            dest = FPDFAction_GetDest(doc, action);
        }
        Link item{index, {rect.left, rect.bottom, rect.right, rect.top}, dest, action, {}};
        for (size_t q = 0, n = FPDFAnnot_CountAttachmentPoints(annot.get()); q < n; ++q) {
            FS_QUADPOINTSF quad{};
            if (!FPDFAnnot_GetAttachmentPoints(annot.get(), q, &quad)) continue;
            item.quads.push_back({std::min({quad.x1, quad.x2, quad.x3, quad.x4}),
                std::min({quad.y1, quad.y2, quad.y3, quad.y4}),
                std::max({quad.x1, quad.x2, quad.x3, quad.x4}),
                std::max({quad.y1, quad.y2, quad.y3, quad.y4})});
        }
        links.push_back(std::move(item));
    }
    return links;
}

void pack_destination(Packet& out, FPDF_DOCUMENT doc, FPDF_DEST dest, FPDF_ACTION action) {
    if (dest) {
        const int index = FPDFDest_GetDestPageIndex(doc, dest);
        if (index >= 0) {
            FPDF_BOOL has_x{}, has_y{}, has_zoom{};
            float x{}, y{}, zoom{};
            FPDFDest_GetLocationInPage(dest, &has_x, &has_y, &has_zoom, &x, &y, &zoom);
            unsigned long count{};
            float params[4]{};
            const auto view = FPDFDest_GetView(dest, &count, params);
            if ((view == PDFDEST_VIEW_FITH || view == PDFDEST_VIEW_FITBH) && count > 0) {
                has_y = true;
                y = params[0];
            }
            if ((view == PDFDEST_VIEW_FITV || view == PDFDEST_VIEW_FITBV) && count > 0) {
                has_x = true;
                x = params[0];
            }
            if (view == PDFDEST_VIEW_FITR && count == 4) {
                has_x = has_y = true;
                x = params[0];
                y = params[3];
            }
            out.integer(1);
            out.integer(index);
            const auto none = std::numeric_limits<double>::quiet_NaN();
            out.number(has_x ? x : none);
            out.number(has_y ? y : none);
            out.number(has_zoom ? zoom : none);
            return;
        }
    }
    if (action && FPDFAction_GetType(action) == PDFACTION_URI) {
        std::vector<char> buffer(FPDFAction_GetURIPath(doc, action, nullptr, 0));
        if (!buffer.empty() && FPDFAction_GetURIPath(doc, action, buffer.data(), static_cast<unsigned long>(buffer.size()))) {
            out.integer(2);
            out.string(buffer);
            return;
        }
    }
    out.integer(0);
}

using ObjectPaths = std::unordered_map<FPDF_PAGEOBJECT, std::vector<int>>;

void text_paths(FPDF_PAGE page, FPDF_PAGEOBJECT form, std::vector<int>& path, ObjectPaths& paths) {
    const int count = form ? FPDFFormObj_CountObjects(form) : FPDFPage_CountObjects(page);
    for (int index = 0; index < count; ++index) {
        const auto object = form ? FPDFFormObj_GetObject(form, index) : FPDFPage_GetObject(page, index);
        const int type = FPDFPageObj_GetType(object);
        path.push_back(index);
        if (type == FPDF_PAGEOBJ_TEXT) paths.emplace(object, path);
        if (type == FPDF_PAGEOBJ_FORM) text_paths(page, object, path, paths);
        path.pop_back();
    }
}

struct CharacterStyle {
    jint id{};
    double size{};
    Color color;
};

CharacterStyle pack_style(Packet& out, const ObjectPaths& paths, FPDF_PAGEOBJECT object,
                          FPDF_TEXTPAGE text, int index, jint id) {
    auto path = paths.find(object);
    out.integer(path == paths.end() ? 0 : static_cast<jint>(path->second.size()));
    if (path != paths.end()) {
        for (int part : path->second) out.integer(part);
    }
    std::vector<char> font(FPDFText_GetFontInfo(text, index, nullptr, 0, nullptr));
    if (!font.empty()) FPDFText_GetFontInfo(text, index, font.data(), static_cast<unsigned long>(font.size()), nullptr);
    out.string(font);
    out.integer(FPDFText_GetFontWeight(text, index));

    Color color;
    FPDFText_GetFillColor(text, index, &color.red, &color.green, &color.blue, &color.alpha);
    out.integer(color.argb());
    return {id, FPDFText_GetFontSize(text, index), color};
}

void pack_range_rects(Packet& out, FPDF_TEXTPAGE text, int start, int count) {
    const int size = std::max(0, FPDFText_CountRects(text, start, count));
    out.integer(size);
    for (int i = 0; i < size; ++i) {
        Box box;
        FPDFText_GetRect(text, i, &box.left, &box.top, &box.right, &box.bottom);
        box.pack(out);
    }
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_app_plamotranslate_desktop_PdfNative_open(JNIEnv * env, jobject, jstring path) {
    std::lock_guard guard(pdfium_mutex);
    init_pdfium();
    const auto * chars = env->GetStringChars(path, nullptr);
    if (!chars) return 0;
    std::wstring filename(chars, chars + env->GetStringLength(path));
    env->ReleaseStringChars(path, chars);
    auto doc = std::make_unique<Document>();
    doc->file.reset(_wfopen(filename.c_str(), L"rb"));
    if (!doc->file || _fseeki64(doc->file.get(), 0, SEEK_END) != 0) return 0;
    const auto length = _ftelli64(doc->file.get());
    if (length <= 0 || length > std::numeric_limits<unsigned long>::max()) return 0;
    doc->access = {static_cast<unsigned long>(length), read_block, doc->file.get()};
    doc->pdf.reset(FPDF_LoadCustomDocument(&doc->access, nullptr));
    return doc->pdf ? reinterpret_cast<jlong>(doc.release()) : 0;
}

JNIEXPORT void JNICALL Java_app_plamotranslate_desktop_PdfNative_close(JNIEnv *, jobject, jlong handle) {
    std::lock_guard guard(pdfium_mutex);
    delete reinterpret_cast<Document *>(handle);
}

JNIEXPORT jint JNICALL Java_app_plamotranslate_desktop_PdfNative_pageCount(JNIEnv *, jobject, jlong handle) {
    std::lock_guard guard(pdfium_mutex);
    return FPDF_GetPageCount(document(handle));
}

JNIEXPORT jfloatArray JNICALL Java_app_plamotranslate_desktop_PdfNative_pageSize(JNIEnv * env, jobject, jlong handle, jint index) {
    std::lock_guard guard(pdfium_mutex);
    FS_SIZEF size{};
    if (!FPDF_GetPageSizeByIndexF(document(handle), index, &size)) return nullptr;
    const jfloat result[] = {size.width, size.height};
    auto output = env->NewFloatArray(2);
    if (output) env->SetFloatArrayRegion(output, 0, 2, result);
    return output;
}

JNIEXPORT jintArray JNICALL Java_app_plamotranslate_desktop_PdfNative_render(
    JNIEnv * env, jobject, jlong handle, jint index, jint width, jint height) {
    std::lock_guard guard(pdfium_mutex);
    if (width < 1 || height < 1 || width > 4096 || height > 4096) return nullptr;
    Page page(FPDF_LoadPage(document(handle), index), FPDF_ClosePage);
    if (!page) return nullptr;
    Bitmap bitmap(FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, nullptr, 0), FPDFBitmap_Destroy);
    if (!bitmap || !FPDFBitmap_FillRect(bitmap.get(), 0, 0, width, height, 0xFFFFFFFF)) return nullptr;
    FPDF_RenderPageBitmap(bitmap.get(), page.get(), 0, 0, width, height, 0, FPDF_ANNOT);
    auto output = env->NewIntArray(width * height);
    if (!output) return nullptr;
    const auto * pixels = static_cast<const jint *>(FPDFBitmap_GetBuffer(bitmap.get()));
    const auto stride = FPDFBitmap_GetStride(bitmap.get()) / sizeof(jint);
    if (stride == static_cast<size_t>(width)) {
        env->SetIntArrayRegion(output, 0, width * height, pixels);
    } else {
        for (int row = 0; row < height; ++row) {
            env->SetIntArrayRegion(output, row * width, width, pixels + row * stride);
        }
    }
    return output;
}

JNIEXPORT jfloatArray JNICALL Java_app_plamotranslate_desktop_PdfNative_glyphs(JNIEnv * env, jobject, jlong handle, jint index) {
    std::lock_guard guard(pdfium_mutex);
    Page page(FPDF_LoadPage(document(handle), index), FPDF_ClosePage);
    if (!page) return nullptr;
    TextPage text(FPDFText_LoadPage(page.get()), FPDFText_ClosePage);
    if (!text) return nullptr;
    const int count = FPDFText_CountChars(text.get());
    constexpr int grid = 100000;
    std::vector<jfloat> values;
    for (int character = 0; character < count; ++character) {
        auto code = FPDFText_GetUnicode(text.get(), character);
        if (code > 0 && code < 32) continue;
        if (code == 0 || FPDFText_HasUnicodeMapError(text.get(), character) == 1) code = 0xFFFD;
        double left, right, bottom, top;
        if (!FPDFText_GetCharBox(text.get(), character, &left, &right, &bottom, &top)) continue;
        int first_x, first_y, second_x, second_y;
        if (!FPDF_PageToDevice(page.get(), 0, 0, grid, grid, 0, left, top, &first_x, &first_y) ||
            !FPDF_PageToDevice(page.get(), 0, 0, grid, grid, 0, right, bottom, &second_x, &second_y)) continue;
        values.insert(values.end(), {static_cast<jfloat>(code),
            static_cast<jfloat>(std::min(first_x, second_x)) / grid,
            static_cast<jfloat>(std::min(first_y, second_y)) / grid,
            static_cast<jfloat>(std::max(first_x, second_x)) / grid,
            static_cast<jfloat>(std::max(first_y, second_y)) / grid});
    }
    if (values.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    const auto size = static_cast<jsize>(values.size());
    auto output = env->NewFloatArray(size);
    if (output && size > 0) env->SetFloatArrayRegion(output, 0, size, values.data());
    return output;
}

#define PDF_JNI(name) Java_app_plamotranslate_desktop_pdf_PdfEditingNative_##name

JNIEXPORT jlong JNICALL PDF_JNI(openBytes)(JNIEnv* env, jobject, jbyteArray bytes) {
    std::lock_guard guard(pdfium_mutex);
    init_pdfium();
    auto doc = std::make_unique<Document>();
    doc->bytes = read_bytes(env, bytes);
    if (doc->bytes.empty() || env->ExceptionCheck()) return 0;
    doc->pdf.reset(FPDF_LoadMemDocument64(doc->bytes.data(), doc->bytes.size(), ""));
    return doc->pdf ? reinterpret_cast<jlong>(doc.release()) : 0;
}

JNIEXPORT jboolean JNICALL PDF_JNI(isEncrypted)(JNIEnv*, jobject, jlong handle) {
    std::lock_guard guard(pdfium_mutex);
    return FPDF_GetSecurityHandlerRevision(document(handle)) >= 0;
}

JNIEXPORT jbyteArray JNICALL PDF_JNI(characters)(JNIEnv* env, jobject, jlong handle, jint index) {
    std::lock_guard guard(pdfium_mutex);
    TextOnPage on(handle, index);
    if (!on.text) return nullptr;
    const auto text = on.text.get();
    const int count = FPDFText_CountChars(text);
    if (count < 0) return nullptr;
    ObjectPaths paths;
    std::vector<int> path;
    text_paths(on.page.get(), nullptr, path, paths);
    const auto links = page_links(document(handle), on.page.get());

    std::unordered_map<FPDF_PAGEOBJECT, CharacterStyle> styles;
    Packet style_data, chars;
    CharacterStyle prior{-1, 0, {}};
    chars.integer(count);
    for (int i = 0; i < count; ++i) {
        const bool generated = FPDFText_IsGenerated(text, i) == 1;
        auto code = FPDFText_GetUnicode(text, i);
        const auto object = generated ? nullptr : FPDFText_GetTextObject(text, i);
        const bool unmapped = FPDFText_HasUnicodeMapError(text, i) == 1;
        const bool has_original = unmapped && object;
        if (!code || unmapped) code = 0xFFFD;
        CharacterStyle style = generated ? prior : CharacterStyle{-1, 0, {}};
        if (object) {
            auto cached = styles.find(object);
            if (cached == styles.end()) {
                style = pack_style(style_data, paths, object, text, i, static_cast<jint>(styles.size()));
                styles.emplace(object, style);
            } else {
                style = cached->second;
            }
            FS_MATRIX matrix{};
            if (FPDFText_GetMatrix(text, i, &matrix)) style.size *= std::hypot(matrix.c, matrix.d);
        }
        prior = style;
        Box box;
        double x{}, y{};
        FPDFText_GetCharBox(text, i, &box.left, &box.right, &box.bottom, &box.top);
        FPDFText_GetCharOrigin(text, i, &x, &y);
        int link = -1;
        if (!generated) {
            const double cx = (box.left + box.right) / 2, cy = (box.top + box.bottom) / 2;
            for (const auto& annotation : links) {
                const auto contains = [&](const Box& area) {
                    return cx >= area.left - .5 && cx <= area.right + .5 &&
                        cy >= area.bottom - .5 && cy <= area.top + .5;
                };
                if (annotation.quads.empty() ? contains(annotation.box) :
                    std::any_of(annotation.quads.begin(), annotation.quads.end(), contains)) {
                    link = annotation.index;
                    break;
                }
            }
        }
        chars.integer(static_cast<jint>(code));
        chars.integer(style.id);
        chars.integer((generated ? 1 : 0) | (FPDFText_IsHyphen(text, i) == 1 ? 2 : 0));
        chars.integer(link);
        chars.number(x);
        chars.number(y);
        box.pack(chars);
        chars.number(style.size);
        FS_RECTF loose{};
        chars.number(!has_original ? -1 : FPDFText_GetLooseCharBox(text, i, &loose) ?
            std::max(0.0, static_cast<double>(loose.right) - x) : std::max(0.0, box.right - x));
    }
    Packet out;
    out.integer(static_cast<jint>(styles.size()));
    out.insert(out.end(), style_data.begin(), style_data.end());
    out.insert(out.end(), chars.begin(), chars.end());
    return packet_array(env, out);
}

JNIEXPORT jbyteArray JNICALL PDF_JNI(links)(JNIEnv* env, jobject, jlong handle, jint index) {
    std::lock_guard guard(pdfium_mutex);
    const auto doc = document(handle);
    Page page(FPDF_LoadPage(doc, index), FPDF_ClosePage);
    if (!page) return nullptr;
    const auto links = page_links(doc, page.get());
    Packet out;
    out.integer(static_cast<jint>(links.size()));
    for (const auto& link : links) {
        out.integer(link.index);
        link.box.pack(out);
        pack_destination(out, doc, link.dest, link.action);
        out.integer(static_cast<jint>(link.quads.size()));
        for (const auto& quad : link.quads) quad.pack(out);
    }
    return packet_array(env, out);
}

JNIEXPORT jbyteArray JNICALL PDF_JNI(outline)(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard guard(pdfium_mutex);
    const auto doc = document(handle);
    struct Node { FPDF_BOOKMARK handle; int parent; };
    std::vector<Node> pending{{FPDFBookmark_GetFirstChild(doc, nullptr), -1}};
    std::unordered_set<FPDF_BOOKMARK> seen;
    Packet nodes;
    int count = 0;
    while (!pending.empty()) {
        auto [bookmark, parent] = pending.back();
        pending.pop_back();
        if (!bookmark || !seen.insert(bookmark).second) continue;
        std::vector<FPDF_WCHAR> title(FPDFBookmark_GetTitle(bookmark, nullptr, 0) / sizeof(FPDF_WCHAR));
        if (!title.empty()) FPDFBookmark_GetTitle(bookmark, title.data(), static_cast<unsigned long>(title.size() * sizeof(FPDF_WCHAR)));
        nodes.integer(parent);
        nodes.string(title);
        const auto action = FPDFBookmark_GetAction(bookmark);
        auto dest = FPDFBookmark_GetDest(doc, bookmark);
        if (!dest && action && FPDFAction_GetType(action) == PDFACTION_GOTO) dest = FPDFAction_GetDest(doc, action);
        pack_destination(nodes, doc, dest, action);
        pending.push_back({FPDFBookmark_GetNextSibling(doc, bookmark), parent});
        pending.push_back({FPDFBookmark_GetFirstChild(doc, bookmark), count++});
    }
    Packet out;
    out.integer(count);
    out.insert(out.end(), nodes.begin(), nodes.end());
    return packet_array(env, out);
}

JNIEXPORT jbyteArray JNICALL PDF_JNI(search)(JNIEnv* env, jobject, jlong handle, jint page, jstring query, jint flags) {
    std::lock_guard guard(pdfium_mutex);
    TextOnPage on(handle, page);
    if (!on.text) return nullptr;
    const auto chars = wide_string(env, query);
    using Search = std::unique_ptr<fpdf_schhandle_t__, decltype(&FPDFText_FindClose)>;
    Search search(FPDFText_FindStart(on.text.get(), chars.data(), flags, 0), FPDFText_FindClose);
    if (!search) return nullptr;
    Packet hits;
    int count = 0;
    while (FPDFText_FindNext(search.get())) {
        const int start = FPDFText_GetSchResultIndex(search.get());
        const int size = FPDFText_GetSchCount(search.get());
        hits.integer(start);
        hits.integer(size);
        pack_range_rects(hits, on.text.get(), start, size);
        ++count;
    }
    Packet out;
    out.integer(count);
    out.insert(out.end(), hits.begin(), hits.end());
    return packet_array(env, out);
}

JNIEXPORT jint JNICALL PDF_JNI(characterAt)(JNIEnv*, jobject, jlong handle, jint page, jdouble x, jdouble y, jdouble tolerance) {
    std::lock_guard guard(pdfium_mutex);
    TextOnPage on(handle, page);
    return on.text ? FPDFText_GetCharIndexAtPos(on.text.get(), x, y, tolerance, tolerance) : -1;
}

JNIEXPORT jbyteArray JNICALL PDF_JNI(selection)(JNIEnv* env, jobject, jlong handle, jint index, jint start, jint length) {
    std::lock_guard guard(pdfium_mutex);
    TextOnPage on(handle, index);
    if (!on.text) return nullptr;
    const int count = FPDFText_CountChars(on.text.get());
    if (start < 0 || start > count || length < 0 || length > count - start) return nullptr;
    std::vector<FPDF_WCHAR> chars(static_cast<size_t>(length) + 1);
    if (length) {
        const int written = FPDFText_GetText(on.text.get(), start, length, chars.data());
        if (written <= 0 || written > static_cast<int>(chars.size())) return nullptr;
        chars.resize(written);
    }
    Packet out;
    out.string(chars);
    if (length) pack_range_rects(out, on.text.get(), start, length);
    else out.integer(0);
    return packet_array(env, out);
}

JNIEXPORT jdoubleArray JNICALL PDF_JNI(viewport)(JNIEnv* env, jobject, jlong handle, jint index) {
    std::lock_guard guard(pdfium_mutex);
    Page page(FPDF_LoadPage(document(handle), index), FPDF_ClosePage);
    if (!page) return nullptr;
    double points[6]{};
    if (!FPDF_DeviceToPage(page.get(), 0, 0, 1000, 1000, 0, 0, 0, &points[0], &points[1]) ||
        !FPDF_DeviceToPage(page.get(), 0, 0, 1000, 1000, 0, 1000, 0, &points[2], &points[3]) ||
        !FPDF_DeviceToPage(page.get(), 0, 0, 1000, 1000, 0, 0, 1000, &points[4], &points[5])) return nullptr;
    auto out = env->NewDoubleArray(6);
    if (out) env->SetDoubleArrayRegion(out, 0, 6, points);
    return out;
}

JNIEXPORT jboolean JNICALL PDF_JNI(embedFonts)(JNIEnv* env, jobject, jlong handle, jbyteArray regular, jbyteArray bold) {
    std::lock_guard guard(pdfium_mutex);
    auto& doc = *reinterpret_cast<Document*>(handle);
    const auto load = [&](auto& font, jbyteArray input) {
        if (font) return true;
        const auto bytes = read_bytes(env, input);
        if (bytes.empty() || env->ExceptionCheck()) return false;
        font.reset(FPDFText_LoadFont(doc.pdf.get(), reinterpret_cast<const uint8_t*>(bytes.data()),
            static_cast<uint32_t>(bytes.size()), FPDF_FONT_TRUETYPE, true));
        return static_cast<bool>(font);
    };
    return load(doc.regular, regular) && load(doc.bold, bold);
}
}

namespace {
using PageObject = std::unique_ptr<fpdf_pageobject_t__, decltype(&FPDFPageObj_Destroy)>;

Color unpack_color(double value) {
    const auto argb = static_cast<uint32_t>(static_cast<jint>(value));
    return {(argb >> 16) & 255, (argb >> 8) & 255, argb & 255, argb >> 24};
}

PageObject text_object(FPDF_DOCUMENT doc, FPDF_FONT font, float size, const FPDF_WCHAR* value,
                       const FS_MATRIX& matrix, const Color& color) {
    PageObject object(font ? FPDFPageObj_CreateTextObj(doc, font, size) : nullptr, FPDFPageObj_Destroy);
    if (object && (!FPDFText_SetText(object.get(), value) || !FPDFPageObj_SetMatrix(object.get(), &matrix) ||
        !color.apply(object.get()))) object.reset();
    return object;
}

PageObject original_character(FPDF_DOCUMENT doc, FPDF_TEXTPAGE text, int index, FPDF_PAGEOBJECT original,
                              float scale, float x, float y) {
    float size{};
    FS_MATRIX matrix{};
    Color fill;
    if (!FPDFTextObj_GetFontSize(original, &size) || !FPDFText_GetMatrix(text, index, &matrix)) {
        return {nullptr, FPDFPageObj_Destroy};
    }
    matrix.a *= scale;
    matrix.b *= scale;
    matrix.c *= scale;
    matrix.d *= scale;
    matrix.e = x;
    matrix.f = y;
    FPDFPageObj_GetFillColor(original, &fill.red, &fill.green, &fill.blue, &fill.alpha);
    const uint32_t code = FPDFText_GetUnicode(text, index);
    PageObject object(nullptr, FPDFPageObj_Destroy);
    const auto font = FPDFTextObj_GetFont(original);
    if (FPDFText_HasUnicodeMapError(text, index) == 1) {
        object.reset(font ? FPDFPageObj_CreateTextObj(doc, font, size) : nullptr);
        if (object && (!FPDFText_SetCharcodes(object.get(), &code, 1) ||
            !FPDFPageObj_SetMatrix(object.get(), &matrix) || !fill.apply(object.get()))) object.reset();
    } else {
        FPDF_WCHAR value[3]{static_cast<FPDF_WCHAR>(code), 0, 0};
        if (code > 0xFFFF && code <= 0x10FFFF) {
            const auto offset = code - 0x10000;
            value[0] = static_cast<FPDF_WCHAR>(0xD800 + (offset >> 10));
            value[1] = static_cast<FPDF_WCHAR>(0xDC00 + (offset & 0x3FF));
        }
        object = text_object(doc, font, size, value, matrix, fill);
    }
    if (object) {
        FPDFTextObj_SetTextRenderMode(object.get(), FPDFTextObj_GetTextRenderMode(original));
        Color stroke;
        if (FPDFPageObj_GetStrokeColor(original, &stroke.red, &stroke.green, &stroke.blue, &stroke.alpha)) {
            FPDFPageObj_SetStrokeColor(object.get(), stroke.red, stroke.green, stroke.blue, stroke.alpha);
        }
    }
    return object;
}

class OriginalGlyphRenderer {
public:
    PageObject render(FPDF_DOCUMENT target, FPDF_TEXTPAGE text, int index, FPDF_PAGEOBJECT original,
                      float scale, float x, float y) {
        PageObject image(nullptr, FPDFPageObj_Destroy);
        if (!page_) {
            document_.reset(FPDF_CreateNewDocument());
            if (!document_) return image;
            page_.reset(FPDFPage_New(document_.get(), 0, 1, 1));
            if (!page_) return image;
        }
        auto glyph = original_character(document_.get(), text, index, original, scale, 0, 0);
        float left{}, bottom{}, right{}, top{};
        if (!glyph || !FPDFPageObj_GetBounds(glyph.get(), &left, &bottom, &right, &top) ||
            !std::isfinite(left) || !std::isfinite(bottom) || !std::isfinite(right) || !std::isfinite(top) ||
            right < left || top < bottom) return image;
        left -= 1;
        bottom -= 1;
        right += 1;
        top += 1;
        const float extent = std::max(right - left, top - bottom);
        if (!std::isfinite(extent) || extent <= 0) return image;
        const float resolution = std::min(6.0f, 2048.0f / extent);
        const int width = static_cast<int>(std::ceil((right - left) * resolution));
        const int height = static_cast<int>(std::ceil((top - bottom) * resolution));
        Bitmap bitmap(FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, nullptr, 0), FPDFBitmap_Destroy);
        if (!bitmap || !FPDFBitmap_FillRect(bitmap.get(), 0, 0, width, height, 0)) return image;
        if (!FPDFPage_InsertObject(page_.get(), glyph.get())) return image;
        const auto inserted = glyph.release();
        const FS_MATRIX matrix{resolution, 0, 0, resolution, -left * resolution, (top - 1) * resolution};
        const FS_RECTF clip{0, 0, static_cast<float>(width), static_cast<float>(height)};
        FPDF_RenderPageBitmapWithMatrix(bitmap.get(), page_.get(), &matrix, &clip, 0);
        if (!FPDFPage_RemoveObject(page_.get(), inserted)) return image;
        glyph.reset(inserted);
        image.reset(FPDFPageObj_NewImageObj(target));
        const FS_MATRIX placement{width / resolution, 0, 0, height / resolution, x + left, y + top - height / resolution};
        if (image && (!FPDFImageObj_SetBitmap(nullptr, 0, image.get(), bitmap.get()) ||
            !FPDFPageObj_SetMatrix(image.get(), &placement))) image.reset();
        return image;
    }

private:
    std::unique_ptr<fpdf_document_t__, decltype(&FPDF_CloseDocument)> document_{nullptr, FPDF_CloseDocument};
    Page page_{nullptr, FPDF_ClosePage};
};

FS_QUADPOINTSF attachment(const Box& box) {
    const auto rect = box.pdf_rect();
    return {rect.left, rect.top, rect.right, rect.top, rect.left, rect.bottom, rect.right, rect.bottom};
}

struct LinkMove {
    Annotation annotation;
    std::vector<Box> boxes;

    bool apply() const {
        auto bounds = boxes.front();
        for (const auto& box : boxes) {
            bounds.left = std::min(bounds.left, box.left);
            bounds.bottom = std::min(bounds.bottom, box.bottom);
            bounds.right = std::max(bounds.right, box.right);
            bounds.top = std::max(bounds.top, box.top);
        }
        const auto rect = bounds.pdf_rect();
        if (!FPDFAnnot_SetRect(annotation.get(), &rect)) return false;
        const auto existing = FPDFAnnot_CountAttachmentPoints(annotation.get());
        if (existing == 0 && boxes.size() == 1) return true;
        // PDFium に QuadPoints の削除 API がないため、余った分も最後の行へ移す。
        for (size_t i = 0; i < std::max(existing, boxes.size()); ++i) {
            const auto quad = attachment(boxes[std::min(i, boxes.size() - 1)]);
            const auto ok = i < existing ? FPDFAnnot_SetAttachmentPoints(annotation.get(), i, &quad)
                : FPDFAnnot_AppendAttachmentPoints(annotation.get(), &quad);
            if (!ok) return false;
        }
        return true;
    }
};

std::vector<jint> read_ints(JNIEnv* env, jintArray array) {
    std::vector<jint> values(env->GetArrayLength(array));
    if (!values.empty()) env->GetIntArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    return values;
}

std::vector<jdouble> read_doubles(JNIEnv* env, jdoubleArray array) {
    std::vector<jdouble> values(env->GetArrayLength(array));
    if (!values.empty()) env->GetDoubleArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    return values;
}

struct MemoryWriter : FPDF_FILEWRITE {
    std::vector<jbyte> bytes;
    bool failed = false;
    MemoryWriter() : FPDF_FILEWRITE{1, write} {}

    static int write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
        auto& writer = *static_cast<MemoryWriter*>(self);
        const auto* start = static_cast<const jbyte*>(data);
        if (writer.failed || size > std::numeric_limits<jsize>::max() - writer.bytes.size()) {
            writer.failed = true;
            return 0;
        }
        try {
            writer.bytes.insert(writer.bytes.end(), start, start + size);
        } catch (const std::bad_alloc&) {
            writer.failed = true;
            return 0;
        }
        return 1;
    }
};
}

extern "C" {
// 1 は成功、0 は変更前の失敗、-1 は変更後の失敗。
JNIEXPORT jint JNICALL PDF_JNI(writePage)(
    JNIEnv* env, jobject, jlong handle, jint page, jintArray remove_objects, jintArray retain_chars,
    jobjectArray contents, jintArray source_chars, jdoubleArray coordinates, jintArray move_indices, jdoubleArray move_boxes,
    jintArray remove_links) {
    std::lock_guard guard(pdfium_mutex);
    const auto& doc = *reinterpret_cast<Document*>(handle);
    TextOnPage on(handle, page);
    if (!on.text) return 0;
    const auto removed = read_ints(env, remove_objects);
    const auto retained = read_ints(env, retain_chars);
    const auto sources = read_ints(env, source_chars);
    const auto fields = read_doubles(env, coordinates);
    const auto moves = read_ints(env, move_indices);
    const auto boxes = read_doubles(env, move_boxes);
    const auto duplicates = read_ints(env, remove_links);
    const int num_ops = env->GetArrayLength(contents);
    if (env->ExceptionCheck() || fields.size() != static_cast<size_t>(num_ops) * 5 || sources.size() != num_ops ||
        moves.size() % 2 || boxes.size() % 4) return 0;

    std::unordered_set<FPDF_PAGEOBJECT> originals;
    for (int index : removed) {
        const auto original = FPDFPage_GetObject(on.page.get(), index);
        if (!original || FPDFPageObj_GetType(original) != FPDF_PAGEOBJ_TEXT) return 0;
        originals.insert(original);
    }
    std::vector<PageObject> additions;
    OriginalGlyphRenderer original_glyphs;
    for (int index : retained) {
        const auto associated = FPDFText_GetTextObject(on.text.get(), index);
        if (!originals.count(associated)) return 0;
        double x{}, y{};
        if (!FPDFText_GetCharOrigin(on.text.get(), index, &x, &y)) return 0;
        const auto left = static_cast<float>(x);
        const auto baseline = static_cast<float>(y);
        auto object = FPDFText_HasUnicodeMapError(on.text.get(), index) == 1 ?
            original_glyphs.render(doc.pdf.get(), on.text.get(), index, associated, 1, left, baseline) :
            original_character(doc.pdf.get(), on.text.get(), index, associated, 1, left, baseline);
        if (!object) return 0;
        additions.push_back(std::move(object));
    }
    for (int i = 0; i < num_ops; ++i) {
        const auto* values = fields.data() + static_cast<size_t>(i) * 5;
        PageObject object(nullptr, FPDFPageObj_Destroy);
        if (sources[i] >= 0) {
            const auto associated = FPDFText_GetTextObject(on.text.get(), sources[i]);
            if (!associated || FPDFText_HasUnicodeMapError(on.text.get(), sources[i]) != 1) return 0;
            object = original_glyphs.render(doc.pdf.get(), on.text.get(), sources[i], associated,
                static_cast<float>(values[2]), static_cast<float>(values[0]), static_cast<float>(values[1]));
            if (!object) return 0;
            additions.push_back(std::move(object));
            continue;
        }
        const auto color = unpack_color(values[4]);
        auto text = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        if (text) {
            const auto chars = wide_string(env, text);
            env->DeleteLocalRef(text);
            const auto font = values[3] == 0 ? doc.regular.get() : doc.bold.get();
            const FS_MATRIX matrix{1, 0, 0, 1, static_cast<float>(values[0]), static_cast<float>(values[1])};
            object = text_object(doc.pdf.get(), font, static_cast<float>(values[2]), chars.data(), matrix, color);
        } else {
            object.reset(FPDFPageObj_CreateNewRect(static_cast<float>(values[0]), static_cast<float>(values[1]),
                static_cast<float>(values[2] - values[0]), static_cast<float>(values[3] - values[1])));
            if (object && (!color.apply(object.get()) ||
                !FPDFPath_SetDrawMode(object.get(), FPDF_FILLMODE_ALTERNATE, false))) return 0;
        }
        if (!object || env->ExceptionCheck()) return 0;
        additions.push_back(std::move(object));
    }

    std::vector<LinkMove> link_moves;
    size_t offset = 0;
    for (size_t i = 0; i < moves.size(); i += 2) {
        const auto count = moves[i + 1];
        Annotation annot(FPDFPage_GetAnnot(on.page.get(), moves[i]), FPDFPage_CloseAnnot);
        if (!annot || FPDFAnnot_GetSubtype(annot.get()) != FPDF_ANNOT_LINK ||
            count <= 0 || static_cast<size_t>(count) > (boxes.size() - offset) / 4) return 0;
        LinkMove move{std::move(annot), {}};
        for (int n = 0; n < count; ++n, offset += 4) {
            move.boxes.push_back({boxes[offset], boxes[offset + 1], boxes[offset + 2], boxes[offset + 3]});
        }
        link_moves.push_back(std::move(move));
    }
    if (offset != boxes.size()) return 0;
    for (int index : duplicates) {
        Annotation annot(FPDFPage_GetAnnot(on.page.get(), index), FPDFPage_CloseAnnot);
        if (!annot || FPDFAnnot_GetSubtype(annot.get()) != FPDF_ANNOT_LINK) return 0;
    }

    on.text.reset();
    for (const auto original : originals) {
        if (!FPDFPage_RemoveObject(on.page.get(), original)) return -1;
        FPDFPageObj_Destroy(original);
    }
    for (auto& object : additions) {
        if (!FPDFPage_InsertObject(on.page.get(), object.release())) return -1;
    }
    for (const auto& move : link_moves) if (!move.apply()) return -1;
    link_moves.clear();
    for (int index : duplicates) if (!FPDFPage_RemoveAnnot(on.page.get(), index)) return -1;
    return FPDFPage_GenerateContent(on.page.get()) ? 1 : -1;
}

JNIEXPORT jbyteArray JNICALL PDF_JNI(save)(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard guard(pdfium_mutex);
    MemoryWriter writer;
    if (!FPDF_SaveAsCopy(document(handle), &writer, FPDF_NO_INCREMENTAL) || writer.failed ||
        writer.bytes.empty()) return nullptr;
    return byte_array(env, writer.bytes.data(), writer.bytes.size());
}

JNIEXPORT jbyteArray JNICALL PDF_JNI(subsetFont)(JNIEnv* env, jobject, jbyteArray data, jintArray unicode) {
    const auto bytes = read_bytes(env, data);
    const auto characters = read_ints(env, unicode);
    if (bytes.empty() || env->ExceptionCheck()) return nullptr;
    const auto blob = std::unique_ptr<hb_blob_t, decltype(&hb_blob_destroy)>(hb_blob_create_or_fail(
        reinterpret_cast<const char*>(bytes.data()), static_cast<unsigned int>(bytes.size()),
        HB_MEMORY_MODE_READONLY, nullptr, nullptr), hb_blob_destroy);
    if (!blob) return nullptr;
    const auto face = std::unique_ptr<hb_face_t, decltype(&hb_face_destroy)>(hb_face_create(blob.get(), 0), hb_face_destroy);
    const auto input = std::unique_ptr<hb_subset_input_t, decltype(&hb_subset_input_destroy)>(
        hb_subset_input_create_or_fail(), hb_subset_input_destroy);
    if (!input) return nullptr;
    auto* set = hb_subset_input_unicode_set(input.get());
    for (int code : characters) hb_set_add(set, static_cast<hb_codepoint_t>(code));
    hb_subset_input_set_flags(input.get(), hb_subset_input_get_flags(input.get()) | HB_SUBSET_FLAGS_RETAIN_GIDS);
    const auto result = std::unique_ptr<hb_face_t, decltype(&hb_face_destroy)>(
        hb_subset_or_fail(face.get(), input.get()), hb_face_destroy);
    if (!result) return nullptr;
    const auto output = std::unique_ptr<hb_blob_t, decltype(&hb_blob_destroy)>(
        hb_face_reference_blob(result.get()), hb_blob_destroy);
    unsigned int length{};
    const auto* contents = hb_blob_get_data(output.get(), &length);
    return length ? byte_array(env, contents, length) : nullptr;
}
#undef PDF_JNI
}
