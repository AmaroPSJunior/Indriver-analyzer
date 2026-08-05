import http from 'http';
import fs from 'fs';
import path from 'path';

const PORT = 3000;

// Resolve APK path relative to workspace root
const possibleApkPaths = [
  path.join(process.cwd(), '.build-outputs/app-debug.apk'),
  path.join(process.cwd(), 'app/build/outputs/apk/debug/app-debug.apk'),
  path.join(process.cwd(), 'public/apk/app-debug.apk'),
  path.join(process.cwd(), 'app-debug.apk'),
  path.join(process.cwd(), 'applet/app/build/outputs/apk/debug/app-debug.apk'),
  path.resolve('/app/app/build/outputs/apk/debug/app-debug.apk'),
  path.resolve('/app/applet/app/build/outputs/apk/debug/app-debug.apk')
];

function getApkPath() {
  for (const p of possibleApkPaths) {
    if (fs.existsSync(p)) {
      return p;
    }
  }
  return null;
}

// In-memory single source of truth for authentic captured rides from inDrive app (starts completely empty if no data captured)
let capturedRides = [];

const HTML_CONTENT = `<!DOCTYPE html>
<html lang="pt-BR" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>inDrive Analyzer • Captura Direta e Exclusiva</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>
        body, html { margin: 0; padding: 0; height: 100%; background-color: #0F172A; color: #F8FAFC; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        .leaflet-container { background: #0F172A !important; }
        .leaflet-popup-content-wrapper { background: #1E293B !important; color: #F8FAFC !important; border-radius: 12px; border: 1px solid #38BDF8; font-size: 13px; box-shadow: 0 10px 25px rgba(0,0,0,0.7); }
        .leaflet-popup-tip { background: #1E293B !important; }
        .route-card { transition: all 0.2s ease; }
        .route-card:hover { transform: translateY(-2px); }
        .scrollbar-hide::-webkit-scrollbar { display: none; }
        .scrollbar-hide { -ms-overflow-style: none; scrollbar-width: none; }
        .avatar-badge-container {
            display: flex; flex-direction: column; align-items: center;
            justify-content: flex-start; width: 160px;
        }
        .avatar-circle {
            position: relative; width: 44px; height: 44px; border-radius: 50%;
            border: 3px solid; overflow: visible; box-shadow: 0 4px 12px rgba(0,0,0,0.9);
            background: #1E293B;
        }
        .avatar-circle img {
            width: 100%; height: 100%; border-radius: 50%; object-fit: cover;
        }
        .passenger-name-pill {
            margin-top: 4px; padding: 2px 8px; border-radius: 12px;
            background: #0F172A; border: 1.5px solid; font-size: 11px;
            font-weight: 800; white-space: nowrap; text-shadow: 0 1px 2px rgba(0,0,0,0.8);
            box-shadow: 0 2px 6px rgba(0,0,0,0.9);
        }
        .modal-overlay {
            position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(15, 23, 42, 0.85); backdrop-filter: blur(8px);
            z-index: 9999; display: flex; align-items: center; justify-content: center;
        }
        @keyframes pulse-border {
            0%, 100% { border-color: #00E5FF; box-shadow: 0 0 15px rgba(0, 229, 255, 0.4); }
            50% { border-color: #38BDF8; box-shadow: 0 0 25px rgba(56, 189, 248, 0.7); }
        }
        .card-selected {
            animation: pulse-border 2s infinite;
            background: rgba(30, 41, 59, 0.95) !important;
        }
    </style>
</head>
<body class="flex flex-col h-screen overflow-hidden">

    <!-- Top Navigation / Status Header -->
    <header class="bg-slate-900 border-b border-slate-800 px-4 py-2.5 flex flex-wrap items-center justify-between gap-3 shrink-0 z-10 shadow-lg">
        <div class="flex items-center gap-3">
            <div class="w-10 h-10 rounded-xl bg-cyan-600 flex items-center justify-center font-black text-xl text-white shadow-md shadow-cyan-500/20">
                ⚡
            </div>
            <div>
                <div class="flex items-center gap-2">
                    <h1 class="font-bold text-base md:text-lg tracking-tight text-white">inDrive Analyzer • Captura Direta da Listagem</h1>
                    <span id="routes-badge" class="px-2 py-0.5 rounded-full text-[11px] font-bold bg-slate-800 text-slate-300 border border-slate-700">
                        ⏳ AGUARDANDO DADOS DO INDRIVE
                    </span>
                </div>
                <p class="text-xs text-slate-400">Captura exclusiva e direta da listagem do inDrive. Sem simulação ou usuários fakes.</p>
            </div>
        </div>

        <div class="flex items-center gap-2">
            <button onclick="clearCapturedRides()" class="px-3.5 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-500 text-white border border-rose-500 text-xs font-bold flex items-center gap-1.5 transition shadow-lg active:scale-95">
                <span>🗑️</span> Limpar Fila
            </button>
            <button onclick="forceRefreshList()" id="btn-refresh" class="px-3.5 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white border border-emerald-500 text-xs font-bold flex items-center gap-1.5 transition shadow-lg shadow-emerald-600/20 active:scale-95">
                <span id="refresh-icon">🔄</span>
                <span id="refresh-text">Sincronizar Agora</span>
            </button>
            <button onclick="openGitHubModal()" class="px-3.5 py-1.5 rounded-lg bg-purple-600 hover:bg-purple-500 text-white border border-purple-500 text-xs font-bold flex items-center gap-1.5 transition shadow-lg shadow-purple-600/20 active:scale-95">
                <span>🐙</span> GitHub & Convites
            </button>
            <button onclick="openSettingsModal()" class="px-3 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white border border-indigo-500 text-xs font-bold flex items-center gap-1.5 transition">
                <span>⚙️</span> Configurações
            </button>
            <a href="/apk/app-debug.apk" download="inDrive-Analyzer-debug.apk" class="px-4 py-2 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-bold shadow-lg shadow-cyan-500/20 flex items-center gap-2 transition transform active:scale-95">
                <span>📥</span>
                <span>Baixar APK Android (v1.0)</span>
            </a>
        </div>
    </header>

    <!-- Main Tablet Split Screen View -->
    <div class="flex-1 flex flex-col md:flex-row overflow-hidden relative">
        
        <!-- Left Panel: Ride List -->
        <div class="w-full md:w-[420px] lg:w-[460px] bg-slate-950 border-r border-slate-800 flex flex-col shrink-0 h-[45%] md:h-full">
            <div class="px-4 py-2 bg-slate-900/60 border-b border-slate-800/80 flex items-center justify-between">
                <span id="list-title" class="text-xs font-bold text-slate-300 uppercase tracking-wider">FILA NA LISTA (ORDEM TOPO → BAIXO)</span>
                <span class="text-[11px] font-medium px-2 py-0.5 rounded bg-slate-800 text-cyan-400">Sincronizado com Mapa</span>
            </div>

            <!-- Dynamic Horizontal Status Bar -->
            <div id="color-pills" class="flex items-center gap-1.5 px-3 py-2 bg-slate-900/40 border-b border-slate-800/60 overflow-x-auto scrollbar-hide">
                <!-- Color pills injected via JS -->
            </div>

            <!-- Sorting & Queue Order Control Bar -->
            <div class="px-3 py-2 bg-slate-900/90 border-b border-slate-800 flex items-center justify-between gap-2 overflow-x-auto scrollbar-hide text-xs">
                <span class="text-slate-400 font-bold shrink-0">Ordenar Fila:</span>
                <div class="flex items-center gap-1">
                    <button onclick="sortQueue('default')" id="sort-btn-default" class="px-2 py-1 rounded bg-cyan-500 text-slate-950 font-bold shrink-0 transition">Topo ↓ Baixo</button>
                    <button onclick="sortQueue('price')" id="sort-btn-price" class="px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 font-medium shrink-0 transition">Maior R$</button>
                    <button onclick="sortQueue('distance')" id="sort-btn-distance" class="px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 font-medium shrink-0 transition">Menor km</button>
                    <button onclick="sortQueue('score')" id="sort-btn-score" class="px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 font-medium shrink-0 transition">Maior Nota ★</button>
                </div>
            </div>

            <div id="ride-list" class="flex-1 overflow-y-auto p-3 space-y-3 scrollbar-hide">
                <!-- Cards will be rendered dynamically -->
            </div>

            <div class="p-3 bg-slate-900/80 border-t border-slate-800 text-center">
                <p class="text-[11px] text-slate-400">
                    💡 <span class="text-slate-200 font-medium">Ordem Sincronizada:</span> Array index zero é rigidamente o topo da fila, sincronizado com o primeiro card e marcador.
                </p>
            </div>
        </div>

        <!-- Right Panel: Interactive Leaflet Map with Colored Routes -->
        <div class="flex-1 h-[55%] md:h-full relative bg-slate-900">
            <!-- Top Selected Ride Inspection Banner -->
            <div id="selected-ride-banner" class="absolute top-3 left-3 right-3 md:right-72 z-[1000] bg-slate-900/95 backdrop-blur-md border border-cyan-500/60 rounded-xl px-4 py-2.5 shadow-2xl flex items-center justify-between gap-3 transition-all duration-300 hidden">
                <div class="flex items-center gap-3 min-w-0">
                    <div class="flex flex-col items-center justify-center shrink-0">
                        <div id="selected-photo-container" class="w-11 h-11 rounded-full border-2 border-cyan-400 overflow-hidden bg-slate-800 shadow-md">
                            <img id="selected-photo" src="" class="w-full h-full object-cover" />
                        </div>
                        <span id="selected-passenger-name" class="font-black text-[11px] text-cyan-300 mt-1 px-2.5 py-0.5 bg-slate-950 rounded-full border border-slate-700 whitespace-nowrap shadow"></span>
                    </div>
                    <div class="min-w-0">
                        <div class="flex items-center gap-2">
                            <span id="selected-sequence-badge" class="px-2 py-0.5 rounded text-[10px] font-extrabold bg-cyan-500/20 text-cyan-300 border border-cyan-500/30"></span>
                            <span id="single-mode-badge" class="hidden px-2 py-0.5 rounded text-[10px] font-extrabold bg-amber-500/20 text-amber-300 border border-amber-500/30">📍 ROTA ÚNICA</span>
                        </div>
                        <div id="selected-route-info" class="text-xs text-slate-200 mt-1 font-medium leading-snug"></div>
                    </div>
                </div>
                <div class="flex items-center gap-3 shrink-0">
                    <div id="selected-price-box" class="text-right">
                        <div id="selected-price" class="text-base font-black text-cyan-400"></div>
                        <div id="selected-metrics" class="text-[10px] text-slate-400 font-bold"></div>
                    </div>
                    <button onclick="hideRide(selectedRideId)" id="btn-hide-selected-ride" class="px-3 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-500 text-white text-xs font-bold shadow-lg border border-rose-400/50 flex items-center gap-1 transition active:scale-95" title="Ocultar viagem realizando gesto de deslizar no inDrive">
                        <span>🙈</span> <span class="hidden sm:inline">Ocultar Viagem</span>
                    </button>
                    <button onclick="showAllRoutes()" id="btn-show-all-routes" class="px-3 py-1.5 rounded-lg bg-indigo-600/90 hover:bg-indigo-500 text-white text-xs font-bold shadow-lg border border-indigo-400/50 flex items-center gap-1 transition active:scale-95" title="Sair do modo rota única e mostrar todas as rotas">
                        <span>⬅️</span> <span class="hidden sm:inline">Mostrar</span> Toda Fila
                    </button>
                </div>
            </div>

            <div id="map" class="w-full h-full"></div>

            <!-- Map Legend Overlay -->
            <div id="legend-box" class="absolute top-3 right-3 z-[1000] bg-slate-900/95 backdrop-blur-md border border-slate-700/80 rounded-xl p-3 shadow-2xl max-w-[240px]">
                <div class="text-[11px] font-bold text-slate-300 uppercase tracking-wider mb-2">Fila no Mapa</div>
                <div id="legend-list" class="space-y-1.5 text-xs">
                    <!-- Dynamic legend -->
                </div>
            </div>
        </div>
    </div>

    <!-- Settings Modal -->
    <div id="settings-modal" class="modal-overlay hidden">
        <div class="bg-slate-900 border border-slate-700 rounded-2xl p-6 w-full max-w-md mx-4 shadow-2xl space-y-5">
            <div class="flex items-center justify-between border-b border-slate-800 pb-3">
                <h3 class="text-base font-bold text-white flex items-center gap-2">
                    <span>⚙️</span> Configurações da Tela & Provedor de Mapa
                </h3>
                <button onclick="closeSettingsModal()" class="text-slate-400 hover:text-white text-lg font-bold">&times;</button>
            </div>

            <div>
                <label class="block text-xs font-bold text-slate-300 uppercase tracking-wider mb-2">
                    Provedor de Mapa Gratuito
                </label>
                <div class="grid grid-cols-1 gap-2">
                    <button onclick="setMapProvider('cartoDark')" id="prov-cartoDark" class="p-2.5 rounded-xl border text-left text-xs font-bold transition flex items-center justify-between bg-cyan-500/10 border-cyan-500 text-cyan-300">
                        <span>🌙 CartoDB Dark Matter (Escuro Padrão)</span>
                        <span>✅</span>
                    </button>
                    <button onclick="setMapProvider('osm')" id="prov-osm" class="p-2.5 rounded-xl border text-left text-xs font-bold transition flex items-center justify-between bg-slate-800 border-slate-700 text-slate-300">
                        <span>🗺️ OpenStreetMap Standard (OSM Padrão)</span>
                        <span></span>
                    </button>
                    <button onclick="setMapProvider('cartoLight')" id="prov-cartoLight" class="p-2.5 rounded-xl border text-left text-xs font-bold transition flex items-center justify-between bg-slate-800 border-slate-700 text-slate-300">
                        <span>☀️ CartoDB Positron (Claro / High Contrast)</span>
                        <span></span>
                    </button>
                </div>
            </div>

            <div>
                <label class="block text-xs font-bold text-slate-300 uppercase tracking-wider mb-2">
                    Quantidade de Rotas no Mapa (Máximo 10)
                </label>
                <div id="max-routes-buttons" class="grid grid-cols-5 gap-2">
                    <button onclick="setMaxRoutes(1)" class="max-btn py-1.5 rounded-lg text-xs font-bold border border-slate-700 bg-slate-800 text-slate-300 hover:bg-slate-700">1</button>
                    <button onclick="setMaxRoutes(3)" class="max-btn py-1.5 rounded-lg text-xs font-bold border border-slate-700 bg-slate-800 text-slate-300 hover:bg-slate-700">3</button>
                    <button onclick="setMaxRoutes(5)" class="max-btn py-1.5 rounded-lg text-xs font-bold border border-slate-700 bg-slate-800 text-slate-300 hover:bg-slate-700">5</button>
                    <button onclick="setMaxRoutes(8)" class="max-btn py-1.5 rounded-lg text-xs font-bold border border-slate-700 bg-slate-800 text-slate-300 hover:bg-slate-700">8</button>
                    <button onclick="setMaxRoutes(10)" class="max-btn py-1.5 rounded-lg text-xs font-bold border border-slate-700 bg-slate-800 text-slate-300 hover:bg-slate-700">10</button>
                </div>
            </div>

            <div class="space-y-3 pt-2 border-t border-slate-800">
                <label class="flex items-center gap-3 cursor-pointer">
                    <input id="chk-show-photo" type="checkbox" checked onchange="toggleSetting('photo')" class="w-4 h-4 rounded text-cyan-500 bg-slate-800 border-slate-700">
                    <span class="text-xs font-medium text-slate-200">📸 Exibir Foto do Usuário no Ponto A (Embarque)</span>
                </label>
                <label class="flex items-center gap-3 cursor-pointer">
                    <input id="chk-show-name" type="checkbox" checked onchange="toggleSetting('name')" class="w-4 h-4 rounded text-cyan-500 bg-slate-800 border-slate-700">
                    <span class="text-xs font-medium text-slate-200">👤 Exibir Nome do Usuário logo abaixo da Foto</span>
                </label>
                <label class="flex items-center gap-3 cursor-pointer">
                    <input id="chk-show-metrics" type="checkbox" checked onchange="toggleSetting('metrics')" class="w-4 h-4 rounded text-cyan-500 bg-slate-800 border-slate-700">
                    <span class="text-xs font-medium text-slate-200">📊 Exibir Métricas e R$/km nos Cards</span>
                </label>
            </div>

            <div class="pt-3 border-t border-slate-800 flex justify-end">
                <button onclick="closeSettingsModal()" class="px-5 py-2 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 text-xs font-black shadow-lg shadow-cyan-500/20 transition">
                    Salvar e Fechar
                </button>
            </div>
        </div>
    </div>

    <!-- GitHub Integration & Invites Modal -->
    <div id="github-modal" class="modal-overlay hidden">
        <div class="bg-slate-900 border border-slate-700 rounded-2xl p-6 w-full max-w-lg mx-4 shadow-2xl space-y-5 text-left">
            <div class="flex items-center justify-between border-b border-slate-800 pb-3">
                <h3 class="text-base font-bold text-white flex items-center gap-2">
                    <span>🐙</span> Integração GitHub & Envio de Convites
                </h3>
                <button onclick="closeGitHubModal()" class="text-slate-400 hover:text-white text-lg font-bold">&times;</button>
            </div>

            <!-- Status Card -->
            <div id="gh-status-card" class="bg-slate-950 p-4 rounded-xl border border-slate-800 text-xs space-y-2">
                <div class="flex items-center justify-between">
                    <span class="text-slate-400 font-bold">Repositório:</span>
                    <span id="gh-repo-name" class="font-mono text-cyan-400 font-extrabold">--</span>
                </div>
                <div class="flex items-center justify-between">
                    <span class="text-slate-400 font-bold">Status Conexão:</span>
                    <span id="gh-status-badge" class="px-2 py-0.5 rounded font-bold bg-slate-800 text-slate-400">Verificando...</span>
                </div>
                <div id="gh-error-msg" class="text-rose-400 text-[11px] hidden font-semibold bg-rose-950/40 p-2 rounded border border-rose-800/50"></div>
            </div>

            <!-- Section 1: Enviar Commit / Push -->
            <div class="bg-slate-950/60 p-4 rounded-xl border border-slate-800/80 space-y-3">
                <h4 class="text-xs font-bold text-slate-200 flex items-center gap-1.5">
                    <span>🚀</span> Commit & Sincronização Direta
                </h4>
                <div>
                    <label class="block text-[11px] text-slate-400 font-bold mb-1">Mensagem do Commit:</label>
                    <input type="text" id="gh-commit-msg" value="feat: sincronização de alterações e rotas do app" class="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-cyan-400" />
                </div>
                <button onclick="sendGitHubCommit()" id="btn-gh-commit" class="w-full py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-lg text-xs transition flex items-center justify-center gap-2">
                    <span>📤</span> Enviar Commit & Push para GitHub
                </button>
            </div>

            <!-- Section 2: Enviar Convite para Colaborador -->
            <div class="bg-slate-950/60 p-4 rounded-xl border border-slate-800/80 space-y-3">
                <h4 class="text-xs font-bold text-slate-200 flex items-center gap-1.5">
                    <span>📩</span> Enviar Convite de Colaborador (Invite)
                </h4>
                <div class="flex gap-2">
                    <input type="text" id="gh-invite-user" placeholder="Usuário do GitHub (ex: octocat)" class="flex-1 bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-cyan-400" />
                    <button onclick="sendGitHubInvite()" id="btn-gh-invite" class="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white font-bold rounded-lg text-xs transition shrink-0">
                        Enviar Convite
                    </button>
                </div>
            </div>

            <!-- Section 3: Configurar / Atualizar Credenciais -->
            <div class="bg-slate-950/40 p-3 rounded-xl border border-slate-800/60 space-y-2">
                <details class="text-xs">
                    <summary class="cursor-pointer text-slate-400 hover:text-slate-200 font-bold">⚙️ Atualizar Repositório / Token</summary>
                    <div class="mt-3 space-y-2">
                        <div>
                            <label class="block text-[10px] text-slate-400 font-bold">GITHUB_REPO:</label>
                            <input type="text" id="gh-cfg-repo" placeholder="usuario/repositorio" class="w-full bg-slate-900 border border-slate-700 rounded px-2.5 py-1 text-xs text-white" />
                        </div>
                        <div>
                            <label class="block text-[10px] text-slate-400 font-bold">GITHUB_TOKEN:</label>
                            <input type="password" id="gh-cfg-token" placeholder="ghp_..." class="w-full bg-slate-900 border border-slate-700 rounded px-2.5 py-1 text-xs text-white" />
                        </div>
                        <button onclick="saveGitHubConfig()" class="px-3 py-1 bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-bold rounded transition">
                            Salvar Credenciais
                        </button>
                    </div>
                </details>
            </div>

            <!-- Feedback Log Box -->
            <div id="gh-log-box" class="bg-black/80 rounded-lg p-3 text-[11px] font-mono text-cyan-300 max-h-28 overflow-y-auto hidden border border-slate-800"></div>

            <div class="flex justify-end pt-2 border-t border-slate-800">
                <button onclick="closeGitHubModal()" class="px-4 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-white text-xs font-bold">
                    Fechar
                </button>
            </div>
        </div>
    </div>

    <script>
        function openGitHubModal() {
            document.getElementById('github-modal').classList.remove('hidden');
            checkGitHubStatus();
        }

        function closeGitHubModal() {
            document.getElementById('github-modal').classList.add('hidden');
        }

        async function checkGitHubStatus() {
            const badge = document.getElementById('gh-status-badge');
            const repoEl = document.getElementById('gh-repo-name');
            const errBox = document.getElementById('gh-error-msg');
            
            badge.className = 'px-2 py-0.5 rounded font-bold bg-slate-800 text-slate-400';
            badge.innerText = 'Verificando...';
            errBox.classList.add('hidden');

            try {
                const res = await fetch('/api/github/status');
                const data = await res.json();
                repoEl.innerText = data.repo || 'Não informado';
                document.getElementById('gh-cfg-repo').value = data.repo || '';

                if (data.configured && data.valid) {
                    badge.className = 'px-2 py-0.5 rounded font-bold bg-emerald-500/20 text-emerald-400 border border-emerald-500/30';
                    badge.innerText = '🟢 Conectado';
                } else if (data.configured && !data.valid) {
                    badge.className = 'px-2 py-0.5 rounded font-bold bg-rose-500/20 text-rose-400 border border-rose-500/30';
                    badge.innerText = '🔴 Token / Credenciais Inválidas';
                    errBox.innerText = 'Erro: ' + (data.error || 'Autenticação falhou') + '. Atualize o token se necessário.';
                    errBox.classList.remove('hidden');
                } else {
                    badge.className = 'px-2 py-0.5 rounded font-bold bg-amber-500/20 text-amber-400 border border-amber-500/30';
                    badge.innerText = '⚠️ Token Pendente';
                }
            } catch (e) {
                badge.innerText = 'Erro de Conexão';
            }
        }

        async function sendGitHubCommit() {
            const msg = document.getElementById('gh-commit-msg').value.trim();
            const btn = document.getElementById('btn-gh-commit');
            const logBox = document.getElementById('gh-log-box');

            btn.disabled = true;
            btn.innerText = '⏳ Sincronizando e enviando...';
            logBox.classList.remove('hidden');
            logBox.innerText = 'Iniciando commit e push para o GitHub...';

            try {
                const res = await fetch('/api/github/commit', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ message: msg })
                });
                const data = await res.json();
                if (data.status === 'success') {
                    logBox.innerText = ['✅ Sucesso!', data.message, data.details || ''].join(String.fromCharCode(10));
                    checkGitHubStatus();
                } else {
                    logBox.innerText = ['❌ Erro no Commit/Push:', data.error || 'Falha ao sincronizar'].join(String.fromCharCode(10));
                }
            } catch (err) {
                logBox.innerText = '❌ Erro de requisição: ' + err.message;
            } finally {
                btn.disabled = false;
                btn.innerHTML = '<span>📤</span> Enviar Commit & Push para GitHub';
            }
        }

        async function sendGitHubInvite() {
            const user = document.getElementById('gh-invite-user').value.trim();
            const btn = document.getElementById('btn-gh-invite');
            const logBox = document.getElementById('gh-log-box');

            if (!user) {
                alert('Digite o nome do usuário do GitHub.');
                return;
            }

            btn.disabled = true;
            btn.innerText = 'Enviando...';
            logBox.classList.remove('hidden');
            logBox.innerText = 'Enviando convite de colaborador para @' + user + '...';

            try {
                const res = await fetch('/api/github/invite', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: user })
                });
                const data = await res.json();
                if (data.status === 'success') {
                    logBox.innerText = ['✅ ' + data.message, data.details || ''].join(String.fromCharCode(10));
                    document.getElementById('gh-invite-user').value = '';
                } else {
                    logBox.innerText = ['❌ Erro ao enviar convite:', data.error || 'Falha na requisição'].join(String.fromCharCode(10));
                }
            } catch (err) {
                logBox.innerText = '❌ Erro: ' + err.message;
            } finally {
                btn.disabled = false;
                btn.innerText = 'Enviar Convite';
            }
        }

        async function saveGitHubConfig() {
            const repo = document.getElementById('gh-cfg-repo').value.trim();
            const token = document.getElementById('gh-cfg-token').value.trim();
            
            if (!repo && !token) return;

            try {
                const res = await fetch('/api/github/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ repo, token })
                });
                const data = await res.json();
                if (data.status === 'success') {
                    alert('Credenciais salvas com sucesso!');
                    checkGitHubStatus();
                }
            } catch (e) {
                alert('Erro ao salvar credenciais.');
            }
        }
        const ROUTE_COLORS = ['#00E5FF', '#22C55E', '#F59E0B', '#EC4899', '#A855F7', '#EAB308', '#14B8A6', '#3B82F6', '#F43F5E', '#8B5CF6'];

        let maxRoutes = 3;
        let showPhoto = true;
        let showName = true;
        let showMetrics = true;
        let selectedRideId = null;
        let isSingleRouteMode = false;
        let currentSortMode = 'default';
        let currentRoutes = [];
        let lastFetchedRidesJson = '';
        let currentMapProvider = 'cartoDark'; // 'cartoDark' | 'osm' | 'cartoLight'
        let map;
        let tileLayer;
        let routeLayers = [];

        const TILE_PROVIDERS = {
            cartoDark: {
                url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
                attribution: '&copy; OpenStreetMap contributors &copy; CARTO'
            },
            osm: {
                url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
                attribution: '&copy; OpenStreetMap contributors'
            },
            cartoLight: {
                url: 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',
                attribution: '&copy; OpenStreetMap contributors &copy; CARTO'
            }
        };

        function initMap() {
            const savedProvider = localStorage.getItem('inDriveMapProvider');
            if (savedProvider && TILE_PROVIDERS[savedProvider]) {
                currentMapProvider = savedProvider;
            }
            map = L.map('map', {
                center: [-23.5800, -46.6750],
                zoom: 12,
                zoomControl: false
            });

            L.control.zoom({ position: 'bottomright' }).addTo(map);

            updateTileLayer();
            updateMapProviderUI();
            fetchRides();
            setInterval(fetchRides, 2000);
        }

        function setMapProvider(providerKey) {
            currentMapProvider = providerKey;
            localStorage.setItem('inDriveMapProvider', providerKey);
            updateTileLayer();
            updateMapProviderUI();
        }

        function updateTileLayer() {
            if (tileLayer) {
                map.removeLayer(tileLayer);
            }
            const config = TILE_PROVIDERS[currentMapProvider] || TILE_PROVIDERS.cartoDark;
            tileLayer = L.tileLayer(config.url, {
                attribution: config.attribution,
                maxZoom: 19
            }).addTo(map);
        }

        function updateMapProviderUI() {
            ['cartoDark', 'osm', 'cartoLight'].forEach(key => {
                const btn = document.getElementById('prov-' + key);
                if (btn) {
                    if (key === currentMapProvider) {
                        btn.className = 'p-2.5 rounded-xl border text-left text-xs font-bold transition flex items-center justify-between bg-cyan-500/10 border-cyan-500 text-cyan-300';
                        btn.querySelector('span:last-child').textContent = '✅';
                    } else {
                        btn.className = 'p-2.5 rounded-xl border text-left text-xs font-bold transition flex items-center justify-between bg-slate-800 border-slate-700 text-slate-300';
                        btn.querySelector('span:last-child').textContent = '';
                    }
                }
            });
        }

        function openSettingsModal() {
            document.getElementById('settings-modal').classList.remove('hidden');
            updateMaxRoutesUI();
            updateMapProviderUI();
        }

        function closeSettingsModal() {
            document.getElementById('settings-modal').classList.add('hidden');
            renderRideList();
            drawRoutesOnMap();
        }

        function setMaxRoutes(n) {
            maxRoutes = n;
            updateMaxRoutesUI();
        }

        function toggleSetting(key) {
            if (key === 'photo') showPhoto = document.getElementById('chk-show-photo').checked;
            if (key === 'name') showName = document.getElementById('chk-show-name').checked;
            if (key === 'metrics') showMetrics = document.getElementById('chk-show-metrics').checked;
            renderRideList();
            drawRoutesOnMap();
        }

        function updateMaxRoutesUI() {
            const btns = document.querySelectorAll('#max-routes-buttons button');
            btns.forEach(b => {
                if (parseInt(b.textContent) === maxRoutes) {
                    b.classList.remove('bg-slate-800', 'text-slate-300');
                    b.classList.add('bg-cyan-500', 'text-slate-950');
                } else {
                    b.classList.add('bg-slate-800', 'text-slate-300');
                    b.classList.remove('bg-cyan-500', 'text-slate-950');
                }
            });
        }

        function isRideValidAndComplete(r) {
            if (!r) return false;
            const p = (r.pickup || r.pickupAddress || r.pickup_address || '').trim();
            const d = (r.dropoff || r.dropoffAddress || r.dropoff_address || '').trim();
            if (p.length < 6 || d.length < 6) return false;

            const invalidKeywords = [
                'origem', 'destino', 'não capturado', 'desconhecido', 
                'simulado', 'fake', 'incompleto', 'endereço', 'não informado',
                'em análise'
            ];
            const pLow = p.toLowerCase();
            const dLow = d.toLowerCase();
            for (const kw of invalidKeywords) {
                if (pLow === kw || dLow === kw || pLow.includes('em análise') || dLow.includes('em análise') || pLow.includes('não capturado') || dLow.includes('não capturado')) {
                    return false;
                }
            }

            const pLat = Number(r.pLat || r.lat || 0);
            const pLng = Number(r.pLng || r.lon || 0);
            const dLat = Number(r.dLat || 0);
            const dLng = Number(r.dLng || 0);
            if (pLat === 0 || pLng === 0 || dLat === 0 || dLng === 0) {
                return false;
            }
            return true;
        }

        function applyCurrentSort() {
            if (currentSortMode === 'price') {
                currentRoutes.sort((a, b) => (b.price || 0) - (a.price || 0));
            } else if (currentSortMode === 'distance') {
                currentRoutes.sort((a, b) => (a.distanceKm || 0) - (b.distanceKm || 0));
            } else if (currentSortMode === 'score') {
                currentRoutes.sort((a, b) => (b.score || 0) - (a.score || 0));
            } else {
                currentRoutes.sort((a, b) => (a.id || 0) - (b.id || 0));
            }
        }

        async function fetchRides() {
            try {
                const res = await fetch('/api/rides');
                if (!res.ok) return;
                const data = await res.json();
                if (Array.isArray(data)) {
                    const validRides = data.filter(isRideValidAndComplete);
                    const newJson = JSON.stringify(validRides);
                    if (newJson !== lastFetchedRidesJson) {
                        lastFetchedRidesJson = newJson;
                        currentRoutes = validRides;
                        applyCurrentSort();
                        if (currentRoutes.length > 0) {
                            if (!selectedRideId || !currentRoutes.some(r => r.id === selectedRideId)) {
                                selectedRideId = currentRoutes[0].id;
                            }
                        } else {
                            selectedRideId = null;
                        }
                        renderRideList();
                        drawRoutesOnMap();
                    }
                }
            } catch (e) {
                console.warn("Aviso ao buscar corridas:", e && e.message ? e.message : e);
            }
        }

        async function clearCapturedRides() {
            try {
                await fetch('/api/rides/clear', { method: 'POST' });
                lastFetchedRidesJson = '[]';
                currentRoutes = [];
                selectedRideId = null;
                renderRideList();
                drawRoutesOnMap();
            } catch (e) {
                console.warn("Aviso ao limpar filas:", e && e.message ? e.message : e);
            }
        }

        async function acceptRide(id) {
            try {
                await fetch('/api/rides/' + id, { method: 'DELETE' });
                await fetchRides();
            } catch (e) {
                console.warn("Aviso ao aceitar/remover corrida:", e && e.message ? e.message : e);
            }
        }

        async function hideRide(id) {
            try {
                // If running inside Android WebView, perform physical gesture left-to-right on inDrive
                if (window.AndroidBridge && window.AndroidBridge.hideTopTrip) {
                    window.AndroidBridge.hideTopTrip();
                }
                await fetch('/api/rides/' + id, { method: 'DELETE' });
                currentRoutes = currentRoutes.filter(r => r.id !== id);
                if (selectedRideId === id) {
                    selectedRideId = currentRoutes.length > 0 ? currentRoutes[0].id : null;
                }
                renderRideList();
                drawRoutesOnMap();
                await fetchRides();
            } catch (e) {
                console.warn("Aviso ao ocultar corrida:", e && e.message ? e.message : e);
            }
        }

        function renderRideList() {
            const listEl = document.getElementById('ride-list');
            const pillsEl = document.getElementById('color-pills');
            const legendEl = document.getElementById('legend-list');
            
            listEl.innerHTML = '';
            pillsEl.innerHTML = '';
            legendEl.innerHTML = '';

            if (!currentRoutes || currentRoutes.length === 0) {
                listEl.innerHTML = \`
                    <div class="flex flex-col items-center justify-center p-8 text-center bg-slate-950/80 rounded-2xl border border-slate-800 m-4">
                        <div class="text-4xl mb-3">📱</div>
                        <h3 class="text-white font-bold text-base mb-1">Aguardando Captura Direta do inDrive</h3>
                        <p class="text-slate-400 text-xs leading-relaxed max-w-xs">
                            O dashboard permanece em branco até que dados autênticos sejam capturados diretamente da listagem do aplicativo inDrive.
                        </p>
                    </div>
                \`;
                document.getElementById('list-title').textContent = 'FILA VAZIA (AGUARDANDO IN-DRIVE)';
                document.getElementById('routes-badge').textContent = '⏳ AGUARDANDO CAPTURA DO INDRIVE';
                const banner = document.getElementById('selected-ride-banner');
                if (banner) banner.classList.add('hidden');
                drawRoutesOnMap();
                return;
            }

            const activeRoutes = currentRoutes.slice(0, maxRoutes);

            document.getElementById('list-title').textContent = \`FILA NA LISTA (\${activeRoutes.length} DE MÁX \${maxRoutes})\`;
            document.getElementById('routes-badge').textContent = \`✅ CAPTURA ATIVA (\${activeRoutes.length} ROTAS SINCRONIZADAS)\`;

            activeRoutes.forEach((r, idx) => {
                const color = ROUTE_COLORS[idx % ROUTE_COLORS.length];
                const queuePosText = idx === 0 ? 'TOPO DA FILA (PRÓXIMO 1º)' : \`POSIÇÃO #\${idx + 1} NA FILA\`;
                const queueBg = idx === 0 ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/40' : 'bg-slate-800 text-slate-300 border-slate-700';

                // Color pill button
                const pill = document.createElement('button');
                pill.className = 'px-3 py-1.5 rounded-full text-xs font-bold shrink-0 flex items-center gap-2 transition transform hover:scale-105';
                pill.style.backgroundColor = color + '22';
                pill.style.border = '1.5px solid ' + color;
                pill.style.color = color;
                pill.innerHTML = \`
                    <span class="w-2.5 h-2.5 rounded-full" style="background-color: \${color};"></span>
                    <span class="font-extrabold">👤 \${r.passenger || 'Passageiro'}</span>
                    <span class="text-slate-200 font-semibold">R$ \${Number(r.price || 0).toFixed(2)}</span>
                \`;
                pill.onclick = () => selectRide(r.id, true);
                pillsEl.appendChild(pill);

                // Legend item
                const legItem = document.createElement('div');
                legItem.className = 'flex items-center justify-between gap-2 cursor-pointer hover:bg-slate-800/50 p-1 rounded transition';
                legItem.onclick = () => selectRide(r.id, true);
                legItem.innerHTML = \`
                    <div class="flex items-center gap-2 truncate">
                        <span class="w-3 h-3 rounded-full shrink-0 shadow-sm" style="background-color: \${color}"></span>
                        <span class="text-slate-200 font-bold truncate">👤 \${r.passenger || 'Passageiro'}</span>
                    </div>
                    <span class="text-slate-400 font-mono text-[11px]">\${r.distanceKm || 0}km</span>
                \`;
                legendEl.appendChild(legItem);

                // Card
                const card = document.createElement('div');
                const isSelected = r.id === selectedRideId;
                card.className = \`route-card bg-slate-900 border-2 rounded-xl p-3.5 cursor-pointer shadow-lg transition-all \${isSelected ? 'card-selected border-cyan-400 ring-2 ring-cyan-400/30' : 'hover:border-slate-600'}\`;
                if (!isSelected) card.style.borderColor = color;
                card.onclick = () => selectRide(r.id, true);

                let metricsHtml = '';
                if (showMetrics) {
                    const epk = r.distanceKm ? (r.price / r.distanceKm) : 0;
                    metricsHtml = \`
                        <span class="text-xs font-bold px-2.5 py-0.5 rounded bg-green-500/10 text-green-400 border border-green-500/20">
                            R$ \${epk.toFixed(2)}/km
                        </span>
                    \`;
                }

                card.innerHTML = \`
                    <div class="flex items-center justify-between mb-2">
                        <span class="text-[10px] font-black px-2.5 py-0.5 rounded-full border tracking-wide uppercase \${queueBg}">
                            \${queuePosText}
                        </span>
                        \${metricsHtml}
                    </div>

                    <div class="flex flex-col items-center justify-center text-center mb-3 p-3 rounded-lg bg-slate-950/60 border border-slate-800">
                        <div class="w-14 h-14 rounded-full border-2 overflow-hidden shrink-0 bg-slate-800 shadow mb-1.5 flex items-center justify-center" style="border-color: \${color};">
                            \${r.passengerPhoto && r.passengerPhoto.trim() !== ''
                                ? \`<img src="\${r.passengerPhoto}" onerror="this.style.display='none'; this.nextElementSibling.style.display='flex';" class="w-full h-full object-cover" /><div class="hidden w-full h-full items-center justify-center text-2xl">👤</div>\`
                                : \`<div class="w-full h-full flex items-center justify-center text-2xl">👤</div>\`}
                        </div>
                        <div class="text-sm font-black text-white px-3 py-0.5 rounded-full bg-slate-900 border border-slate-700 shadow" style="color: \${color};">👤 \${r.passenger || 'Passageiro'}</div>
                        <div class="flex items-center justify-center gap-2 mt-1.5 text-xs font-bold">
                            <span style="color: \${color};">R$ \${Number(r.price || 0).toFixed(2)}</span>
                            <span class="text-slate-400">• \${r.distanceKm || 0} km</span>
                            <span class="text-amber-400 font-bold">• ★ \${r.score || 9.0}</span>
                        </div>
                    </div>

                    <div class="space-y-2 text-xs bg-slate-950/40 p-3 rounded-lg border border-slate-800/80">
                        <div class="flex items-start gap-2">
                            <span class="w-2.5 h-2.5 rounded-full bg-green-400 shrink-0 shadow-sm shadow-green-400/50 mt-1"></span>
                            <div class="text-left">
                                <span class="text-[10px] uppercase font-bold text-slate-400 block">Origem Completa:</span>
                                <span class="text-slate-200 font-semibold leading-snug break-words">\${r.pickup || r.pickupAddress || r.pickup_address || 'Endereço não informado'}</span>
                            </div>
                        </div>
                        <div class="flex items-start gap-2">
                            <span class="w-2.5 h-2.5 rounded-full bg-red-400 shrink-0 shadow-sm shadow-red-400/50 mt-1"></span>
                            <div class="text-left">
                                <span class="text-[10px] uppercase font-bold text-slate-400 block">Destino Completo:</span>
                                <span class="text-slate-200 font-semibold leading-snug break-words">\${r.dropoff || r.dropoffAddress || r.dropoff_address || 'Endereço não informado'}</span>
                            </div>
                        </div>
                    </div>

                    <div class="mt-2.5 pt-2 border-t border-slate-800/80 flex items-center justify-between text-[11px]">
                        <span class="text-slate-400">⏱️ Est. \${r.timeMin || 10} min</span>
                        <div class="flex items-center gap-1.5" onclick="event.stopPropagation();">
                            <button onclick="hideRide(\${r.id})" class="px-2.5 py-1 rounded bg-rose-600/30 hover:bg-rose-600/50 text-rose-300 border border-rose-500/40 font-extrabold text-[11px] shadow transition flex items-center gap-1" title="Ocultar viagem realizando gesto deslizar no inDrive">
                                <span>🙈</span> Ocultar
                            </button>
                            <button onclick="acceptRide(\${r.id})" class="px-2.5 py-1 rounded bg-green-600 hover:bg-green-500 text-white font-extrabold text-[11px] shadow transition flex items-center gap-1">
                                <span>✅</span> Aceitar / Remover
                            </button>
                            \${isSingleRouteMode && isSelected
                                ? '<button onclick="showAllRoutes()" class="px-2.5 py-1 rounded bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 font-extrabold border border-amber-500/40 transition ml-1">⬅️ Ver Todas</button>'
                                : '<button onclick="selectRide(' + r.id + ', true)" class="px-2.5 py-1 rounded bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-300 font-extrabold border border-cyan-500/30 transition ml-1">📍 Focar Rota</button>'
                            }
                        </div>
                    </div>
                \`;

                listEl.appendChild(card);
            });

            if (isSingleRouteMode && currentRoutes.length > 1) {
                const bannerBox = document.createElement('div');
                bannerBox.className = 'p-2.5 bg-cyan-950/80 border border-cyan-500/60 rounded-xl flex items-center justify-between gap-2 mb-2 text-xs text-cyan-200 shadow-lg';
                bannerBox.innerHTML = [
                    '<div class="flex items-center gap-1.5 font-bold">',
                    '    <span>📍</span>',
                    '    <span>Exibindo Apenas Rota Selecionada</span>',
                    '</div>',
                    '<button onclick="showAllRoutes()" class="px-2.5 py-1 bg-cyan-600 hover:bg-cyan-500 text-white font-extrabold rounded-lg text-[11px] shadow transition shrink-0">',
                    '    ⬅️ Ver Toda Fila',
                    '</button>'
                ].join('');
                listEl.insertBefore(bannerBox, listEl.firstChild);
            }

            updateSelectedRideBanner();
        }

        function updateSelectedRideBanner() {
            const activeRoutes = currentRoutes.slice(0, maxRoutes);
            const banner = document.getElementById('selected-ride-banner');
            if (!activeRoutes || activeRoutes.length === 0) {
                if (banner) banner.classList.add('hidden');
                return;
            }
            const target = activeRoutes.find(r => r.id === selectedRideId) || activeRoutes[0];
            selectedRideId = target.id;

            if (banner) {
                banner.classList.remove('hidden');
                const photoEl = document.getElementById('selected-photo');
                if (photoEl) {
                    if (target.passengerPhoto && target.passengerPhoto.trim() !== '') {
                        photoEl.src = target.passengerPhoto;
                        photoEl.style.display = 'block';
                    } else {
                        photoEl.style.display = 'none';
                    }
                }
                const nameEl = document.getElementById('selected-passenger-name');
                if (nameEl) nameEl.textContent = '👤 ' + (target.passenger || 'Passageiro');
                const badgeEl = document.getElementById('selected-sequence-badge');
                if (badgeEl) badgeEl.textContent = 'TOPO DA FILA (PRÓXIMO 1º)';

                const singleBadgeEl = document.getElementById('single-mode-badge');
                if (singleBadgeEl) {
                    if (isSingleRouteMode) {
                        singleBadgeEl.classList.remove('hidden');
                    } else {
                        singleBadgeEl.classList.add('hidden');
                    }
                }

                const infoEl = document.getElementById('selected-route-info');
                if (infoEl) infoEl.textContent = '🟢 ' + (target.pickup || '') + ' ➔ 🔴 ' + (target.dropoff || '');
                const priceEl = document.getElementById('selected-price');
                if (priceEl) priceEl.textContent = 'R$ ' + Number(target.price || 0).toFixed(2);
                const metricsEl = document.getElementById('selected-metrics');
                if (metricsEl) {
                    const epk = target.distanceKm ? (target.price / target.distanceKm) : 0;
                    metricsEl.textContent = (target.distanceKm || 0) + ' km • R$ ' + epk.toFixed(2) + '/km • ★ ' + (target.score || 9.0);
                }
            }
        }

        function selectRide(rideId, isolate = true) {
            selectedRideId = rideId;
            if (isolate) {
                isSingleRouteMode = true;
            }
            renderRideList();
            const activeRoutes = currentRoutes.slice(0, maxRoutes);
            const target = activeRoutes.find(r => r.id === rideId);
            if (target) {
                focusRoute(target);
                drawRoutesOnMap();
            }
        }

        function showAllRoutes() {
            isSingleRouteMode = false;
            renderRideList();
            drawRoutesOnMap();
        }

        function getDetailedRouteWaypoints(pLat, pLng, dLat, dLng) {
            return [[pLat, pLng], [dLat, dLng]];
        }

        function drawRoutesOnMap() {
            routeLayers.forEach(layer => map.removeLayer(layer));
            routeLayers = [];

            const allPoints = [];
            const activeRoutes = currentRoutes.slice(0, maxRoutes);

            let routesToDraw = activeRoutes;
            if (isSingleRouteMode && selectedRideId) {
                const target = activeRoutes.find(r => r.id === selectedRideId);
                if (target) {
                    routesToDraw = [target];
                }
            }

            const sortedForDrawing = [...routesToDraw].reverse();

            sortedForDrawing.forEach((r) => {
                const idx = activeRoutes.indexOf(r);
                const color = ROUTE_COLORS[idx % ROUTE_COLORS.length];
                const isSelected = r.id === selectedRideId;

                const lat1 = Number(r.pLat || 0);
                const lng1 = Number(r.pLng || 0);
                const lat2 = Number(r.dLat || 0);
                const lng2 = Number(r.dLng || 0);
                if (!lat1 || !lng1 || !lat2 || !lng2) return;

                let photoHtml = '';
                if (showPhoto && r.passengerPhoto && r.passengerPhoto.trim() !== '') {
                    photoHtml = \`
                        <div class="avatar-circle" style="border-color: \${color};">
                            <img src="\${r.passengerPhoto}" onerror="this.style.display='none';" />
                        </div>
                    \`;
                } else {
                    photoHtml = \`
                        <div class="w-10 h-10 rounded-full flex items-center justify-center font-bold text-lg border-2 border-white shadow-lg" style="background-color: \${color}; color: #0F172A;">
                            👤
                        </div>
                    \`;
                }

                let nameHtml = '';
                if (showName) {
                    nameHtml = \`<div class="passenger-name-pill" style="border-color: \${color}; color: \${color};">👤 \${r.passenger || 'Passageiro'}</div>\`;
                }

                const pickupHtml = \`<div class="avatar-badge-container \${isSelected ? 'scale-110 z-50' : ''}">\${photoHtml}\${nameHtml}</div>\`;
                const dropoffHtml = \`
                    <div class="w-8 h-8 rounded-full flex items-center justify-center shadow-lg border-2 border-white transform \${isSelected ? 'scale-110' : ''}" style="background-color: \${color};">
                        <span class="text-slate-950 font-black text-xs">🏁</span>
                    </div>
                \`;

                const pickupIcon = L.divIcon({
                    className: '',
                    html: pickupHtml,
                    iconSize: [160, 75],
                    iconAnchor: [80, 22]
                });
                const dropoffIcon = L.divIcon({
                    className: '',
                    html: dropoffHtml,
                    iconSize: [32, 32],
                    iconAnchor: [16, 16]
                });

                const pMarker = L.marker([lat1, lng1], { icon: pickupIcon }).addTo(map)
                    .bindPopup(\`
                        <b>👤 \${r.passenger || 'Passageiro'} (🟢 EMBARQUE)</b><br>
                        <b>R$ \${Number(r.price || 0).toFixed(2)} (\${r.distanceKm || 0} km)</b><br>
                        Partida: \${r.pickup || ''}
                    \`);
                const dMarker = L.marker([lat2, lng2], { icon: dropoffIcon }).addTo(map)
                    .bindPopup(\`
                        <b>🏁 DESTINO • 👤 \${r.passenger || 'Passageiro'}</b><br>
                        <b>R$ \${Number(r.price || 0).toFixed(2)}</b><br>
                        Destino: \${r.dropoff || ''}
                    \`);

                const waypoints = getDetailedRouteWaypoints(lat1, lng1, lat2, lng2);
                const line = L.polyline(waypoints, {
                    color: color,
                    weight: isSelected ? 8 : 6,
                    opacity: isSelected ? 1.0 : 0.85,
                    smoothFactor: 1
                }).addTo(map);

                // Fetch real OSRM route if possible
                fetch(\`https://router.project-osrm.org/route/v1/driving/\${lng1},\${lat1};\${lng2},\${lat2}?overview=full&geometries=geojson\`)
                    .then(res => res.json())
                    .then(data => {
                        if (data.routes && data.routes[0] && data.routes[0].geometry) {
                            const coords = data.routes[0].geometry.coordinates.map(c => [c[1], c[0]]);
                            line.setLatLngs(coords);
                        }
                    })
                    .catch(() => {});

                if (isSelected) {
                    line.bringToFront();
                    pMarker.setZIndexOffset(1000);
                    dMarker.setZIndexOffset(900);
                }

                routeLayers.push(pMarker, dMarker, line);
                allPoints.push([lat1, lng1], [lat2, lng2]);
            });

            if (allPoints.length > 0) {
                const bounds = L.latLngBounds(allPoints);
                map.fitBounds(bounds, { padding: [40, 40] });
            }
        }

        function sortQueue(mode) {
            currentSortMode = mode;
            const btns = ['default', 'price', 'distance', 'score'];
            btns.forEach(b => {
                const el = document.getElementById('sort-btn-' + b);
                if (el) {
                    if (b === mode) {
                        el.className = 'px-2 py-1 rounded bg-cyan-500 text-slate-950 font-bold shrink-0 transition';
                    } else {
                        el.className = 'px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 font-medium shrink-0 transition';
                    }
                }
            });

            applyCurrentSort();

            renderRideList();
            drawRoutesOnMap();
        }

        function focusRoute(r) {
            const lat1 = Number(r.pLat || 0);
            const lng1 = Number(r.pLng || 0);
            const lat2 = Number(r.dLat || 0);
            const lng2 = Number(r.dLng || 0);
            if (!lat1 || !lng1 || !lat2 || !lng2) return;
            map.flyTo([(lat1 + lat2)/2, (lng1 + lng2)/2], 13, {
                duration: 1.0
            });
        }

        function forceRefreshList() {
            const icon = document.getElementById('refresh-icon');
            const text = document.getElementById('refresh-text');
            if (icon) icon.classList.add('animate-spin');
            if (text) text.textContent = 'Sincronizando...';

            fetchRides().finally(() => {
                setTimeout(() => {
                    if (icon) icon.classList.remove('animate-spin');
                    if (text) text.textContent = 'Sincronizar Agora';
                }, 400);
            });
        }

        window.addEventListener('DOMContentLoaded', initMap);
    </script>
</body>
</html>
`;

const server = http.createServer(async (req, res) => {
  const urlPath = req.url?.split('?')[0] || '/';
  const method = req.method || 'GET';

  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, PUT, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Requested-With');

  if (method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (urlPath === '/' || urlPath === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(HTML_CONTENT);
    return;
  }

  if (urlPath === '/api/rides' && method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(capturedRides));
    return;
  }

  if (urlPath === '/api/rides' && method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', async () => {
      try {
        const parsed = JSON.parse(body || '{}');
        let ridesToAdd = [];
        if (Array.isArray(parsed)) {
          ridesToAdd = parsed;
        } else if (parsed && Array.isArray(parsed.rides)) {
          ridesToAdd = parsed.rides;
        } else if (parsed && typeof parsed === 'object') {
          ridesToAdd = [parsed];
        }

        const validNewRides = [];
        for (const ride of ridesToAdd) {
          const pickup = (ride.pickup || ride.pickupAddress || ride.pickup_address || '').trim();
          const dropoff = (ride.dropoff || ride.dropoffAddress || ride.dropoff_address || '').trim();
          if (pickup.length < 6 || dropoff.length < 6) continue;
          
          const invalidKeywords = ['origem', 'destino', 'não capturado', 'desconhecido', 'simulado', 'fake', 'incompleto', 'endereço', 'não informado', 'em análise'];
          const pLow = pickup.toLowerCase();
          const dLow = dropoff.toLowerCase();
          let isInvalid = false;
          for (const kw of invalidKeywords) {
            if (pLow === kw || dLow === kw || pLow.includes('em análise') || dLow.includes('em análise') || pLow.includes('não capturado') || dLow.includes('não capturado')) {
              isInvalid = true;
              break;
            }
          }
          if (isInvalid) continue;

          ride.pickup = pickup;
          ride.dropoff = dropoff;
          ride.price = Number(ride.price || ride.price_brl || 0);
          ride.passenger = ride.passenger || 'Passageiro';
          ride.passengerPhoto = ride.passengerPhoto || ride.passenger_photo || '';
          ride.distanceKm = Number(ride.distanceKm || ride.total_distance_km || 0);
          ride.score = Number(ride.score || 9.0);
          ride.timeMin = Number(ride.timeMin || ride.estimated_time_min || 10);

          const geocode = async (addr) => {
            if (!addr) return { lat: null, lon: null };
            try {
              const resp = await fetch(`https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(addr)}&format=json&limit=1`, {
                headers: { 'User-Agent': 'inDrive-Analyzer-App-v2' }
              });
              const data = await resp.json();
              if (data && data[0]) return { lat: parseFloat(data[0].lat), lon: parseFloat(data[0].lon) };
            } catch (e) { console.error("Geocode error:", e); }
            return { lat: null, lon: null };
          };

          if (!ride.pLat || !ride.pLng || !ride.dLat || !ride.dLng) {
            const p = await geocode(ride.pickup);
            const d = await geocode(ride.dropoff);
            if (p.lat && p.lon) { ride.pLat = p.lat; ride.pLng = p.lon; }
            if (d.lat && d.lon) { ride.dLat = d.lat; ride.dLng = d.lon; }
          }

          const pLat = Number(ride.pLat || ride.lat || 0);
          const pLng = Number(ride.pLng || ride.lon || 0);
          const dLat = Number(ride.dLat || 0);
          const dLng = Number(ride.dLng || 0);
          if (pLat !== 0 && pLng !== 0 && dLat !== 0 && dLng !== 0) {
            ride.pLat = pLat;
            ride.pLng = pLng;
            ride.dLat = dLat;
            ride.dLng = dLng;
            validNewRides.push(ride);
          }
        }

        capturedRides = [...validNewRides, ...capturedRides].slice(0, 50); // Keep latest 50
        
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ status: 'success', count: capturedRides.length }));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'Invalid JSON' }));
      }
    });
    return;
  }

  if (urlPath === '/api/rides/clear' && (method === 'POST' || method === 'DELETE')) {
    capturedRides = [];
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ status: 'success', count: 0 }));
    return;
  }

  if (urlPath.startsWith('/api/rides/') && method === 'DELETE') {
    const idStr = urlPath.split('/')[3];
    const rideId = Number(idStr);
    capturedRides = capturedRides.filter(r => r.id !== rideId);
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ status: 'success', count: capturedRides.length }));
    return;
  }

  if (urlPath === '/apk/app-debug.apk' || urlPath === '/app-debug.apk' || urlPath === '/download') {
    const apkPath = getApkPath();
    if (apkPath && fs.existsSync(apkPath)) {
      const stat = fs.statSync(apkPath);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="inDrive-Analyzer-debug.apk"',
        'Content-Length': stat.size
      });
      const readStream = fs.createReadStream(apkPath);
      readStream.pipe(res);
      return;
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('APK ainda não gerado. Verifique o build Android via Gradle.');
      return;
    }
  }

  if (urlPath === '/api/github/status' && method === 'GET') {
    const repo = process.env.GITHUB_REPO || 'AmaroPSJunior/AppLgOs';
    const token = process.env.GITHUB_TOKEN || '';
    const tokenFormatted = token ? (token.substring(0, 6) + '...' + token.substring(token.length - 4)) : 'Não informado';

    if (!token) {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ configured: false, repo, tokenFormatted, status: 'Token não configurado' }));
      return;
    }

    try {
      const apiRes = await fetch(`https://api.github.com/repos/${repo}`, {
        headers: {
          'Authorization': `token ${token}`,
          'User-Agent': 'inDrive-Analyzer-App',
          'Accept': 'application/vnd.github+json'
        }
      });
      if (apiRes.ok) {
        const repoData = await apiRes.json();
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({
          configured: true,
          valid: true,
          repo,
          tokenFormatted,
          owner: repoData.owner?.login,
          private: repoData.private,
          defaultBranch: repoData.default_branch,
          url: repoData.html_url
        }));
      } else {
        const errData = await apiRes.json().catch(() => ({}));
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({
          configured: true,
          valid: false,
          repo,
          tokenFormatted,
          error: errData.message || `Erro HTTP ${apiRes.status}`
        }));
      }
    } catch (err) {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ configured: true, valid: false, repo, tokenFormatted, error: err.message }));
    }
    return;
  }

  if (urlPath === '/api/github/config' && method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        const data = JSON.parse(body || '{}');
        if (data.repo) process.env.GITHUB_REPO = data.repo.trim();
        if (data.token) process.env.GITHUB_TOKEN = data.token.trim();
        
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ status: 'success', repo: process.env.GITHUB_REPO }));
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  if (urlPath === '/api/github/commit' && method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', async () => {
      try {
        const { exec } = await import('child_process');
        const util = await import('util');
        const execAsync = util.promisify(exec);

        const parsed = JSON.parse(body || '{}');
        const msg = (parsed.message || 'feat: atualizações do projeto inDrive Analyzer').replace(/"/g, '\\"');
        const repo = process.env.GITHUB_REPO || 'AmaroPSJunior/AppLgOs';
        const token = process.env.GITHUB_TOKEN || '';

        if (!token) {
          res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: 'GITHUB_TOKEN não configurado.' }));
          return;
        }

        const remoteUrl = `https://x-access-token:${token}@github.com/${repo}.git`;
        let logOutput = [];
        
        try {
          await execAsync('git init', { cwd: process.cwd() });
          await execAsync('git config user.email "arcamos.j@gmail.com"', { cwd: process.cwd() });
          await execAsync('git config user.name "arcamos"', { cwd: process.cwd() });
          await execAsync('git add .', { cwd: process.cwd() });
          
          try {
            const { stdout: commitOut } = await execAsync(`git commit -m "${msg}"`, { cwd: process.cwd() });
            logOutput.push(`Commit: ${commitOut}`);
          } catch (cErr) {
            logOutput.push(`Commit: ${cErr.stdout || cErr.message}`);
          }

          await execAsync(`git remote add origin "${remoteUrl}" || git remote set-url origin "${remoteUrl}"`, { cwd: process.cwd() });
          await execAsync('git branch -M main', { cwd: process.cwd() });
          const { stdout: pushOut } = await execAsync('git push -u origin main', { cwd: process.cwd() });
          logOutput.push(`Push: ${pushOut}`);

          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ status: 'success', message: 'Alterações enviadas e sincronizadas com sucesso no GitHub!', details: logOutput.join('\n') }));
        } catch (cmdErr) {
          res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: cmdErr.stderr || cmdErr.stdout || cmdErr.message }));
        }
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  if (urlPath === '/api/github/invite' && method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', async () => {
      try {
        const parsed = JSON.parse(body || '{}');
        const targetUser = (parsed.username || '').trim();
        const repo = process.env.GITHUB_REPO || 'AmaroPSJunior/AppLgOs';
        const token = process.env.GITHUB_TOKEN || '';

        if (!targetUser) {
          res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: 'Nome de usuário do GitHub é obrigatório.' }));
          return;
        }

        if (!token) {
          res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: 'GITHUB_TOKEN não configurado.' }));
          return;
        }

        const inviteRes = await fetch(`https://api.github.com/repos/${repo}/collaborators/${targetUser}`, {
          method: 'PUT',
          headers: {
            'Authorization': `token ${token}`,
            'Accept': 'application/vnd.github+json',
            'User-Agent': 'inDrive-Analyzer-App',
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ permission: 'push' })
        });

        if (inviteRes.ok || inviteRes.status === 201 || inviteRes.status === 204) {
          const invData = await inviteRes.json().catch(() => ({}));
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({
            status: 'success',
            message: `Convite enviado com sucesso para @${targetUser}!`,
            details: invData.html_url ? `Link do convite: ${invData.html_url}` : 'Convite pendente de aceitação pelo usuário.'
          }));
        } else {
          const errData = await inviteRes.json().catch(() => ({}));
          res.writeHead(inviteRes.status, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({
            error: errData.message || `Erro ao enviar convite (HTTP ${inviteRes.status})`
          }));
        }
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  if (urlPath === '/api/status') {
    const apkPath = getApkPath();
    const apkExists = apkPath ? fs.existsSync(apkPath) : false;
    const sizeBytes = apkExists && apkPath ? fs.statSync(apkPath).size : 0;
    const responsePayload = {
      status: 'ready',
      appName: 'inDrive Analyzer (Captura Direta e Exclusiva)',
      apk: {
        exists: apkExists,
        path: '/apk/app-debug.apk',
        sizeBytes: sizeBytes,
        sizeMB: +(sizeBytes / (1024 * 1024)).toFixed(2)
      },
      capturedRidesCount: capturedRides.length
    };
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(responsePayload, null, 2));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('404 - Not Found');
});

let retryCount = 0;
const MAX_RETRIES = 15;

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    retryCount++;
    console.error(`⚠️ Port ${PORT} is already in use (attempt ${retryCount}/${MAX_RETRIES}). Retrying in 1s...`);
    if (retryCount <= MAX_RETRIES) {
      setTimeout(() => {
        try {
          server.close();
        } catch (e) {}
        server.listen(PORT, '0.0.0.0');
      }, 1000);
    } else {
      console.error(`❌ Could not bind to port ${PORT} after ${MAX_RETRIES} attempts.`);
    }
  } else {
    console.error('Server error:', err);
  }
});

process.on('uncaughtException', (err) => {
  console.error('Uncaught Exception:', err);
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

process.on('SIGTERM', () => {
  console.log('Received SIGTERM, shutting down server cleanly...');
  server.close(() => {
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 1500);
});

process.on('SIGINT', () => {
  console.log('Received SIGINT, shutting down server cleanly...');
  server.close(() => {
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 1500);
});

server.listen(PORT, '0.0.0.0', () => {
  retryCount = 0;
  console.log(`✅ inDrive Analyzer preview server listening on http://0.0.0.0:${PORT}`);
});
