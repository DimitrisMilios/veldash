// Replays a fix list into the Android emulator over ONE console connection.
// `adb emu geo fix` opens a new console session per call and the emulator refuses new
// sessions after a few dozen, which silently freezes a replay. This keeps a single socket.
//
// Usage: node tools/emu-drive.js <drive.txt> [consolePort=5554] [serial=emulator-5554] [tag=drive] [knots]
//   drive.txt: one "lon lat" per line, one fix per second.
//   knots:     optional reported velocity; omit to exercise the app's distance/time fallback.
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const file = process.argv[2] || "drive.txt";
const port = parseInt(process.argv[3] || "5554", 10);
const serial = process.argv[4] || `emulator-${port}`;
const tag = process.argv[5] || "drive";
const knots = process.argv[6];

const fixes = fs.readFileSync(file, "utf8").trim().split(/\r?\n/).map(l => l.trim().split(/\s+/));
const token = fs.readFileSync(path.join(os.homedir(), ".emulator_console_auth_token"), "utf8").trim();
const shotsDir = path.join(path.dirname(path.resolve(file)), "shots");
fs.mkdirSync(shotsDir, { recursive: true });

function adb(args, out) {
  const buf = execFileSync("adb", ["-s", serial, ...args], { maxBuffer: 64 * 1024 * 1024 });
  if (out) fs.writeFileSync(out, buf);
  return buf.toString();
}

const sock = net.createConnection({ host: "127.0.0.1", port }, () => {});
let buffer = "";
let stage = 0; // 0 = wait banner, 1 = wait auth OK, 2 = streaming
let i = 0;

function send(cmd) { sock.write(cmd + "\n"); }

function step() {
  if (i >= fixes.length) {
    setTimeout(() => {
      adb(["exec-out", "screencap", "-p"], path.join(shotsDir, `${tag}-end.png`));
      console.log(`fixes sent: ${i}`);
      console.log(adb(["shell", "dumpsys", "meminfo", "com.veldash"]).split("\n")
        .filter(l => /^\s+(Native Heap|Dalvik Heap|TOTAL)\s/.test(l)).slice(0, 3).join("\n"));
      console.log("DRIVE_DONE");
      sock.end();
    }, 5000);
    return;
  }
  const [lon, lat] = fixes[i];
  send(knots ? `geo fix ${lon} ${lat} 0 6 ${knots}` : `geo fix ${lon} ${lat}`);
  i++;
  if (i === 20 || i === 60 || i === 100) {
    adb(["exec-out", "screencap", "-p"], path.join(shotsDir, `${tag}-${String(i).padStart(3, "0")}.png`));
  }
  if (i === 60) {
    console.log(adb(["shell", "dumpsys", "meminfo", "com.veldash"]).split("\n")
      .filter(l => /^\s+(Native Heap|Dalvik Heap|TOTAL)\s/.test(l)).slice(0, 3).join("\n"));
  }
  setTimeout(step, 1000);
}

sock.on("data", d => {
  buffer += d.toString();
  if (stage === 0 && /OK\r?\n/.test(buffer)) { stage = 1; buffer = ""; send(`auth ${token}`); return; }
  if (stage === 1 && /OK\r?\n/.test(buffer)) { stage = 2; buffer = ""; console.log("console authenticated"); step(); return; }
  if (stage === 2 && /KO/.test(buffer)) { console.error("console error:", buffer.trim()); buffer = ""; }
  if (buffer.length > 4096) buffer = "";
});
sock.on("error", e => { console.error("socket error", e.message); process.exit(1); });
