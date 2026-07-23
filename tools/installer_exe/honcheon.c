/* 혼천 설치·실행기 — exe 입구 (bat 의 exe 판 · 낙관 아이콘)
 *
 * 하는 일 둘뿐이다: ① 릴리스에서 최신 설치기 본체(ps1)를 받아 ② 숨김 파워셸로 실행.
 * 본체가 수묵 GUI 를 띄우고, 설치 후 게임을 자동 시작한다 (설치 = 실행).
 * 핀·그림·문구 전부 본체(ps1)에 있다 — 이 exe 는 얇은 문이라 갱신할 일이 거의 없다.
 */
#include <windows.h>
#include <urlmon.h>
#include <wchar.h>

static const wchar_t *PS1_URL =
    L"https://github.com/jokwangwon/honcheon-pack/releases/download/pack/honcheon_setup.ps1";

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show) {
    (void)inst; (void)prev; (void)cmd; (void)show;
    wchar_t tmp[MAX_PATH], ps1[MAX_PATH];
    GetTempPathW(MAX_PATH, tmp);
    swprintf(ps1, MAX_PATH, L"%shoncheon_setup.ps1", tmp);
    DeleteFileW(ps1);   /* 항상 최신 본체를 받는다 — 이 파일이 곧 갱신 통로다 */
    if (URLDownloadToFileW(NULL, PS1_URL, ps1, 0, NULL) != S_OK) {
        MessageBoxW(NULL,
            L"설치 자료를 내려받지 못했습니다.\n인터넷 연결을 확인한 뒤 다시 실행해 주세요.",
            L"혼천 설치기", MB_ICONERROR | MB_OK);
        return 1;
    }
    wchar_t args[1024];
    swprintf(args, 1024,
        L"-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"%s\"", ps1);
    ShellExecuteW(NULL, L"open", L"powershell.exe", args, NULL, SW_HIDE);
    return 0;
}
