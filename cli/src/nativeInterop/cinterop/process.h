#ifndef TEAMCODE_PROCESS_H
#define TEAMCODE_PROCESS_H
#include <spawn.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#include <errno.h>
#ifdef __APPLE__
#include <crt_externs.h>
#endif

static inline char **teamcode_environment(void) {
#ifdef __APPLE__
    return *_NSGetEnviron();
#else
    extern char **environ;
    return environ;
#endif
}

/* No shell or fork inside the multithreaded Kotlin runtime; inherit the caller's terminal. */
static inline int teamcode_spawn(const char *command, char *const argv[], char *const envp[], int *child) {
    posix_spawnattr_t attributes;
    int result = posix_spawnattr_init(&attributes);
    if (result != 0) return result;
    sigset_t defaults;
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGINT);
    sigaddset(&defaults, SIGTERM);
    sigaddset(&defaults, SIGHUP);
    sigaddset(&defaults, SIGQUIT);
    posix_spawnattr_setsigdefault(&attributes, &defaults);
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETSIGDEF);
    pid_t pid;
    result = posix_spawnp(&pid, command, NULL, &attributes, argv, envp);
    posix_spawnattr_destroy(&attributes);
    if (result == 0) *child = (int)pid;
    return result;
}

/* Poll only this exact owned child. A reaped PID is never signalled. */
static inline int teamcode_poll(int child, int *code) {
    int status;
    pid_t result = waitpid((pid_t)child, &status, WNOHANG);
    if (result == 0 || (result < 0 && errno == EINTR)) return 0;
    if (result < 0) return -1;
    if (WIFEXITED(status)) *code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) *code = 128 + WTERMSIG(status);
    else return 0;
    return 1;
}
static inline void teamcode_terminate(int child, int force) { kill((pid_t)child, force ? SIGKILL : SIGTERM); }
#endif
