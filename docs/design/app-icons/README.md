# App icon sources

The Bloem Admin launcher icon is the Bloem Studios tulip on a dark graphite plate (`#141417`, see `background.txt`),
with a small Lucide `server` badge (ISC licence).

| File | What it is |
| --- | --- |
| `admin-icon.svg` | Full icon: plate, tulip and badge, 108×108 units |
| `admin-foreground.svg` | Tulip and badge on transparent (the adaptive-icon foreground) |
| `admin-mono.svg` | White, one-colour layer used for themed icons, with the badge knocked out of the tulip |
| `bloem-tulip-logo.svg`, `bloem-tulip-logo-mono.svg` | The tulip alone |
| `reference-bloem-black.png`, `tulip-trace.json`, `tulip-overlay-comparison.png` | The logo the tulip is traced from, the trace, and an overlay of the redraw on the logo |

The canvas is the 108×108 adaptive-icon canvas. Launcher masks show the central 72×72 units, and everything important sits
inside the 66-unit safe circle. The app's own launcher drawables (`ic_launcher_foreground.xml`,
`ic_launcher_monochrome.xml`) are generated from these masters, so change the masters and regenerate instead of editing
the drawables.

The Bloem name and tulip logo are trademarks of Bloem Studios and are not licensed under the AGPL. See
[THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md).

## Rebuilding

Run these from this folder:

- `python3 generate.py` writes the SVGs and the Android VectorDrawables (to `android/admin/`; pass `--out DIR` to write
  elsewhere) from `tulip-trace.json`.
- `python3 trace_tulip.py` re-traces the reference into `tulip-trace.json` (needs numpy, Pillow, scipy and scikit-image).
- `python3 fit_layout.py` picks the tulip scale and badge position (needs headless Chromium, numpy and Pillow).
- `python3 compare_tulip.py` rewrites `tulip-overlay-comparison.png` (needs headless Chromium and Pillow).
