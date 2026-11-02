// Trimmed from BlockShips/test-bot/lib/helpers.js — only what defcorelib-bot.js uses.
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')

// =============================================================================
// Configuration
// =============================================================================

// MC_VERSION (set by the Makefile) wins; .mc-version is for running the bot by hand.
function getVersion() {
  if (process.env.MC_VERSION) return process.env.MC_VERSION
  try {
    return fs.readFileSync(path.join(__dirname, '..', '.mc-version'), 'utf8').trim()
  } catch (e) {
    return '1.21.11'
  }
}

function createBot() {
  return mineflayer.createBot({
    host: process.env.MC_HOST || 'localhost',
    port: parseInt(process.env.MC_PORT || '25578'),
    username: process.env.MC_USERNAME || 'TestBot',
    version: getVersion(),
    auth: 'offline',
    hideErrors: false
  })
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// =============================================================================
// Test Tracking
// =============================================================================

function createTestTracker(prefix, resultsFile) {
  const state = { passed: 0, failed: 0, results: [] }
  const log = (msg) => console.log(`[${prefix}] ${msg}`)
  if (resultsFile) fs.writeFileSync(resultsFile, '')

  const pass = (name) => {
    log(`PASS: ${name}`)
    state.passed++
    state.results.push({ name, passed: true })
    if (resultsFile) fs.appendFileSync(resultsFile, `PASS: ${name}\n`)
  }

  const fail = (name, reason) => {
    log(`FAIL: ${name}: ${reason}`)
    state.failed++
    state.results.push({ name, passed: false, reason })
    if (resultsFile) fs.appendFileSync(resultsFile, `FAIL: ${name} - ${reason}\n`)
  }

  const printSummary = () => {
    log('='.repeat(60))
    for (const r of state.results) log(`  ${r.passed ? 'PASS' : 'FAIL'}: ${r.name}${r.reason ? ` - ${r.reason}` : ''}`)
    log(`Total: ${state.passed} passed, ${state.failed} failed`)
    log('='.repeat(60))
    if (resultsFile) fs.appendFileSync(resultsFile, `\nTotal: ${state.passed} passed, ${state.failed} failed\n`)
  }

  return { state, log, pass, fail, printSummary }
}

// =============================================================================
// Events + chat buffer
// =============================================================================
//
// message.toString() strips colour codes, so assertion patterns must not contain section signs.
// Action-bar text is buffered too: the plugin sends readouts (wrench, glue) there, Paper sends those
// as the dedicated action_bar packet, and mineflayer surfaces only the system_chat overlay flavour.

const CHAT_BUFFER_LIMIT = 200

function recordChat(bot, text) {
  if (!bot._chatLog) bot._chatLog = []
  bot._chatLog.push(text)
  if (bot._chatLog.length > CHAT_BUFFER_LIMIT) bot._chatLog.shift()
}

function setupBotEvents(bot, log, onMain) {
  bot.once('spawn', () => {
    log('Bot spawned in world')
    setTimeout(onMain, 2000)
  })
  bot.on('error', (err) => {
    console.error('Bot error:', err)
    process.exit(1)
  })
  bot.on('kicked', (reason) => {
    log(`Bot was kicked: ${JSON.stringify(reason)}`)
    process.exit(1)
  })
  bot.on('end', () => log('Bot disconnected'))
  bot.on('message', (message) => {
    const text = message.toString()
    if (text.trim()) log(`[CHAT] ${text}`)
    recordChat(bot, text)
  })
  bot._client.on('action_bar', (packet) => {
    const text = componentText(bot, packet.text)
    if (text.trim()) log(`[ACTIONBAR] ${text}`)
    recordChat(bot, text)
  })
}

/** Plain text of a network text component (NBT on 1.20.3+, JSON before). */
function componentText(bot, component) {
  try {
    return require('prismarine-chat')(bot.registry).fromNotch(component).toString()
  } catch (e) {
    return JSON.stringify(component)
  }
}

/** Drop everything received so far, so a later wait can't match a stale line. */
function clearChat(bot) {
  bot._chatLog = []
}

/** First buffered-or-later chat/action-bar line matching `pattern`, or null on timeout. */
async function waitForChat(bot, pattern, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const hit = (bot._chatLog || []).find((line) => pattern.test(line))
    if (hit) return hit
    await sleep(100)
  }
  return null
}

// =============================================================================
// Windows
// =============================================================================

function windowTitle(bot, window) {
  return window && window.title != null ? componentText(bot, window.title) : ''
}

/**
 * Right-click `block` and resolve with the open window (null if none opened within `timeoutMs`).
 * Returns bot.currentWindow after a settle delay rather than the windowOpen argument: the engine
 * retitles its view right after opening ("Engine - Ns fuel"), which the server sends as a SECOND
 * open_window, and mineflayer replaces currentWindow — the first Window object then goes stale.
 */
async function openWindowAt(bot, block, timeoutMs = 3000) {
  const opened = new Promise((resolve) => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', onOpen); resolve(false) }, timeoutMs)
    function onOpen() { clearTimeout(timer); resolve(true) }
    bot.once('windowOpen', onOpen)
  })
  await bot.activateBlock(block)
  if (!await opened) return null
  await sleep(500)
  return bot.currentWindow
}

async function closeWindow(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(400)
}

/** Total count of items named `name` in the bot's own inventory. */
function countItem(bot, name) {
  return bot.inventory.items().filter(it => it.name === name).reduce((n, it) => n + it.count, 0)
}

module.exports = {
  createBot,
  sleep,
  createTestTracker,
  setupBotEvents,
  clearChat,
  waitForChat,
  windowTitle,
  openWindowAt,
  closeWindow,
  countItem
}
