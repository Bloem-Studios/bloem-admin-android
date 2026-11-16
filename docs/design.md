# Design rules

How Bloem Admin looks and reads. The code is the source of truth: colours, type, spacing and shapes live in
`app/src/main/java/com/bloemstudios/admin/ui/theme/` (`VeilPalette.kt`, `VeilTypography.kt`, `VeilTokens.kt`,
`VeilMotion.kt`). `StaffLookGuardTest` and `VeilPaletteContrastTest` fail the build when the icon, font, copy or contrast
rules below drift.

The character is steady, exact and a little warm: a control room at night, graphite surfaces and one orange light. Each
screen's one live figure or chart carries the richness, and everything around it stays quiet.

## Colour

Both a dark and a light scheme ship. The app follows the system setting by default; Settings can override it with System,
Light or Dark.

| Token | Dark | Light | Use |
|---|---|---|---|
| canvas | #141417 | #F6F6F8 | page |
| sidebar | #0F0F12 | #EEEEF2 | drawer |
| card | #1C1C20 | #FFFFFF | rows, panels, cards |
| raised | #28282E | #E9E9EE | filled fields, chips, dialogs, pressed |
| selected | #202023 | #DDDDE1 | current page, chosen row |
| hairline | #E8E8EC at 8% | #1A1A1F at 10% | dividers, card edges |
| text | #E8E8EC | #1A1A1F | primary text |
| muted | #A4A4AE | #5A5A65 | secondary text |
| outline | #787882 | #85858F | field and control outlines |
| accent | #FA6400 | #A04000 | the one accent, as text or as a fill |
| onAccent | #141417 | #FFFFFF | text on an accent fill |
| tally | #FA6400 | #E85D00 | the live dot only |
| ok | #57CB60 | #1A6A2B | |
| warning | #EAB532 | #7F4E00 | |
| danger | #F46666 | #B42626 | |
| onDanger | #141417 | #FFFFFF | |

- **Accent** is for the primary action, the current selection and the one figure that matters. Nothing else.
- **Tally dot:** the orange dot means something is live right now or needs attention. It is never decoration.
- **Charts** use this order: #5F74EE, #169E88, #BF7F22, #C2578F, #3F97CF. A single-series chart uses the accent for the
  series that matters and neutral steps for the rest.
- **Depth:** on dark it comes from surface steps and hairlines, with no shadows. On light, shadows are small, soft and
  neutral. No gradients, glows or tinted surfaces.
- **Contrast:** every text colour reaches 4.5:1 on every surface of its scheme, and outlines reach 3:1.

## Type

- **Font:** Plus Jakarta Sans (SIL OFL 1.1), bundled as one variable font. No system font may be used.
- **Weights:** 400 for reading, 500 for labels and row titles, 600 for headings.
- **Scale (size / line height):** display 45/52, 36/44, 32/40; headline 28/36, 24/32, 22/28; title large 20/28, title
  medium 16/24, title small 14/20; body 16/24, 14/20, 12/16; label 14/20, 12/16, 11/16.
- **Numbers** use tabular figures.
- **Treatments:** sentence case everywhere. No all-caps tracked labels, no italic for emphasis, no negative tracking on
  small text.

## Spacing and shape

- **Grid:** everything sits on a 4 dp grid: 4, 8, 12, 16, 24, 32. Touch targets are at least 48 dp.
- **Padding:** compact rows and panels 16, cards 16 to 20, page edges 16 on a phone and 24 to 32 on a tablet.
- **Radii:** controls (buttons, fields, chips, nav rows) 10; cards 12; large surfaces and sheets 16; inner marks 4. A nested
  radius is the outer radius minus the padding. Not everything is a pill.
- **Equal things are equal:** rows in one list share a height and gap, and cards in one row share a padding.

## Icons

- **Set:** Lucide (ISC licence), kept as stroke-only vector drawables named `res/drawable/lucide_<name>.xml`. Material
  icons are not used.
- **Size:** 18 dp in rows and menus, 20 dp in top bars, 16 dp inline, with a 1.5 stroke.
- **Colour:** an icon takes its text role's colour, usually muted, and never gets a colour of its own.
- **Names** follow meaning: `layout-dashboard` overview, `radio` live or now playing, `server` servers, `users` people,
  `building-2` organizations, `library` libraries, `inbox` requests, `history` playback history, `scroll-text` logs,
  `shield-check` audit, `settings` settings, `calendar-clock` scheduled tasks, `cpu` nodes, `bell` alerts, `log-out` sign
  out, `triangle-alert` warning, `circle-check` success, `circle-x` error.
- **No emoji** anywhere in the interface. Where a category needs colour, use a small status dot.
- **App icon:** the Bloem tulip on a graphite plate with a small `server` badge. The sources are in
  [design/app-icons](design/app-icons/).

## Layout and patterns

- **Shell:** the top bar shows the page title (title large) and the server switcher. Navigation is a drawer on phones and a
  240 to 280 dp sidebar on tablets, with no bottom bar. Groups are sentence case. The current page uses the `selected`
  surface with an accent icon.
- **Drawer foot:** three rows, in this order: Manage servers, Settings, Sign out.
- **Settings** is a normal page, opened from the drawer foot, with these sections: Appearance (a System, Light, Dark
  control), App lock, and Server alerts with the push notification switches.
- **Buttons:** one primary per screen (accent fill), secondary on `raised`. Destructive actions use danger text, and the
  confirmation names the thing being changed. Labels are verbs in sentence case.
- **Controls:** a segmented control switches views within a page, filter chips filter a list. Both use radius 10 and mark
  the selected item with the `selected` surface. A screen has at most one of each and never stacks them.
- **Lists and cards:** hairline edges. Counts and meta are muted text, not pills. Status is a small dot plus a word.
- **States:** an empty state says what goes there and offers the one action that fills it. An error says what went wrong
  and how to fix it, with a Try again button, and never apologizes or shows raw server text. Loading is a quiet progress
  indicator or skeleton.
- **Pull to refresh** is on every page that shows server data.
- **App lock:** when the biometric lock is on, the system prompt opens by itself when the lock screen appears. The lock
  screen and its Unlock button stay behind it as the fallback, and a cancelled prompt does not reopen in a loop. While
  the lock is on, the app switcher shows a cover instead of the app.
- **Motion:** one standard easing curve. 150 to 200 ms for feedback, 250 ms for entering. No loops except live indicators,
  no bounce.

## Copy

- Sentence case. No exclamation marks. No trailing period on titles, labels, buttons or captions; only paragraphs keep
  theirs. No greeting headlines.
- Standard words: Sign in, Sign out, Add server, Remove, Save changes, Try again, Cancel, Done, Search, Now playing,
  Playback history, Scheduled tasks. A provider button reads "Sign in with <provider>".
- **Silo for the server, Bloem Admin for the app:** the app works with any Silo server, so in-app copy says "Silo" where
  it names the server software. The app always calls itself Bloem Admin (lock screen, unlock prompt, permission text,
  error messages, widget descriptions) and never "Silo Admin". Features that only a Bloem server offers appear only when the server is one, with no error and no empty
  page on a plain Silo server.
- A change states what it compares to, for example "+12% vs last week".

## Widgets

- Widgets never open a connection of their own; they draw what the app last fetched.
- **Lock screen:** a widget on the lock screen shows counts and statuses only (no person, title, library or node name)
  unless "Show details on lock screen" is on. That setting is per device and off by default, and it also decides what the
  Quick Settings tiles and the progress notification show on a locked device. See `WidgetPrivacy`.
- **Unlocked phone:** a home-screen widget shows full detail and its buttons work. The phone's own unlock is the gate.
- **Header:** the app's mark and the name Bloem Admin, drawn in `muted` and never in the accent. Small widgets show the mark
  alone.
