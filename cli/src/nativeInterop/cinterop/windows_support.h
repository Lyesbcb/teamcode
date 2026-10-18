#ifndef TEAMCODE_WINDOWS_SUPPORT_H
#define TEAMCODE_WINDOWS_SUPPORT_H
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#include <windows.h>
#include <bcrypt.h>
#include <aclapi.h>
#include <sddl.h>
#include <stdlib.h>
#include <wchar.h>

static inline int teamcode_windows_random(void *buffer, unsigned long count) {
    return BCryptGenRandom(NULL, (PUCHAR)buffer, count, BCRYPT_USE_SYSTEM_PREFERRED_RNG) >= 0;
}

/* Caller frees the returned LocalAlloc SID string. Never expose token credentials. */
static inline wchar_t *teamcode_windows_user_sid(void) {
    HANDLE token = NULL;
    DWORD size = 0;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return NULL;
    GetTokenInformation(token, TokenUser, NULL, 0, &size);
    TOKEN_USER *user = (TOKEN_USER *)malloc(size);
    wchar_t *sid = NULL;
    if (user && GetTokenInformation(token, TokenUser, user, size, &size))
        ConvertSidToStringSidW(user->User.Sid, &sid);
    free(user);
    CloseHandle(token);
    return sid;
}

static inline int teamcode_windows_owner_matches(const wchar_t *path, PSID expected) {
    PSECURITY_DESCRIPTOR descriptor = NULL;
    PSID owner = NULL;
    DWORD result = GetNamedSecurityInfoW((wchar_t *)path, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION,
        &owner, NULL, NULL, NULL, &descriptor);
    int matches = result == ERROR_SUCCESS && owner && EqualSid(owner, expected);
    if (descriptor) LocalFree(descriptor);
    return matches;
}

/* Protected DACL with one explicit allow entry for the current user, on both directory and file. */
static inline int teamcode_windows_write_private(const wchar_t *path, const wchar_t *parent,
        const wchar_t *temporary, const void *bytes, unsigned long length) {
    wchar_t *sid = teamcode_windows_user_sid();
    if (!sid) return 0;
    size_t capacity = 64 + 2 * wcslen(sid);
    wchar_t *sddl = (wchar_t *)calloc(capacity, sizeof(wchar_t));
    PSECURITY_DESCRIPTOR descriptor = NULL;
    int success = 0;
    HANDLE file = INVALID_HANDLE_VALUE;
    int created = 0;
    if (!sddl) goto cleanup;
    swprintf(sddl, capacity, L"O:%lsD:P(A;OICI;FA;;;%ls)", sid, sid);
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL)) goto cleanup;
    SECURITY_ATTRIBUTES attributes = { sizeof(SECURITY_ATTRIBUTES), descriptor, FALSE };
    PSID owner = NULL;
    PACL dacl = NULL;
    BOOL defaulted = FALSE, present = FALSE;
    if (!GetSecurityDescriptorOwner(descriptor, &owner, &defaulted) ||
        !GetSecurityDescriptorDacl(descriptor, &present, &dacl, &defaulted) || !present || !dacl) goto cleanup;
    if (!CreateDirectoryW(parent, &attributes) && GetLastError() != ERROR_ALREADY_EXISTS) goto cleanup;
    DWORD parent_flags = GetFileAttributesW(parent);
    if (parent_flags == INVALID_FILE_ATTRIBUTES || !(parent_flags & FILE_ATTRIBUTE_DIRECTORY) ||
        (parent_flags & FILE_ATTRIBUTE_REPARSE_POINT) || !teamcode_windows_owner_matches(parent, owner)) goto cleanup;
    if (SetNamedSecurityInfoW((wchar_t *)parent, SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, NULL, NULL, dacl, NULL) != ERROR_SUCCESS) goto cleanup;
    DWORD destination_flags = GetFileAttributesW(path);
    if (destination_flags != INVALID_FILE_ATTRIBUTES &&
        ((destination_flags & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY)) || !teamcode_windows_owner_matches(path, owner))) goto cleanup;
    file = CreateFileW(temporary, GENERIC_WRITE, 0, &attributes, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) goto cleanup;
    created = 1;
    DWORD offset = 0;
    while (offset < length) {
        DWORD written = 0;
        if (!WriteFile(file, (const char *)bytes + offset, length - offset, &written, NULL) || written == 0) goto cleanup;
        offset += written;
    }
    if (!FlushFileBuffers(file)) goto cleanup;
    CloseHandle(file);
    file = INVALID_HANDLE_VALUE;
    if (!MoveFileExW(temporary, path, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) goto cleanup;
    success = 1;
cleanup:
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    if (created && !success) DeleteFileW(temporary);
    if (descriptor) LocalFree(descriptor);
    free(sddl);
    LocalFree(sid);
    return success;
}

/* Duplicate only standard streams; do not make arbitrary parent handles inheritable. */
static inline HANDLE teamcode_windows_standard(DWORD kind, int quiet) {
    HANDLE result = NULL;
    if (quiet && kind != STD_INPUT_HANDLE) {
        SECURITY_ATTRIBUTES attributes = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
        return CreateFileW(L"NUL", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, &attributes, OPEN_EXISTING, 0, NULL);
    }
    HANDLE source = GetStdHandle(kind);
    if (!source || source == INVALID_HANDLE_VALUE) {
        SECURITY_ATTRIBUTES attributes = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
        return CreateFileW(L"NUL", kind == STD_INPUT_HANDLE ? GENERIC_READ : GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE, &attributes, OPEN_EXISTING, 0, NULL);
    }
    if (!DuplicateHandle(GetCurrentProcess(), source, GetCurrentProcess(), &result, 0, TRUE, DUPLICATE_SAME_ACCESS)) return NULL;
    return result;
}

/* The returned handle owns precisely the created process; callers never signal by name or reused PID. */
static inline void *teamcode_windows_spawn(const wchar_t *application, wchar_t *command_line,
        const wchar_t *environment, int quiet) {
    STARTUPINFOEXW startup = {0};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    HANDLE handles[3] = { teamcode_windows_standard(STD_INPUT_HANDLE, quiet),
        teamcode_windows_standard(STD_OUTPUT_HANDLE, quiet), teamcode_windows_standard(STD_ERROR_HANDLE, quiet) };
    PROCESS_INFORMATION process = {0};
    SIZE_T size = 0;
    int initialized = 0;
    for (int i = 0; i < 3; i++) if (!handles[i] || handles[i] == INVALID_HANDLE_VALUE) goto cleanup;
    startup.StartupInfo.hStdInput = handles[0];
    startup.StartupInfo.hStdOutput = handles[1];
    startup.StartupInfo.hStdError = handles[2];
    InitializeProcThreadAttributeList(NULL, 1, 0, &size);
    startup.lpAttributeList = (LPPROC_THREAD_ATTRIBUTE_LIST)malloc(size);
    if (!startup.lpAttributeList || !InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &size)) goto cleanup;
    initialized = 1;
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
        handles, sizeof(handles), NULL, NULL)) goto cleanup;
    if (!CreateProcessW(application, command_line, NULL, NULL, TRUE,
        CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT, (void *)environment, NULL,
        &startup.StartupInfo, &process)) goto cleanup;
    CloseHandle(process.hThread);
cleanup:
    if (initialized) DeleteProcThreadAttributeList(startup.lpAttributeList);
    free(startup.lpAttributeList);
    for (int i = 0; i < 3; i++) if (handles[i] && handles[i] != INVALID_HANDLE_VALUE) CloseHandle(handles[i]);
    return process.hProcess;
}
static inline int teamcode_windows_poll(void *process, unsigned long *code) {
    DWORD status = WaitForSingleObject((HANDLE)process, 0);
    if (status == WAIT_TIMEOUT) return 0;
    if (status != WAIT_OBJECT_0 || !GetExitCodeProcess((HANDLE)process, code)) return -1;
    return 1;
}
static inline void teamcode_windows_close(void *process) { CloseHandle((HANDLE)process); }
static inline void teamcode_windows_terminate(void *process) { TerminateProcess((HANDLE)process, 130); }
#endif
