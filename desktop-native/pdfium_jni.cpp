#define NOMINMAX
#include <jni.h>
#include <fpdf_text.h>
#include <fpdfview.h>
#include <algorithm>
#include <cstdio>
#include <limits>
#include <memory>
#include <string>
#include <vector>

namespace {
struct Pdfium {
    Pdfium() { FPDF_InitLibrary(); }
    ~Pdfium() { FPDF_DestroyLibrary(); }
};

int read_block(void * handle, unsigned long offset, unsigned char * bytes, unsigned long length) {
    auto * file = static_cast<FILE *>(handle);
    return _fseeki64(file, offset, SEEK_SET) == 0 && std::fread(bytes, 1, length, file) == length;
}

struct Document {
    std::unique_ptr<FILE, decltype(&std::fclose)> file{nullptr, std::fclose};
    FPDF_FILEACCESS access{};
    std::unique_ptr<fpdf_document_t__, decltype(&FPDF_CloseDocument)> pdf{nullptr, FPDF_CloseDocument};
};

using Page = std::unique_ptr<fpdf_page_t__, decltype(&FPDF_ClosePage)>;
using TextPage = std::unique_ptr<fpdf_textpage_t__, decltype(&FPDFText_ClosePage)>;
using Bitmap = std::unique_ptr<fpdf_bitmap_t__, decltype(&FPDFBitmap_Destroy)>;

FPDF_DOCUMENT document(jlong handle) {
    return reinterpret_cast<Document *>(handle)->pdf.get();
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_app_plamotranslate_desktop_PdfNative_open(JNIEnv * env, jobject, jstring path) {
    static Pdfium library;
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
    delete reinterpret_cast<Document *>(handle);
}

JNIEXPORT jint JNICALL Java_app_plamotranslate_desktop_PdfNative_pageCount(JNIEnv *, jobject, jlong handle) {
    return FPDF_GetPageCount(document(handle));
}

JNIEXPORT jfloatArray JNICALL Java_app_plamotranslate_desktop_PdfNative_pageSize(JNIEnv * env, jobject, jlong handle, jint index) {
    FS_SIZEF size{};
    if (!FPDF_GetPageSizeByIndexF(document(handle), index, &size)) return nullptr;
    const jfloat result[] = {size.width, size.height};
    auto output = env->NewFloatArray(2);
    if (output) env->SetFloatArrayRegion(output, 0, 2, result);
    return output;
}

JNIEXPORT jintArray JNICALL Java_app_plamotranslate_desktop_PdfNative_render(
    JNIEnv * env, jobject, jlong handle, jint index, jint width, jint height) {
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
}
