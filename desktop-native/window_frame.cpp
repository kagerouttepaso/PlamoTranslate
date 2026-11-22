#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <windowsx.h>
#include <jni.h>
#include <memory>

// Swing が作った主ウィンドウだけで非クライアント領域を描き替える。
// 移動・サイズ変更・スナップ・システムメニューは Windows の標準処理に任せる。
namespace {
const wchar_t property[] = L"Mockingword.Frame";
struct Frame {
    WNDPROC previous;
    int titleHeight;
    int buttonWidth;
    bool maxPressed = false;
};

LRESULT CALLBACK windowProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    auto* frame = static_cast<Frame*>(GetPropW(hwnd, property));
    if (!frame) return DefWindowProcW(hwnd, msg, wp, lp);
    switch (msg) {
    case WM_NCCALCSIZE:
        if (wp && IsZoomed(hwnd)) {
            MONITORINFO info{sizeof(info)};
            if (GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), &info))
                reinterpret_cast<NCCALCSIZE_PARAMS*>(lp)->rgrc[0] = info.rcWork;
        }
        return 0;
    case WM_NCHITTEST: {
        POINT p{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
        ScreenToClient(hwnd, &p);
        RECT r{};
        GetClientRect(hwnd, &r);
        UINT dpi = GetDpiForWindow(hwnd);
        int edge = GetSystemMetricsForDpi(SM_CXSIZEFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
        if (!IsZoomed(hwnd)) {
            bool left = p.x < edge, right = p.x >= r.right - edge;
            if (p.y < edge) return left ? HTTOPLEFT : right ? HTTOPRIGHT : HTTOP;
            if (p.y >= r.bottom - edge) return left ? HTBOTTOMLEFT : right ? HTBOTTOMRIGHT : HTBOTTOM;
            if (left) return HTLEFT;
            if (right) return HTRIGHT;
        }
        if (p.y < MulDiv(frame->titleHeight, dpi, 96)) {
            int width = MulDiv(frame->buttonWidth, dpi, 96);
            if (p.x >= r.right - width) return HTCLIENT;
            if (p.x >= r.right - 2 * width) return HTMAXBUTTON;
            if (p.x >= r.right - 3 * width) return HTCLIENT;
            return HTCAPTION;
        }
        return HTCLIENT;
    }
    case WM_NCLBUTTONDOWN:
        if (wp == HTMAXBUTTON) {
            frame->maxPressed = true;
            SetCapture(hwnd);
            return 0;
        }
        return DefWindowProcW(hwnd, msg, wp, lp);
    case WM_LBUTTONUP:
        if (frame->maxPressed) {
            POINT p{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            ClientToScreen(hwnd, &p);
            frame->maxPressed = false;
            ReleaseCapture();
            if (SendMessageW(hwnd, WM_NCHITTEST, 0, MAKELPARAM(p.x, p.y)) == HTMAXBUTTON)
                PostMessageW(hwnd, WM_SYSCOMMAND, IsZoomed(hwnd) ? SC_RESTORE : SC_MAXIMIZE, 0);
            return 0;
        }
        break;
    case WM_CAPTURECHANGED:
        frame->maxPressed = false;
        break;
    case WM_NCLBUTTONDBLCLK:
    case WM_NCRBUTTONUP:
        return DefWindowProcW(hwnd, msg, wp, lp);
    case WM_NCDESTROY: {
        auto previous = frame->previous;
        RemovePropW(hwnd, property);
        delete frame;
        return CallWindowProcW(previous, hwnd, msg, wp, lp);
    }
    }
    return CallWindowProcW(frame->previous, hwnd, msg, wp, lp);
}

// Compose の描画面は別の子 HWND。枠と帯の入力だけを親の非クライアント処理へ返す。
const wchar_t canvasProperty[] = L"Mockingword.CanvasProc";
LRESULT CALLBACK canvasProc(HWND child, UINT msg, WPARAM wp, LPARAM lp) {
    auto previous = reinterpret_cast<WNDPROC>(GetPropW(child, canvasProperty));
    if (msg == WM_NCHITTEST) {
        HWND root = GetAncestor(child, GA_ROOT);
        if (SendMessageW(root, WM_NCHITTEST, wp, lp) != HTCLIENT) return HTTRANSPARENT;
    }
    if (msg == WM_NCDESTROY) RemovePropW(child, canvasProperty);
    return CallWindowProcW(previous, child, msg, wp, lp);
}

BOOL CALLBACK installCanvas(HWND child, LPARAM) {
    auto previous = GetWindowLongPtrW(child, GWLP_WNDPROC);
    if (SetPropW(child, canvasProperty, reinterpret_cast<HANDLE>(previous))) {
        if (!SetWindowLongPtrW(child, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(canvasProc)))
            RemovePropW(child, canvasProperty);
    }
    return TRUE;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_plamotranslate_desktop_WindowsFrame_install(JNIEnv*, jobject, jlong handle, jint height, jint width) {
    HWND hwnd = GetAncestor(reinterpret_cast<HWND>(handle), GA_ROOT);
    DWORD owner = 0;
    GetWindowThreadProcessId(hwnd, &owner);
    if (!hwnd || owner != GetCurrentProcessId()) return JNI_FALSE;
    if (GetPropW(hwnd, property)) return JNI_TRUE;
    auto frame = std::make_unique<Frame>();
    frame->previous = reinterpret_cast<WNDPROC>(GetWindowLongPtrW(hwnd, GWLP_WNDPROC));
    frame->titleHeight = height;
    frame->buttonWidth = width;
    if (!SetPropW(hwnd, property, frame.get())) return JNI_FALSE;
    if (!SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(windowProc))) {
        RemovePropW(hwnd, property);
        return JNI_FALSE;
    }
    frame.release();
    EnumChildWindows(hwnd, installCanvas, 0);
    auto style = GetWindowLongPtrW(hwnd, GWL_STYLE);
    SetWindowLongPtrW(hwnd, GWL_STYLE, style | WS_OVERLAPPEDWINDOW);
    SetWindowPos(hwnd, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    return JNI_TRUE;
}
