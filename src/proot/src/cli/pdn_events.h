#ifndef PDN_EVENTS_H
#define PDN_EVENTS_H

int pdn_events_begin(const char *operation);
void pdn_events_disable(void);
void pdn_events_finish(int status);
void pdn_events_stage(const char *stage);
void pdn_events_progress(long long current, long long total);
void pdn_events_problem(const char *code, const char *message, const char *suggestion);
void pdn_events_system_problem(const char *fallback, const char *message, const char *suggestion, int saved_errno);
int pdn_events_has_error(void);
void pdn_events_clear_error(void);
void pdn_events_error(const char *message);
void pdn_events_guest_loaded(void);
void pdn_events_guest_exit(int status, int signal);
void pdn_events_cancelled(int signal);

#endif
