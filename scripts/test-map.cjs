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
const map = {setView() { return this; }, removeLayer(layer) { layers.delete(layer); }, invalidateSize() {}, flyTo() {}, fitBounds() {}};
const created = [];
const overlays = [];
const selections = [];
let failRoute = false;
function overlay(kind, options) {
    const layer = {kind, options, events: {}, addTo() { overlays.push(this); return this; },
        bindPopup() { return this; }, bindTooltip() { return this; },
        on(event, fn) { this.events[event] = fn; return this; }, setStyle() {}, bringToFront() {}};
    return layer;
}
const context = {
    console, setTimeout() { return 0; }, clearTimeout() {}, Promise,
    document: {head: {appendChild() {}}, createElement() { return {}; }, getElementById() { return status; }},
    AndroidBridge: {onMapReady() { ready++; }, openRide(payload) { selections.push(JSON.parse(payload)); }},
    fetch() {
        return failRoute ? Promise.reject(new Error('offline')) :
            Promise.resolve({json() { return Promise.resolve({routes: [{geometry: {coordinates: [[-46,-23],[-47,-24]]}}]}); }});
    },
    L: {
        map() { return map; }, divIcon(options) { return options; },
        marker(coords, options) { return overlay('marker', options); },
        polyline(coords, options) { return overlay('line', options); },
        featureGroup: function() { this.getBounds = () => ({}); },
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
async function selectionTests() {
    const first = {passenger:"D'Ávila", passengerPhoto:'data:image/png;base64,abc', showName:false, showPhoto:true,
        price:25.5, pickup:"Rua D'Ávila, 120", dropoff:'Avenida Brasil, 42', pLat:-23, pLng:-46, dLat:-24, dLng:-47};
    const second = {...first, pickup:'Rua Segunda, 15', price:32};
    const flush = () => new Promise(resolve => setImmediate(resolve));
    context.updateMultiRouteMap(JSON.stringify([first, second]));
    await flush();
    const initial = overlays.slice();
    assert.equal(initial.length, 6, 'two markers and a line for each ride');
    const firstMarker = initial[0];
    assert.match(firstMarker.options.icon.html, /25,50/);
    assert.doesNotMatch(firstMarker.options.icon.html, /<img|D'Ávila|base64/);
    for (const layer of initial) layer.events.click();
    assert.equal(selections.length, 6);
    assert.equal(selections.filter(s => s.pickup === first.pickup && s.price === first.price).length, 3);
    assert.equal(selections.filter(s => s.pickup === second.pickup && s.price === second.price).length, 3);
    failRoute = true;
    overlays.length = 0;
    context.updateMultiRouteMap(JSON.stringify([second, first]));
    await flush();
    firstMarker.events.click();
    assert.equal(selections.length, 6, 'a removed marker cannot select a stale trip');
    overlays[0].events.click();
    assert.deepEqual(selections.at(-1), {pickup:second.pickup, dropoff:second.dropoff, price:second.price});
    const fallback = overlays.filter(l => l.kind === 'line');
    assert.equal(fallback.length, 2);
    fallback[1].events.click();
    assert.deepEqual(selections.at(-1), {pickup:first.pickup, dropoff:first.dropoff, price:first.price});
    console.log('Map tests passed: readiness, providers, themes, fare labels, marker/route selection, reordered queue, stale events and offline routes.');
}
selectionTests().catch(error => { console.error(error); process.exitCode = 1; });
