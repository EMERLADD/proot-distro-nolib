#ifndef PDN_EXEC_POLICY_H
#define PDN_EXEC_POLICY_H
struct tracee;
/* Per-invocation rules live in the tracer, shared by all traced descendants. */
#define PDN_EXEC_RULE_LIMIT 128
typedef struct { const char *program, *cwd; int command; } PdnExecRule;
extern PdnExecRule pdn_exec_rules[PDN_EXEC_RULE_LIMIT];
extern unsigned pdn_exec_rule_count;
int pdn_exec_policy_check(struct tracee *tracee, const char *guest_program);
int pdn_exec_policy_check_argv(struct tracee *tracee, const char *guest_program);
int pdn_exec_policy_check_applet(struct tracee *tracee, const char *guest_program, const char *applet);
#endif
