# Bloem Admin against a plain Silo server

Screenshots of this app signed in to a plain upstream Silo server (Silo-Server `c11b0a96`, a throwaway sandbox with
synthetic data only), on an Android 15 emulator. Every page of the drawer and every detail screen was opened.

- `01`-`16`: All servers (drawer, every page) and the single server's drawer. There is no Platform group.
- `20`-`33`: every page of the single-server drawer.
- `40`-`46`, `50`-`58`: detail screens: a user (Profiles, Access & limits), Add user, Invitations, a play, an audit entry,
  a log entry, a task, background jobs, a request, library actions, plugin actions, backup actions.
- `60`-`61`: Manage servers and App settings.
- `70`-`80`: every Settings page.
- `90`-`93`: the debug fixture screen, a Bloem server against a plain Silo server (`bloemfixture://...&server=silo`):
  Organizations and Support access appear only on the Bloem server.

The emulator reached the sandbox over HTTPS through a throwaway local TLS tunnel, because the app accepts only HTTPS.
