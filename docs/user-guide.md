---
title: Bloem Admin for Android — User Guide
description: Every screen and control in the Bloem Admin app for Android, in the order a new administrator meets them, with what each one does and what it asks the server to do.
summary: Signing in, the lock screen, adding and managing servers, the All servers view, every management page (Dashboard, Now playing, Libraries, Playback history, Scheduled tasks, Requests, Users, Server settings, Audit, Logs and more), organizations, widgets, notifications, and what the messages mean.
tags:
  - end-user
  - admin
  - android
  - getting-started
audience:
  - end-user
last_reviewed: 2026-11-16
related:
  - admin-guide.md
---

# Bloem Admin for Android — User Guide

> [!CAUTION]
> **Offered without support.** Bloem Admin is provided as-is, with no support of any kind. Issues are turned off, questions go unanswered, and pull requests are not accepted: they are closed without review. You are free to fork it under the AGPL v3.

Bloem Admin is an admin app for any Silo server, for your phone or tablet. Use it to see who is streaming, approve a
request, end a session, invite a user or restart the server. This guide walks through every screen in the order you
will meet them. Labels in **bold** are the exact words you see in the app.

If you are building or installing the app, see the [Admin Guide](admin-guide.md).

---

## 1. First launch

### 1.1 Opening the app

With no saved server, the app shows a welcome (**Add a Silo server**) and one **Add server** button, which starts the
sign-in. There is no menu until a server is saved.

Once a server is saved, the app opens **Now playing** for **All servers**. Choosing a single server in the server
switcher (section 1.3) makes the app open on that server from then on, and choosing **All servers** switches back. If
the remembered server was removed, the app opens **All servers**.

### 1.2 The lock screen

The app lock is **off by default**. To turn it on, open the drawer, choose **Settings** and switch on **Require device
unlock** under **App lock**. The choice is saved on this device.

With the lock on, the app locks whenever it leaves the screen, and on start. Rotating the device does not lock it.
The lock screen reads "<app name> is locked" with *Unlock with your fingerprint, face or device PIN.* and an **Unlock**
button. The system prompt opens by itself, once each time the app locks. If you cancel it, **Unlock** opens it again.
If the device cannot show a prompt right now, the screen says so. A device with no screen lock set up skips the lock.
While the lock is on, the app switcher shows a cover instead of the app.

### 1.3 Navigation

Every page has one top bar. From left to right it has a **menu** button (*Open menu*), the page title, a **search**
button (*Search pages*) and the server switcher.

- **Menu** opens the drawer. Choosing a page opens it and closes the drawer. The Back gesture, or a tap outside the
  drawer, closes it without choosing. On tablets and other wide windows the drawer is always open and there is no menu
  button.
- **Search** opens a full-screen search of the pages of what you are looking at. Type a few letters: it lists the
  pages whose name, description or search words match, under the drawer's group names. A group's own name (such as
  *Users*) lists all its pages. Tap a page to open it. The keyboard's search key opens the first match.
- **The server switcher** is the current name followed by a chevron (`Name ⌄`): *All servers*, a server's name or an
  organization's name. It opens a sheet titled **Servers**. The sheet lists **All servers**, then each saved server
  with the organizations you administer on it indented beneath, and a check on the current one. **Add server** is the
  last row.
- The screens that come before a server's pages have the same bar with their own title and the switcher, but no menu or
  search: **Checking access**, **Sign in**, **Organizations**, **No access** and **Can't reach server**.

Search and the switcher are dimmed while a dialog or confirmation is open.

**The drawer.** It groups pages as the web admin does. Only the pages this app has are listed, and a group with no
page is hidden:

| Group | Pages |
|---|---|
| **Platform** | **Organizations** (every platform administrator, when the server has organizations) and **Support access** (the server's Owner only) |
| **Monitor** | **Dashboard**, **Now playing**, **Logs** (one server only) and **Audit** |
| **Manage** | **Libraries**, **Requests**, **Users**, **Playback history**, **Nodes** (only on a server that offers it) |
| **Configure** | **Server settings**, **Scheduled tasks**, **Backups**, **API keys** and **Plugins** (Backups, API keys and Plugins only on a server that offers them) |

Inside an organization the drawer has one group, **Organization**: **Overview**, **Now playing** (where the server
offers it), **People**, **Cohorts**, **Access groups**, **Libraries**, **Invitations**, **Access checks** and
**Activity**.

The open page has a lighter row and an orange icon. **Requests** shows a quiet count of requests waiting for approval.

Three items stay at the foot of the drawer, in this order:

- **Manage servers**: the server list, where you add, rename, sign in again, forget credentials for and remove servers.
- **Settings**: the settings page (section 1.5).
- **Sign out**: asks first, naming the server (or all servers, on All servers), and removes the saved sign-in from this
  device. The server stays in your list.

Every drawer item is dimmed while a dialog or confirmation is open.

### 1.4 Look and feel

The app follows the system light or dark setting, and you can pin one under **Appearance** in **Settings** (section
1.5). Both schemes are graphite with Bloem orange as the only accent. A small orange dot means something is live right
now (a playing stream, a live connection, a running task) and is used for nothing else. Status is a word, usually in
plain grey, with a dot where one helps (Playing, Running). Color marks exceptions only: amber for Paused, Transcode,
Suspended and Signed out, red for failures. Text follows the system font size.

### 1.5 Settings

**Settings** is a page of its own, with a back arrow. Open it from the drawer or from **Manage servers**. It holds:

- **Appearance**: **System**, **Light** or **Dark**.
- **App lock**: **Require device unlock** (section 1.2).
- **Server alerts** and **Notifications**: see section 1.6.

Settings has no sign-out: that is in the drawer.

### 1.6 Notifications, widgets, tile and shortcuts

These are optional. Nothing here runs until you turn it on. Notifications, widgets and the lock screen show server
names and counts only, never titles, people, email addresses, IP addresses or sign-in details, unless you turn on
**Show details on lock screen**.

**Server alerts.** Under **Server alerts** in **Settings**, turn on **Alert me when a server stops answering**. Android 13
and later asks to allow notifications right then. If you say no, alerts stay off and a note explains how to change it
(**Open system settings**). Then switch on the servers under **Servers to watch**. The app checks them about every 15
minutes while the device is online, without signing in. After two missed checks in a row you get one notification,
"Can't reach <server>". When the server answers again you get one quiet note. One missed check is ignored, so a brief
network drop does not wake you. Tap the notification to open that server. Android may run a check later than 15
minutes to save battery, and **Force stop** pauses checks until you open the app.

**Show details on lock screen** (off by default, this device only) lets the lock screen also show when a server stopped
answering, and names and titles in widgets, tiles and progress notifications. If you remove a server, its alert switch,
its alert and its widget numbers go.

**Push notifications.** Under **Notifications** in **Settings**, each saved server has its own section. A server that
can send push notifications to this app shows a status (*This server sends you notifications*) and a switch for each
kind: **Server can't be reached**, **Server is back**, **Request waiting**, **Library scan failed**, **Backup failed**,
**Node offline** and **Plugin needs attention**. A plain Silo server cannot push, so it says *Push isn't available on
this server. Only the server-down checks apply* and shows only the first two kinds. The same applies when the build of
the app you installed is not set up to receive push; see [Push notifications](push.md). If notifications are not
allowed, **Allow notifications** and **Open system settings** appear.

The app asks for the notification permission once, after the first data loads from a server that can push, never at
launch. A request notification has **Approve** and **Decline** buttons that work only on an unlocked device. Tapping a
notification opens the item it is about, behind the app lock.

**Home-screen widgets.** Five widgets, each asking which server to show, or **All servers**, with a small, medium and
large layout (stretch it to change layout):

- **Server status**: each server up or down, with its streams playing and requests waiting.
- **Now playing**: the streams playing, with who watches what, how far along and whether it is Direct or Transcode.
- **Requests**: the newest requests waiting, with who asked and how long ago. **Approve** (tick) and **Decline** (cross)
  act at once. If one fails the widget says **Couldn't approve, open the app**.
- **Libraries and scans**: each library's last scan, warnings and a scan under way.
- **Nodes and resources**: each node's state, CPU, memory and disk.

Tap a widget to open the matching page, or a row to open that item. **Refresh** (the circular arrow) reads the servers
now. Otherwise they are read about every 15 minutes, when you change something in the app, and when a server that can
push tells the app something changed. If a server cannot be reached the widget says **Can't reach** and shows the last
data with **Updated** and its time. If a server does not offer a feature (for example Requests), the widget says so.
Without a stored sign-in it asks you to sign in; tap it to open the server. Widgets follow the app's **Appearance**
choice. On the lock screen a widget shows counts only unless **Show details on lock screen** is on.

**Quick Settings tiles.** Edit the Quick Settings panel and add **Now playing** (the stream count) or **Requests** (the
requests waiting). Each opens its page on All servers. While the phone is locked they show no count.

**Scan and backup progress.** When you start a library scan or a backup in the app, an ongoing notification follows it
until it ends. The lock screen shows only "Library scan running" or "Backup running" unless **Show details on lock
screen** is on.

**Shortcuts.** Press and hold the launcher icon for **Now playing**, **Requests** and **Dashboard** on All servers, plus
Now playing and Requests for the two servers you opened most recently.

Every tap that opens the app goes through the app lock and the admin check, so a widget or notification never skips them.

**Turn it all off.** Switch off **Server alerts**, remove the widgets and tiles, or turn off notifications for the app in
Android settings.

---

## 2. Servers

### 2.1 The server list

**Manage servers** (from the drawer) is a list of grouped rows, with a back arrow when you came from a server. A
full-width **Add a Silo server** button stays at the bottom. The page has:

- **All servers**: view and manage every added server at once (section 3).
- **Saved servers**: one row per server with its display name, its HTTPS address and whether this device is **Signed
  in** or **Signed out** (amber) on it. Tap a row to open that server; a signed-out server asks you to sign in. *No
  individual servers saved yet* shows until you add one. The **⋮** button (*Actions for <server>*) has **Rename**,
  **Forget credentials** and **Remove server** (section 2.4).
- **App**: **Settings** (section 1.5).

A row that shows, in red, *This server uses an insecure address. Remove it and add its HTTPS address.* was saved by an
older version of the app with an address the app no longer accepts. It cannot be opened. Remove it and add the server
again.

### 2.2 Adding a server

Tap **Add a Silo server** (or **Add server** in the switcher sheet). Adding a server takes two steps.

**Step one: the address.** Enter the **Server URL** (placeholder `media.example.com`): the address you use in a browser,
with the port if it needs one, and nothing after the host — no path, no `?`, no `#`, no `user:password@`. You can leave
out `https://`: the app adds it. An `http://` address is refused. When you stop typing and the address looks finished
(it ends in a name such as `.com`), the app checks it by itself and moves on. **Continue** checks at once; use it for an
address the app does not check by itself, such as an IP address or `localhost`. *Checking server…* shows while the app
checks the address, checks that the server is healthy and reads how it lets people sign in. The note *HTTPS required.
Credentials stored encrypted on this device.* sits underneath, and **Cancel** closes the dialog. If the server
advertises a name, that is the name you see in the list. Otherwise the host is used, and you can rename it later.

**Step two: how to sign in.** The screen says *Choose how to sign in to <server>* and shows what the server offers:

- A **Sign in with <provider>** button for each sign-in provider the server offers to apps, with Authentik listed
  first when the server has it. Tap it and your browser opens the provider's page. Sign in there and the app comes back
  on its own and confirms the account is an administrator. If you close the browser without finishing, you return to the
  options. The sign-in keeps waiting if the app locks while the browser is open; unlock the app when you come back.
- **Username** and **Password** fields, if the server allows passwords, with **Show** / **Hide** for the password. The
  password is used once to sign in and is not kept. **Sign in** stays greyed out until both fields are filled in.
- If the server offers neither, you see *This server's sign-in options aren't available in this app*. Tap **Change
  server** to go back, or ask whoever runs the server.

**Change server** returns to step one, and **Cancel** closes the dialog. Either one also stops a provider sign-in that
is waiting.

What can go wrong, in the order the app checks:

- *Enter an HTTPS server address without a path, query, or sign-in details* — the address is not a plain
  `https://host[:port]`.
- *Couldn't reach that server. Check the address, that it uses https, and that this device is online* — the server is
  down, you are on a network that cannot see it, or the device does not trust its certificate.
- *This server didn't pass the required health check. Check that the address is a Silo server and that it is running* —
  something answered, but not a working server (a proxy error page, for instance).
- *The username or password wasn't accepted. Check them and try again.*
- *This account needs administrator access. Sign in with an administrator account* — the sign-in worked but the account
  is neither a platform administrator nor an administrator of an organization on that server. An account that
  administers an organization can add the server and opens it in organization mode.
- *This server isn't compatible with Bloem Admin. Update the server, then try again* — the address answers, but not with
  the admin API this app expects.
- *This server needs a newer version of Bloem Admin* — the server has retired the API this version speaks. Update the app.
- *Couldn't save this server on this device. Try again.* — the app could not write to its secure storage.

When you sign in with a provider, these can also appear:

- *This account isn't allowed to sign in to this server.* / *No account on this server uses that sign-in. Ask the
  server's owner for an invitation.* / *An account with that email already exists. Sign in with its password
  instead.* / *That sign-in is linked to a different account* / *This account is disabled*
- *<provider> isn't available right now. Try again later.*
- *The sign-in took too long or was interrupted. Try again.*, *The sign-in was interrupted. Try again.* and *Sign-in
  didn't finish. Try again.*
- *The sign-in response didn't come from this server. Try again.* — the app refused a reply it could not match to your
  sign-in.
- *Install or turn on a web browser to sign in with <provider>*

**Signing back in.** When a session expires, the sign-in form shows the same options for that server, with the provider
you used last listed first. If the app cannot read the server's options, it shows the username and password form so you
can still sign in. The app uses the `org.siloserver.silo` address scheme to come back from the browser. Another app that
grabbed it could not use the reply, because it would not match your sign-in.

### 2.3 Opening a server: the admin check

Tap a server row. The app checks that your account is still allowed to administer that server.

- If it is, the server's pages appear (section 4).
- If your sign-in has expired you see *Your session on <server> has expired. Sign in again to continue.* with the
  sign-in options. Sign in and the pages appear.
- If the account is not the server's administrator but administers one or more **organizations** on it, the app opens
  the organization (one) or asks you to choose (several). That is organization mode, section 4.11.
- If the account is no longer an administrator you see *This account doesn't have admin access on <server>* with
  **Try again** and **Back to servers**. If an administrator has just restored the access, choose **Try again**.
  Otherwise go back, **Remove server**, and add it again with an admin account.
- If the server cannot be reached you see *Couldn't reach the server* or the server's own error, with **Try again** and
  **Back to servers**.

The app renews your sign-in silently, so the sign-in form is rare. You will mainly see it when an administrator has
revoked your sessions on the server, or after **Forget credentials**.

Two dialogs can appear on the server list itself:

- **Sign-in credentials cleared** — *Encrypted credential storage was repaired. Your server list was preserved, but you
  need to sign in to each server again.* The app found its encrypted sign-in store unreadable (typically after a device
  reset or a change of screen lock) and rebuilt it. Tap **Close**. Each server asks you to sign in once.
- **Secure credential storage unavailable** — the store could not be rebuilt either. Your servers are still listed but
  you cannot sign in until the app is restarted on a device whose secure storage works.

### 2.4 Rename, Forget credentials, Remove server

From the **⋮** menu on a server row:

- **Rename** opens *Rename <server>* with a **Display name** field and **Save changes**. It changes only what the app
  shows; nothing is sent to the server.
- **Forget credentials** asks *Forget credentials for <server>?* — *This removes the locally stored access and refresh
  tokens from this device. It doesn't revoke server-side sessions.* The server stays in the list and asks you to sign in
  the next time you open it. Use this on a shared device, or to switch to a different admin account on the same server.
  To end the session on the server as well, revoke it under **Users →** the user **→ Sign-in** first.
- **Remove server** asks *Remove <server>?* — *This removes the server and its stored sign-in from this device. You can
  add it again later.*

---

## 3. All Servers

Choose **All servers** in the server switcher, or the **All servers** row in **Manage servers**, to see every server at
once. The switcher reads **All servers**. Twelve pages are available: **Dashboard**, **Now playing**, **Audit**,
**Libraries**, **Requests**, **Users**, **Playback history**, **Nodes**, **Server settings**, **Scheduled tasks**,
**Backups** and **Plugins**. A thirteenth, **Organizations** (under Platform), is added when at least one saved server has
organizations. Each page shows the same information as the single-server page of that name (section 4), divided into
one section per server, headed with the server's name. Servers are contacted in parallel, so a slow or offline server only
delays its own section.

Each section can be in one of these states:

| Section shows | Meaning | What to do |
|---|---|---|
| The server's rows | Loaded normally | Use the actions as on the single-server destination |
| *No streams* (Now playing) or an empty section | Loaded, nothing to show | — |
| **Sign in to continue** — *The saved session for <server> needs attention.* with a **Sign in** button (Now playing, Playback history); **Signed out. Tap to sign in again** (the other pages) | Your sign-in on that server expired | Tap it; you get that server's sign-in form; go back to All servers afterwards |
| **Server unavailable** — *Can't reach <server>. Tap to try again* with **Try again** (Now playing, Playback history); the same line as a row (the others) | The server did not answer in time | Tap to try again |

Requests, Now playing, Users and Settings keep action failures visible until you choose **Dismiss**
or a newer explicit action replaces the outcome. Refreshing does not acknowledge an action failure.
A confirmed write followed by a failed refresh remains confirmed; the refresh failure is separate.

What is different in All servers:

- **Organizations** lists each server's organizations in its own section, with status and counts, and a search that narrows
  the rows of every server. A server without organizations says *This server doesn't have organizations*; a server where your
  account is not a platform administrator says so. It only reads: to suspend an organization or change its plan, open that
  server on its own (4.7). **Show more** loads the next page of one server only.
- **Not available here:** **Logs** and **Invitations**. Open the server on its
  own for those. The Now playing **Message** action works here: it goes to the server whose section
  the row is in, and a failure names that server.
- **Users** still offers **+ Add user** and search per server. Tapping a row opens that user's page
  for that server and names it (the page shows *Server: <name>*); everything in 4.6.2 works the same
  there, and a change only ever goes to that one server.
- Paging in **History** and **Audit** is per section — **Load more** in one server's section does
  not affect another's. **Audit** in All servers has a **Refresh** button instead of pull-to-refresh.

---

## 4. Inside a server

The top bar shows the current page and, at the right, the server switcher. Open the menu to move between pages, or use
search to find one by name or topic (section 1.3). **Manage servers** in the drawer opens the server list.

Some behavior is shared by every page:

- **Live updates.** Pages that show something that moves are told by the server when it changes, over one connection per
  server, and read it again then: Now playing and the Dashboard's streams, Scheduled tasks, Libraries and Server settings. The
  source label distinguishes **Connecting**, **Live**, **Reconnecting** and **Unavailable**, and asks you to sign in
  again when the server no longer accepts your sign-in.
  Last-known sessions can remain visible with a stale label during a connection problem. Only while the connection cannot
  be made (three tries in a row) does a page read on a timer. Every other page, and the
  Dashboard's figures and charts, read when you open them and when you pull down; they do not refresh by themselves. The
  connection is open only while such a page is on screen and closes 10 seconds after you leave the app.
- **Pull to refresh.** Drag down from the top of any list to reload it now. This includes every
  organization page that has something to reload (not the group editor or an access check's details).
- **First load.** A loading panel shows until the first data arrives; it does not reappear on later
  refreshes.
- **Empty and failed states.** Now playing, Playback history and the Dashboard show a card instead of a blank list: an
  empty state with a **Refresh** button (*No streams*, *No activity yet*), a failure state with **Try again** (*Active
  streams unavailable*, *Activity unavailable*, followed by the server's message), or, when data is already on screen and
  a refresh fails, a *Latest refresh failed* notice above the list with **Try again**. Other pages show a plain message
  at the top, in the server's own words when it gave one, otherwise a short fallback such as *Couldn't load scheduled
  tasks* or *Couldn't complete that action*. A successful load clears its own read failure. Action failures in Requests,
  Now playing, Users and Settings stay until you choose **Dismiss** or a newer action replaces them.
- **Pending actions.** Affected controls show **Action in progress** and reject duplicate or
  conflicting submissions until the request and reconciliation finish. Other entities and servers
  remain available. Restart conflicts with settings saves on that server. Editor and message
  results stay inside their dialog; a busy submission is never reported as saved or sent.
- **Confirmations.** Anything destructive asks first, and the phone gives a short vibration once the
  server has actually confirmed the most serious ones (delete user, terminate session, restart
  server, revoke session). While a confirmation dialog is open the header is disabled
  until you answer it.

### 4.1 Dashboard

The dashboard follows the server's web admin dashboard, in the same order. Under **All servers** every
server gets its own section with its name as a heading, and each section is laid out the same way.

1. **Number tiles**: **Active streams** and **Egress now** lead, side by side, in large figures (a small
   orange dot beside **Active streams** means streams are playing now). **Profiles · 24h**, **Movies**,
   **Shows** and **Storage** follow in a quieter panel, two to a line, so each label reads on one line at
   phone width and at large text sizes (one under the other from font scale 1.5). A tile only appears once its number is known, so a
   tile you do not see has no data yet. **Egress now** is left out when the newest egress sample is more than two minutes old,
   rather than showing an old rate as the current one.
2. **Server health**, a compact strip: **Version**, **Uptime**, **Postgres**, **Redis**, **Nodes**
   (healthy of enabled) and **Errors · 24h** with the warning count. **Redis** reads *Not configured*
   on a single-node server, which is normal. Below them, the host's resources are small labelled figures
   in the same style: **CPU** (with its cores and load), **Memory** (used, then *of* the total),
   **Network in** and **Network out** in MB/s, **Fullest disk** (the share of the disk with the highest
   share used, then its used and total size; amber from 85 percent, red from 95) and **GPU busy** with its
   VRAM for each GPU the host measures. A figure the server does not report is left out. How old the
   sample is follows; a stale sample says *Last sample …* in amber.
3. **Now playing**, with **Manage streams**. It shows up to four current streams (title, viewer,
   playback method with its chart color, playing or paused, progress) and **+N more active streams** for the
   rest. With nothing playing it says *Nothing playing right now*. The live-connection line (*Live*,
   *Reconnecting*, …) sits here, and a failed refresh keeps the last sessions and labels them stale.
4. **Concurrent streams** (the one large chart), then the smaller **Playback activity** and **Egress**, then
   **Top titles** and **Most active profiles**. The three charts are described below; the two top lists
   cover the last 7 days.
5. **Libraries**, **Users** (newest activity first, up to five) and **Recent activity**.

**Trend charts.** Concurrent streams, Playback activity and Egress are drawn on the phone, in the same
windows as the web: **1h**, **24h** (the start), **7d** and **30d**. One range control, in the Concurrent
streams panel, sets the window of all three; under **All servers** each server has its own. The buttons say
what they show (*Show the last 7 days*) and the chosen one has a filled step. Direct play, direct stream and
transcode are the web's chart colors (blue, teal and amber) everywhere they appear, in charts and in rows.

- **Concurrent streams** is the main chart: the number of streams over the window, stacked by how each is
  delivered (direct play at the bottom, direct stream above it, transcode on top). A server that does not
  report the split shows the whole count in grey.
- **Playback activity** stacks the sessions started in each hour (each day, for a week or a month) by
  play method: **Direct play**, **Direct stream** and **Transcode**. Quiet hours stay in place as empty
  columns. The legend under the chart gives each method's count.
- **Egress** is a line in Mbps with the window's **Total** above it. When the window includes download
  traffic, a dashed **Downloads** line is drawn under the total. Wider windows show each period's peak
  minute, so a short spike is not averaged away.
- A line breaks where the server recorded no sample (for example while it was down) instead of dropping to
  zero, because *no sample* and *nobody watching* are different things. Above each line chart a summary
  gives the **Latest**, **Peak** and **Lowest** value of the window; the axis starts at zero and its ends
  read *24h ago* and *now* (or the window you chose).
- **Show values** under a chart swaps it for the same information as plain numbers: latest, peak (and when),
  lowest, average, the egress total and a handful of readings spread across the window; for playback, the
  count per method, the total and the busiest hour or day. **Show chart** switches back.
- A chart is one image to TalkBack, described by its window, latest and peak (for example *Egress over the
  last 24 hours, in megabits per second. Latest 12 Mbps, peak 40 Mbps, lowest 2.0 Mbps.*). The plain-number
  view is the alternative to it. Charts follow the theme's colors, and with reduced motion (animations turned
  off in system settings) they appear at once instead of drawing themselves in.
- A window you have not opened yet is read when you tap it, and shows *Loading…* until it arrives; it never
  shows another window's chart in its place. A 1h chart reuses the *Egress now* read.

Each section loads on its own. If one fails, the others stay and that section shows what went wrong,
keeping its last good numbers or chart. The 24h playback chart and every 1h chart refresh every 30 seconds with
everything else; sections 4 and 5 (and the version), the 24h egress and concurrent-streams charts, and the
7d and 30d windows read when the screen opens, all only while the screen is visible. Pull to refresh asks the
server to recompute its statistics and re-reads everything.

Not on the phone yet: egress split **by node** (the web's *Total/Nodes* switch), tapping a chart to read a
single sample, the web's other widgets (Server resources, Playback reliability, Watch providers,
Nodes, Scanner, Offline downloads, Scan activity, Recent errors), and rearranging or
hiding widgets. Library scans are on the **Libraries** page (section 4.3a).

### 4.2 Now playing

One card per active playback session, drawn as the stream's signal path. The card shows, top to bottom:

- **Title** — for an episode *<series> · S2E3 · <episode name>*, otherwise the title — and *<username> · <profile>*.
- **Signal path** — three steps joined by a line: **Source** (video format and container), **Playback** (the
  method in the web's words: **Direct play**, **Remux**, **Direct stream** or **Transcode**, with a small dot in
  its chart color) and **Client** (the app and its device, *Silo Android 1.4.2 · Android*). Only a transcode is
  lit: its step is amber and the line into it is amber, with the hardware mode and the output format under it.
- **Progress** — a bar, *position / length* (*12:34 / 44:12*) and the bitrate (*8.2 Mbps*), in figures that
  keep their width as they tick. While a
  stream plays the numbers move forward once a second between updates; a paused stream stays put. With
  the device's reduced-motion setting on (animations off) nothing moves between updates. When the server
  does not report the file's length you see the position only.
- **State** — **Playing** (with the orange dot: it is live right now) or **Paused** in amber.

Anything the server or player did not report is simply left out.

**Tap a card** to open its details; tap it again to close them. They list what support needs: the
method, video and audio (source and output), the audio track and language, the transcoder, the source
container and how it is delivered (*MKV → HLS · MPEG-TS*), source and stream bitrate, the exact client
version, the playing node (*Transcode: <node>*, *Proxy*, *Server*), local or remote network, **Started**
and **Last update**. **Client IP** appears only when you are signed in as platform administrator; it is
never shown in an organization context. A screen reader reads each card as one sentence with the key
facts and announces whether its details are shown.

In **All servers**, each server's section lists the same cards, with the same **⋮** actions,
**Message** included.

Tap **⋮** (*Actions for <username>*) on a row:

| Action | What it does |
|---|---|
| **Pause** / **Resume** | Shown according to whether the session is currently paused; toggles it. |
| **Stop playback** | Stops playback on the client; the viewer can start it again. |
| **End session** | The server ends the session. Asks *End this session?* — *The server ends <user>’s session for “<title>”. They can start playback again.* — with **End session** (in red) / **Cancel**. |
| **Message** | Opens *Message <username>* with **Title (optional)** and **Message**, a **Send** button and **Close**. **Send** is enabled once the message is non-empty. Success shows *Message sent*. If the playback client is not connected right now you see *This client isn't connected right now, so it can't receive this message.* |
| **Logs** / **FFmpeg logs** | Single server only. Opens **Logs** filtered to this stream's playback session (**FFmpeg logs** also to the FFmpeg component), with the session's summary on top. |

Each command carries a command id and a sequence number. If a newer command for the same stream
got there first, the app shows *Another command reached this stream first. Try again.* — repeat the
action.

The list is re-read from the server each time the live connection signals a change, so rows appear
and disappear without a manual refresh.

### 4.3 Playback history

Every finished play, newest first, for working out what happened: who watched what, when, for how long and how.
Each row shows:

- the **title**: an episode leads with its series, with *S02E03 · <episode title>* under it; a movie keeps its
  own title;
- *<username> · <profile>* (the profile is left out when it repeats the user), and **Completed** or **Partial**
  at the top right;
- when the play **ended**, in your device's time zone (*Nov 14, 2026, 9:36 PM*), the **watch time out of the
  length** (*1h 11m of 2h 46m*; *≈* in front when the play was recorded before watch time was measured, so the
  figure is the server's estimate), and the **play method** with a dot in the web's chart color: **Direct
  play** (blue), **Direct stream** or **Remux** (teal), **Transcode** (amber).

**Tap a row** for the details: both times, watch time and the position the play ended at, time spent paused, the
play method, the video and audio decisions (**Direct**, **Remux**, **Transcode**), how it ended (*Stopped by the
viewer*, *Ended by an administrator*, *Client stopped responding*, *Ended by the server*, *Replaced by a newer
play*), the client and device platform, the client IP and the playback session ID. **Copy** and **Share** send
the details as plain text. On a single server, **Logs** and **FFmpeg logs** open Logs for that playback session.

Filters, at the top of the list:

- **User** — opens a list of the server's users by name (with a search box and each user's email); *All users*
  clears it.
- **Profile** — that user's profiles; it says *Choose a user first* until a user is picked, and picking another
  user clears it.
- **All**, **Completed** or **Partial** — *Partial* is the plays that broke off.

With filters set and no matches you see *No plays match these filters*. The list reloads when a filter changes.
In **All servers** the rows and details are the same, but the filter stays a numeric user ID, because each server
has its own users.

- **Load more** appears at the bottom when the server reports more entries after the 50 shown; each
  tap adds the next 50. Pages you have loaded stay loaded when the page is read again.

### 4.3a Libraries

**Manage → Libraries** lists the server's media libraries. It is for platform administrators; inside an
organization there is no such page (the organization's **Libraries** page is about what members may reach).
Each library is a card with:

- its name, its type, how many paths it reads, and *Enabled* or *Disabled*;
- *Last scanned* (how long ago) or *Not scanned yet*;
- a **scan warning** when the server has raised one, in the server's own words: *Empty root* (the last scan
  found no media files, so cleanup was paused), *Unreachable root* (a root is unreachable, or mounted but
  empty; nothing is deleted until it is back) or *Some paths could not be read*. It also says when it was raised;
- the scan under way or the last one: *Scan: Queued*, *Scan: Running*, *Scan: Completed* (with *N new, N updated,
  N missing, N errors* and how long it took), *Scan: Failed* (with the server's reason) or *Scan: Cancelled*.

Actions on each card:

- **Scan** — asks the server to scan that library. The server only answers that it accepted the scan, so the
  card shows *Queued*, then *Running*, then the result as the server reports them. Off while that library is
  queued or running.
- **Stop** — appears only while that library has a queued or running scan, and stops it. It asks first and
  names the library (*Stop scanning Movies?*).
- **Check the mount** — asks the server whether it can reach every folder of the library. The answer is
  *Mount is reachable*, *Mount is not reachable* (with each bad folder and the reason) or *Mount is reachable but
  looks empty* (a folder that is there but whose known files are all missing, which is how a lost network mount
  often looks). A healthy answer also clears an *Empty root* or *Unreachable root* warning.
- **Refresh metadata** — asks which to do: **Refresh missing metadata** (items never refreshed, missing an overview
  or artwork, or that failed before) or **Refresh all metadata** (every item again; a large library can take
  hours). Both name the library, and *Refresh all* has the red button. The card then shows
  *Refreshing metadata: N of M items* and **Cancel refresh**; a finished refresh stays on the card as
  *Metadata refresh finished: N refreshed, N failed*. A refresh someone else started shows too.

**Recent scans** at the bottom lists the newest scans of any kind (yours, a task's, an automation's): library,
what started it, folder or file for a partial scan, the result counts, how long it took and how long ago.

The page reads when it opens and each time the server says a scan or refresh moved; pull down
to read now. **Scan** and **Stop** appear only when the server says it can start scans; a server that does not list
scan runs shows no scan status and says so. In **All servers** each server has its own section and every action
goes to that section's server.

### 4.4 Scheduled tasks

The server's background tasks (scans, cleanups, metadata refreshes). Tap a row to open the task; open **⋮**
on a row for quick actions. A row shows:

- the task name;
- how the last run ended with its length and age, for example *Completed · 4 min 14 s · 7 hr ago*
  (a failed run is red), or *Never run*;
- its schedule in words and when it runs next, for example *Daily at 02:00* and *Next run in 16 hr*. A task
  that is past its time says *Overdue* in amber;
- while it runs, the orange dot, *Running · 42.5%* and a progress bar. A task that is being stopped says
  *Stopping*. The list reads again when the server says a task or job changed.

**⋮** offers **Task page**, **Run now** (only while the task is idle), **Stop** (only while it runs),
**Schedule** (not for manual-only tasks, and not for the backup task) and **Run history**. **Stop** asks
*Stop <task>?* first: work already done stays done and the task stops at its next safe point. Running a
task does not promise that it survives a server process failure.

**The task page** shows the description, the category, the same status lines and **Run now** or **Stop**, then:

- **Schedule** — each trigger in words. Daily and weekly times are the **server's local time**, which may
  not be yours. **Edit schedule** opens *Schedule: <task name>*:
  - **Add a trigger** adds an hourly one. Each trigger has a **Type**: **Every** (a number and **Seconds**,
    **Minutes** or **Hours**), **Daily** (a time as 24-hour HH:MM), **Weekly** (a **Day** and a time) or
    **At startup**.
  - **Maximum runtime (minutes)** stops a run that goes past it; leave it empty for no limit.
  - A trigger of a kind the app does not know is listed as *kept as it is* and is saved unchanged.
  - **Save changes** writes the schedule against the revision you opened. If it changed elsewhere, review the
    current saved schedule and adopt its revision before saving your retained draft. Changes apply to this
    server now; other servers in a cluster pick them up when they restart.
- **Recent runs** — each run's outcome, when it started, how long it took, its error and, for database
  maintenance, the steps that failed. **Load older runs** reads further back.

The backup task's time is set by **Backups → Nightly backup**, so its page says *The time is set on the
Backups page* and offers no schedule editor.

**Background jobs** above the list shows the retained jobs (restores, imports, cleanups), with **Load more**
and **Refresh**.

### 4.5 Requests

Media requests made by viewers, as the web admin's Requests page shows them. Four views sit under the top bar with
how many requests each holds: **Needs approval**, **In progress**, **Failed** and **Done**. The page opens on Needs
approval when anything waits, else on In progress. A search box finds a title or a TMDB ID, **All / Movies / Series**
narrows the list, and a request's account can be picked from its **⋮** menu (*Show only this account's requests*).
Tap a request for its details and history. Each row shows the poster, title, year and type, who asked and when, its
state, and where it was sent, with the buttons its view allows: **Approve** and **Decline** on Needs approval,
**Cancel request** on In progress while nothing was sent, **Try again** and **Cancel request** on Failed. Decline
and cancel ask for an optional note first.

**The count on the menu.** The **Requests** item in the menu shows how many requests need approval. It is read every
60 seconds while the app is in front, and in All servers it is the sum over every saved server. It disappears at
zero, and a server that says it has no request administration is left out.

**Bulk triage.** On **Needs approval**, long press a request, or tap **Select**, to start choosing. Each row gets a
check box and a tap checks or unchecks it. **Select all shown** checks every request on the list. **Approve N**
approves all N at once; **Decline N** asks for one optional note, then declines them all with it. The app sends the
requests four at a time and shows *Approving 3 of 12* with a bar; the buttons wait until it is done. A request the
server refuses (another admin got there first, for example) stays checked, and a line under the bar says why:
*<title>: <reason>*. **Dismiss** clears that message and keeps the checks; fix the cause and tap **Approve N** or
**Decline N** again, or tap **Done** to leave. Picking another view also leaves select mode. In **All servers** each
request is acted on at its own server, and a refusal names the server.

**When the server has no request administration**, the page says *Request administration isn't available on this
server*, with nothing to retry, and stops asking. Pull down to look again after it is set up. In All servers that
server's section says the same while the others carry on. If a refresh fails while requests are still on screen, a line
says *Couldn't refresh · last updated 14:32*.

### 4.6 Users

The heading row has **Users**, **+ Add user** and **Invitations**. Under it is a search box —
**Search by username or email** — that filters the list as you type (matching is done on the
phone, so it is instant). Each row shows the username, the email, and *<Role> · Enabled/Disabled*
(the server owner's role reads *Owner*). **Tap a row to open that user's page.** There is no menu on
the row any more; everything you can do to a user is on their page. In **All servers** the list has
one section per server, each with its own search and **+ Add user**, and a user's page opens for the
server the row is under and names it (*Server: <name>*).

The user page is a page inside the app, not a window over it: the top bar with its menu button and
the server switcher, and the navigation drawer, stay visible and work while it is open.
Closing the page with **Back** (the arrow at its top left, or the system back gesture) returns to
the list exactly as you left it: the same search text and the same place in the list. If the drawer
is open, back closes the drawer first. Choosing another destination in the drawer, or another server
or scope in the switcher, leaves the page; coming back to **Users** shows the list.

There is no way to act *as* another user from this app; to check what a viewer sees, sign in to
the viewer app with that account, or use *View as user* in the server's web admin.

#### 4.6.1 Add user

**+ Add user** opens *Add user* with **Username**, **Email**, **Password** and a role choice —
**User** or **Admin** — and a **Create** button that enables once the three text fields are filled.
The dialog closes on success; a server error is shown inside it.

#### 4.6.2 The user page

The page follows the server's web admin. At the top are the username, then small badges for the
role (*User*, *Admin* or *Owner*), the status (*Active* or *Disabled*), and the sign-in method
(*Password sign-in* or *External sign-in*), plus *Temporary password* when the account must choose a
new password at its next sign-in. Under them are the email, *Joined <date>* and *Last active
<date>* (or *No recorded activity*).

**Actions** sit under the badges. Only the ones you can take are shown:

| Action | Shown when | What it does |
|---|---|---|
| **Reset password** | the account signs in with a password | Emails a one-time reset link, or creates one for you to hand over — see below |
| **Set temporary password** | the account is enabled | Opens *Set a temporary password* — see below |
| **Disable account** | the account is enabled, and is not yours or the owner's | Asks *Disable <username>?* — *They can't sign in until you enable the account again. Their profiles, history and downloads are kept.* |
| **Enable account** | the account is disabled | Enables it at once |
| **Delete account** | the account is not yours or the owner's | Asks *Delete <username>?* — *This deletes the account, its profiles and its history. This cannot be undone. To keep them, disable the account instead.* — then closes the page |

Who can do what, as in the web admin: the **server owner** (the platform admin) can manage every
account except that nobody can disable or delete the owner. An **organization admin** can manage
regular users and their own account, but not another admin and not the owner. On an account you
may only look at, the page says *This is the server owner. Only the owner can change this account.*
or *Only the server owner can change another admin account. You can view it.* and shows no actions.
You can never disable or delete your own account here. A disabled account shows *This account can't
sign in. Its profiles and history are kept.*

**Reset password** opens *Reset password for <username>* with two choices, each shown only when the server can
do it:

- **Email a link** (*Send reset email*) sends a one-time reset link to the account's address. The dialog confirms
  whether the mail server accepted delivery; an unknown result does not mean the link is inactive. Check delivery
  before issuing another link, which replaces the old one.
- **Create link** shows a one-time link for you to give to the person yourself, for when mail is off or the address is
  wrong. The link is shown **once**: *One-time reset link* has the link, **Copy link** (the clipboard flags it as
  sensitive and takes it back after a minute, or when you close the dialog) and **Share link**, and says when it stops
  working. **Done** closes the dialog and the app forgets the link; it is never saved or logged, and opening **Reset
  password** again asks afresh. A server that has no public address set cannot create links and says so.

A turned-off account gets neither choice (*This account is turned off. Turn it on to send a reset*), and email also
needs an address on the account.

**Set temporary password** works on every account you may manage, whether it signs in with a password or through a
provider. *Set a temporary password* has **New password** (at least 8 characters, at most 72 bytes; the field shows
what is wrong) and **Require change at next sign-in** (on by default). For a provider account, setting a password
turns password sign-in back on, so it also recovers an account whose provider is gone. Share the password with the
person yourself; the app never shows it again. A server rule such as *password is too common* is shown in the dialog.

Enable, Disable, Delete and Set temporary password read the user's current version just before sending. If it
changed in between you see *This user changed since it was loaded. Review it and try again.*

Below the actions is a row of tabs: **Overview**, **Profiles** (only when the server lets an admin manage profiles),
**Access & limits**, **Sign-in**, **Activity**, **Downloads** (only when the server offers account downloads) and
**Preferences**. The row scrolls
sideways when it is wider than the screen.

##### Overview

**Watching now** comes first while the account is playing something: the title, *Playing* (with the orange dot) or
*Paused*, the profile, app and device, a progress bar and *12 of 45 min*. It is read again when the server says a session
changed and is not shown when nothing is playing.

*Details* (email and role), *Access* (the limits the server resolved for the account: libraries,
max streams, max transcodes, max profiles, downloads and requests), *Profiles* (each profile and
when it was last seen), and *Last 30 days* (plays, how many were finished, watch time and the last
time anything was played).

##### Access & limits

It opens with two read-only summary lines — *Effective libraries: Unrestricted* or *Effective
libraries: Restricted to N*, and *Effective permissions: None assigned* or *Effective permissions:
N assigned* — followed by:

- **Access group** — *No group* or one of the server's groups (not shown for an admin: *Admins don't
  use access groups. Unset values follow the server default.*). Picking a group shows a one-line
  summary of it (*Family: Movies · 1080p · 2 streams*).
- **Libraries** — either *Use the group's libraries* (or *Use the server default* when there is no
  group) or *Choose for this account* with one checkbox per library. The first choice says what it
  gives right now (*All libraries*, or the library names), and follows the group you have picked
  before you save, so the words are never about a different group than the one on screen. Choosing
  *Choose for this account* starts from what the account can see now. Ticking nothing says *This
  account won't see any library.*
- **Permissions** — *Marker editing*, *Metadata curation* and *Watch Live TV*, each a switch. When
  the account's group does not allow a permission the switch is off and locked, with *<Group> group
  doesn't allow this*; if the permission is also assigned on the account itself it says *Set on this
  account; has no effect under <Group>* and **Remove from account** clears it.
- **Max streams**, **Max transcodes** and **Max profiles** — number fields, each with an **Inherit**
  checkbox. Ticking **Inherit** greys the field out and removes the user's own limit so the
  server's default applies again; unticking it lets you type a number.
- **Downloads allowed**, **Requests allowed**, **Transcode allowed**, **Audio transcode allowed** —
  each a switch with its own **Inherit** checkbox beside it, working the same way.
- **Save changes** sends only what you changed, in one request, against the version of the user the
  page was loaded with; choosing **Inherit** on something that had a value is sent as "clear this".
  If someone else changed the user since, you see *This user changed since it was loaded. Review it
  and try again.* (it stays on screen), and the form reloads with what is on the server now, so you
  can repeat the change. Nothing is ever sent twice. **Save changes** is disabled until something
  changed, and while a non-inherited number field is empty or not a number (the field turns red).
  *Saved.* confirms a save.
- Leaving the page with unsaved changes asks *Discard changes?* — **Discard** or **Keep editing**.

If the groups or libraries cannot be read, the tab says so with **Try again**; limits stay editable.

Only the server owner changes an admin's access and limits, including an admin's own; for those
accounts the tab reads *Only the server owner can change an admin's access and limits.* and the
fields are disabled. On an account you can only view it reads *You can view this account's access
and limits but not change them.* Role is not editable here; create the user with the right role or
change it in the server's web admin.

##### Profiles

Platform mode, and only where the server offers profile management. Each profile shows its name, *Primary* for the
household parent, *Kids*, *PIN* when it has one, and when it was last seen. **Add profile** asks for a name (1 to 64
characters). **⋮** on a profile offers **Rename**, **Set PIN** or **Change PIN**, **Remove PIN** (when it has one) and
**Delete profile** (not on the primary profile, which can only go with the account). A PIN is exactly four digits;
setting or removing one ends the profile sign-ins made with the old PIN, and removing the primary profile's PIN also opens the
household's management. Deleting asks first and removes the profile's settings, watchlist and ratings. Only the server
owner changes the owner's or another admin's profiles; otherwise the list is read-only. The server refuses a name
another profile on the account uses, and a household at its profile limit.

##### Sign-in

*Password* says how the account signs in, and shows *The password is temporary…* while a new one is
required. *Sessions & devices* has two lists:

- **Sessions** — each active sign-in, shown by device name (or *Unknown device*) with its IP address
  and *this session* on your own, then *Last seen <date and time>* and *Expires <date and time>*. **Revoke** on a row asks *Revoke this session?* — *The device using
  this session will be signed out immediately.* **Revoke all sessions** (shown when there is at least
  one) asks *Revoke ALL sessions?* — *Every device this user is signed in on will be signed out
  immediately. If this user is your own admin account, this will also sign YOU out.* *No active
  sessions.* appears when the list is empty. Accounts you can only view show no Revoke buttons.
- **Devices** — each device the server knows for this user, with its platform and *Last seen <date and time>*
  (*Not seen yet* for a device known only from saved settings). Revoking a device
  is hidden for now and returns in a later version; to sign a device out, revoke its session
  above. *No known devices.* appears when empty.

##### Activity

*Watch history* lists what this user played (title, when, and *Completed* or *Partial*), newest
first, with **Load more**. *IP addresses* lists the addresses they used in the last 30 days with
the request count and the last time.

##### Downloads

Shown only when the server offers account downloads: a summary (*N downloads on M devices: …*) and
the user's downloads with their status and size, with **Load more**. It is read-only.

##### Preferences

The settings this person saved in their apps, by level, like the web admin's Preferences tab. A
**Setting level** button at the top lists the levels with how many settings each holds: *Account-wide*
(only when there are account-level rows), and for each profile *All devices*, each device it uses,
*<TV/Mobile/…> apps*, each library and each series that has settings. Pick one to see only what it
stores. Each row shows the setting's category and name, and *Replaces Main: Auto* or *Replaces app
default: Off* — what the setting would fall back to if removed.

- Switches write at once. Choices open a menu of the server's own options. Numbers and text have a
  **Save changes** button that stays off until the value is valid for the setting (*Enter a number from 100
  to 200000.*). Each write changes exactly one stored row.
- The bin icon asks *Go back to the app default?* (or *Use <profile>'s value?*) and then removes that
  row.
- **Add a setting** lists the settings that level can hold and are not set there; each starts at the
  value it replaces, so nothing changes until you edit it.
- Settings made of several values (such as subtitle appearance) are shown but not edited here, and
  say *View only*. A setting this server version does not know is marked *Not in this version*.
- On an account you may only view, or when the server's settings contract cannot be read, every value
  is shown and none is editable.

**Not on the page yet:** *View as user*, *Make owner*, profile editing, the *Watching now* card, and
in Preferences the raw JSON editor, *Clear device settings* and the profile home-sections editor.

#### 4.6.3 Invitations

**Invitations** (on the Users heading row) opens a full-screen list titled **Invitations** with
**+ Invite** and **Close**. Each row shows the email, then *<Role> · <Status> · Expires <date and time>* (for example *User · Pending · Expires Nov 21, 2026, 9:12 AM*, in your device's time zone), with
**Resend** and **Revoke** buttons. *No invitations yet.* appears when empty.

- **+ Invite** opens *Invite a user*: **Email**, a role choice (**User** / **Admin**), **Note
  (optional)**, and **Send invite**.
- After sending or resending, a dialog tells you whether the email went out and shows the
  **Invite link** with **Copy link** (which turns into *Link copied*). It is titled **Invitation sent** (*The invite
  email was sent*), **Email may not have arrived** (*Don't assume it arrived: share this link with the user directly*) or
  **Invitation link ready** (the server has no email set up, so nothing was sent).

> The invite link is shown once. The server keeps only a fingerprint of it afterwards, so if you
> close the dialog without copying it, use **Resend** to get a new one. The copied link is marked
> sensitive (Android hides it from the clipboard preview) and is removed from the clipboard again
> when you close the dialog, so paste it before closing.

- **Revoke** asks *Revoke this invitation?* — *<email> will no longer be able to use this invite
  link.* — **Revoke** / **Cancel**.

### 4.7 Organizations

Under **Platform**, for every platform administrator of a server that has organizations (a plain server, or an older one,
doesn't show the page). The server's Owner also keeps **Support access**.

**The list.** Every organization on the server, by name, with its short name and *12 people · 25 profiles · 3 libraries*. The
status is *Active*, *Suspended* or *Setting up* (anything but active is marked in the warning color). **Search** matches the name or
short name; **Status** narrows to one status. **Show more** loads the next page. Pull down to refresh. A refusal says
*This account can't manage organizations on this server. Ask the server owner for platform access.*

**One organization.** Tap a row. The page shows its people (and how many are active), profiles, own libraries and the libraries
shared with it; its **Status**, **Owner** (by username, or email if there is no username; *Name unavailable* if the owner
can't be found among the members; never an account number) and **Short name**; and its **Members** with *Owner*, *Admin* or
*Member* and *Active*, *Invited* or *Suspended*. **Show more members** loads the next page.

- **Open as administrator** appears only when your account may. If you are a member of the organization (and it is active) it
  opens like any organization in the top-bar menu. If you are the server's Owner and not a member, it first asks **why** (10 to
  500 characters), then opens the organization under support access with its banner (4.11). Anyone else doesn't see the button,
  and a suspended organization can't be opened.
- **Suspend organization** asks first: *Suspend Acme Media — 10 active members lose access to Acme Media until you reactivate
  it. Nothing is deleted.* — **Suspend** / **Cancel**. **Reactivate organization** asks *Members of Acme Media can use it
  again.* Nothing is sent until you confirm. If the organization changed while you were looking (someone else suspended it, or
  its settings moved on) nothing is applied: the page reloads and says so, and you ask again.
- **Plan** (when the server has plans). It shows the **Current plan** with its revision (or *No plan yet*), the organization's
  limits (*50 screens · 4 conversions at once*), its libraries, when the plan was **Last applied**, and the **Default settings
  for members**. To change it, choose a plan under **Choose a plan** and tap **Preview changes**. The preview lists what would
  change in plain words, for example *Plan: Standard, revision 3 → Premium, revision 2* and *Screens at once: 2 → 4*, or says
  *Nothing changes: it already uses this plan*. **Apply plan** asks *Put Acme Media on Premium?* with the same list and *Members
  on the default settings get the changes you reviewed. Access groups the organization made itself stay as they are.* A preview is
  needed for every apply; if the organization changed after the preview you are asked to preview again. Only plans that are on
  offer can be chosen; plans are created on the web admin.

Not in the app yet (use the web admin): creating or renaming an organization, changing a member's role or suspending one
member, transferring ownership, domains, branding and editing plans.

### 4.8 Settings

**Server settings** (in the **Configure** group) opens a list of named pages, like the web admin's settings
pages. In **All servers**, settings are per server: each server's section has **Open settings**, and
its pages open in place of the list.

At the top of the list:

- **Uptime** and *Started <date and time>*, in your device's time zone.
- If a restart is waiting: *Restart needed: <setting names>*, for example *Restart needed: Hardware
  acceleration, Public URL*. Each name is the setting's own label. *Active streams stop while the
  server restarts.* sits under it.
- **Restart server** — asks *Restart this server?* and says what a restart costs: the server shuts
  down, it comes back on its own only if a service manager such as Docker or systemd restarts it,
  and active streams are interrupted. A second request while the server is already restarting
  answers *A restart is already under way*. The app vibrates once the server has accepted the
  restart.
- **Search settings** finds a single setting by its name (*Log level*, *Public URL*, *SMTP*) as well
  as a page. Picking a setting opens its page at that setting.

The pages (a page the server does not have is not listed):

| Page | What it holds |
|---|---|
| **General** | Server name, login subtitle, public URL, show on the local network, local network address, trusted proxies, maximum Postgres connections, and a **Redis** connection check |
| **Network access** | Tailscale, NetBird and other overlay providers: each host's state, **Connect**, **Disconnect**, and the authorization link |
| **Sign-in** | Allow password sign-in, public signups, self-service password reset, match existing accounts by email, and each single sign-on provider with **Test connection** |
| **Playback** | Watched and Continue Watching thresholds, transcoding on or off, hardware acceleration, 4K, HEVC, tone mapping, throttling, connection speed tests, skip marker source |
| **Logging** | Log level, quiet log prefixes, and how much log history the database keeps. The **Logs** page has a **Log settings** link to it |
| **Email** | Send email from this server, from address and name, mail server, port, encryption, username, and whether a password is set; **Send test email** |
| **Notifications** | Delivery channels (in-app, web push, Apple and Android push, email, Discord, personal webhooks, server channels), how new content becomes notifications, retention; each server channel with its last failure and **Send test** |
| **Downloads** | Allow downloads, prepared copies, limits for the server and for each user, disk ceiling, cache time |
| **Requests** | Allow requests, approval, request limit and window, watchlist requests, also request 4K |
| **Connection checks** | Redis, public and private storage, search engine, MDBList, AI text model, AI speech to text: each one **Check** against the settings saved on the server |
| **Other settings** | Every other setting the server reports, by its stored name, with **Edit** |

Every control has a label, the web admin's help text, and the right kind of input: a switch, a number
with its unit, a choice or a text field. Secrets are never shown; a secret shows only *Set* or *Not
set*, and a setting the server's environment owns shows that it cannot be changed here. A setting that
only takes effect after a restart says *Applies after a restart*. Settings still at their default are
listed and can be changed.

Edits are kept until you save. A bar appears at the bottom (*2 changes*, **Discard**, **Save changes**), and
**Save changes** writes only the changed settings in one request. While a page has unsaved edits the menu and
the top bar stay locked and **Back** asks *Discard your changes?*. If another administrator saved
anything in the meantime, the server refuses the save: the app reads the settings again, says
*Settings changed on the server*, keeps your edits on screen, and you save again. When a saved
setting needs a restart the page says *Saved. This takes effect after a restart.*

Turning off **Allow password sign-in** can be refused when no break-glass admin could still sign in;
the page shows the server's reason and keeps your edit.

**Test actions** show *Passed* or *Failed* with the server's own reason (for example *535 Authentication
failed*): **Send test email** (it uses the saved mail settings, so save first), the connection checks,
**Send test** on a server channel, and **Test connection** on a sign-in provider (each step with its own
result). *The test could not run* means the server refused or could not be reached, which is different
from the thing under test failing.

The **Requests** page edits the server-wide request settings with the same save bar and the same
conflict rule. It is shown only on a server that serves guarded request settings.

### 4.9 Audit

The server's request log — every API call it received, newest first — written so you can see who changed what.
A row shows:

- the **action** (*Update user*, *Update access group*; *HTTP request* when the call names none) with the
  **before and after** of each field it changed under it (*max streams: 2 → 4*, *role: user → admin*;
  *inherit* for a setting the account follows from its group, *all libraries* for an access group with no
  list, *absent* for a side the server did not record). A profile's PIN is never shown, only *set*, *cleared*
  or *changed*;
- the HTTP method and path second (*PATCH /api/v2/admin/users/3*);
- *<date and time> · <status code>* in your device's time zone (a server error is red);
- who did it, by name when the user list is available (*pat*, or *pat as jonas* for a call made while
  impersonating, otherwise *User 7*), and what it acted on (*user #3*, *access group #3*, *API key #9*).

**Tap a row** for the details: the changes, the target, the request, status and duration, the **client IP**,
the **user agent**, the user (and who impersonated them), and the request ID, node, session and playback
session. **Copy** and **Share** send them as plain text; **Logs for this playback session** and **Logs for
this request** open Logs on them.

- **User** — a list of the server's users by name. It filters on the acting account, as the web does, so
  calls made while impersonating that user are included. In **All servers** the filter stays a numeric user ID.
- **Load more** fetches the next 100 entries.
- The first page is read when you open the page. Pages you brought in with **Load more** stay, and a
  re-read first-page row replaces its older copy. Pull down to start again from the first page.

### 4.10 Logs

The server's own operational log (distinct from Audit, which is about requests). Controls at the top:

- Level chips: **All**, **Debug**, **Info**, **Warn**, **Error** and **Warn and error**. One is selected at a time.
- **Search messages** — free-text search, applied a moment after you stop typing.
- **Filters** (with the number set, *Filters (2)*) — **Component** (a list that matches the web's, with a search box;
  *All components* clears it), **Request ID** and **Playback session ID**.

Each row has the level (*Error* in red, *Warn* in amber), the message, and *<date and time> · <component>* in your
device's time zone, with the status and duration after them for a request. **Tap a row** for the details: the
message, component, level and time, the request's **method**, **status**, **duration** and path (read from the
line's attributes, as the web does), the **client IP**, **user ID**, **request ID**, **node**, **session** and
**playback session**, and all attributes as indented text. **Copy** and **Share** send them as plain text;
**Logs for this playback session** and **Logs for this request** narrow the list to them.

**One playback session.** From **Now playing → ⋮ → Logs** or **FFmpeg logs**, from a play's details in **Playback
history**, or from an audit entry, Logs opens narrowed to that playback session (*FFmpeg logs* also to the
FFmpeg component). A summary sits on top, as on the web: the **application**, **FFmpeg** and **audit** line
counts, when the session was **first** and **last seen**, and the **nodes** it ran on. The counts are of the
lines loaded, and read *N or more* when older ones remain. **FFmpeg only** / **All components** switches the
component; **Clear session** leaves the session.

**Load more** fetches the next 100. Like Audit, this destination reads its first page when it opens,
keeps the pages you brought in with **Load more**, and starts again from the first page on a
filter change or a pull down.

### 4.11 Organization mode

An **organization** is a customer's own part of a server: its members, profiles, access groups and the
libraries it may use. If you administer one, the app shows only that organization's pages and only that
organization's data. Whoever runs the server (the platform administrator) sees an organization this way
too, for an organization they are a member of; the server's Owner can also open any other organization for support
(see **Support access** below).

**Getting in.** Open the server switcher and choose the organization under the server, or open the
server from the list: an account that administers a single organization lands in it straight away, and
one that administers several is asked to choose. The top bar then shows the organization's name where
a server name would be. **Manage servers** in the drawer takes you back to the server list.

**The pages.** The drawer lists the web admin's organization menu and nothing else:

| Page | What it is for |
|---|---|
| **Overview** | Members (and how many are active), profiles, the libraries the organization owns and the ones the server shares with it, its owner by name (display name, else email; *Name unavailable* if it can't be read), and its settings revision |
| **Now Playing** | Only if the server can show it. Who in the organization is watching right now — see below. On a server that supports live updates the list, **People** and **Activity** change by themselves; otherwise pull down to refresh |
| **People** | Everyone in the organization — see below |
| **Policy Cohorts** | Read only: groups of accounts that share the same settings |
| **Access Groups** | Create, edit and delete the groups that decide what each profile can use |
| **Libraries & Entitlements** | What members can reach at most; pause, restore or give back a shared library |
| **Invitations** | Invite someone, with an access group and a note; resend or revoke |
| **Policy Decisions** | Read only: the access checks the server recorded, and why each was allowed or refused |
| **Activity** | Changes made to the organization, newest first. Each shows when and who: *By account 4* for one of the organization's own administrators, *By Platform support* for the server's operator while a support access is open (with *Reason: …* on the event that opened it, as the operator wrote it), or *By Bloem* when the server does not say |

**People.** Each person shows their name and email, a status (**Active**, **Suspended**, **Invited**),
an *Administrator* and *You* tag where they apply, when they were last active, and each profile with its
access group.

- **Search** matches name, email or profile. **Membership status** and **Access group** narrow the list.
  **Sort people** orders it by **Name**, **Email** or **Recent activity**. **Active since** keeps only people
  active in the last 24 hours, 7, 30 or 90 days. **Show more** loads the next page.
- **Access group** on a profile moves that one profile to another group.
- **Suspend** and **Reactivate** each ask first. They never appear on your own row, and the organization's
  owner cannot be suspended — the app shows the server's refusal.
- **Details** opens the person's own page: their membership and status, last activity, each profile with its
  access group and what that group allows, the changes made to their membership among the newest entries of the
  organization's activity (the server has no per-person activity list, so the page says how many entries it
  looked through), and the same Suspend, Reactivate and move actions. It also lists what you cannot do here
  (below).
- **Select people** lets you change several people at once, as the web's bulk bar does. Tick people, or tap
  **Select all N** to take everyone the current filters match. A bar shows how many are selected and offers
  **Move to access group**, **Reactivate** and **Suspend**. Each asks first, naming the organization, the
  filters in words (*matching "sam", active, in Family, active in the last 7 days*) and, for **Select all**,
  how many more match but are left out. Afterwards a result card says how many were changed, skipped or failed,
  and for each skipped or failed person why, in the web's words (*already had these settings*, *is the organization
  owner and can't be changed here*, *skipped so you keep your own admin access*, …).
  - **Select all N** hands the change to the server as one job. You can leave the screen: it keeps running, and
    the card follows it until it finishes.
  - People you **ticked** are changed by the app one after another, each with its own change. If one cannot be
    changed the rest carry on; if the organization is taken away from you partway, it stops.
  - Nothing is ever sent twice. If the server says someone changed first, that person is reported, not retried.

**Access Groups.** **New group** asks for a name and opens the group so you can set it up. A group has a name and
description; whether it is the **default for new users** (you can make a group the default, not unmake it); the
libraries it reaches (**every library the organization can use**, or **only the libraries I choose**); the best
picture quality (**Any quality**, **Up to 1080p**, **Up to 4K**); downloads, converted downloads and media requests;
how many screens and conversions at once (0 means no limit); video and audio conversion; and permissions (all,
or a chosen few). **Save changes** writes the whole group. **Delete group** asks first and says where its profiles
go: to the default group. The default group cannot be deleted. A group the server manages through a plan can only be
changed there; the server says so and the app shows its words.

**Libraries & Entitlements.** The top card counts what members can use *right now*: the libraries the organization
owns plus the shared ones that are active — the most any member can see. Each person's plan, access group and profile
can narrow it further. A shared library offers **Pause** (members cannot use it until you **Restore** it) and
**Give back** (members lose it for good; only the server's administrator can share it again; no files are deleted).
Each asks first. The organization's own libraries have no actions here.

**Policy Cohorts** show each cohort's origin (*From plan family, revision 1*, *Changed from another cohort*,
*Organization default*), how many accounts are in it, and its settings as labelled rows — libraries by name, whether
it plays video, screens and profiles allowed, video and audio conversion, conversions at once, downloads, best picture
quality, media requests and extra permissions. **Details** adds how it was made, its revision, the cohort it started
from, when and by whom it was created, its access group, its ID and a fingerprint of its settings. **Archived cohorts**
sit behind their own switch; nobody can be moved to one.

**Policy Decisions** list the recorded checks (*Download: refused*, with the reason). **See why** opens one: who asked
(account and profile), the access group, the libraries they could have used, what they asked for (values the server
considers sensitive are shown as *Sensitive value hidden*), and under **Details** the check's ID, the reason code, the
organization and membership IDs, and the rules the server checked.

**Helping a person.** On a person's page (**People → Details**), where the server offers it, you can help them get
back in and fix their profiles. You help the organization's members; the organization's **owner** can also help its
other administrators. Nobody helps themselves this way (use the app's own settings), only the platform operator helps
the owner, and a platform account stays protected. When the server refuses, the page says who is protected and why,
in words rather than a bare error. Each action below appears only if the server says it can do it, so on an older
server the page shows nothing new. Anything that cannot be undone asks first, and nothing is sent twice.

- **Reset password.** *Email the link* sends the person a link to choose a new password (valid 24 hours; the server
  must have email set up, otherwise only the link option shows). *Show a one-time link* makes a link (valid 4 hours)
  that you hand over yourself. You never see or type a password. The link is shown **once**, in a box with **Copy
  link** and **Share link**; closing the box removes it for good, and it is never saved. A copied link is marked as
  sensitive and cleared from the clipboard after a minute or when the box closes. The person also gets an email saying
  a reset was issued. The server limits how often a reset can be issued; if it says to wait, wait.
- **Signed in** lists the person's sign-ins: device, platform, the profile in use, when they signed in and were last
  seen. There is no address anywhere. **Sign out** ends one sign-in; **Sign out everywhere** ends all of them, and every
  app asks the person for their password again. Both ask first.
- **Devices** lists the devices they are signed in on and the profiles used on each. **Sign out device** ends that
  device's sign-ins only; their other devices stay signed in.
- **Profiles.** **Add profile** asks for a name (up to 100 characters) and an access group. **Rename** changes the name.
  **Clear PIN** (shown only for a profile that has one) removes it; the person chooses a new one, and you can never set a
  PIN or change age limits. **Remove** deletes the profile and its watch history and asks first; the person's main
  profile can't be removed, and a stream playing on a profile should be stopped first.

If someone changed the person while you were looking, the page reloads, says so, and you ask again; your change was not
applied.

**Now Playing.** The same card as the server's Now Playing page: title (series, season and episode), the person and
profile, the app and device, playing or paused, the playback decision (Direct Play, Remux, Direct Stream or Transcode),
a progress bar and the video and audio decisions. Tap a card for its details. It shows **no client address, node or
routing**: those describe the server, not your organization. The list reads when you open the page and when you pull down. The **⋮** menu has
**Send message** (a title and a message, shown on the viewer's screen) and **Stop stream**, which asks first. Your own
stream and a platform account's stream have no menu. If the viewer's app isn't connected the page says so.

**Support access (the server's Owner only).** If your account is the server's Owner and the server has the support
feature, the drawer has **Support access** under Platform (you can also start from **Open as administrator** on an organization's
page under **Platform → Organizations**, 4.7). It lists every organization by name. Choose **Open for
support** on one you are not a member of (a suspended one can't be opened), write **why** (10 to 500 characters), and
**Open organization**. The reason and every change you then make are shown to that organization's administrators in
**Activity** as *Platform support*, without your name. While you are in, a banner on every page reads **Platform
support access** with the time left (the access ends by itself four hours after it opened) and **End access**, which
asks first and then returns you to the server's own pages. If the access runs out you see *This support access has
ended.* and choose where to go next. The reason is kept in the app only until you leave; opening again asks again. Anyone
who isn't the Owner never sees the page, and the server answers them with the usual "not found".

**What you cannot do here.** You cannot set a PIN, change age limits or other parental settings, or sign in as someone.
Personal API keys survive *Sign out everywhere*. Neither the web's *Change plan or settings* (policy previews and jobs)
nor storage sources and request integrations are in the app yet; use the web admin for those. On a server without the
support feature the person page says that resetting, signing out and editing profiles aren't available to
organization administrators, and to ask whoever runs the server.

**When the organization is taken away.** If the server suspends the organization you see *This organization is
suspended. Contact the server owner.*; if your account stops administering it (your role changed, or your membership
ended) you see *You no longer administer this organization.* Either way **Choose another organization** returns to the
choice, and the organization disappears from the server switcher until the server lists it for you again. If you are
signed out you get the usual sign-in form.

---

### 4.12 Nodes

Under **Manage**, only on a server that offers it. One card per stream node, headed by its name and one of
**Healthy**, **Draining**, **Drained** (safe to stop: no work left), **Not answering** (red) or **Off**, with
its type and group.

- **Load** — CPU, memory, disk (the fullest mount) and network from the node's last health check. A reading
  at 90% or more is amber. A node that is not answering, or whose sample has gone stale, shows *No current
  reading* instead of old numbers.
- **Capacity** — transcodes (or streams, on a proxy node) against the node's limit, a proxy node's egress,
  and when the node was last checked.
- **⋮** offers **Drain** (transcode nodes) or **Resume** (after a drain you requested), and **Turn off** or
  **Turn on**. Each asks first. A drain stops new work while current sessions finish and cuts nothing off.
  Turning a node off keeps its streams running but sends it no new work.

The dashboard's **Nodes** health cell opens this page. In **All servers**, each server has its own section and
every action goes to the server whose card you used.

### 4.13 Backups

Under **Configure**, only on a server that offers it. The status card shows the last successful backup, the
next one (from the backup task, in your time zone), the nightly time and number of copies kept, a failed or
interrupted last run in red, and any warning the server raises. While a backup runs it shows the orange dot,
the percentage and a progress bar.

- **Back up now** asks first, then starts a backup on the server. It is off, with the reason underneath,
  while another backup or restore holds the lock, when the server has no storage for backups, when an
  encryption key needs confirming on the web, or when the destination cannot be reached.
- **Nightly backup** opens an editor for **Back up every night**, **Time (24-hour, server time)** and
  **Copies to keep (1 to 60)**. **Save changes** writes only what you changed. If another administrator changed the
  settings while you edited, the editor says so and offers **Load current values**.
- **Saved backups** lists each copy with its kind, time, size and state. **Delete** asks first and is off while
  a backup or restore runs.
- *Restoring a backup is done on the web.* Restore, upload, download and the backup destination are not in
  this app.

### 4.14 Plugins

Under **Configure**, only on a server that offers it. The installed plugins, problems first, each with its
version and one of **Running**, **Starting**, **Stopped**, **Restarting after a failure** (amber), **Failed**
(red) or **Off**, the last error, a refused update and a failed release signature. The filter at the top has
**All**, **Needs attention**, **Updates** and **Off**, each with its count.

**⋮** offers **Restart** (not for a plugin that is off), **Update to <version>** (when one is available) and
**Turn off** or **Turn on**. Each asks first. Turning a plugin off stops what depends on it until you turn it
back on. *Installing plugins and changing their settings is done on the web.*

### 4.15 API keys

**Configure → API keys** lists the server's API keys, newest first, where the server offers key administration (an older
server does not list the page). Each key shows its label, the start of the key (so you can recognise it), *Owner <name>*,
*Access:* its scopes, or *Full access* when it has none, and *Last used <date and time>* or *Never used*. A key on the
elevated rate limit says so. **Load more** adds the next 50.

**Revoke** asks *Revoke <label>?* — *Anything using this key stops working at once. This can't be undone* — and, once you
confirm, reads the key's current version and revokes it only if nobody changed it meanwhile (otherwise it says so and
reads the list again). As on the web, only the server owner can revoke another admin's key, and nobody can revoke the
owner's; those keys say *Only the server owner can revoke this key*. Keys are created, and rate limits changed, in the
web admin. The page is for one server, not All servers.

## 5. Quick reference: where things are

| I want to… | Go to |
|---|---|
| See who is watching right now | **Now playing** (or **Dashboard → Active streams** for the count) |
| Kick someone off | **Now playing → ⋮ → End session** |
| Send a note to a viewer's screen | **Now playing → ⋮ → Message** |
| Approve a request | **Requests → Approve** on its row |
| Approve or decline many requests | **Requests → Needs approval →** long press a row **→ Select all shown → Approve N** or **Decline N** |
| Give someone a reset link when mail is off | **Users →** the user **→ Reset password → Create link** |
| Give someone a temporary password | **Users →** the user **→ Set temporary password** |
| Clear a forgotten profile PIN (platform) | **Users →** the user **→ Profiles →** the profile **→ ⋮ → Remove PIN** |
| Revoke a leaked API key | **Configure → API keys → Revoke** |
| Scan one library now | **Libraries →** the library **→ Scan** |
| See whether the server can reach a library's storage | **Libraries →** the library **→ Check the mount** |
| Refresh a library's posters and descriptions | **Libraries →** the library **→ Refresh metadata** |
| Run a library scan now | **Scheduled tasks → Run** on the scan task |
| Change how often a task runs | **Scheduled tasks →** the task **→ Edit schedule** |
| See whether last night's job worked | **Scheduled tasks →** the task **→ Recent runs** |
| Take a node out of rotation | **Nodes → ⋮ → Drain** |
| Back up before a risky change | **Backups → Back up now** |
| Move the nightly backup time | **Backups → Nightly backup** |
| Restart a crashed plugin | **Plugins → ⋮ → Restart** |
| Invite someone by email | **Users → Invitations → + Invite** |
| Create an account directly | **Users → + Add user** |
| Sign a lost phone out of a user's account | **Users →** the user **→ Sign-in → Revoke** on that phone's session |
| Limit a user to two streams | **Users →** the user **→ Access & limits → Max streams** (untick **Inherit**) |
| Restart the server | **Server settings → Restart server** |
| Move several people to another access group | **Organization → People → Select people →** tick or **Select all →** **Move to access group** |
| Suspend a member of an organization | **Organization → People →** the person **→ Suspend** |
| Help a member who can't sign in | **Organization → People →** the person **→ Details → Reset password** |
| Sign a member out of a lost phone | **Organization → People →** the person **→ Details → Signed in / Devices → Sign out** |
| Clear a forgotten profile PIN | **Organization → People →** the person **→ Details →** the profile **→ Clear PIN** |
| See or stop what an organization is watching | **Organization → Now Playing → ⋮** |
| Open a customer's organization (server Owner) | **Platform → Support access →** the organization **→ Open for support**, or **Platform → Organizations →** the organization **→ Open as administrator** |
| Look up an organization and who runs it | **Platform → Organizations →** search, then the organization |
| Cut off an organization, or let it back in | **Platform → Organizations →** the organization **→ Suspend organization** or **Reactivate organization** |
| Change an organization's plan | **Platform → Organizations →** the organization **→ Plan →** choose **→ Preview changes → Apply plan** |
| Give a shared library back | **Organization → Libraries & Entitlements → Give back** |
| Make a new access group | **Organization → Access Groups → New group** |
| Find out why a member was refused something | **Organization → Policy Decisions → See why** |
| Find out why something failed | **Logs** (level **Error**) and **Audit** (status codes) |
| Watch several servers at once | **Server switcher → All servers** |
| Be told when a server stops answering | **Settings → Server alerts** |
| See streams and requests without opening the app, or approve a request | Add the **Server status**, **Now playing** or **Requests** widget, or a Quick Settings tile |
| Sign out of a server on this device | **Manage servers → ⋮ → Forget credentials** |
| Forget a server | **Manage servers → ⋮ → Remove server** |

---

## 6. When something is not working

| What you see | What it means | What to try |
|---|---|---|
| A screen's own failure line followed by *(HTTP 502)*, for example *Couldn't load scheduled tasks (HTTP 502)* | The server, or a proxy in front of it, answered with an error that carried no readable message; the number is the HTTP status | Try again; if it keeps coming back, check the server (a 502 or 503 usually means it is down or restarting) and look in **Logs** |
| *Enter an HTTPS server address without a path, query, or sign-in details* when adding a server | The address is not a plain `https://host[:port]` | Remove anything after the host; use `https://` |
| *Couldn't reach that server…* when adding a server | No route to the server, or its certificate is not trusted | Open the address in the phone's browser; fix it; try again |
| *This server didn't pass the required health check…* / *This server isn't compatible with Bloem Admin…* | The address answers, but not as a Bloem server | Check the address with whoever runs the server |
| *The username or password wasn't accepted…* | Wrong sign-in details | Try again |
| *This account needs administrator access…* when adding a server, or *This account doesn't have admin access on <server>* when opening one | The account is not a platform administrator and administers no organization | Use an admin account, or ask for the account to be promoted |
| *This server uses an insecure address…* on a saved server | Saved by an older version with an address the app no longer accepts | **Remove server**, then add it again with `https://` |
| Repeated *Your session on <server> has expired* | Your sessions were revoked on the server, or the server's token lifetime is short | Sign in again; if it keeps happening ask whoever runs the server to check sessions |
| *Sign-in credentials cleared* at launch | The app rebuilt its encrypted sign-in store | Sign in to each server again; nothing else is lost |
| *Secure credential storage unavailable* | The device's secure storage is not working | Restart the app; check the device's screen-lock settings |
| A page shows *… unavailable* or *Couldn't load …* | The request timed out (15 seconds) or the server errored | Tap **Try again** or pull to refresh; check the server from a browser |
| *Latest refresh failed* above a list | The data shown is from an earlier refresh; the newest attempt failed | Tap **Try again**; if it persists, check the server |
| *Couldn't complete that action* | The server rejected the action without a message, or the request timed out | Try again; check **Logs** on the server |
| *Sign in to continue* / *Signed out. Tap to sign in again* | All servers section whose sign-in expired | Tap it, sign in, go back |
| **Organizations** missing from the drawer | The server has no organizations (a plain or older server), or you are not a platform administrator there | Nothing to fix; an organization-only administrator uses the organization menu |
| *This account can't manage organizations on this server* | The server refused platform access to your account | Ask the server owner for platform access |
| *This organization changed while you were looking at it* | Someone changed it after you opened it; nothing was applied | Check the reloaded page and try again |
| *This organization is suspended. Contact the server owner.* | The server suspended the organization | Ask the server owner; it returns to your list when they reactivate it |
| *You no longer administer this organization.* | Your role there changed or your membership ended | Ask the organization's owner or the server owner |
| *Someone else changed this just now. It has been reloaded; check it and try again.* | Another administrator changed the same person, group or library first | Look at what the page now shows and repeat the change if it is still wanted |
| *That selection expired. Select the people again.* | A **Select all** selection is only kept for a short while | Tap **Select all** again |
| *This is taking a while. It keeps running on the server…* | A bulk job is slow | Open **People** again later; the result is in **Activity** |
| An organization is missing from the server switcher | You are no longer an administrator of it, it is suspended, or the server could not be asked | Open the server switcher again after signing in; ask the server owner |
| *This server needs a newer version of Bloem Admin* | The server no longer accepts this version of the app | Install a newer version |
| *Another command reached this stream first. Try again.* | A newer stream command arrived first | Try the command again |
| *Sampled metrics unavailable* instead of host figures on the Dashboard | The server cannot measure its host | Nothing to fix |
| **Save changes** greyed out in Access & limits | A number field that is not set to Inherit is empty or not a number | Enter a number, or tick **Inherit** where offered |
| No fingerprint prompt at launch | No screen lock enrolled on the device | Enrol one in Android settings if you want the lock |

---

## Glossary

- **Admin check** — the check made when you open a server, that your account can administer it.
- **Access check** — a record that the server decided whether someone may do something (Policy Decisions).
- **Access group** — a named set of limits and permissions a profile belongs to.
- **Cohort** — a group of accounts in an organization that share the same settings; it never changes.
- **Organization** — a customer's own part of a server, with its own members, groups and libraries.
- **Organization mode** — the app's view for one organization, for an organization administrator (section 4.11).
- **All Servers** — the first row of the server list; it shows every server's data in sections.
- **Page** — one management screen inside a server (Dashboard, Now playing, …).
- **Direct play / Transcoding** — the file is sent unchanged / converted on the fly for the client.
- **Forget credentials** — remove this device's stored sign-in for a server without removing the server.
- **Inherit** — let a user's quota or permission fall back to the server's default instead of a value of their own.
- **Invitation** — an emailed link that lets someone create their own account.
- **Playback session** — one viewer playing one thing on one client (Now playing).
- **Auth session** — one sign-in of a user on one device (Users → a user → Sign-in).
- **Request** — a viewer asking for media the library does not have.
- **Trigger** — a rule that starts a job: an interval, server startup, or another kind the app leaves alone.
