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

char *pdn_rootfs_base(void);

static int same_entry(int parent, const char *name, const struct stat *opened)
{
    struct stat current;
    if (fstatat(parent, name, &current, AT_SYMLINK_NOFOLLOW) < 0) return 0;
    if (current.st_dev == opened->st_dev && current.st_ino == opened->st_ino) return 1;
    errno = ESTALE;
    return 0;
}

static int remove_contents(int fd, dev_t device)
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
            continue;
        }
        child = openat(fd, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (child < 0) break;
        if (fstat(child, &opened) < 0) { close(child); break; }
        if (opened.st_dev != device || opened.st_ino != st.st_ino || opened.st_dev != st.st_dev) {
            close(child); errno = EXDEV; break;
        }
        if (fchmod(child, opened.st_mode | S_IRWXU) < 0 || remove_contents(child, device) < 0) {
            saved = errno; close(child); errno = saved; break;
        }
        close(child);
        if (!same_entry(fd, entry->d_name, &opened) || unlinkat(fd, entry->d_name, AT_REMOVEDIR) < 0) break;
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
    const char *message = NULL;
    if (!base) { message = "set PDN_ROOTFS_DIR or HOME"; goto done; }
    resolved = realpath(base, NULL);
    if (!resolved) goto system_error;
    if (!strcmp(resolved, "/")) { message = "rootfs parent must not be /"; goto done; }
    basefd = open(resolved, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (basefd < 0 || fstat(basefd, &parent) < 0) goto system_error;
    lock = openat(basefd, ".pdn-install.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) {
        message = "cannot acquire install/uninstall lock"; goto done;
    }
    dir = fdopendir(dup(basefd));
    if (!dir) goto system_error;
    for (;;) {
        errno = 0;
        entry = readdir(dir);
        if (!entry) { if (errno) goto system_error; break; }
        if (strcasecmp(entry->d_name, requested)) continue;
        if (name) { message = "ambiguous rootfs name"; goto done; }
        name = strdup(entry->d_name);
        if (!name) goto system_error;
    }
    if (!name) { message = "rootfs not found"; goto done; }
    rootfd = openat(basefd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (rootfd < 0) { message = "rootfs must be a real directory, not a symlink"; goto done; }
    if (fstat(rootfd, &root) < 0) goto system_error;
    if (root.st_dev != parent.st_dev || root.st_ino == parent.st_ino) {
        message = "refusing rootfs on another filesystem or parent directory"; goto done;
    }
    if (flock(rootfd, LOCK_EX | LOCK_NB) < 0) {
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
    if (fchmod(rootfd, root.st_mode | S_IRWXU) < 0 || remove_contents(rootfd, root.st_dev) < 0 ||
        !same_entry(basefd, name, &root) || unlinkat(basefd, name, AT_REMOVEDIR) < 0) {
        fprintf(stderr, "pdn: uninstall incomplete: %s; remaining files kept at %s/%s\n",
                strerror(errno), resolved, name);
        goto done;
    }
    printf("Uninstalled %s.\n", name);
    result = 0;
    goto done;
system_error:
    message = strerror(errno);
done:
    if (message) fprintf(stderr, "pdn: %s: %s\n", message, requested);
    if (dir) closedir(dir);
    if (rootfd >= 0) close(rootfd);
    if (lock >= 0) close(lock);
    if (basefd >= 0) close(basefd);
    free(name); free(resolved); free(base);
    return result;
}
