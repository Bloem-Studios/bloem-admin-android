# Features

Bloem Admin is a native Android app for phones and tablets that lets a server administrator look after one or more Silo or
Bloem servers. You need an administrator account on a server you can reach over HTTPS. It is not a viewer app and has no
Android TV build.

> Bloem Admin is offered without support. See [Offered without support](../README.md) in the README.

For build and install steps see the [README](../README.md). For every screen and control see the
[User guide](user-guide.md). For people who build and run the app see the [Admin guide](admin-guide.md).

## Servers and sign-in

- **Several servers.** Add as many as you like. The address must be an HTTPS origin. Adding a server checks it, signs you in
  and confirms the account is an administrator before anything is saved.
- **Sign in with a provider.** Adding a server has two steps: the address, then how to sign in. The second step offers a
  username and password and a **Sign in with <provider>** button for each OAuth or OpenID Connect provider the server offers
  to apps, with Authentik first. The provider opens in the system browser, never an embedded web view. Signing in again
  puts the provider you used last first.
- **Quiet recovery.** An expired token is renewed in the background. If that fails, the app asks you to sign in again and
  returns you to where you were. Writes and stream commands are never replayed after a failed sign-in.
- **Secure storage.** Tokens are kept encrypted on the device and never logged.
- **Server switcher.** The top bar shows the current server as `Name ⌄`. Tapping it opens a sheet to switch between a server,
  **All servers** and an organization, or to add a server.
- **All servers.** One view that shows every saved server in its own section. A slow or signed-out server does not hold up the
  others. Logs, Invitations, a user's sessions and the Now playing message action are single-server only.
- **Silo and Bloem.** The app works with both. Pages that only a Bloem server has, such as Platform and organizations, appear
  only where the server says it supports them. A plain Silo server never sees them and shows no error.

## The drawer

The navigation drawer follows the web admin's menu: Platform, Monitor, Manage and Configure. A group with no page is hidden,
and a page the server does not offer is left out. On wide windows the drawer stays open at the left. Search in the top bar
finds a page by name.

At the foot of the drawer are three rows:

- **Manage servers** opens the server list, where you can rename a server, forget its saved sign-in or remove it.
- **Settings** opens the app's own settings (see below).
- **Sign out** removes the saved sign-in for the current server, or for every server on All servers. The servers stay in the
  list.

## Pages

**Monitor**

- **Dashboard.** Counts for items, users, movies, shows and active streams, storage, and a Server health card with CPU,
  memory, network, disk and GPU.
- **Now playing.** Live streams with pause, resume, stop, end session and message to the player. Each stream shows its source,
  playback decision and client, progress and bitrate.
- **Logs and Audit.** Read-only, paged views of the server's application log and request audit trail, with filters and a
  detail sheet with Copy and Share.

**Manage**

- **Libraries.** Scan, stop a scan, check a mount, and refresh metadata, with the last scan and any warning for each library.
- **Requests.** Approve, decline, retry, cancel or close media requests. Triage several at once with the selection mode. The
  menu shows how many are waiting.
- **Users.** Search, create, enable, disable, delete, edit limits and permissions, reset or set a password, manage profiles,
  invite by email, and see and revoke sign-in sessions. The app cannot act as another user.
- **Playback history.** Paged, with filters for user, profile and completed or partial plays.
- **Nodes.** Each stream node's state and load, with drain, resume, turn on and turn off.

**Configure**

- **Settings.** The server's settings, in the same pages as the web admin, with a save bar and conflict detection. Secrets show
  only as set or not set.
- **Scheduled tasks.** Run now, stop, and edit schedules, with recent runs.
- **Backups.** Back up now, delete, and edit the nightly backup. Restoring and downloading stay on the web.
- **API keys.** See each key's owner, scope and last use, and revoke it.
- **Plugins.** See status and errors, restart, turn on or off, and update.

**Platform and organizations.** Where a server has organizations, platform administrators can list, suspend and reactivate
them, change their plan and open one as an administrator. Inside an organization the pages are Overview, Now playing, People,
Cohorts, Access groups, Libraries, Invitations, Access checks and Activity.

Destructive actions ask first. Changes that could overwrite someone else's edit are checked with the server's version tag, and
a conflict is shown instead of being overwritten.

## How it looks and behaves

- **Status is a dot plus a word.** A small dot and a plain word such as Playing, Paused or Failed. Colour marks exceptions
  only, and the orange dot means live right now.
- **Inline errors with "Try again".** When a page cannot load, the error appears in place with a **Try again** button.
  Content already on screen stays visible with a note that it is out of date.
- **Pull to refresh** on every list and page.
- **Live updates without polling.** One events connection per server, open only while a page that uses it is visible. If it
  cannot connect, the visible page falls back to reading about once a minute.
- **Appearance.** Follows the system light or dark setting, or pin Light or Dark in Settings. The font is Plus Jakarta Sans and
  the icons are Lucide.
- **Accessibility.** Content descriptions, touch targets of at least 48dp, system font scaling and reduced animation.
- **Tablets.** The layout adapts to wide windows.

## Settings page

Open **Settings** from the drawer foot. It holds:

- **Appearance:** System, Light or Dark.
- **App lock:** **Require device unlock**.
- **Server alerts:** a switch for server-down alerts, a per-server switch, and **Show details on lock screen**.
- **Push notifications:** which kinds you want from each server that can send them (see [push.md](push.md)).

## Lock screen

The app lock is off by default. With **Require device unlock** on, the app is locked when it opens and after it has been in
the background. The system fingerprint, face or PIN prompt opens by itself, once each time the app locks. If you cancel it,
the **Unlock** button asks again. If the device has no screen lock or biometric set up, the lock does not trap you out.

While the lock is on, the app switcher shows a cover instead of the app's content. On Android 12 and earlier this is done by
blocking screenshots while the app is locked or in the background; on Android 13 and later only the app-switcher thumbnail is
hidden, and screenshots of the unlocked app stay possible.

Links from notifications, widgets and shortcuts wait until the lock and the admin check have been passed.

## Widgets, tiles, shortcuts and alerts

All of this is optional and does nothing until you turn it on.

- **Widgets.** Server status, Now playing, Requests (with Approve and Decline), Libraries and scans, and Nodes and resources.
  Each shows one server or All servers, in small, medium and large sizes where they fit. On the lock screen, widgets show
  counts only unless **Show details on lock screen** is on.
- **Quick Settings tiles** for Now playing and Requests.
- **Launcher shortcuts** to Now playing, Requests and Dashboard.
- **Server alerts.** A background check about every 15 minutes tells you when a saved server stops answering and when it is
  back.
- **Progress notification** for a library scan or backup you start in the app.
- **Push.** A server that supports it can notify you of waiting requests and failed scans, backups, nodes and plugins. See
  [push.md](push.md).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to your servers and run checks only with a network |
| `POST_NOTIFICATIONS` | Show alerts and push. Asked when you turn on alerts or after you sign in to a server that can push, never at launch |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `FOREGROUND_SERVICE` | Added by the background-work library. The app starts no foreground service |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | The optional app lock |

The background check sends `GET /api/v1/health` to each watched server with no sign-in. For a server that a widget or tile
shows, it also reads that widget's data with the stored session. Nothing is shown outside the app that carries titles, user
names, email addresses, IP addresses, tokens or server addresses, except in widgets on an unlocked phone. To turn everything
off, switch off Server alerts, remove the widgets and tiles, or turn off notifications for the app in Android settings.

## Links into the app

Notifications, widgets, tiles and shortcuts open the app with `bloemadmin://server/{serverId}/{page}` or
`bloemadmin://all/{page}`. A link only names a place. It is held until the app lock and the admin check have been passed, and a
link to a server that was removed opens nothing.

## Requirements

Android 8.0 (API 26) or newer. Building needs JDK 17 and the Android SDK for API 36.
