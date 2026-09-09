/**
 * The interaction smoke test.
 *
 * The unit suites cover every rule about what a page *becomes*; nothing in them can tell you that a
 * drag actually starts, that the grip is the thing that starts it, or that Alt+ArrowUp reaches the
 * handler. Those are properties of the browser, so this asks a browser.
 *
 * It drives the demo workspace, which needs no token and touches no network.
 *
 *   npm run dev          # in another terminal
 *   npm run test:ui
 *
 * Uses the system Chromium if Playwright's own download is absent, which is the usual case on a
 * machine that already has a browser.
 */
import { existsSync } from 'node:fs'
import { chromium } from 'playwright'

const URL = process.env.YANTRA_URL ?? 'http://localhost:5173'
const SYSTEM = ['/usr/bin/chromium', '/usr/bin/google-chrome', '/usr/bin/chromium-browser']
  .find((p) => existsSync(p))

const fail = (msg) => { console.error('✗ ' + msg); process.exitCode = 1 }
const pass = (msg) => console.log('✓ ' + msg)

const browser = await chromium.launch(SYSTEM ? { executablePath: SYSTEM } : {})
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))

await page.goto(`${URL}/?demo#work`, { waitUntil: 'networkidle' })
const titles = () => page.locator('.block-row .title').allInnerTexts()
const rows = page.locator('.block-row')
const grips = page.locator('.block-row .grip')

const start = await titles()
if (start.length < 3) fail(`expected several rows, saw ${start.length}`)

// A drag from the last row to the first.
await grips.nth(start.length - 1).dragTo(rows.nth(0))
await page.waitForTimeout(300)
const dragged = await titles()
dragged[0] === start[start.length - 1]
  ? pass('drag moves a row to the drop target')
  : fail(`drag did nothing: ${JSON.stringify(dragged)}`)

// The same move from the keyboard.
await grips.nth(1).focus()
const second = (await titles())[1]
await page.keyboard.press('Alt+ArrowUp')
await page.waitForTimeout(300)
;(await titles())[0] === second
  ? pass('Alt+ArrowUp moves a row up')
  : fail('Alt+ArrowUp did nothing')

// Tab indents the focused row.
await grips.nth(1).focus()
const before = await rows.nth(1).evaluate((e) => e.style.paddingLeft)
await page.keyboard.press('Tab')
await page.waitForTimeout(300)
const after = await rows.nth(1).evaluate((e) => e.style.paddingLeft)
after !== before ? pass('Tab indents the focused row') : fail('Tab did not indent')

// The grip is the drag source, not the row — a draggable row makes its text unselectable.
const rowDraggable = await rows.nth(0).evaluate((e) => e.draggable)
rowDraggable ? fail('the row is draggable; the title cannot be selected or edited')
             : pass('only the grip is draggable')

errors.length === 0 ? pass('no page errors') : fail(`page errors: ${errors.join('; ')}`)
await browser.close()
