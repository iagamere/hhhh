standby.elf = a tiny payload that puts the PS4 in rest mode (see standby.c).
The app cannot do this by itself: the download server (server.elf) and the console's discovery protocol have no "rest mode" command.
Not built or tested by the author of this change. If the PS4 does not go to rest mode, the app says so after 40 s.
