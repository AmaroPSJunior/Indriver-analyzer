const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.join(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'app/src/main/java/com/uberanalyzer/MainActivity.kt'), 'utf8');
const providers = fs.readFileSync(path.join(root, 'app/src/main/assets/map-providers.js'), 'utf8');
const html = source.split('val mapHtml = """')[1].split('""".trimIndent()')[0];
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace('${mapProvidersJs}', providers);
const compiled = new vm.Script(js); // Catch syntax errors in the actual Kotlin raw-string payload.
let ready = 0;
const layers = new Set();
const status = {style: {}, textContent: ''};
const map = {setView() { return this; }, removeLayer(layer) { layers.delete(layer); }, invalidateSize() {}, flyTo() {}};
const created = [];
const context = {
    console, setTimeout() { return 0; }, clearTimeout() {}, Promise,
    document: {head: {appendChild() {}}, createElement() { return {}; }, getElementById() { return status; }},
    AndroidBridge: {onMapReady() { ready++; }},
    L: {
        map() { return map; }, divIcon(options) { return options; },
        tileLayer(url, options) {
            const layer = {url, options, events: {}, on(event, fn) { this.events[event] = fn; return this; }, addTo() { layers.add(this); return this; }};
            created.push(layer); return layer;
        }
    },
};
context.window = context;
context.addEventListener = () => {};
vm.createContext(context);
compiled.runInContext(context);
assert.equal(ready, 0, 'document load is not map readiness');
context.initializeMap();
assert.equal(ready, 1);
context.setMapProvider('carto', '', true);
assert.match(created.at(-1).url, /\.com\/dark_all\//);
const route = {}; layers.add(route);
context.setMapProvider('osm', '', false);
assert.equal(created.at(-1).url, 'https://tile.openstreetmap.org/{z}/{x}/{y}.png');
assert.equal(created.at(-1).options.className, '');
assert(layers.has(route), 'changing provider preserves route overlays');
context.setMapProvider('osm', '', true);
assert.equal(created.at(-1).options.className, 'osm-dark-tiles');
context.setMapProvider('carto', '', false);
assert.match(created.at(-1).url, /rastertiles\/voyager/);
const failed = created.at(-1);
failed.events.tileerror(); failed.events.tileerror(); failed.events.tileerror();
assert.match(created.at(-1).url, /tile.openstreetmap.org/);
context.updateMultiRouteMap(JSON.stringify([{passenger:"D'Ávila", passengerPhoto:'data:image/png;base64,abc', price:10, pickup:'', dropoff:''}]));
context.focusRouteByIdx(0); // Address validation must be visible outside the route-render loop.
assert.equal(typeof context.setMapProvider, 'function');
console.log('Map script parses; readiness, providers, themes, fallback, route preservation and focus passed.');
