#include <stddef.h>
extern int sceKernelLoadStartModule(const char *name, size_t argc, const void *argv, unsigned flags, void *opt, int *res);
extern int sceKernelDlsym(int handle, const char *symbol, void **addr);

/* Loads libSceSystemStateMgr (tries three places, none of them verified on a console) and calls one exported function by name.
 * Exit code: 0 or the function's own return value; 1 module not found; 2 function not exported. */
static int call_statemgr(const char *symbol) {
    static const char *paths[] = { "libSceSystemStateMgr.sprx", "/system/common/lib/libSceSystemStateMgr.sprx", "/system/priv/lib/libSceSystemStateMgr.sprx" };
    int h = -1;
    for (unsigned i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        h = sceKernelLoadStartModule(paths[i], 0, NULL, 0, NULL, NULL);
        if (h >= 0) break;
    }
    if (h < 0) return 1;
    int (*fn)(void) = NULL;
    if (sceKernelDlsym(h, symbol, (void **)&fn) != 0 || !fn) return 2;
    return fn();
}
