// In-game bot tests for DefCoreLib: drive real player interactions (GUIs, item use, place/break,
// glue) that the headless showcase tests can't. Each scenario asks the server for a fixed fixture
// (`/defcorelib bottest fixture <name>`, test-only — needs -Ddefcorelib.botTest=true), acts on it as
// a player, then checks what the bot can see plus `/defcorelib bottest assert <name>` for server state.
//
//   node defcorelib-bot.js                     run every scenario
//   node defcorelib-bot.js --only=engine,glue  run the scenarios whose key contains any of these
//
// Env: MC_HOST, MC_PORT (25578), MC_USERNAME (TestBot), MC_VERSION. Exit 0 = all passed.
const path = require('path')
const { Vec3 } = require('vec3')
const {
  createBot, sleep, createTestTracker, setupBotEvents, clearChat, waitForChat,
  windowTitle, openWindowAt, closeWindow, countItem
} = require('./lib/helpers')

const OVERALL_TIMEOUT_MS = 5 * 60 * 1000
const RESULTS_FILE = path.join(__dirname, 'test-results.txt')

const tracker = createTestTracker('BOT', RESULTS_FILE)
const { log, pass, fail } = tracker
let bot = null

// =============================================================================
// Server round-trips
// =============================================================================

/** Teleport next to fixture `name`'s origin (offset `stand`), build it, return the origin as a Vec3. */
async function setupFixture(name, stand) {
  bot.chat('/clear @s')
  bot.chat('/gamemode creative')
  bot.setQuickBarSlot(0)
  clearChat(bot)
  bot.chat(`/defcorelib bottest fixture ${name}`)
  const line = await waitForChat(bot, new RegExp(`^FIXTURE (READY|FAIL) ${name}\\b`), 8000)
  if (!line) throw new Error(`fixture ${name}: no reply (is the server running with -Ddefcorelib.botTest=true?)`)
  if (line.startsWith('FIXTURE FAIL')) throw new Error(line)
  const [x, y, z] = line.split(' ').slice(3).map(Number)
  const origin = new Vec3(x, y, z)
  const s = origin.plus(stand)
  // Integer /tp coordinates are centre-corrected to .5, so the bot stands mid-cell.
  bot.chat(`/tp @s ${s.x} ${s.y} ${s.z}`)
  await sleep(1500)   // chunk + block updates reach the client
  return origin
}

/** Run `/defcorelib bottest assert <name> [arg]`; null on pass, else the failure text. */
async function serverAssert(name, arg = '') {
  clearChat(bot)
  bot.chat(`/defcorelib bottest assert ${name}${arg === '' ? '' : ' ' + arg}`)
  const line = await waitForChat(bot, new RegExp(`^ASSERT (PASS|FAIL) ${name}(:|$)`), 5000)
  if (!line) return `no reply to bottest assert ${name}`
  return line.startsWith('ASSERT PASS') ? null : line
}

/** Poll until the client sees a block at `pos` matching `pred` (block updates lag the command). */
async function waitForBlock(pos, pred, timeoutMs = 4000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const b = bot.blockAt(pos)
    if (b && pred(b)) return b
    await sleep(100)
  }
  return null
}

const isHead = (b) => b.name.includes('head')

/** Give one DefCoreLib item (inventory cleared first) and return it once it lands, or null. */
async function giveCoreLibItem(id, itemName) {
  bot.chat('/clear @s')
  await sleep(300)
  bot.chat(`/defcorelib give ${id}`)
  for (let i = 0; i < 15; i++) {
    await sleep(200)
    const item = bot.inventory.items().find(it => it.name === itemName)
    if (item) return item
  }
  return null
}

async function giveVanilla(name, count) {
  bot.chat(`/give @s minecraft:${name} ${count}`)
  for (let i = 0; i < 15 && countItem(bot, name) < count; i++) await sleep(200)
  return countItem(bot, name)
}

// =============================================================================
// Scenarios
// =============================================================================

// a. Machine GUI: right-click the engine, its fuel window opens, shift-click coal in, close; the
//    engine must start running with every piece of coal accounted for (no loss, no dupe).
async function testEngineGui() {
  const NAME = 'Engine fuel GUI'
  const COAL = 4
  const o = await setupFixture('engine', new Vec3(2, 0, 0))
  if (await giveVanilla('coal', COAL) !== COAL) return fail(NAME, 'no coal from /give')
  bot.setQuickBarSlot(8)   // empty hand: the click must reach the engine, not use the coal
  const engine = await waitForBlock(o, isHead)
  if (!engine) return fail(NAME, `no engine head at ${o}`)

  const win = await openWindowAt(bot, engine)
  if (!win) return fail(NAME, 'right-clicking the engine opened no window')
  const title = windowTitle(bot, win)
  if (!/engine/i.test(title) || !/hopper/.test(win.type)) {
    await closeWindow(bot)
    return fail(NAME, `unexpected window: type=${win.type} title=${JSON.stringify(title)}`)
  }
  // Shift-click (mode 1) every coal stack from the player half of the window into the engine.
  for (let i = win.inventoryStart; i < win.inventoryEnd; i++) {
    const it = win.slots[i]
    if (it && it.name === 'coal') { await bot.clickWindow(i, 0, 1); await sleep(150) }
  }
  await sleep(300)
  await closeWindow(bot)
  const left = countItem(bot, 'coal')
  if (left !== 0) return fail(NAME, `${left} coal still in the bot's inventory after shift-clicking`)

  await sleep(2500)   // the engine ticks every 20 game ticks; give it two to auto-start
  const err = await serverAssert('engine_fuelled', COAL)
  if (err) return fail(NAME, err)
  pass(NAME)
}

// b. Use item on a custom block: sneak + wrench on the shaft prints the network readout (action
//    bar). The engine → shaft → fan fixture is one 3-block network.
async function testWrenchReadout() {
  const NAME = 'Wrench network readout'
  const o = await setupFixture('wrench', new Vec3(2, 0, 0))
  const wrench = await giveCoreLibItem('mech:wrench', 'golden_axe')
  if (!wrench) return fail(NAME, 'no wrench from /defcorelib give mech:wrench')
  await bot.equip(wrench, 'hand')
  const shaft = await waitForBlock(o.offset(0, 1, 0), isHead)
  if (!shaft) return fail(NAME, 'no shaft head above the engine')

  clearChat(bot)
  // Sneak: a plain wrench click on a shaft toggles its encasing instead of reading out.
  bot.setControlState('sneak', true)
  await sleep(250)
  try {
    await bot.activateBlock(shaft)
  } finally {
    await sleep(250)
    bot.setControlState('sneak', false)
  }
  const line = await waitForChat(bot, /\d+\/\d+ Power, \d+ blocks|Not in rotation network/, 4000)
  if (!line) return fail(NAME, 'no readout after sneak+wrench on the shaft')
  if (!/ Power, 3 blocks/.test(line)) return fail(NAME, `unexpected readout: ${line}`)
  pass(NAME)
}

// c. Place + break: place a shaft by hand, check the block + its display exist, break it in survival
//    and check exactly one shaft item comes back (and nothing is left on the ground).
async function testPlaceBreak() {
  const NAME = 'Place and break a shaft'
  // Stand one cell east: close enough that the drop at the cell centre is inside pickup range.
  const o = await setupFixture('place', new Vec3(1, 0, 0))
  const item = await giveCoreLibItem('mech:shaft', 'player_head')
  if (!item) return fail(NAME, 'no shaft from /defcorelib give mech:shaft')
  await bot.equip(item, 'hand')
  const floor = await waitForBlock(o.offset(0, -1, 0), b => b.name === 'stone')
  if (!floor) return fail(NAME, 'no stone pad under the fixture')
  try {
    await bot.placeBlock(floor, new Vec3(0, 1, 0))
  } catch (e) {
    return fail(NAME, `placing the shaft failed: ${e.message}`)
  }
  await sleep(800)
  let err = await serverAssert('placed')
  if (err) return fail(NAME, err)
  // Placing in creative doesn't use up the item: clear it, so the only head held afterwards is the drop.
  bot.chat('/clear @s player_head')
  await sleep(400)

  bot.chat('/gamemode survival')
  await sleep(600)
  try {
    const head = await waitForBlock(o, isHead)
    if (!head) return fail(NAME, 'placed shaft not visible to the client')
    try {
      await bot.dig(head)
    } catch (e) {
      return fail(NAME, `digging the shaft failed: ${e.message}`)
    }
    await sleep(1500)
    // Drops scatter a little; step onto the (now empty) cell so a drifted drop is still collected.
    if (countItem(bot, 'player_head') === 0) {
      bot.chat(`/tp @s ${o.x} ${o.y} ${o.z}`)
      await sleep(1000)
    }
    const heads = countItem(bot, 'player_head')
    if (heads !== 1) return fail(NAME, `bot holds ${heads} heads after breaking the shaft (want 1)`)
    err = await serverAssert('broken')
    if (err) return fail(NAME, err)
    pass(NAME)
  } finally {
    bot.chat('/gamemode creative')
    await sleep(300)
  }
}

// d. Filter pipe menu: put an item in a filter slot, close, reopen (it must still be there and gone
//    from the bot), shift-click it back out, reopen (filter empty, bot holds exactly one).
async function testFilterMenu() {
  const NAME = 'Filter pipe menu round trip'
  const o = await setupFixture('filter', new Vec3(2, 0, 0))
  if (await giveVanilla('cobblestone', 1) !== 1) return fail(NAME, 'no cobblestone from /give')
  bot.setQuickBarSlot(8)
  const pipe = await waitForBlock(o, isHead)
  if (!pipe) return fail(NAME, `no filter pipe head at ${o}`)

  let win = await openWindowAt(bot, pipe)
  if (!win) return fail(NAME, 'right-clicking the filter pipe opened no window')
  const title = windowTitle(bot, win)
  if (!/item filter/i.test(title)) {
    await closeWindow(bot)
    return fail(NAME, `unexpected window title ${JSON.stringify(title)}`)
  }
  let src = -1
  for (let i = win.inventoryStart; i < win.inventoryEnd; i++) {
    if (win.slots[i] && win.slots[i].name === 'cobblestone') { src = i; break }
  }
  if (src < 0) { await closeWindow(bot); return fail(NAME, 'cobblestone not in the window\'s player half') }
  await bot.clickWindow(src, 0, 0)   // pick the stack up onto the cursor
  await sleep(200)
  await bot.clickWindow(0, 0, 0)     // put it down in filter slot 0
  await sleep(300)
  await closeWindow(bot)
  if (countItem(bot, 'cobblestone') !== 0) return fail(NAME, 'cobblestone still in the bot\'s inventory after filing it')

  win = await openWindowAt(bot, pipe)
  if (!win) return fail(NAME, 'filter pipe did not reopen')
  const slot0 = win.slots[0]
  if (!slot0 || slot0.name !== 'cobblestone' || slot0.count !== 1) {
    await closeWindow(bot)
    return fail(NAME, `filter slot 0 after reopen: ${slot0 ? `${slot0.count}x ${slot0.name}` : 'empty'}`)
  }
  await bot.clickWindow(0, 0, 1)     // shift-click it back into the bot's inventory
  await sleep(300)
  await closeWindow(bot)
  const held = countItem(bot, 'cobblestone')
  if (held !== 1) return fail(NAME, `bot holds ${held} cobblestone after taking it out (want 1)`)

  win = await openWindowAt(bot, pipe)
  if (!win) return fail(NAME, 'filter pipe did not reopen the second time')
  const after = win.slots[0]
  await closeWindow(bot)
  if (after) return fail(NAME, `filter slot 0 still holds ${after.count}x ${after.name} after taking it out`)
  pass(NAME)
}

// e. Glue brush: open a session on a rotator, left-click the plank behind it, close; the anchor's
//    glue must now hold that cell, and the plank must not have been broken by the click.
async function testGlueBrush() {
  const NAME = 'Glue brush marks a block'
  const o = await setupFixture('glue', new Vec3(2, 0, 0))
  const brush = await giveCoreLibItem('glue', 'brush')
  if (!brush) return fail(NAME, 'no glue brush from /defcorelib give glue')
  await bot.equip(brush, 'hand')
  const rotator = await waitForBlock(o, isHead)
  const plank = await waitForBlock(o.offset(0, 0, 1), b => b.name === 'oak_planks')
  if (!rotator || !plank) return fail(NAME, 'rotator or plank missing from the fixture')

  clearChat(bot)
  await bot.activateBlock(rotator)
  if (!await waitForChat(bot, /Glue: editing/, 3000)) return fail(NAME, 'right-clicking the rotator opened no glue session')
  clearChat(bot)
  // A left-click is a dig start. In creative mineflayer "finishes" at once and blanks the block
  // locally; the server cancels the break (glue session) and resends the plank.
  try { await bot.dig(plank, true) } catch (e) { /* client-side bookkeeping only */ }
  await sleep(600)
  await bot.activateBlock(rotator)   // close the session
  await waitForChat(bot, /Glue: session closed/, 3000)

  const err = await serverAssert('glued')
  if (err) return fail(NAME, err)
  pass(NAME)
}

// f. Cart rider logout: ride a Railbound dispenser cart (shears in hand = mount, not GUI), quit while
//    riding, rejoin as the same player. The quit handler must have stepped the bot off first: the cart
//    stays in the world, still tracked, with its live contents; the bot comes back riding nothing.
//    (Without it vanilla saves the cart into the player file and respawns it untracked with stale contents.)
async function testCartLogout() {
  const NAME = 'Cart rider logout keeps contents'
  const ITEMS = 20   // BotTestCommands.CART_ITEMS
  await setupFixture('cart', new Vec3(2, 0, 0))
  if (await giveVanilla('shears', 1) !== 1) return fail(NAME, 'no shears from /give')
  const shears = bot.inventory.items().find(it => it.name === 'shears')
  await bot.equip(shears, 'hand')
  const cart = bot.nearestEntity(e => e.name === 'minecart')
  if (!cart) return fail(NAME, 'client sees no minecart near the fixture')

  bot.mount(cart)
  for (let i = 0; i < 20 && !bot.vehicle; i++) await sleep(100)
  if (!bot.vehicle) return fail(NAME, 'right-clicking the cart with shears did not mount it')
  let err = await serverAssert('cart_ridden')
  if (err) return fail(NAME, err)

  await reconnect()
  await sleep(1000)   // entities around the rejoin point reach the client
  if (bot.vehicle) return fail(NAME, `bot rejoined riding entity ${bot.vehicle.id}`)
  err = await serverAssert('cart_kept', ITEMS)
  if (err) return fail(NAME, err)
  pass(NAME)
}

const TESTS = {
  engine_gui: { name: 'Engine fuel GUI', fn: testEngineGui },
  wrench_readout: { name: 'Wrench network readout', fn: testWrenchReadout },
  place_break: { name: 'Place and break a shaft', fn: testPlaceBreak },
  filter_menu: { name: 'Filter pipe menu round trip', fn: testFilterMenu },
  glue_brush: { name: 'Glue brush marks a block', fn: testGlueBrush },
  cart_logout: { name: 'Cart rider logout keeps contents', fn: testCartLogout }
}

// =============================================================================
// Connection
// =============================================================================

/** Connect a bot (same MC_USERNAME every time) and resolve once it has spawned and settled. */
function connect() {
  const b = createBot()
  bot = b
  // A drop mid-run must fail fast — never let an emptied event loop exit 0 with scenarios unrun.
  // A deliberate quit (reconnect) marks its bot first; a replaced bot's late 'end' is ignored too.
  b.on('end', () => {
    if (!finished && !b._expectedQuit && b === bot) { log('Disconnected before the run finished'); process.exit(1) }
  })
  return new Promise((resolve) => setupBotEvents(b, log, resolve))
}

/** Quit and log back in as the same player; `bot` is the new connection afterwards. */
async function reconnect() {
  const old = bot
  old._expectedQuit = true
  const ended = new Promise((resolve) => old.once('end', resolve))
  old.quit()
  await Promise.race([ended, sleep(5000)])
  // Bukkit's connection-throttle (4 s default) would refuse an immediate rejoin from the same address.
  await sleep(4500)
  await connect()
  log(`Reconnected as ${bot.username}`)
}

// =============================================================================
// Main
// =============================================================================

async function main() {
  log(`Connected as ${bot.username} (${bot.version})`)
  // Preflight: the bot must be op and the server must expose the test-only subcommand.
  clearChat(bot)
  bot.chat('/defcorelib bottest')
  const pre = await waitForChat(bot, /bottest|permission|Unknown subcommand/, 8000)
  if (!pre || !/Usage \(player\): \/defcorelib bottest/.test(pre)) {
    log(`Preflight failed: ${pre || 'no reply'} — need op and -Ddefcorelib.botTest=true`)
    process.exit(1)
  }

  const only = process.argv.find(a => a.startsWith('--only='))
  const filter = only ? only.slice('--only='.length).split(',') : null
  for (const [key, test] of Object.entries(TESTS)) {
    if (filter && !filter.some(f => key.includes(f))) continue
    log(`=== ${test.name} ===`)
    try {
      await test.fn()
    } catch (e) {
      fail(test.name, e.message)
      log(e.stack)
    }
    if (bot.currentWindow) await closeWindow(bot)
    await sleep(1000)
  }

  tracker.printSummary()
  finished = true
  bot.quit()
  await sleep(500)
  process.exit(tracker.state.failed === 0 && tracker.state.passed > 0 ? 0 : 1)
}

let finished = false
connect().then(main).catch((e) => { log(e.stack); process.exit(1) })

setTimeout(() => {
  log(`Overall timeout (${OVERALL_TIMEOUT_MS / 1000}s) reached`)
  tracker.printSummary()
  process.exit(1)
}, OVERALL_TIMEOUT_MS)
