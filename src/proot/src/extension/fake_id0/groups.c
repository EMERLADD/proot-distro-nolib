#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include "extension/fake_id0/groups.h"
#include "tracee/mem.h"
#include "tracee/reg.h"

static int compare_group(const void *left, const void *right)
{
    gid_t a = *(const gid_t *)left, b = *(const gid_t *)right;
    return (a > b) - (a < b);
}

int fake_groups_copy(Config *destination, const Config *source)
{
    destination->groups = NULL;
    if (!source->group_count) return 0;
    destination->groups = talloc_memdup(destination, source->groups, source->group_count * sizeof(gid_t));
    return destination->groups ? 0 : -ENOMEM;
}

int fake_groups_exit(Tracee *tracee, Config *config, int setting, int narrow)
{
    int count = (int)peek_reg(tracee, ORIGINAL, SYSARG_1), result = 0;
    word_t address = peek_reg(tracee, ORIGINAL, SYSARG_2);
    gid_t *groups = NULL;
    uint16_t *short_groups = NULL;
    size_t i;
    if (setting && config->euid != 0) { result = -EPERM; goto done; }
    if (count < 0 || (setting && count > 65536)) { result = -EINVAL; goto done; }
    if (!setting && count == 0) { result = (int)config->group_count; goto done; }
    if (!setting && (size_t)count < config->group_count) { result = -EINVAL; goto done; }
    size_t length = setting ? (size_t)count : config->group_count;
    if (length && narrow) {
        short_groups = talloc_array(tracee->ctx, uint16_t, length);
        if (!short_groups) { result = -ENOMEM; goto done; }
    }
    if (setting) {
        if (length) {
            groups = talloc_array(config, gid_t, length);
            if (!groups) { result = -ENOMEM; goto done; }
            result = read_data(tracee, narrow ? (void *)short_groups : (void *)groups,
                               address, length * (narrow ? sizeof(uint16_t) : sizeof(gid_t)));
            if (result < 0) { result = -EFAULT; goto done; }
            if (narrow) for (i = 0; i < length; i++) groups[i] = short_groups[i];
            for (i = 0; i < length; i++) if (groups[i] == (gid_t)-1) { result = -EINVAL; goto done; }
            qsort(groups, length, sizeof(gid_t), compare_group);
        }
        talloc_free(config->groups);
        config->groups = groups;
        config->group_count = length;
        groups = NULL;
    } else {
        if (narrow) for (i = 0; i < length; i++)
            short_groups[i] = config->groups[i] > UINT16_MAX ? 65534 : config->groups[i];
        if (length) result = write_data(tracee, address,
            narrow ? (void *)short_groups : (void *)config->groups,
            length * (narrow ? sizeof(uint16_t) : sizeof(gid_t)));
        result = result < 0 ? -EFAULT : (int)length;
    }
 done:
    talloc_free(groups);
    talloc_free(short_groups);
    poke_reg(tracee, SYSARG_RESULT, result);
    return 0;
}
