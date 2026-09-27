// Executed inside initializeMap: only the base layer changes; route layers survive.
var baseLayer = null;
var baseLayers = [];
var providerGeneration = 0;
var vectorLibraryPromise = null;

function keepOnlyBaseLayer(layer) {
    baseLayers.slice().forEach(function(candidate) {
        if (candidate !== layer && map.hasLayer(candidate)) map.removeLayer(candidate);
    });
    baseLayers = [layer];
}

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
    mapStatus('Carregando mapa…');
    var tileErrors = 0;
    function raster(selected) {
        if (generation !== providerGeneration) return;
        var url = selected === 'carto'
            ? 'https://{s}.basemaps.cartocdn.com/' + (dark ? 'dark_all' : 'rastertiles/voyager') + '/{z}/{x}/{y}{r}.png'
            : 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
        var layer = L.tileLayer(url, {
            maxZoom: 19,
            className: selected === 'osm' && dark ? 'osm-dark-tiles' : '',
            attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>' + (selected === 'carto' ? ' © CARTO' : '')
        });
        baseLayer = layer;
        baseLayers.push(layer);
        layer.on('tileload', function() {
            if (generation !== providerGeneration || baseLayer !== layer) return;
            keepOnlyBaseLayer(layer);
            mapStatus('');
        });
        layer.on('tileerror', function() {
            if (generation !== providerGeneration || baseLayer !== layer) return;
            tileErrors++;
            if (tileErrors === 3 && selected !== 'osm') {
                raster('osm');
                mapStatus('Provedor indisponível. Tentando OpenStreetMap…');
            } else if (tileErrors >= 3) mapStatus('Sem conexão com o mapa. Verifique a internet ou troque o provedor.');
        });
        layer.addTo(map);
    }
    // Keep a usable raster map while optional WebGL assets are loaded.
    raster(provider === 'carto' ? 'carto' : 'osm');
    if (provider === 'openfreemap') {
        loadVectorLibrary().then(function() {
            if (generation !== providerGeneration) return;
            var vector = L.maplibreGL({style: 'https://tiles.openfreemap.org/styles/' + (dark ? 'dark' : 'liberty')});
            baseLayers.push(vector);
            vector.addTo(map);
            baseLayer = vector;
            var gl = vector.getMaplibreMap();
            // MapLibre's WebGL canvas can briefly paint as a black rectangle while
            // the style and tiles are loading. Keep the raster map visible below it
            // and reveal the vector canvas only after its first complete render.
            var vectorCanvas = gl.getContainer();
            vectorCanvas.style.opacity = '0';
            vectorCanvas.style.backgroundColor = 'transparent';
            gl.once('load', function() {
                if (generation !== providerGeneration || baseLayer !== vector) return;
                vectorCanvas.style.opacity = '1';
                mapStatus('');
            });
            gl.on('movestart', function() {
                if (generation === providerGeneration && baseLayer === vector) vectorCanvas.style.opacity = '0';
            });
            gl.on('idle', function() {
                if (generation === providerGeneration && baseLayer === vector && gl.isStyleLoaded()) vectorCanvas.style.opacity = '1';
            });
            gl.on('error', function() {
                if (generation === providerGeneration && baseLayer === vector) {
                    vectorCanvas.style.opacity = '0';
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
