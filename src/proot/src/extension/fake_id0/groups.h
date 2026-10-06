#ifndef FAKE_ID0_GROUPS_H
#define FAKE_ID0_GROUPS_H
#include "extension/fake_id0/config.h"
#include "tracee/tracee.h"
int fake_groups_exit(Tracee *tracee, Config *config, int setting, int narrow);
int fake_groups_copy(Config *destination, const Config *source);
#endif
