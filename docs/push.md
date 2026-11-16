# Push notifications

A Bloem server can push to this app when something is broken or waiting on you. This page explains how that works and what a
build needs.

## Which builds receive push

- **The official build** receives push through Bloem's push service and Firebase Cloud Messaging.
- **A build you make yourself has push off.** It works fully otherwise, including the local server-down alerts. Settings say
  that push is not set up in this build, and the app never calls Firebase.
- **Your own Firebase config.** If you supply a `google-services.json` for your own Firebase project (see
  [Firebase config](#firebase-config)), the build starts Firebase for that project. Bloem's push service sends only through
  the official project, so your build receives messages only from a push service that sends through yours.

A plain Silo server never pushes. Only a Bloem server that offers push can.

## How it works

1. The app asks each saved server for its capabilities. A server that lists `admin_push_v1` can push to this app. For any other
   server, settings say **Push isn't available on this server. Only the server-down checks apply.** and the app sends it no
   registration.
2. For a server that can push, the app registers this installation with its Firebase token and the notification kinds you have
   switched off. The server returns a device id and a display token, which the app stores encrypted.
3. A push carries only a delivery id and a mode. The push service never sees any text.
4. The app reads the text from your server with the display token, then posts the notification. If the server does not answer
   in time, the app posts a generic notice with no names or counts.

The app registers only when it has to: for the first time, when the Firebase token changes, when a switch has not reached the
server, or after a week. Signing out or removing a server retires its device registration, waiting at most five seconds.

## Notifications

- **Channels:** Servers, Requests, Libraries, Backups, Nodes and Plugins.
- **Kinds:** server down and server back (produced by the app's own checks), request waiting, scan failed, backup failed,
  node offline and plugin failed.
- **Lock screen:** every notification is private. The lock screen shows only the server's generic text, such as "New request
  waiting". **Show details on lock screen** in Settings shows the full notification.
- **Buttons:** a request notification offers Approve and Decline. They run only on an unlocked device. A request someone else
  already handled counts as done, and a failure says "Couldn't approve, open the app". Nothing destructive is offered.
- **Taps** open the exact item through the app's link scheme, behind the app lock.
- **Switches** in Settings are per server and per kind. A change is kept on the device and sent to the server.
- **Permission:** asked once, after your first sign-in to a server that can push. Never at launch. If you deny it, Settings
  explains how to allow it.

## Widget wakes

Widgets never open connections of their own. A server that lists `admin_widget_wakes_v1` sends a silent push when something a
widget shows has changed, and only to a device that has told it a widget is placed. The app tells the server which kinds of
widget are placed (now playing, requests, libraries, nodes), and again when that changes. A wake carries no content and never
shows a notification. It schedules one background refresh of the widgets. A visible push also refreshes them.

## Firebase config

Push needs a Firebase Android config for the package `com.bloemstudios.admin`. The build looks for it in this order:

1. the file named by the environment variable `BLOEM_ADMIN_GOOGLE_SERVICES_JSON`;
2. `app/google-services.json`, which is git-ignored.

`gradle/firebase-config.gradle.kts` takes only the client for that package and turns four values into string resources. The
Google services plugin is not applied. With no usable file, the build still works and push is off
(`BuildConfig.FIREBASE_CONFIGURED` is false).

To check a build, run `./gradlew :app:printFirebaseConfig`. It prints whether a config was found and its non-secret values,
never the API key.

Never commit the real file. It is git-ignored.
