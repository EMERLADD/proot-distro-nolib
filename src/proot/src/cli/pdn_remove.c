#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#include "cli/pdn_events.h"

char *pdn_rootfs_base(void);

static int same_entry(int parent, const char *name, const struct stat *opened)
{
    struct stat current;
    if (fstatat(parent, name, &current, AT_SYMLINK_NOFOLLOW) < 0) return 0;
    if (current.st_dev == opened->st_dev && current.st_ino == opened->st_ino) return 1;
    errno = ESTALE;
    return 0;
}

static int remove_contents(int fd, dev_t device, int *removed)
{
    DIR *dir = fdopendir(dup(fd));
    struct dirent *entry;
    int result = -1, saved;
    if (!dir) return -1;
    for (;;) {
        struct stat st, opened;
        int child;
        errno = 0;
        entry = readdir(dir);
        if (!entry) { if (!errno) result = 0; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (fstatat(fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0) break;
        if (!S_ISDIR(st.st_mode)) {
            if (unlinkat(fd, entry->d_name, 0) < 0) break;
            *removed = 1;
            continue;
        }
        child = openat(fd, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (child < 0) break;
        if (fstat(child, &opened) < 0) { saved = errno; close(child); errno = saved; break; }
        if (opened.st_dev != device || opened.st_ino != st.st_ino || opened.st_dev != st.st_dev) {
            close(child); errno = EXDEV; break;
        }
        if (fchmod(child, opened.st_mode | S_IRWXU) < 0 || remove_contents(child, device, removed) < 0) {
            saved = errno; close(child); errno = saved; break;
        }
        close(child);
        if (!same_entry(fd, entry->d_name, &opened) || unlinkat(fd, entry->d_name, AT_REMOVEDIR) < 0) break;
        *removed = 1;
    }
    saved = errno;
    closedir(dir);
    errno = saved;
    return result;
}

int pdn_remove(const char *requested, int yes)
{
    char *base = pdn_rootfs_base(), *resolved = NULL, *name = NULL;
    DIR *dir = NULL;
    struct dirent *entry;
    struct stat parent, root;
    int basefd = -1, lock = -1, rootfd = -1, result = 2;
    const char *message = NULL, *code = "uninstall_failed";
    int saved = 0, removed = 0;
    if (!base) {
        code = (getenv("PDN_ROOTFS_DIR") && *getenv("PDN_ROOTFS_DIR")) ||
            (getenv("HOME") && *getenv("HOME")) ? "out_of_memory" : "invalid_argument";
        message = "set PDN_ROOTFS_DIR or HOME"; goto done;
    }
    resolved = realpath(base, NULL);
    if (!resolved) goto system_error;
    if (!strcmp(resolved, "/")) { code = "invalid_argument"; message = "rootfs parent must not be /"; goto done; }
    basefd = open(resolved, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (basefd < 0 || fstat(basefd, &parent) < 0) goto system_error;
    lock = openat(basefd, ".pdn-install.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0) {
        saved = errno; code = "lock_failed";
        message = "cannot acquire install/uninstall lock"; goto done;
    }
    if (flock(lock, LOCK_EX | LOCK_NB) < 0) {
        saved = errno;
        code = saved == EWOULDBLOCK || saved == EAGAIN ? "operation_busy" : "lock_failed";
        message = "cannot acquire install/uninstall lock"; goto done;
    }
    dir = fdopendir(dup(basefd));
    if (!dir) goto system_error;
    for (;;) {
        errno = 0;
        entry = readdir(dir);
        if (!entry) { if (errno) goto system_error; break; }
        if (strcasecmp(entry->d_name, requested)) continue;
        if (name) { code = "name_ambiguous"; message = "ambiguous rootfs name"; goto done; }
        name = strdup(entry->d_name);
        if (!name) goto system_error;
    }
    if (!name) { code = "rootfs_missing"; message = "rootfs not found"; goto done; }
    rootfd = openat(basefd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (rootfd < 0) { saved = errno; code = saved == ENOENT ? "rootfs_missing" : "directory_unavailable"; message = "rootfs must be a real directory, not a symlink"; goto done; }
    if (fstat(rootfd, &root) < 0) goto system_error;
    if (root.st_dev != parent.st_dev || root.st_ino == parent.st_ino) {
        message = "refusing rootfs on another filesystem or parent directory"; goto done;
    }
    if (flock(rootfd, LOCK_EX | LOCK_NB) < 0) {
        saved = errno;
        code = saved == EWOULDBLOCK || saved == EAGAIN ? "operation_busy" : "lock_failed";
        message = "rootfs in use; exit its login sessions first"; goto done;
    }
    fprintf(stderr, "Rootfs: %s/%s\n", resolved, name);
    if (!yes) {
        char answer[16];
        fprintf(stderr, "Delete this Linux and ALL files inside it? [y/N] ");
        fflush(stderr);
        if (!fgets(answer, sizeof(answer), stdin)) answer[0] = '\0';
        answer[strcspn(answer, "\r\n")] = '\0';
        if (strcasecmp(answer, "y") && strcasecmp(answer, "yes")) {
            fprintf(stderr, "Cancelled; no rootfs files removed.\n");
            result = 1; goto done;
        }
    }
    if (!same_entry(basefd, name, &root)) { message = "rootfs changed; refusing removal"; goto done; }
    if (fchmod(rootfd, root.st_mode | S_IRWXU) < 0 || remove_contents(rootfd, root.st_dev, &removed) < 0 ||
        !same_entry(basefd, name, &root) || unlinkat(basefd, name, AT_REMOVEDIR) < 0) {
        saved = errno;
        fprintf(stderr, "pdn: uninstall incomplete: %s; remaining files kept at %s/%s\n",
                strerror(saved), resolved, name);
        pdn_events_system_problem(removed ? "uninstall_incomplete" : "uninstall_failed", "uninstall incomplete; remaining files kept in the rootfs",
            "Inspect the remaining files and retry uninstall", saved);
        saved = 0;
        goto done;
    }
    printf("Uninstalled %s.\n", name);
    result = 0;
    goto done;
system_error:
    saved = errno;
    if (basefd < 0) code = "directory_unavailable";
    message = strerror(saved);
done:
    if (message) {
        if (saved == ENOENT && !strcmp(code, "directory_unavailable")) code = "directory_missing";
        else if (saved == ENOTDIR || saved == ELOOP) code = "directory_not_directory";
        else if (saved == EACCES || saved == EPERM) code = "directory_permission";
        else if (saved == EROFS) code = "directory_read_only";
        if (!strcmp(code, "directory_missing") || !strcmp(code, "directory_not_directory") ||
            !strcmp(code, "directory_permission") || !strcmp(code, "directory_read_only") ||
            !strcmp(code, "rootfs_missing"))
            pdn_events_problem(code, message, "Check the selected rootfs directory and retry");
        else pdn_events_system_problem(code, message,
            !strcmp(code, "operation_busy") ? "Wait for other operations to finish and exit rootfs sessions before uninstall" :
            !strcmp(code, "lock_failed") ? "Check filesystem locking support and permissions; inspect stderr" :
            "Check the selected rootfs and retry uninstall", saved);
    }
    if (message) fprintf(stderr, "pdn: %s: %s\n", message, requested);
    if (dir) closedir(dir);
    if (rootfd >= 0) close(rootfd);
    if (lock >= 0) close(lock);
    if (basefd >= 0) close(basefd);
    free(name); free(resolved); free(base);
    return result;
}
