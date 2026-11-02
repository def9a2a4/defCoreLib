// Usage: node add-op.js <ops.json> <username>
// Ops <username> by its OFFLINE-mode UUID, merging into an existing ops.json rather than rewriting it:
// test-server/ is shared with the docs server, whose own ops must survive a bot run. (A console
// `op <name>` is no substitute: it can resolve the name to a real Mojang account's UUID.)
const fs = require('fs')
const crypto = require('crypto')

const [file, name] = process.argv.slice(2)
if (!file || !name) { console.error('usage: node add-op.js <ops.json> <username>'); process.exit(2) }

// Offline UUID: a version-3 UUID of MD5("OfflinePlayer:<name>").
const d = crypto.createHash('md5').update(`OfflinePlayer:${name}`).digest()
d[6] = (d[6] & 0x0f) | 0x30
d[8] = (d[8] & 0x3f) | 0x80
const h = d.toString('hex')
const uuid = `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`

let ops = []
try { ops = JSON.parse(fs.readFileSync(file, 'utf8')) } catch (e) { /* absent or empty: start fresh */ }
ops = ops.filter(o => o.uuid !== uuid)
ops.push({ uuid, name, level: 4, bypassesPlayerLimit: false })
fs.writeFileSync(file, JSON.stringify(ops, null, 2) + '\n')
