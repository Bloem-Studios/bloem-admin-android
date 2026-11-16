---
title: Bloem Admin for Android — Admin Guide
description: How to install Bloem Admin, connect it to your Silo server, what it can manage, which parts need a Bloem server, and how it keeps your sign-in safe.
summary: Requirements, installing, adding a server and signing in (password or provider), the admin areas, Bloem-only extras, notifications, widgets, app lock and screenshots, Settings, stored data and permissions, and troubleshooting.
tags:
  - admin
  - android
  - silo
  - bloem
audience:
  - administrator
last_reviewed: 2026-11-16
related:
  - user-guide.md
  - push.md
---

# Bloem Admin for Android — Admin Guide

> [!CAUTION]
> **Offered without support.** Bloem Admin is provided as-is, with no support of any kind. Issues are turned off, questions go unanswered, and pull requests are not accepted: they are closed without review. You are free to fork it under the AGPL v3.

Bloem Admin is a phone and tablet app for the administrator of a Silo server. It manages the server through the server's own admin API: it stores no media and plays none, and everything on screen is read from the server when you open it. It works with any Silo server. A few extras appear only on a Bloem server (see [Bloem servers](#bloem-servers)).

This guide covers setup and the things an administrator needs to know before trusting the app with an admin login. [docs/user-guide.md](user-guide.md) walks through each screen.

## Requirements

- Android 8.0 (API 26) or newer, on a phone or tablet. There is no Android TV build.
- A Silo server reachable over **HTTPS**, and an account on it with administrator access.

## Install

Download the signed APK from the project's GitHub Releases page and open it on the device. Android asks you to allow installs from your browser or file manager the first time. Each release lists a SHA-256 checksum next to the APK; compare it before installing. There is no in-app updater: install a newer release over the old one and your servers and sign-ins stay.

To build the app yourself, see "Build it yourself" in the [README](../README.md). Android will not install your build over an official release, because the signing keys differ; uninstall first, which clears the saved servers. A self-built app has no push notifications (see [Notifications](#notifications)).

## Add a server

Open the app, tap **Add a Silo server** (or **Manage servers** in the drawer, then add one) and enter the **Server URL**.

**The address** is the server's origin as a browser would use it, such as `https://media.example.com` or `https://media.example.test:8090`. Typed without a scheme, it is taken as `https://`. It must be HTTPS and must not have a path, query, fragment or `user:password@`; anything else is refused with *Enter an HTTPS server address without a path, query, or sign-in details*. Plain `http://` never works, and a certificate the device does not trust is refused as unreachable, so use a publicly trusted certificate or install your CA on the device.

**Step one** checks the server without signing in: its health (`GET /api/v1/health`, which supplies the display name), its identity and how it lets people sign in. **Step two** shows the options the server offers:

- A username and password form, when the server allows password sign-in.
- One **Sign in with <provider>** button for each single sign-on provider the server offers to native apps. The default provider is listed first.
- If the server offers neither, the app says *This server's sign-in options aren't available in this app*; **Change server** goes back to step one.

**Sign in with a provider** opens the provider in a browser tab, never an in-app web view, and returns to the app through the `org.siloserver.silo` redirect. The app uses PKCE and redeems the returned code only if the redirect came from the same server it started with; anything else is refused with *The sign-in response didn't come from this server. Try again.* Closing the browser without finishing just cancels. Another app can register the same redirect scheme, in which case Android may show a chooser; it still cannot use the code.

**Administrator check.** Before a server is saved the app signs in and asks the server for its status. An account that is not an administrator is refused with *This account needs administrator access. Sign in with an administrator account*, unless the account administers at least one organization on a Bloem server (see [Organization mode](#organization-mode)). The password is never stored.

The server list remembers which provider you last used and lists it first when you sign in again. If the providers cannot be read, re-login falls back to the password form.

### Signing in again, and signing out

Every time you open a server the app checks admin access again. A 401 anywhere in the app is first handled silently with the refresh token. If that fails you see the sign-in form with a reason, such as *Your session on … has expired. Sign in again to continue.*, and the app resumes where you were. A passing network error never signs you out.

Account actions, from the server list or the drawer foot:

- **Rename** changes only the name shown in the app.
- **Forget credentials** deletes the stored tokens from this device but keeps the server in the list. It does not end the session on the server.
- **Remove server** deletes the entry and its tokens.
- **Sign out** (drawer foot) forgets the credentials of the server you are in, or of every server when All servers is open, and unregisters this device from push on those servers.

## What you can manage

Pages are grouped like the web admin's menu. A page the server cannot serve is left out, so an older server never shows a page that would not load. Page search in the top bar jumps to any page.

| Group | Pages |
|---|---|
| **Monitor** | **Dashboard**, **Now playing**, **Logs**, **Audit** |
| **Manage** | **Libraries**, **Requests**, **Users**, **Playback history**, **Nodes** |
| **Configure** | **Server settings**, **Scheduled tasks**, **Backups**, **API keys**, **Plugins** |
| **Platform** (Bloem only) | **Organizations**, **Support access** |
| **Organization** (Bloem only, inside one organization) | **Overview**, **Now playing**, **People**, **Cohorts**, **Access groups**, **Libraries**, **Invitations**, **Access checks**, **Activity** |

What each does:

- **Dashboard**: server and library figures, host health, charts and recent activity.
- **Now playing**: live streams, with pause, resume, stop, terminate and message.
- **Logs**: the application log with level, search and component filters. **Audit**: who changed what, including requests made by other clients.
- **Libraries**: scan, stop a scan, check a mount, refresh metadata, and recent scan runs.
- **Requests**: approve, decline, retry and cancel media requests, singly or in bulk.
- **Users**: add, edit, enable, disable and delete users, quotas and permissions, profiles, sign-in sessions and devices, password resets, and invitations.
- **Playback history**: what has been watched, filtered by user and profile.
- **Nodes**: stream nodes, with drain, resume and turn on or off. **Backups**: status, **Back up now** and the schedule. **Plugins**: restart, enable or disable, and update. **API keys**: list and revoke; keys are created on the web.
- **Server settings**: the pages General, Sign-in, Playback, Logging, Email, Notifications, Downloads, Network access and Requests, an **Other settings** editor for any other stored key, **Connection checks**, and server restart. Saves are conditional: if another administrator saved first, your edits are kept and you review before saving again.
- **Scheduled tasks**: run, cancel, edit triggers and read run history.

**Nodes, Backups, Plugins and API keys** appear only where the server reports the capability. Secrets in settings can be seen as set or unset but not changed here. Creating API keys, sign-in provider setup, restoring backups and the plugin catalogue stay on the web admin.

### All servers

**All servers** is a pinned entry in **Manage servers**. It shows every saved server in sections: Dashboard, Now playing, Audit, Libraries, Requests, Users, Playback history, Nodes, Server settings, Scheduled tasks, Backups and Plugins, plus Organizations when any server has them. Each server loads on its own with a 15-second timeout, so one slow server does not hold up the rest, and a server that needs sign-in shows a tap-to-sign-in row. **Logs**, **API keys**, a user's sign-in sessions and **Invitations** are single-server only.

## Bloem servers

A Bloem server is a Silo server with extras. The app asks each server once, without a token, `GET /api/bloem/v2/capabilities`. A plain Silo server answers 404 and is never sent another `/api/bloem/` request, so the extras are simply absent, with no error. An answer that says nothing (offline, a timeout, a 5xx) is not remembered and the app asks again next time.

| Needs a Bloem server | Any Silo server |
|---|---|
| **Platform › Organizations** (when the server reports organization memberships), **Support access**, organization mode | Every page in Monitor, Manage and Configure |
| Push notifications (the server must list `admin_push_v1`) | Server-down alerts (checked from the device) |
| Silent widget refresh (the server must list `admin_widget_wakes_v1`) | Widgets, tiles and shortcuts, refreshed about every 15 minutes |
| Live organization pages (the server must list `organization_live_v1`) | Live updates on the pages that have them |

### Platform › Organizations

For platform administrators of a server that has organizations: list and search organizations, open one to see its members, status and plan, **suspend** or **reactivate** it, and preview and apply a plan change. All servers shows this page when any saved server has organizations. Creating or renaming an organization, changing member roles and transferring ownership stay on the web.

### Organization mode

An account that administers an organization but is not a platform administrator signs in to that organization's pages only. Choose the organization in the server dropdown in the top bar. The app exchanges your sign-in for a short-lived pass (at most 15 minutes) for that one organization. The pass is held in memory only, never saved, and is renewed as needed. The server checks your role on every request. A write is sent once and never repeated: if the pass or the organization changed meanwhile, the page reloads and you decide again.

If you lose access you see why: *This organization is suspended. Contact the server owner.*, *You no longer administer this organization.*, or *You don't have access to this organization.*, with **Choose another organization**.

### Support access

Only the server Owner sees **Support access**, and only on a server that lists `organization_support_v1`. It opens an organization you are not a member of, after you give a reason of 10 to 500 characters. The server records it for at most four hours and shows it to the customer in their activity as platform support access.

## Notifications

Two separate mechanisms, both off until you switch them on in **Settings**.

**Server alerts** work with any Silo server. A background check about every 15 minutes (Android may delay it to save battery) calls the public health route of each server you chose, with no sign-in and no token. Two misses in a row raise one **Server can't be reached** alert, and a later answer raises one **Server is back** note. Switch on **Alert me when a server stops answering**, then pick the servers under **Servers to watch**. Android asks for notification permission at that moment, never at launch.

**Push** needs a Bloem server and the official release build. A server that lists `admin_push_v1` can notify you through Bloem's push service when something is broken or waiting. Under **Notifications** in Settings, each server shows whether it can send: *Push isn't available on this server. Only the server-down checks apply* for a plain Silo server, or for a self-built app. The kinds, switched per server, are **Server can't be reached**, **Server is back**, **Request waiting**, **Library scan failed**, **Backup failed**, **Node offline** and **Plugin needs attention**.

The push message itself carries no text. The app fetches the text from your server with a per-device token, and falls back to a generic notice if the server does not answer in time. A request notification has **Approve** and **Decline** buttons that run only on an unlocked device. Android lists the channels Servers, Requests, Libraries, Backups, Nodes and Plugins so you can mute any of them. [docs/push.md](push.md) has the details.

**Lock screen.** Notifications, widgets, tiles and the scan and backup progress notification show only server names and counts on a locked screen. **Show details on lock screen** adds names, titles and times.

## Widgets, tiles and shortcuts

Optional, and none is active until you add it.

- **Widgets**, in small, medium and large sizes: **Server status**, **Now playing**, **Requests** (with **Approve** and **Decline**), **Libraries and scans** and **Nodes and resources**. Each shows one server or All servers, chosen when you place it, and **Refresh** reads again.
- **Quick settings tiles**: **Now playing** and **Requests**, showing counts from the last check and hidden while the device is locked.
- **Shortcuts**: long-press the launcher icon for **Now playing**, **Requests** and **Dashboard** across all servers, plus Now playing and Requests for your two most recent servers.
- **Scan and backup progress**: while a library scan or backup started from the app runs, one ongoing notification follows it in the **Scans and backups** channel.

Widgets never call the server themselves. A background job that runs about every 15 minutes, and after you act in the app, reads the data with the same stored sign-in as the app and caches it in app-private storage that is never backed up. A Bloem server that supports it also sends a silent push when something a widget shows changes. A widget shows its last data with an update time when the server is unreachable, **Sign in** when the stored session is gone, and a plain message when the server lacks the feature. Tapping a widget opens the matching page, or the exact request or stream, behind the app lock.

## Settings and the drawer

The foot of the drawer has three rows, in this order: **Manage servers**, **Settings** and **Sign out**. Wide windows keep the drawer open; phones open it from the menu button.

**Settings** is the app's own page, not the server's (that is **Server settings**, under Configure). It holds:

- **Appearance**: **System**, **Light** or **Dark**.
- **App lock**: **Require device unlock** — *Use a fingerprint or device PIN when opening this app*.
- **Server alerts**: the switches described under [Notifications](#notifications), including **Show details on lock screen**.
- **Notifications**: push status and the per-server, per-kind switches.

## App lock and screenshots

**Require device unlock** is off by default. When on:

- The app starts locked and locks again whenever it goes to the background. The lock screen says *Bloem Admin is locked* with an **Unlock** button, which shows the system biometric prompt. Fingerprint, face or the device PIN, pattern or password all count.
- Locking also clears every screen's server data from memory; screens reload after you unlock.
- A link from a notification, widget or shortcut waits behind the lock and the admin check.
- A device with no biometrics or screen lock enrolled bypasses the lock, so the app never locks you out. Enforce a device screen lock if you need one.

**Screenshots and the app switcher.** Screenshots and screen recording work in the unlocked app. While the lock is on, the app switcher (recent apps) shows a cover instead of the app's content. On Android 13 and later the app turns off its recents thumbnail. On Android 8 to 12 it sets a secure window flag while the app is locked or not in the foreground; that flag also blocks screenshots, so it is not left on while you are using the unlocked app.

## What the app stores

| What | Where | Encrypted |
|---|---|---|
| Server list: id, address, display name | App-private preferences | No, no secrets |
| Access and refresh tokens per server | `EncryptedSharedPreferences`, master key in the Android Keystore | Yes |
| Passwords | Not stored | — |
| Settings, alert and widget choices, last server | App-private preferences | No, no secrets |
| Widget data (titles and user names) | App-private file, not backed up | No |

Nothing is backed up or transferred to another device (`allowBackup` is off, and the token file is excluded from device transfer). Reinstalling means adding servers again. If the Keystore entry is lost (a factory reset or, on some devices, a screen-lock change), the app rebuilds the token store, keeps the server list and shows **Sign-in credentials cleared**; sign in to each server again. If rebuilding fails, **Secure credential storage unavailable** blocks sign-in until the app is restarted on a device whose secure storage works.

There is no analytics, no telemetry, no log file and no impersonation feature. Tokens, passwords and request bodies are never logged. Password-reset links and invitation links are held in memory only; copying one uses the sensitive-clipboard flag and clears it after a minute or when the dialog closes. Destructive actions (delete user, terminate session, restart server, remove server, forget credentials, decline) ask for confirmation.

**Permissions.** `INTERNET` and `ACCESS_NETWORK_STATE` for network calls; `POST_NOTIFICATIONS` for alerts and push, requested in context; `RECEIVE_BOOT_COMPLETED` to restore background checks after a restart; and the biometric permissions for the lock. Cleartext traffic is disabled.

**Live updates.** Pages that show live data hold one WebSocket per server only while they are on screen, and close it ten seconds after the last one leaves. The ticket is single-use and travels as a subprotocol, never in a URL. If the socket cannot connect, a visible page reads again every 15 to 60 seconds; nothing runs in the background except the checks above.

## Troubleshooting

| You see | Cause | What to do |
|---|---|---|
| *Enter an HTTPS server address without a path, query, or sign-in details* | `http://`, or the address has more than `https://host[:port]` | Enter the origin only |
| *Couldn't reach that server…* | Typo, firewall, no network, or a certificate the device does not trust | Open the address in the device's browser; use a trusted certificate |
| *This server didn't pass the required health check…* | `/api/v1/health` returned an error, often a proxy or maintenance page | Check the address serves the Silo API |
| *The username or password wasn't accepted* | Wrong credentials | Re-enter them |
| *This account needs administrator access…* | Not an administrator and no organization to administer | Use an administrator account |
| *This server isn't compatible with Bloem Admin…* | The server answered 404 for login or admin status | Check the address and update the server |
| *This server's sign-in options aren't available in this app* | Password sign-in is off and no native provider is configured | Enable one on the server |
| *The sign-in response didn't come from this server* or *The sign-in was interrupted* | The provider redirect failed a check, or the app was closed meanwhile | Start the sign-in again |
| *Install or turn on a web browser to sign in with …* | No browser can open the provider | Install or enable a browser |
| *This server uses an insecure address…* on a saved server | Saved by an older build | Remove it and add its HTTPS address |
| *This server needs a newer version of Bloem Admin* | The server requires a newer client | Install a newer release |
| Sign-in form with *Your admin session expired…* | The refresh token was revoked or expired | Sign in again |
| *Sign-in needed — tap to reconnect this server* in All servers | That server's stored session is gone | Tap it and sign in |
| *This user changed since it was loaded…* | Another administrator edited the user | Reopen the dialog and repeat |
| *Settings changed on the server* | Another administrator saved a setting | Your edits are kept; review them, then save |
| *Another command reached this stream first* | A newer command already reached that stream | Try again |
| No alert arrives | Alerts off, server not picked, notifications blocked, or battery saver delays checks | Check **Server alerts**, then Android's notification and battery settings |
| *Push isn't available on this server…* | Plain Silo server, push off on the server, or a self-built app | Expected; only server-down alerts apply |
| A page is missing | The server does not report that capability | Expected; use the web admin |
| The app opens without a fingerprint prompt | **Require device unlock** is off, or nothing is enrolled on the device | Turn it on and set a device screen lock |
| *This organization is suspended…* or *You no longer administer this organization.* | The server refused your organization pass | Ask the server's owner to reactivate it or restore your role |
