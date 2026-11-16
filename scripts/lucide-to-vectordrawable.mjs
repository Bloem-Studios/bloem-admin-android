#!/usr/bin/env node
// Turns Lucide icons (ISC licence, https://lucide.dev) into the VectorDrawables under app/src/main/res/drawable/.
//
//   node scripts/lucide-to-vectordrawable.mjs <lucide-react folder> <output folder> <icon name>...
//   node scripts/lucide-to-vectordrawable.mjs ~/some/node_modules/lucide-react app/src/main/res/drawable layout-dashboard radio
//
// The app ships only the icons it uses, as lucide_<name with underscores>.xml. Each one keeps Lucide's own 24 x 24 grid,
// round caps and joins and its 2-unit stroke, which draws as a 1.5dp line at the 18dp size rows and menus use. Black strokes:
// the app always tints an icon with its text role's color. Names that point the way (chevrons, arrows) mirror in right-to-left.
// The source is read as data (no code from the package is run). The icons in the app were made from lucide-react 0.577.0.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const [source, outDir, ...names] = process.argv.slice(2);
if (!source || !outDir || names.length === 0) {
  console.error('usage: lucide-to-vectordrawable.mjs <lucide-react folder> <output folder> <icon name>...');
  process.exit(2);
}

const MIRRORED = new Set(['chevron-left', 'chevron-right']);
const num = (v) => String(Math.round(parseFloat(v) * 1000) / 1000);

function attributes(text) {
  const out = {};
  for (const m of text.matchAll(/(\w+):\s*"([^"]*)"/g)) out[m[1]] = m[2];
  return out;
}

function pathFor(tag, a) {
  switch (tag) {
    case 'path':
      return a.d;
    case 'circle': {
      const cx = parseFloat(a.cx), cy = parseFloat(a.cy), r = parseFloat(a.r);
      return `M${num(cx - r)} ${num(cy)}a${num(r)} ${num(r)} 0 1 0 ${num(2 * r)} 0a${num(r)} ${num(r)} 0 1 0 ${num(-2 * r)} 0`;
    }
    case 'ellipse': {
      const cx = parseFloat(a.cx), cy = parseFloat(a.cy), rx = parseFloat(a.rx), ry = parseFloat(a.ry);
      return `M${num(cx - rx)} ${num(cy)}a${num(rx)} ${num(ry)} 0 1 0 ${num(2 * rx)} 0a${num(rx)} ${num(ry)} 0 1 0 ${num(-2 * rx)} 0`;
    }
    case 'rect': {
      const x = parseFloat(a.x ?? '0'), y = parseFloat(a.y ?? '0'), w = parseFloat(a.width), h = parseFloat(a.height);
      const rx = Math.min(parseFloat(a.rx ?? a.ry ?? '0'), w / 2), ry = Math.min(parseFloat(a.ry ?? a.rx ?? '0'), h / 2);
      if (!rx && !ry) return `M${num(x)} ${num(y)}h${num(w)}v${num(h)}h${num(-w)}z`;
      return `M${num(x + rx)} ${num(y)}h${num(w - 2 * rx)}a${num(rx)} ${num(ry)} 0 0 1 ${num(rx)} ${num(ry)}v${num(h - 2 * ry)}` +
        `a${num(rx)} ${num(ry)} 0 0 1 ${num(-rx)} ${num(ry)}h${num(-(w - 2 * rx))}a${num(rx)} ${num(ry)} 0 0 1 ${num(-rx)} ${num(-ry)}` +
        `v${num(-(h - 2 * ry))}a${num(rx)} ${num(ry)} 0 0 1 ${num(rx)} ${num(-ry)}z`;
    }
    case 'line':
      return `M${num(a.x1)} ${num(a.y1)}L${num(a.x2)} ${num(a.y2)}`;
    case 'polyline':
    case 'polygon': {
      const pts = a.points.trim().split(/[\s,]+/).map(Number);
      let d = `M${pts[0]} ${pts[1]}`;
      for (let i = 2; i < pts.length; i += 2) d += `L${pts[i]} ${pts[i + 1]}`;
      return tag === 'polygon' ? d + 'z' : d;
    }
    default:
      throw new Error(`unsupported element <${tag}>`);
  }
}

mkdirSync(outDir, { recursive: true });
for (const name of names) {
  const file = join(source, 'dist', 'esm', 'icons', `${name}.js`);
  const js = readFileSync(file, 'utf8');
  const body = js.match(/const __iconNode = \[([\s\S]*?)\];\s*const \w+ = createLucideIcon\(/);
  if (!body) throw new Error(`${name}: no icon nodes found in ${file}`);
  const paths = [...body[1].matchAll(/\[\s*"(\w+)",\s*\{([^}]*)\}\s*\]/g)].map((m) => pathFor(m[1], attributes(m[2])));
  if (paths.length === 0) throw new Error(`${name}: no shapes`);
  const mirror = MIRRORED.has(name) ? '\n    android:autoMirrored="true"' : '';
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<!-- Lucide "${name}" (ISC licence, lucide.dev). Made by scripts/lucide-to-vectordrawable.mjs. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"${mirror}>
${paths
  .map(
    (d) => `    <path
        android:pathData="${d}"
        android:strokeColor="#FF000000"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />`,
  )
  .join('\n')}
</vector>
`;
  writeFileSync(join(outDir, `lucide_${name.replace(/-/g, '_')}.xml`), xml);
  console.log(`lucide_${name.replace(/-/g, '_')}.xml (${paths.length} shape${paths.length > 1 ? 's' : ''})`);
}
