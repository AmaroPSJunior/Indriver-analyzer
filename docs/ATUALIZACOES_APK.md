# Atualizações dos APKs de teste

O applicationId permanece com.uberanalyzer. Debug e release usam o mesmo
debug.keystore. O build nunca gera outra chave automaticamente.

## Configuração única

Em Settings > Secrets and variables > Actions, crie o repository secret
ANDROID_TEST_KEYSTORE_BASE64 com o conteúdo Base64 da chave permanente de testes.
A chave deve ter alias androiddebugkey e senhas de armazenamento/chave android.
Guarde um backup privado; não publique o keystore nem seu Base64 no Git.

Se você tem a chave usada no APK instalado, reutilize-a. Se a chave antiga foi
gerada em um runner descartável e não foi guardada, ela não pode ser recuperada
do APK: será necessária uma última reinstalação para adotar a chave permanente.
Não desinstale antes de confirmar essa incompatibilidade.

Depois de configurar o secret, execute Build Android APK em Actions.
O workflow para com erro se o segredo estiver ausente ou inválido, sem publicar
um APK com uma chave diferente. Ele valida certificado, applicationId e
versionCode do APK antes de disponibilizá-lo.

## Atualizações seguintes

Em Configurações, toque em **⬇️ Atualizar aplicativo**. O app consulta a release
estável mais recente deste repositório, compara o número da versão instalada e,
se houver uma versão maior, baixa o APK e abre o instalador do Android.
Autorize a instalação por este aplicativo quando solicitado e confirme Atualizar.
A confirmação do Android é obrigatória; não há instalação silenciosa.

O download continua ao sair da tela. Reabra o botão para retomar o acompanhamento
e abrir o instalador quando terminar. É possível cancelar e tentar novamente.
O app verifica tamanho, SHA-256 (quando publicado pelo GitHub), identidade,
versionCode e assinatura do APK antes de solicitar a instalação. APKs incompatíveis
não são instalados. O atualizador usa o formato de release `v1.0.<versionCode>`
publicado pelo workflow; ao mudar esse formato, atualize também ReleasePolicy.

Baixe o APK da versão mais recente e abra-o no celular; confirme Atualizar.
Não desinstale nem limpe os dados. Com ADB: adb install -r caminho/do/app.apk.
Dados e permissões normalmente são preservados; permissões novas e acessos
especiais podem exigir confirmação do Android.

O versionCode usa run_number * 100 + run_attempt. Novas execuções têm números
maiores; uma repetição da mesma execução também incrementa o número.
Não repita execuções antigas para distribuir versões: use Run workflow na main.
Não recrie/renomeie este workflow nem troque o secret de assinatura durante
a sequência de versões instaladas sem planejar a compatibilidade.

## Build local

Restaure a mesma chave em debug.keystore na raiz, que está no .gitignore.
Use -PbuildNumber com um número maior que o instalado para gerar uma atualização.
Os testes unitários não precisam da chave; empacotar um APK assinado exige a chave.
