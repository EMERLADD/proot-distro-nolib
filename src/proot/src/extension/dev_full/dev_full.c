#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/uio.h>
#include <unistd.h>

#include "extension/dev_full/dev_full.h"
#include "path/path.h"
#include "path/temp.h"
#include "syscall/sysnum.h"
#include "tracee/mem.h"
#include "tracee/statx.h"

typedef struct {
	const char *path;
	struct stat backing;
} DevFullConfig;

#define DEV_FULL_RESULT_MARKER ((word_t) 0x64657666756c6c)

static word_t memory_address(const Tracee *tracee, word_t address)
{
#if defined(ARCH_ARM64)
	if (sizeof_word(tracee) == 8)
		return address & UINT64_C(0x00ffffffffffffff);
#else
	(void) tracee;
#endif
	return address;
}

static int native_full_status(void)
{
	struct stat st;
	if (lstat("/dev/full", &st) < 0)
		return errno == ENOENT || errno == EACCES ? 0 : -errno;
	if (!S_ISCHR(st.st_mode) || st.st_rdev != makedev(1, 7))
		return 1;
	if (access("/dev/full", R_OK | W_OK) == 0)
		return 1;
	return errno == EACCES ? 0 : -errno;
}

static const FilteredSysnum dev_full_sysnums[] = {
	{ PR_read, FILTER_SYSEXIT }, { PR_pread64, FILTER_SYSEXIT },
	{ PR_readv, FILTER_SYSEXIT }, { PR_preadv, FILTER_SYSEXIT },
	{ PR_preadv2, FILTER_SYSEXIT }, { PR_write, FILTER_SYSEXIT },
	{ PR_pwrite64, FILTER_SYSEXIT }, { PR_writev, FILTER_SYSEXIT },
	{ PR_pwritev, FILTER_SYSEXIT }, { PR_pwritev2, FILTER_SYSEXIT },
	{ PR_lseek, FILTER_SYSEXIT }, { PR_mmap, FILTER_SYSEXIT },
	{ PR_mmap2, FILTER_SYSEXIT }, { PR_ftruncate, FILTER_SYSEXIT },
	{ PR_ftruncate64, FILTER_SYSEXIT }, { PR_ioctl, FILTER_SYSEXIT },
	{ PR_fsync, FILTER_SYSEXIT }, { PR_fdatasync, FILTER_SYSEXIT },
	{ PR_splice, FILTER_SYSEXIT }, { PR_sendfile, FILTER_SYSEXIT },
	{ PR_sendfile64, FILTER_SYSEXIT }, { PR_copy_file_range, FILTER_SYSEXIT },
	{ PR_fallocate, FILTER_SYSEXIT }, { PR_fstat, FILTER_SYSEXIT },
	{ PR_fstat64, FILTER_SYSEXIT }, { PR_stat, FILTER_SYSEXIT },
	{ PR_stat64, FILTER_SYSEXIT }, { PR_lstat, FILTER_SYSEXIT },
	{ PR_lstat64, FILTER_SYSEXIT }, { PR_newfstatat, FILTER_SYSEXIT },
	{ PR_fstatat64, FILTER_SYSEXIT },
	{ PR_statx, FILTER_SYSEXIT },
	FILTERED_SYSNUM_END,
};

static bool same_inode(const DevFullConfig *config, const struct stat *st)
{
	return st->st_dev == config->backing.st_dev && st->st_ino == config->backing.st_ino;
}

static bool full_fd(Tracee *tracee, const DevFullConfig *config, word_t fd)
{
	char path[64];
	struct stat st;
	snprintf(path, sizeof(path), "/proc/%d/fd/%d", tracee->pid, (int) fd);
	return stat(path, &st) == 0 && same_inode(config, &st);
}

static int fd_flags(Tracee *tracee, word_t fd, unsigned long *flags)
{
	char path[64], line[256];
	FILE *fp;
	int status = -EBADF;
	snprintf(path, sizeof(path), "/proc/%d/fdinfo/%d", tracee->pid, (int) fd);
	fp = fopen(path, "r");
	if (fp == NULL)
		return status;
	while (fgets(line, sizeof(line), fp) != NULL) {
		if (sscanf(line, "flags:\t%lo", flags) == 1) {
			status = 0;
			break;
		}
	}
	fclose(fp);
	return status;
}

static size_t accessible_span(Tracee *tracee, word_t address, size_t count, bool writing)
{
	char path[64], line[512], permissions[5];
	unsigned long long start, end;
	FILE *fp;
	size_t result = 0;
	address = memory_address(tracee, address);
	if (count == 0 || address == 0 || count > UINTPTR_MAX - address)
		return 0;
	snprintf(path, sizeof(path), "/proc/%d/maps", tracee->pid);
	fp = fopen(path, "r");
	if (fp == NULL)
		return 0;
	while (fgets(line, sizeof(line), fp) != NULL) {
		if (sscanf(line, "%llx-%llx %4s", &start, &end, permissions) != 3)
			continue;
		if (start <= address && address < end) {
			if (permissions[writing ? 1 : 0] == (writing ? 'w' : 'r'))
				result = end - address < count ? end - address : count;
			break;
		}
	}
	fclose(fp);
	return result;
}

static int copy_output(Tracee *tracee, word_t address, const void *data, size_t count)
{
	size_t done = 0;
	address = memory_address(tracee, address);
	while (done < count) {
		size_t span = accessible_span(tracee, address + done, count - done, true);
		int status;
		if (span == 0)
			return -EFAULT;
		status = write_data(tracee, address + done, (const char *) data + done, span);
		if (status < 0)
			return status;
		done += span;
	}
	return 0;
}

static long zero_read(Tracee *tracee, word_t address, size_t count)
{
	static const char zeros[4096] = { 0 };
	size_t done = 0;
	address = memory_address(tracee, address);
	if (count > 65536)
		count = 65536;
	while (done < count) {
		size_t chunk = count - done < sizeof(zeros) ? count - done : sizeof(zeros);
		size_t span;
		if (address > UINTPTR_MAX - done)
			return done ? (long) done : -EFAULT;
		span = accessible_span(tracee, address + done, chunk, true);
		if (span == 0 || write_data(tracee, address + done, zeros, span) < 0)
			return done ? (long) done : -EFAULT;
		done += span;
	}
	return done;
}

static long vector_io(Tracee *tracee, bool writing, word_t address, word_t count)
{
	struct iovec vectors[1024];
	size_t total = 0, done = 0;
	word_t i;
	address = memory_address(tracee, address);
	if ((long) count < 0 || count > 1024)
		return -EINVAL;
	if (count == 0)
		return 0;
	{
		size_t length = count * sizeof(*vectors), done = 0;
		while (done < length) {
			size_t span = accessible_span(tracee, address + done, length - done, false);
			if (span == 0)
				return -EFAULT;
			done += span;
		}
	}
	if (read_data(tracee, vectors, address, count * sizeof(*vectors)) < 0)
		return -EFAULT;
	for (i = 0; i < count; i++) {
		if (vectors[i].iov_len > SSIZE_MAX - total)
			return -EINVAL;
		total += vectors[i].iov_len;
	}
	if (writing)
		return total ? -ENOSPC : 0;
	for (i = 0; i < count && done < 65536; i++) {
		size_t chunk = vectors[i].iov_len;
		long result;
		if (chunk > 65536 - done)
			chunk = 65536 - done;
		result = zero_read(tracee, (word_t) vectors[i].iov_base, chunk);
		if (result < 0)
			return done ? (long) done : result;
		done += result;
		if ((size_t) result < chunk)
			break;
	}
	return done;
}

static int finish(Tracee *tracee, long result)
{
	set_sysnum(tracee, PR_void);
	poke_reg(tracee, SYSARG_RESULT, (word_t) result);
	poke_reg(tracee, SYSARG_6, DEV_FULL_RESULT_MARKER);
	return 1;
}

static void device_stat(struct stat *st)
{
	st->st_mode = S_IFCHR | 0666;
	st->st_rdev = makedev(1, 7);
	st->st_size = 0;
	st->st_blocks = 0;
	st->st_uid = 0;
	st->st_gid = 0;
	st->st_nlink = 1;
}

static int stat_enter(Tracee *tracee, const DevFullConfig *config, Sysnum num)
{
	struct stat st;
	char guest[PATH_MAX], host[PATH_MAX];
	Reg output = SYSARG_2;
	word_t fd = peek_reg(tracee, ORIGINAL, SYSARG_1);
	int status;
	bool at = num == PR_newfstatat || num == PR_fstatat64;
	if (num == PR_fstat || num == PR_fstat64) {
		if (!full_fd(tracee, config, fd))
			return 0;
		st = config->backing;
	} else {
		Reg path_arg = at ? SYSARG_2 : SYSARG_1;
		int flags = at ? peek_reg(tracee, ORIGINAL, SYSARG_4) : 0;
		status = read_string(tracee, guest, peek_reg(tracee, ORIGINAL, path_arg), sizeof(guest));
		if (status <= 0 || status >= PATH_MAX)
			return 0;
		if (at && guest[0] == '\0' && (flags & AT_EMPTY_PATH)) {
			if (!full_fd(tracee, config, fd))
				return 0;
			st = config->backing;
		} else {
			bool deref = num != PR_lstat && num != PR_lstat64 && !(flags & AT_SYMLINK_NOFOLLOW);
			status = translate_path(tracee, host, at ? (int) fd : AT_FDCWD, guest, deref);
			if (status < 0 || (deref ? stat(host, &st) : lstat(host, &st)) < 0 || !same_inode(config, &st))
				return 0;
		}
		if (at) {
			if (flags & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT))
				return finish(tracee, -EINVAL);
			output = SYSARG_3;
		}
	}
	if (sizeof_word(tracee) != 8)
		return finish(tracee, -EOPNOTSUPP);
	device_stat(&st);
	status = copy_output(tracee, peek_reg(tracee, ORIGINAL, output), &st, sizeof(st));
	return finish(tracee, status);
}

static int statx_enter(Tracee *tracee, const DevFullConfig *config)
{
	char guest[PATH_MAX], host[PATH_MAX];
	struct stat st;
	struct statx output = { 0 };
	word_t fd = peek_reg(tracee, ORIGINAL, SYSARG_1);
	word_t flags = peek_reg(tracee, ORIGINAL, SYSARG_3);
	word_t mask = peek_reg(tracee, ORIGINAL, SYSARG_4);
	bool deref = !(flags & AT_SYMLINK_NOFOLLOW);
	int status = read_string(tracee, guest,
		memory_address(tracee, peek_reg(tracee, ORIGINAL, SYSARG_2)), sizeof(guest));
	if (status <= 0 || status >= PATH_MAX)
		return 0;
	if (guest[0] == '\0' && (flags & AT_EMPTY_PATH)) {
		if (!full_fd(tracee, config, fd))
			return 0;
		st = config->backing;
	} else {
		status = translate_path(tracee, host, (int) fd, guest, deref);
		if (status < 0 || (deref ? stat(host, &st) : lstat(host, &st)) < 0 || !same_inode(config, &st))
			return 0;
	}
	if (sizeof_word(tracee) != 8)
		return finish(tracee, -EOPNOTSUPP);
	if ((flags & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT | 0x6000)) ||
	    (flags & 0x6000) == 0x6000 || (mask & UINT32_C(0x80000000)))
		return finish(tracee, -EINVAL);
	output.stx_mask = STATX_BASIC_STATS;
	output.stx_blksize = st.st_blksize;
	output.stx_mode = S_IFCHR | 0666;
	output.stx_nlink = 1;
	output.stx_ino = st.st_ino;
	output.stx_atime.tv_sec = st.st_atim.tv_sec;
	output.stx_atime.tv_nsec = st.st_atim.tv_nsec;
	output.stx_mtime.tv_sec = st.st_mtim.tv_sec;
	output.stx_mtime.tv_nsec = st.st_mtim.tv_nsec;
	output.stx_ctime.tv_sec = st.st_ctim.tv_sec;
	output.stx_ctime.tv_nsec = st.st_ctim.tv_nsec;
	output.stx_dev_major = major(st.st_dev);
	output.stx_dev_minor = minor(st.st_dev);
	output.stx_rdev_major = 1;
	output.stx_rdev_minor = 7;
	status = copy_output(tracee, peek_reg(tracee, ORIGINAL, SYSARG_5), &output, sizeof(output));
	return finish(tracee, status);
}

static int syscall_enter(Tracee *tracee, const DevFullConfig *config)
{
	Sysnum num = get_sysnum(tracee, ORIGINAL);
	word_t fd = peek_reg(tracee, ORIGINAL, SYSARG_1);
	word_t address = peek_reg(tracee, ORIGINAL, SYSARG_2);
	word_t count = peek_reg(tracee, ORIGINAL, SYSARG_3);
	unsigned long flags;
	bool reading = false, writing = false, vector = false, positioned = false;
	long result;
	switch (num) {
	case PR_statx:
		return statx_enter(tracee, config);
	case PR_stat: case PR_stat64: case PR_lstat: case PR_lstat64:
	case PR_fstat: case PR_fstat64: case PR_newfstatat: case PR_fstatat64:
		return stat_enter(tracee, config, num);
	case PR_mmap: case PR_mmap2:
		if (peek_reg(tracee, ORIGINAL, SYSARG_4) & MAP_ANONYMOUS)
			return 0;
		fd = peek_reg(tracee, ORIGINAL, SYSARG_5);
		break;
	case PR_splice: case PR_copy_file_range:
		if (full_fd(tracee, config, peek_reg(tracee, ORIGINAL, SYSARG_3)))
			return finish(tracee, -EINVAL);
		break;
	case PR_sendfile: case PR_sendfile64:
		if (full_fd(tracee, config, address))
			return finish(tracee, -EINVAL);
		break;
	case PR_read: reading = true; break;
	case PR_write: writing = true; break;
	case PR_pread64: reading = positioned = true; break;
	case PR_pwrite64: writing = positioned = true; break;
	case PR_readv: reading = vector = true; break;
	case PR_writev: writing = vector = true; break;
	case PR_preadv: reading = vector = positioned = true; break;
	case PR_pwritev: writing = vector = positioned = true; break;
	case PR_preadv2: case PR_pwritev2: case PR_lseek:
	case PR_ftruncate: case PR_ftruncate64: case PR_ioctl:
	case PR_fsync: case PR_fdatasync: case PR_fallocate:
		break;
	default:
		return 0;
	}
	if (!full_fd(tracee, config, fd))
		return 0;
	if (sizeof_word(tracee) != 8)
		return finish(tracee, -EOPNOTSUPP);
	if (fd_flags(tracee, fd, &flags) < 0 || (flags & O_PATH))
		return finish(tracee, -EBADF);
	if ((reading && (flags & O_ACCMODE) == O_WRONLY) ||
	    (writing && (flags & O_ACCMODE) == O_RDONLY))
		return finish(tracee, -EBADF);
	if (reading || writing) {
		if (positioned && (int64_t) peek_reg(tracee, ORIGINAL, SYSARG_4) < 0)
			return finish(tracee, -EINVAL);
		if (vector)
			result = vector_io(tracee, writing, address, count);
		else if ((long) count < 0)
			result = -EINVAL;
		else
			result = writing ? -ENOSPC : zero_read(tracee, address, count);
		return finish(tracee, result);
	}
	switch (num) {
	case PR_lseek:
		return finish(tracee, count > 4 ? -EINVAL : 0);
	case PR_mmap: case PR_mmap2:
		return finish(tracee, -ENODEV);
	case PR_ioctl:
		return finish(tracee, -ENOTTY);
	case PR_preadv2: case PR_pwritev2:
		return finish(tracee, -EOPNOTSUPP);
	default:
		return finish(tracee, -EINVAL);
	}
}

static int translated_path(Tracee *tracee, const DevFullConfig *config, char *path)
{
	Sysnum num = get_sysnum(tracee, ORIGINAL);
	if (strcmp(path, "/dev/full") == 0) {
		if (native_full_status() != 0)
			return 0;
		if (sizeof_word(tracee) != 8)
			return -EOPNOTSUPP;
		strcpy(path, config->path);
	}
	if (strcmp(path, config->path) != 0)
		return 0;
	switch (num) {
	case PR_unlink: case PR_unlinkat: case PR_rename:
	case PR_renameat: case PR_renameat2: case PR_link: case PR_linkat:
		return -EPERM;
	case PR_truncate: case PR_truncate64:
		return -EINVAL;
	default:
		return 0;
	}
}

int dev_full_callback(Extension *extension, ExtensionEvent event, intptr_t data1, intptr_t data2 UNUSED)
{
	Tracee *tracee = TRACEE(extension);
	DevFullConfig *config = extension->config;
	switch (event) {
	case INITIALIZATION: {
		int native_status = native_full_status();
		if (native_status > 0)
			return 0;
		if (native_status < 0)
			return native_status;
		if (sizeof(word_t) != 8)
			return -EOPNOTSUPP;
		config = talloc_zero(extension, DevFullConfig);
		if (config == NULL)
			return -ENOMEM;
		extension->config = config;
		errno = 0;
		config->path = create_temp_file(config, "pdn-full");
		if (config->path == NULL)
			return errno ? -errno : -ENOMEM;
		if (stat(config->path, &config->backing) < 0)
			return -errno;
		extension->filtered_sysnums = dev_full_sysnums;
		return 0;
	}
	case INHERIT_PARENT:
		return 1;
	case INHERIT_CHILD: {
		Extension *parent = (Extension *) data1;
		extension->config = parent->config == NULL ? NULL : talloc_reference(extension, parent->config);
		extension->filtered_sysnums = parent->filtered_sysnums;
		return 0;
	}
	default:
		break;
	}
	if (config == NULL)
		return 0;
	switch (event) {
	case TRANSLATED_PATH:
		return translated_path(tracee, config, (char *) data1);
	case SYSCALL_ENTER_START:
		return syscall_enter(tracee, config);
	case SYSCALL_EXIT_START:
		if (get_sysnum(tracee, MODIFIED) == PR_void && tracee->status >= 0 &&
		    peek_reg(tracee, MODIFIED, SYSARG_6) == DEV_FULL_RESULT_MARKER) {
			poke_reg(tracee, SYSARG_RESULT, peek_reg(tracee, MODIFIED, SYSARG_RESULT));
			return 1;
		}
		return 0;
	case STATX_SYSCALL: {
		struct statx_syscall_state *state = (struct statx_syscall_state *) data1;
		struct stat st;
		if (stat(state->host_path, &st) == 0 && same_inode(config, &st)) {
			state->statx_buf.stx_mode = S_IFCHR | 0666;
			state->statx_buf.stx_rdev_major = 1;
			state->statx_buf.stx_rdev_minor = 7;
			state->statx_buf.stx_size = 0;
			state->statx_buf.stx_blocks = 0;
			state->statx_buf.stx_uid = 0;
			state->statx_buf.stx_gid = 0;
			state->statx_buf.stx_nlink = 1;
			state->updated_stats = true;
		}
		return 0;
	}
	default:
		return 0;
	}
}
