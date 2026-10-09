#ifndef PDN_JSON_H
#define PDN_JSON_H

#include <stdio.h>

static inline void pdn_json_string(FILE *stream, const char *value)
{
    const unsigned char *p = (const unsigned char *)value;
    fputc('"', stream);
    for (; *p; p++) {
        if (*p == '"' || *p == '\\') {
            fputc('\\', stream);
            fputc(*p, stream);
        } else if (*p < 32) fprintf(stream, "\\u%04x", *p);
        else fputc(*p, stream);
    }
    fputc('"', stream);
}

#endif
