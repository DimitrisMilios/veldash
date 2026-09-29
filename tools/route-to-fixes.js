// Turns an OSRM route response (route.json) into drive.txt: one "lon lat" per line, ~12 m apart,
// i.e. one GPS fix per second at ~43 km/h. Usage: node tools/route-to-fixes.js route.json drive.txt
const fs = require("fs");
const r = JSON.parse(fs.readFileSync(process.argv[2] || "drive.json", "utf8"));
const g = r.routes[0].geometry;
let idx = 0, lat = 0, lon = 0; const pts = [];
while (idx < g.length) {
  let b, sh = 0, res = 0;
  do { b = g.charCodeAt(idx++) - 63; res |= (b & 0x1f) << sh; sh += 5; } while (b >= 0x20);
  lat += (res & 1) ? ~(res >> 1) : (res >> 1);
  sh = 0; res = 0;
  do { b = g.charCodeAt(idx++) - 63; res |= (b & 0x1f) << sh; sh += 5; } while (b >= 0x20);
  lon += (res & 1) ? ~(res >> 1) : (res >> 1);
  pts.push([lat / 1e6, lon / 1e6]);
}
const mLat = 111320, mLon = 111320 * Math.cos(40.64 * Math.PI / 180), step = 12;
const out = [];
let carry = 0;
for (let i = 1; i < pts.length; i++) {
  const [la0, lo0] = pts[i - 1], [la1, lo1] = pts[i];
  const dx = (lo1 - lo0) * mLon, dy = (la1 - la0) * mLat, len = Math.hypot(dx, dy);
  let d = step - carry;
  while (d <= len) { out.push([lo0 + (lo1 - lo0) * d / len, la0 + (la1 - la0) * d / len]); d += step; }
  carry = len - (d - step);
}
out.push([pts[pts.length - 1][1], pts[pts.length - 1][0]]);
fs.writeFileSync(process.argv[3] || "drive.txt", out.map(p => p[0].toFixed(6) + " " + p[1].toFixed(6)).join("\n"));
console.log("route distance m:", Math.round(r.routes[0].distance), "points:", pts.length, "fixes:", out.length);
