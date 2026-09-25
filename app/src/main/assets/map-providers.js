// Executed inside initializeMap: only the base layer changes; route layers survive.
var baseLayer = null;
var providerGeneration = 0;
var vectorLibraryPromise = null;

function mapStatus(message) {
    var element = document.getElementById('map-status');
    element.textContent = message;
    element.style.display = message ? 'block' : 'none';
}

function loadScript(url) {
    return new Promise(function(resolve, reject) {
        var script = document.createElement('script');
        var timeout = setTimeout(function() { reject(new Error('Tempo esgotado')); }, 12000);
        script.src = url;
        script.onload = function() { clearTimeout(timeout); resolve(); };
        script.onerror = function() { clearTimeout(timeout); reject(new Error('Falha ao carregar mapa')); };
        document.head.appendChild(script);
    });
}

function loadVectorLibrary() {
    if (L.maplibreGL) return Promise.resolve();
    if (!vectorLibraryPromise) {
        var css = document.createElement('link');
        css.rel = 'stylesheet';
        css.href = 'https://unpkg.com/maplibre-gl@5.6.1/dist/maplibre-gl.css';
        document.head.appendChild(css);
        vectorLibraryPromise = loadScript('https://unpkg.com/maplibre-gl@5.6.1/dist/maplibre-gl.js')
            .then(function() { return loadScript('https://unpkg.com/@maplibre/maplibre-gl-leaflet@0.1.0/leaflet-maplibre-gl.js'); })
            .catch(function(error) { vectorLibraryPromise = null; throw error; });
    }
    return vectorLibraryPromise;
}

function setMapProvider(provider, unusedKey, dark) {
    var generation = ++providerGeneration;
    if (baseLayer) { map.removeLayer(baseLayer); baseLayer = null; }
    mapStatus('Carregando mapa…');
    var tileErrors = 0;
    function raster(selected) {
        if (generation !== providerGeneration) return;
        if (baseLayer) map.removeLayer(baseLayer);
        var url = selected === 'carto'
            ? 'https://{s}.basemaps.cartocdn.com/' + (dark ? 'dark_all' : 'rastertiles/voyager') + '/{z}/{x}/{y}{r}.png'
            : 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
        var layer = L.tileLayer(url, {
            maxZoom: 19,
            className: selected === 'osm' && dark ? 'osm-dark-tiles' : '',
            attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>' + (selected === 'carto' ? ' © CARTO' : '')
        });
        baseLayer = layer;
        layer.on('tileload', function() { if (generation === providerGeneration && baseLayer === layer) mapStatus(''); });
        layer.on('tileerror', function() {
            if (generation !== providerGeneration || baseLayer !== layer) return;
            tileErrors++;
            if (tileErrors === 3 && selected !== 'osm') {
                raster('osm');
                mapStatus('Provedor indisponível. Tentando OpenStreetMap…');
            } else if (tileErrors >= 3) mapStatus('Sem conexão com o mapa. Verifique a internet ou troque o provedor.');
        });
        baseLayer.addTo(map);
    }
    // Keep a usable raster map while optional WebGL assets are loaded.
    raster(provider === 'carto' ? 'carto' : 'osm');
    if (provider === 'openfreemap') {
        loadVectorLibrary().then(function() {
            if (generation !== providerGeneration) return;
            var vector = L.maplibreGL({style: 'https://tiles.openfreemap.org/styles/' + (dark ? 'dark' : 'liberty')});
            var previous = baseLayer;
            vector.addTo(map);
            baseLayer = vector;
            map.removeLayer(previous);
            var gl = vector.getMaplibreMap();
            gl.on('load', function() { if (generation === providerGeneration) mapStatus(''); });
            gl.on('error', function() {
                if (generation === providerGeneration && baseLayer === vector) {
                    raster('osm');
                    mapStatus('OpenFreeMap indisponível. Usando OpenStreetMap.');
                }
            });
        }).catch(function() {
            if (generation === providerGeneration) {
                raster('osm');
                mapStatus('OpenFreeMap indisponível. Usando OpenStreetMap.');
            }
        });
    }
    map.invalidateSize();
}
