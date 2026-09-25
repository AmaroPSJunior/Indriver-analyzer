# Parser espacial estrito (experimental)

O parser atual permanece intacto. `StrictSpatialRideParser` recebe linhas com caixas,
limites de cards e regiões relativas (0 a 1). O resultado usa campos ausentes (`null`
nos acessores numéricos), sem os valores padrão de `InDriverRide`. Não há conversão
automática para a fila, mapa, geocodificação ou aceitação de corridas.

## Regras

- Uma caixa precisa caber inteiramente no card e na região do campo.
- Duas linhas que intersectam uma região tornam o campo ambíguo, mesmo se só uma
  tiver conteúdo válido. Endereços quebrados em várias linhas ficam vazios nesta versão.
- Cards sobrepostos ou parcialmente fora da área visível são rejeitados por inteiro.
- Nome, avaliação, preço, distâncias e endereços passam por validações específicas.
  Endereços sem prefixo reconhecido são omitidos. Isso favorece precisão sobre cobertura.
- Não há preenchimento por fallback, valor padrão, estimativa ou memória de outra captura.
- Cada campo aceito guarda o índice e a caixa da linha original para auditoria.
- A ordem da saída segue a posição vertical dos cards, independentemente da ordem do OCR.

## Detecção e calibração

O detector opcional usa preços isolados como âncoras, uma proporção altura/largura
medida e a posição relativa da região de preço. Não usa a próxima linha ou o próximo
preço para estender os limites de um card. Preços ausentes não criam cards; âncoras
duplicadas produzem sobreposição e são rejeitadas pelo parser.

**Não existe perfil de produção habilitado.** A fixture nos testes é sintética.
Este detector só serve para um layout de altura fixa previamente validado. Cards
com altura variável exigem limites confirmados por outro detector, passados a `parse`.
Um perfil errado pode atribuir posições incorretas: a geometria sozinha não prova
que um limite corresponde a um card real, nem que o OCR leu o texto corretamente.

Para calibrar, recolha capturas do mesmo dispositivo com lista parada, rolada,
primeiro/último card cortado, endereços longos, fonte ampliada e tela dividida.
Anote os limites reais e o conteúdo esperado por card; calcule as regiões relativas
a partir desses limites, sem comprimir um card cortado para a área visível.
Não habilite o perfil em tamanhos de janela ou layouts para os quais não foi validado.

## Comparação em debug

Em um teste de depuração, chame `SpatialParserTrial.configure(Configuration(profile,
normalizedListViewport))` com o perfil medido e a área normalizada da lista (excluindo
barra do sistema e overlays). `configure(null)` desliga e limpa o resultado.
Essa API é programática; não há novo botão nas configurações do usuário.

O serviço executa a comparação após o parser espacial legado, somente em builds
debuggable. `latestReport` expõe o resultado estrito e a contagem de corridas do
parser legado no mesmo frame. Logcat, tag `SpatialParserTrial`, registra apenas
contagens, sem nomes nem endereços. O relatório é volátil, não é persistido e não
altera o comportamento do parser atual. A contagem estrita inclui cards com campos
ausentes; não significa corridas completas. A comparação não mede acurácia sozinha.

## Validação

Execute `./gradlew testDebugUnitTest` em ambiente com JDK 17 e Android SDK 34.
Os testes JUnit do núcleo não usam classes Android e também podem ser compilados
com Kotlin/JVM 1.9.24 e executados diretamente com JUnit 4.13.2.

A suíte cobre isolamento de três cards, entrada fora de ordem, dados faltantes,
unidades, conteúdo incorreto, linhas conflitantes, limites cruzados, sobreposição,
cards cortados, caixas inválidas, escala, translação, âncoras e modo opt-in.

Antes de promover o parser, compare cada campo com capturas anotadas manualmente:
conte contaminações entre cards, valores sem evidência, erros de leitura do OCR e
cobertura por campo separadamente. Zero contaminações e zero valores inventados
são critérios de aceitação do conjunto validado, não uma garantia geral baseada
apenas nos testes sintéticos.
