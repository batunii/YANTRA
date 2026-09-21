/**
 * Differential test: does this client write the same bytes the Android app writes?
 *
 * Point it at any workspace clone — a checkout of a tasks repo, or a directory pulled off a phone
 * with `adb ... run-as <pkg> tar -cf - files/workspaces` — and it decodes and re-encodes every page
 * in it, expecting the result to be identical to what the Kotlin writer produced.
 *
 * The unit suite pins behaviour this port was *written* to have; this pins the behaviour it must
 * actually have. Passing the first and failing the second means the port is self-consistently
 * wrong, and would corrupt a real repository on the first write.
 *
 *   npx vite-node scripts/roundtrip.ts ~/some/workspace/clone
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { decode, encode } from '../src/format/pageCodec'

const root = process.argv[2]!
const files: string[] = []
const walk = (d: string) => {
  for (const e of readdirSync(d)) {
    const p = join(d, e)
    if (statSync(p).isDirectory()) walk(p)
    else if (p.endsWith('.md')) files.push(p)
  }
}
walk(root)

let ok = 0
const bad: string[] = []
for (const f of files) {
  const src = readFileSync(f, 'utf8')
  const out = encode(decode(src))
  if (out === src) ok++
  else {
    bad.push(f)
    console.log('--- MISMATCH', f)
    const a = src.split('\n'), b = out.split('\n')
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
      if (a[i] !== b[i]) console.log(`  line ${i + 1}\n    kotlin: ${JSON.stringify(a[i])}\n    ts:     ${JSON.stringify(b[i])}`)
    }
  }
}
console.log(`\n${ok}/${files.length} real pages round-trip byte-for-byte`)
if (bad.length) process.exit(1)
