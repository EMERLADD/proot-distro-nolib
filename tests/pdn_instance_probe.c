#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "pdn_instance.h"

int main(int argc, char **argv)
{
    struct pdn_instance instance;
    int result = -1;
    if (argc == 3 && !strcmp(argv[1], "name"))
        return pdn_instance_valid_name(argv[2]) ? 0 : 2;
    if (argc < 3) return 2;
    int rootfd = open(argv[2], O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (!strcmp(argv[1], "read")) {
        result = pdn_instance_read(rootfd, &instance);
        if (result == 1) pdn_instance_json(stdout, &instance);
        else if (result == 0) fputs("null", stdout);
    } else if (argc == 9 && !strcmp(argv[1], "create")) {
        result = pdn_instance_create(rootfd, argv[3], argv[4], argv[5], argv[6], argv[7], argv[8]);
    } else if (argc == 4 && !strcmp(argv[1], "restore")) {
        result = pdn_instance_restore(rootfd, argv[3]);
    }
    int saved = errno;
    if (rootfd >= 0) close(rootfd);
    if (result < 0) fprintf(stderr, "errno=%d\n", saved);
    return result < 0 ? 2 : 0;
}
