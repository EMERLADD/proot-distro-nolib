#ifndef PDN_RECOVERY_H
#define PDN_RECOVERY_H

#include <stddef.h>

int pdn_recovery_begin(int basefd, char *stage, size_t size, const char *target);
int pdn_recovery_phase(int basefd, const char *stage, const char *phase);
int pdn_recovery(const char *id, int clearing, int json);

#endif
