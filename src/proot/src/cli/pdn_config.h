#ifndef PDN_CONFIG_H
#define PDN_CONFIG_H

#define PDN_OPTION_MAX 256

typedef struct {
    char *binds[PDN_OPTION_MAX];
    char *env[PDN_OPTION_MAX];
    int bind_count;
    int env_count;
    char *user;
    char *workdir;
} PdnOptions;

typedef struct {
    char *name;
    char *home;
    char *shell;
    char ids[64];
} PdnIdentity;

int pdn_option_add(PdnOptions *options, char kind, const char *value);
int pdn_options_load(int rootfd, PdnOptions *options);
int pdn_options_save(int rootfd, const PdnOptions *options);
int pdn_options_clear(int rootfd);
void pdn_options_show(const PdnOptions *options);
void pdn_options_free(PdnOptions *options);
int pdn_identity(int rootfd, const char *user, PdnIdentity *identity);
void pdn_identity_free(PdnIdentity *identity);

#endif
