/*
 * standby.c - puts the PS4 into rest mode, then exits.
 *
 * Sent by PS4 Download Monitor to GoldHEN's payload loader (TCP 9090) when a download marked
 * "Turn the PS4 off when the download finishes" is done.
 *
 * NOT built and NOT tried on a console by the author of this change. Build it with a PS4 payload
 * toolchain (for example ps4-payload-sdk: `make`), name the result standby.elf, then either put it in
 * app/src/main/assets/standby.elf before building the app, or choose it in the app:
 * PS4 settings > "Turn off after downloads" > "Choose standby.elf".
 *
 * It only resolves one system function by name and calls it:
 *   libSceSystemStateMgr.sprx : sceSystemStateMgrEnterStandby()
 * If your firmware / payload loader does not export it, the app reports "the PS4 is still on".
 */
#include <stddef.h>

extern int sceKernelLoadStartModule(const char *name, size_t argc, const void *argv, unsigned flags, void *opt, int *res);
extern int sceKernelDlsym(int handle, const char *symbol, void **addr);

int main(void) {
    int h = sceKernelLoadStartModule("libSceSystemStateMgr.sprx", 0, NULL, 0, NULL, NULL);
    if (h < 0) return 1;
    int (*enter_standby)(void) = NULL;
    if (sceKernelDlsym(h, "sceSystemStateMgrEnterStandby", (void **)&enter_standby) != 0 || !enter_standby) return 2;
    return enter_standby();
}
