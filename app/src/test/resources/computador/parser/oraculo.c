/* Oráculo de teste: usa o parser.c real, sem JNI nem uma segunda implementação do parser. */
#include "vterm_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    VTerm *vt;
    unsigned proibidos;
    int modo, selecao;
    unsigned char ultimos[16];
    size_t textos;
} Observacao;
static int texto(const char *bytes, size_t len, void *user) {
    Observacao *o = user;
    size_t i = 0;
    for (; i < len; i++) {
        unsigned char b = bytes[i];
        if (b < 32 || b == 127 || (!vterm_get_utf8(o->vt) && b >= 128 && b < 160)) break;
        o->ultimos[o->textos++ % 16] = b;
    }
    return i;
}
static int controle(unsigned char c, void *user) {
    if (c == 5) ((Observacao *)user)->proibidos |= 1;
    return 1;
}
static int escape(const char *s, size_t n, void *user) {
    Observacao *o = user;
    if (n == 2 && !memcmp(s, " G", 2)) o->proibidos |= 2;
    if (n == 2 && !memcmp(s, "%@", 2)) vterm_set_utf8(o->vt, 0);
    if (n == 2 && !memcmp(s, "%G", 2)) vterm_set_utf8(o->vt, 1);
    return 1;
}
static int csi(const char *lider, const long args[], int n, const char *inter, char final, void *user) {
    Observacao *o = user;
    if (!lider && !inter && final == 't' && (CSI_ARG(args[0]) == 20 || CSI_ARG(args[0]) == 21)) o->proibidos |= 4;
    if (lider && !strcmp(lider, "?") && !inter && (final == 'h' || final == 'l')) {
        for (int i = 0; i < n; i++) if (CSI_ARG(args[i]) == 1) o->modo = final == 'h';
    }
    return 1;
}
static int osc(int comando, VTermStringFragment f, void *user) {
    Observacao *o = user;
    if (comando == 21) o->proibidos |= 8;
    if (f.initial) o->selecao = 0;
    if (comando == 52) for (size_t i = 0; i < f.len; i++) {
        if (o->selecao == 0) { if (f.str[i] == ';') o->selecao = 1; }
        else if (o->selecao == 1) { if (f.str[i] == '?') o->proibidos |= 16; o->selecao = 2; }
    }
    return 1;
}
static int dcs(const char *comando, size_t n, VTermStringFragment f, void *user) {
    (void)f;
    if (n == 2 && !memcmp(comando, "$q", 2)) ((Observacao *)user)->proibidos |= 32;
    return 1;
}
int main(int argc, char **argv) {
    char *linha = NULL; size_t cap = 0; ssize_t n;
    const VTermParserCallbacks cb = { .text = texto, .control = controle, .escape = escape, .csi = csi, .osc = osc, .dcs = dcs };
    while ((n = getline(&linha, &cap, stdin)) > 0) {
        Observacao o = {0}; o.vt = vterm_new(24, 80);
        vterm_set_utf8(o.vt, argc < 2 || strcmp(argv[1], "8bit"));
        vterm_parser_set_callbacks(o.vt, &cb, &o);
        unsigned char *bytes = malloc((size_t)n/2+1); size_t len = 0;
        for (ssize_t i = 0; i+1 < n && linha[i] != '\n'; i += 2) { unsigned x; if (sscanf(linha+i, "%2x", &x) != 1) return 2; bytes[len++] = x; }
        vterm_input_write(o.vt, (const char *)bytes, len);
        printf("%u %d %d ", o.proibidos, o.modo, o.vt->parser.state);
        size_t inicio = o.textos > 16 ? o.textos-16 : 0;
        for (size_t i = inicio; i < o.textos; i++) printf("%02x", o.ultimos[i % 16]);
        printf("\n"); free(bytes); vterm_free(o.vt);
    }
    free(linha); return 0;
}
