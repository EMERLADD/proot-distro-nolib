#ifndef PDN_INSTANCE_H
#define PDN_INSTANCE_H

#include <stdint.h>
#include <stdio.h>

struct pdn_instance {
    char id[33];
    char name[129];
    char distro[129];
    char distro_version[129];
    char architecture[16];
    char source[16];
    char source_url[2049];
    char sha256[65];
    int64_t created_at;
};

int pdn_instance_valid_name(const char *name);
int pdn_instance_read(int rootfd, struct pdn_instance *instance);
void pdn_instance_json(FILE *stream, const struct pdn_instance *instance);
int pdn_instance_create(int rootfd, const char *name, const char *distro,
                        const char *version, const char *sha256,
                        const char *source, const char *url);
int pdn_instance_restore(int rootfd, const char *name);

#endif
