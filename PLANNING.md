# Flower Disease — Planejamento e Arquitetura

Este documento existe pra qualquer pessoa (ou IA) conseguir continuar o desenvolvimento do mod
sem precisar reconstruir o contexto do zero. Ele descreve o que já existe, por quê, e o que está
planejado a seguir. Sempre que uma decisão de arquitetura relevante for tomada, atualize este arquivo.

## Visão geral do mod

Minecraft 1.21.1, NeoForge `21.1.250`. A ideia central: uma "Diseased Flower" é uma versão doente de
cada flor do vanilla que, plantada, se espalha lentamente pelo terreno ao redor com o tempo, criando um
campo florido de forma orgânica. A cada salto, o FILHO nascido pode ser sorteado entre várias espécies
(configurável pela bag, ver "Sem lógica de cadeia" abaixo). Quando uma planta não consegue mais se
reproduzir (lotação ou terreno sem espaço), ela se estabiliza virando uma flor normal — sempre ELA MESMA,
nunca um sorteio (ver "Sistema de settle" abaixo).

Objetivo maior do jogador (dono do projeto): permitir tanto um "jardim de bolso" controlado (plantado via
um item — a "Garden Bag", **implementada**, ver seção própria mais abaixo) quanto, em tese, um
espalhamento sem limite pelo mundo inteiro caso o jogador queira (plantando a flor na mão, sem a bag) —
isso deve continuar possível mesmo que ninguém realisticamente infecte o mundo inteiro de propósito.

Não-objetivo por enquanto (mas desejado no futuro): flores corrompendo blocos e subindo em árvores.
Isso fica pra depois, não mexer nisso agora.

## Estado atual — o que já está implementado

### Mecânica de espalhamento (núcleo)

- As flores usam o **random tick nativo do Minecraft** (`isRandomlyTicking`/`randomTick`), não um
  scheduler customizado. Isso é o que dá o espalhamento "de graça" em termos de performance: o motor já
  amostra só ~3 blocos por subchunk por tick, então a maioria das flores não custa nada na maior parte
  do tempo.
- Em cima disso, `Config.FLOWER_SPREAD_CHANCE` é um filtro extra de probabilidade que controla o RITMO
  real do espalhamento (o random tick sozinho já dispara ~15-20x/dia por bloco, rápido demais pro efeito
  "many days to fill a field" desejado).
- Ao tentar se espalhar, a flor sorteia posições próximas (`Config.FLOWER_SPREAD_DISTANCE`) e usa
  `BlockState#canSurvive` pra validar o local (mesma checagem que o jogo usa ao plantar manualmente).
  Também tenta alturas próximas (`Config.FLOWER_SPREAD_VERTICAL_RANGE`) pra acompanhar ladeiras/degraus
  de terreno, não só chão perfeitamente plano.
- Antes de tentar espalhar, conta quantas flores da MESMA espécie (ver `SettleTable.isSameSpecies`)
  existem num raio (`Config.FLOWER_DENSITY_RADIUS`); se já atingiu `Config.FLOWER_MAX_NEARBY`, não tenta
  espalhar — vai direto pro "settle" (assentar/virar outra coisa).
- **Regra importante**: qualquer tentativa de random tick que NÃO resulte em um novo espalhamento bem
  sucedido (seja por lotação, seja por terreno sem espaço) faz a flor **assentar imediatamente** (virar
  outra coisa, ver Settle abaixo). Isso evita o bug histórico de flores presas para sempre em cavernas
  íngremes sem nunca desistir (ver "Bugs corrigidos" abaixo).
- **Contador de gerações**: cada Diseased Flower carrega um valor de "gerações restantes"
  (`SettleTable.GENERATION`, um `IntegerProperty` de blockstate, 0-64 — usado quando NÃO há um
  `SpreadProfileBlockEntity` com override ativo, ver abaixo). Uma flor plantada na mão começa com
  `Config.FLOWER_MAX_GENERATIONS`; cada filho nasce com `pai - 1`. Quando o valor calculado pro filho
  seria 0, o filho nasce **direto já como flor assentada** (nunca chega a existir como Diseased Flower
  ativa). Isso limita o alcance máximo de uma plantação sem usar um raio fixo (que ficaria redondo
  demais) — o contorno fica irregular porque cada salto é uma posição aleatória, não um raio geométrico.
- **Perfil de espalhamento por planta** (`SpreadProfileBlockEntity`) — fundação da Garden Bag: TODA
  Diseased Flower (inclusive plantada na mão) tem um `BlockEntity` leve e opcional. Por padrão ele não
  tem nenhum override (`hasOverride() == false`) e o comportamento é idêntico ao de antes, lendo tudo do
  `Config.java`/blockstate. Quando algo popula esse BlockEntity (hoje, só o comando de debug
  `/diseasedflower profile set`; no futuro, a Garden Bag), a flor passa a usar os valores dele em vez do
  config global para: gerações restantes (sem teto, `-1` = infinito), `spreadChance`, `spreadDistance`,
  densidade (como "flores desejadas por 16x16", convertida internamente via
  `SettleTable.densityTargetToMaxNearby`), e uma lista de "espécies possíveis" pro filho (reaproveita o
  parser/sorteio do `SettleTable`) — sem nenhuma restrição de categoria, ver "Sem lógica de cadeia" logo
  abaixo. Ao espalhar com um perfil ativo, o filho recebe uma CÓPIA do
  perfil do pai (via `SpreadProfileBlockEntity#copyFrom`), com o contador de gerações já decrementado —
  é assim que a herança pelos filhos funciona.
- **A lista de espécies do perfil (`speciesWeights`) é a ÚNICA fonte de "o que um FILHO pode ser"** ao
  espalhar. Não existe mais nenhuma tabela global de settle no `Config.java` (ver "Sistema de settle"
  abaixo pro histórico de por que ela foi removida).
- **Sem "lógica de cadeia" ao ESPALHAR: cada filho é um sorteio independente sobre o pool inteiro da
  bag, sem herdar a espécie do pai.** Isso passou por várias rodadas de correção — as duas primeiras (Bug
  1 e Bug 2, histórico abaixo) foram legítimas, mas a correção do Bug 2 introduziu um conceito de
  "família" que restringia até o SORTEIO DE FILHOS à mesma espécie que o pai, o que o dono do projeto
  **não queria**: uma bag com Poppy + Rose Bush deve poder produzir um filho Rose Bush a partir de uma
  Poppy (e vice-versa) — cada nascimento sorteia livremente entre as espécies configuradas na bag.
  1. **Bug 1**: plantar só Poppy pela bag também gerava Fern, Tall Grass, Rose Bush etc. Causa raiz: o
     settle colocava o próprio BLOCO DOENTE escolhido da lista de espécies como "resultado final", em vez
     do bloco vanilla. Um `DiseasedFlowerBlock` recém-colocado por `SettleTable.place` nasce com
     `GENERATION = 0` (valor padrão do blockstate) e um `SpreadProfileBlockEntity` zerado (sem o perfil da
     bag) — ou seja, o "settle" na real replantava uma flor doente "solta", sem dono, que no PRÓXIMO
     random tick assentava de novo usando a settleWeights GLOBAL da espécie (que ainda existia no
     `Config.java` na época), o que parecia um "flicker" entre espécies aleatórias.
  2. **Bug 2** (mesma família de sintoma, causa diferente, corrigido DEPOIS que o Bug 1 já tinha sido
     resolvido e a settleWeights global removida): numa bag com Lilac + Peony juntos, uma planta nascia
     como Lilac mas eventualmente assentava como Peony, o que na época pareceu um bug de "vazamento" entre
     sorteios. A correção aplicada então foi travar cada planta na sua própria família — inclusive o
     sorteio de QUAL FILHO nasce ao espalhar, não só o destino de settle — impedindo justamente esse tipo
     de troca. Essa restrição **overcorrigiu**: o dono do projeto esclareceu depois que uma Poppy nascer
     filha de uma Rose Bush plantada pela mesma bag não é um bug, é o comportamento desejado, desde que a
     espécie esteja configurada na bag. `SettleTable.familyOptions` foi removida.
  **Estado atual do ESPALHAMENTO**: `DiseasedPlantLogic` (engine única de espalhamento/settle, ver
  "Arquivos principais") faz um sorteio ponderado NOVO e independente sobre o pool inteiro
  (`speciesWeights`) toda vez que um FILHO nasce ao espalhar — nunca restrito por "família"/espécie do
  pai, e nunca restrito por categoria/formato (uma Poppy pode gerar um filho Rose Bush de 2 blocos, e
  vice-versa; a busca de posição, `findSpreadTarget`, se adapta ao formato — `Shape` `SINGLE`/`TALL` — da
  espécie sorteada: 1 célula livre, ou 2 células livres).
- **Settle é DIFERENTE de nascer: uma planta que para de se espalhar sempre vira ELA MESMA, nunca um
  sorteio novo.** Isso foi corrigido depois de testes em jogo: uma primeira versão desta correção também
  fez o SETTLE sortear livremente sobre o pool inteiro (pelo mesmo raciocínio "sem lógica de cadeia" do
  espalhamento) — só que o dono do projeto testou e reportou que isso estava errado: "quando uma planta
  para de se reproduzir ela ainda está se transformando em outra planta aleatória da bag ao invés de
  permanecer do mesmo tipo que ela é". A distinção que importa: nascer um FILHO é um evento novo (sorteio
  livre, ver acima); **assentar não é um nascimento**, é a MESMA planta se estabilizando — ela sempre vira
  sua própria `fallbackBlock` (a versão vanilla/terminal dela mesma), sem nenhum sorteio, sem olhar pro
  pool da bag. `DiseasedPlantLogic#settle` reflete isso: recebe direto o bloco vanilla a virar (o
  `fallbackBlock` da planta, ou — quando um filho recém-sorteado já nasce sem orçamento de gerações — a
  própria espécie que acabou de ser sorteada pra ele), nunca um pool pra escolher entre várias opções.
- **Modo "territorial"** (`SpreadProfileBlockEntity#respectAllSpecies`, opt-in, só pela bag): por padrão a
  checagem de lotação (`countNearbyFieldFlowers`) só conta vizinhos da MESMA espécie/família
  (`SettleTable.isSameSpecies`). Com o modo territorial ligado, ela conta QUALQUER planta por perto
  (`SettleTable.isAnyPlant`, checa `instanceof BushBlock`, ignorando a metade de cima de plantas de 2
  blocos pra não contar em dobro) — assim um jardim de rosas não invade os buracos de um jardim de peonias
  já plantado ao lado. Ativado pela bag colocando qualquer Fence (`ItemTags.FENCES`) no slot dedicado.
  **Ajuste em relação ao plano original**: a ideia era "flor plantada na mão não tem BlockEntity nenhum".
  Na prática isso exigiria duplicar cada bloco/recurso em duas versões (com e sem dono), então a decisão
  foi dar o BlockEntity pra TODAS, sempre "vazio" por padrão pra plantio na mão. O custo é pequeno (é só
  alguns números por flor, sem ticker, nada roda nele sozinho) e evita duplicar os ~90 arquivos de
  recurso que já existem.

### Espécies existentes

13 flores pequenas (`DiseasedFlowerBlock`, um bloco só): Dandelion, Poppy, Blue Orchid, Allium,
Azure Bluet, Red/Orange/White/Pink Tulip, Oxeye Daisy, Cornflower, Lily of the Valley, **Wither Rose**
(`DiseasedWitherRoseBlock`, uma classe separada que estende `WitherRoseBlock` em vez de `FlowerBlock`
pra manter o dano de Wither ao encostar e a regra extra de poder crescer em netherrack/soul sand/soul
soil — mas usa exatamente a mesma lógica de espalhamento/settle que as outras, via `DiseasedPlantLogic`).

4 flores altas de 2 blocos (`DiseasedTallFlowerBlock`): Sunflower, Lilac, Rose Bush, Peony. Só a metade
de baixo (`HALF=LOWER`) age no random tick — a de cima é ignorada pra não duplicar a taxa de
espalhamento. Ao espalhar, usa `DoublePlantBlock.placeAt` pra colocar as duas metades corretamente.

`DiseasedPlantLogic.java` é onde mora TODA a lógica de espalhamento/settle, compartilhada por TODAS as
espécies (1 e 2 blocos) — ver "Arquivos principais" abaixo. Um único parâmetro `Shape` (`SINGLE`/`TALL`)
diferencia o comportamento espacial necessário (quantas células livres verificar, `setBlock` vs
`DoublePlantBlock.placeAt`); fora isso o sorteio de espécie é idêntico para os dois formatos e nunca
restrito por formato — um filho sorteado pode ter um formato diferente do pai, ver "Sem lógica de cadeia"
acima. Extraída como utilitário estático porque as classes de bloco em si não podem compartilhar uma
superclasse Java comum (`FlowerBlock`, `WitherRoseBlock`, `TallGrassBlock`, `DeadBushBlock`,
`TallFlowerBlock`, `DoublePlantBlock` são todas raízes vanilla diferentes).

12 blocos decorativos de bloco único (`DecorativeFlowerBlock`): um Top e um Bottom pra cada uma das 6
espécies de 2 blocos (Sunflower, Lilac, Rose Bush, Peony, Tall Grass, Large Fern). Cada um tem seu próprio
item e é uma opção SEPARADA e independente na grade de espécies da bag (ver "Garden Bag" abaixo) — colocar
"Rose Bush Top" na grade não afeta a espécie completa "Rose Bush" nem o "Rose Bush Bottom", cada um tem seu
próprio peso. Por si só o `DecorativeFlowerBlock` não se espalha (não tem random tick nem
`SpreadProfileBlockEntity`) — é sempre o resultado TERMINAL de uma planta que assentou.

**Top/Bottom são espécies independentes, não uma variação da planta completa** (pedido explícito do dono
do projeto: "eu quero que as versões top e bottom ajam como flores novas adicionadas pelo mod... elas não
são consideradas uma variação da full mas sim uma flor nova individual"). Cada um dos 12 decorativos tem
uma contraparte que se espalha, `DiseasedDecorativeFlowerBlock` (12 blocos, ex. `DISEASED_ROSE_BUSH_TOP`)
— mesmo formato/textura do decorativo (reaproveita `DecorativeFlowerBlock.SHAPE`), mas com random tick,
`GENERATION`, `SpreadProfileBlockEntity` e delega pra `DiseasedPlantLogic.randomTickSingle` igual a
qualquer outra espécie de 1 bloco. Registrado em `FlowerDisease.diseasedByFallback()` com o decorativo
como `fallbackBlock` (ex. `ROSE_BUSH_TOP.get() -> DISEASED_ROSE_BUSH_TOP`) — como essa é a MESMA
infraestrutura genérica usada por todas as espécies, uma bag com só "Rose Bush Top" plantado já funciona
como raiz, espalha filhos "Rose Bush Top" (sorteados livremente do pool como qualquer outro filho, ver
acima), e assenta de volta em "Rose Bush Top" — sem nenhuma mudança de código em `GardenBagItem`/
`SettleTable`/`DiseasedPlantLogic`, só de registro em `FlowerDisease.java`.

Grama/textura adicional, pra dar mais variedade visual às plantações (pedido do dono do projeto, não são
"flores" no sentido de densidade cruzada com as de cima por padrão — cada família só compete com ela
mesma, a não ser no modo territorial):

- 3 de bloco único: Short Grass (`DiseasedGrassBlock extends TallGrassBlock`, delega pra
  `DiseasedPlantLogic`), Fern (mesma classe `DiseasedGrassBlock` — vanilla usa `TallGrassBlock` pras
  duas), Dead Bush (`DiseasedDeadBushBlock extends DeadBushBlock`, delega pra `DiseasedPlantLogic`).
- 2 de dois blocos: Tall Grass, Large Fern (mesma classe `DiseasedTallGrassBlock extends DoublePlantBlock`
  — delega pra `DiseasedPlantLogic`, mesma engine da grama pequena e das flores).

Tintura de bioma (verde da grama): as classes novas não herdam o registro `BlockColors`/`ItemColors` do
vanilla (é vinculado à instância exata do `Block` vanilla, não à classe). Registrado manualmente em
`FlowerDiseaseClient#onRegisterBlockColors`/`onRegisterItemColors`, espelhando exatamente o que o vanilla
faz pra `SHORT_GRASS`/`FERN`/`TALL_GRASS`/`LARGE_FERN` (`BiomeColors.getAverageGrassColor` pro bloco no
mundo, `GrassColor.getDefaultColor()`/`.get(0.5, 1.0)` pro ícone do item). Os modelos usam
`"parent": "minecraft:block/tinted_cross"` (não `cross` puro) pra habilitar a tintura; Dead Bush usa
`cross` puro porque não é tingido no vanilla também.

### Sistema de "settle" (`SettleTable.java`)

**Mudança de arquitetura importante**: originalmente cada espécie tinha sua própria tabela de settle no
`Config.java` (seção `[settleWeights]`, 22 listas). O dono do projeto esclareceu que isso era só pra testar
a MECÂNICA de settle isoladamente, e que a configuração de verdade deveria vir inteiramente da Garden Bag.
Essa seção do `Config.java` foi **removida** (junto com `validateSettleEntry`/o helper `settleWeights(...)`).

**Settle não usa mais NENHUMA tabela, nem a da bag** (ver "Sem lógica de cadeia" mais acima pro porquê:
assentar não é um sorteio, é a planta virando ela mesma). `SpreadProfileBlockEntity#speciesWeights` (escrito
pela bag ou pelo comando de debug) continua existindo, mas hoje serve só pra UMA coisa: o pool de onde um
FILHO é sorteado ao espalhar. O formato de cada entrada é `"<block id> <peso>"` — sem sufixo de metade
(`full`/`lower`/`upper` foi removido junto com `SettleTable.place`/`placeTop`/`placeBottom`, que ficaram
sem uso nenhum depois que settle parou de consultar essa lista). Cada entrada nomeia o bloco final exato
que representa: o vanilla pra uma espécie de 2 blocos completa, ou um dos blocos decorativos Top/Bottom
— que hoje são espécies próprias e independentes (ver "Espécies existentes" acima), não mais uma notação
de "metade" de outra espécie.

`FlowerDisease.diseasedByFallback()` (método lazy, `Map<Block vanilla-ou-decorativo, DeferredBlock<? extends
Block> diseased>`) é o mapa reverso usado pra saber, a partir de um bloco "espécie completa" citado no
pool, qual bloco Doente correspondente plantar como filho (ou como raiz da bag) — hoje TODAS as 34 espécies
possíveis da grade (22 normais + 12 Top/Bottom) têm uma entrada aqui, então todas são igualmente
espalháveis e plantáveis como raiz. É `Map.ofEntries(...)` construído só na primeira chamada (não um campo
`static final` comum), pelo mesmo motivo do `bagOutcomeItems()` logo abaixo: as chaves dos 12 Top/Bottom
exigem `.get()` em blocos do próprio mod, que só é seguro depois que o registro já rodou.

### Bugs corrigidos (histórico, não repetir)

1. **Fundo branco/transparência**: blocos modados não herdam layer de renderização automaticamente.
   Corrigido adicionando `"render_type": "minecraft:cutout"` nos modelos de bloco (campo específico do
   NeoForge, lido por `ExtendedBlockModelDeserializer`).
2. **Não acompanhava desníveis de terreno**: o espalhamento só tentava a mesma altura Y do pai. Corrigido
   com busca em alturas próximas (`followTerrain`/`spreadVerticalRange`).
3. **Flor presa pra sempre em caverna íngreme**: a regra antiga só assentava quando "lotado", nunca
   quando "sem espaço válido" — em terreno muito restrito isso nunca virava lotação, e a flor ficava
   tentando pra sempre. Corrigido: qualquer falha em achar posição (lotação OU terreno) assenta.
4. **Drop de item + metade de cima sumindo ao nascer/assentar**: causa raiz era o uso da flag
   `Block.UPDATE_CLIENTS` sozinha nos `setBlock` internos — ela não é suficiente pra suprimir a
   revalidação reativa de `canSurvive` nos vizinhos (isso é controlado por `Block.UPDATE_KNOWN_SHAPE`,
   uma flag separada). Ao limpar a metade de cima antiga durante um "settle", isso disparava uma
   revalidação na metade de baixo, que falhava e virava uma destruição de verdade (com drop). Corrigido
   usando `SettleTable.PLACEMENT_FLAGS = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS` em
   TODOS os `setBlock`/`placeAt` internos do mod. **Qualquer código novo que coloque ou remova blocos do
   mod deve usar essa constante, nunca `Block.UPDATE_CLIENTS` sozinho.**

### Ferramentas de debug

- Comando `/cleargarden [radius] [verticalRange]` (`FlowerDiseaseCommands.java`): apaga (sem drop) todo
  bloco `instanceof BushBlock`. Cobre flores vanilla e do mod, grama, ferns, dead bush — qualquer coisa
  que estenda `BushBlock` (inclui saplings também, efeito colateral aceitável pra uma ferramenta de
  debug). Três formas de uso:
  - `/cleargarden` (sem argumentos): limpa **todos os chunks carregados no momento** ao redor de quem
    executa (raio derivado do view-distance do servidor), não um raio fixo — é o que o Ctrl+P dispara.
  - `/cleargarden <radius>`: limpa só dentro de `radius` blocos horizontalmente ao redor de quem executa,
    pra quando você quer restringir a uma área menor (ou testar algo fora do alcance carregado padrão).
  - `/cleargarden <radius> <verticalRange>`: como acima, mas também especificando o alcance vertical
    (padrão 24 pra cima/baixo quando omitido).
  Cada coluna só é varrida se `level.hasChunk(...)` confirmar que o chunk já está carregado — importante
  pra um comando de debug nunca forçar o jogo a carregar/gerar chunks nele mesmo.
- Atalho **Ctrl+P** (`FlowerDiseaseClient.java`, `CLEAR_GARDEN_KEY`): client-side, envia
  `player.connection.sendCommand("cleargarden")` (sem argumentos, então limpa tudo que está carregado) —
  não precisa digitar o comando, e não precisa de rede customizada (reaproveita o pipeline de comando do
  próprio vanilla).
- Comando `/diseasedflower profile set <gerações> <spreadChance> <spreadDistance> <densidadePor16x16> [espécies]`
  e `/diseasedflower profile clear`: configura (ou limpa) o `SpreadProfileBlockEntity` da flor que o
  jogador está mirando, pra testar o sistema de perfil por-planta ANTES da Garden Bag existir. Cada
  argumento numérico aceita `-1` pra "sem override, usa o config global nesse campo específico"
  (exceto gerações, onde `-1` = infinito de verdade). `espécies` é opcional, uma string separada por
  vírgula de entradas `"<block id> <peso>"` — pool de onde os FILHOS ao espalhar são sorteados (settle
  nunca consulta essa lista, ver "Sistema de settle" acima). Tanto ids vanilla (`minecraft:rose_bush`)
  quanto os 12 ids decorativos Top/Bottom (`flowerdisease:rose_bush_top`) funcionam igual, cada um sua
  própria espécie espalhável, ex.: `"minecraft:poppy 70,flowerdisease:rose_bush_top 30"`.

## Arquivos principais

| Arquivo | Responsabilidade |
|---|---|
| `FlowerDisease.java` | Registro de blocos/itens/block entity types, creative tab, config, comandos |
| `FlowerDiseaseClient.java` | Config screen, keybind de debug (client-only) |
| `FlowerDiseaseCommands.java` | Comandos `/cleargarden` e `/diseasedflower profile` |
| `DiseasedFlowerBlock.java` | Flores de 1 bloco que se espalham (fino wrapper, delega pra `DiseasedPlantLogic`) |
| `DiseasedWitherRoseBlock.java` | Wither Rose doente, 1 bloco (delega pra `DiseasedPlantLogic`) |
| `DiseasedTallFlowerBlock.java` | Flores de 2 blocos que se espalham (fino wrapper, delega pra `DiseasedPlantLogic`) |
| `DiseasedGrassBlock.java` | Short Grass/Fern doentes, 1 bloco (delega pra `DiseasedPlantLogic`) |
| `DiseasedDeadBushBlock.java` | Dead Bush doente, 1 bloco (delega pra `DiseasedPlantLogic`) |
| `DiseasedTallGrassBlock.java` | Tall Grass/Large Fern doentes, 2 blocos (delega pra `DiseasedPlantLogic`) |
| `DiseasedPlantLogic.java` | Engine única de espalhamento/settle pra TODAS as espécies (1 e 2 blocos) — filhos sorteados livremente do pool (sem restrição de família/categoria), settle sempre vira a própria espécie (sem sorteio), ver "Sem lógica de cadeia" acima |
| `DecorativeFlowerBlock.java` | Bloco decorativo de 1 bloco, terminal (resultado de settle de um Top/Bottom) |
| `DiseasedDecorativeFlowerBlock.java` | Contraparte espalhável de cada `DecorativeFlowerBlock` (Top/Bottom como espécie própria, delega pra `DiseasedPlantLogic`) |
| `SpreadProfileBlockEntity.java` | Overrides opcionais por-planta (gerações/velocidade/distância/densidade/espécies/territorial) |
| `SettleTable.java` | Lógica compartilhada: parsing de outcomes, sorteio ponderado, flags de placement, `GENERATION` property, conversão de densidade, `isSameSpecies`/`isAnyPlant` (territorial) |
| `GardenBagItem.java` | Item da bag: abre o menu, tooltip, lógica de plantio (`useOn`) |
| `GardenBagMenu.java` | Container da bag (slots filtrados por tipo de item, ligação com `ItemContainerContents`) |
| `GardenBagScreen.java` | Tela da bag (client-only, sem textura própria) |
| `Config.java` | `ModConfigSpec` — só os knobs globais de espalhamento (sem mais settleWeights) |

Recursos (`src/main/resources`): cada espécie/bloco decorativo tem blockstate + modelo(s) de bloco +
modelo de item + loot table, todos reaproveitando texturas vanilla via referência cross-namespace
(`minecraft:block/...`), sem duplicar nenhum asset. Blockstates das flores que se espalham usam
**`multipart`** (não `variants`) justamente pra ignorar o `generation` property sem precisar de uma
variante por valor.

## Config atual (`run/config/flowerdisease-common.toml`)

Knobs globais (aplicam a todas as espécies, flor plantada na mão):
`maxNearbyFlowers`, `densityCheckRadius`, `spreadDistance`, `spreadVerticalRange`, `spreadAttempts`,
`spreadChance`, `maxGenerations`. Não existe mais seção `[settleWeights]` — ver "Sistema de settle" acima.

## Garden Bag — implementada (passos 4-7)

### Visão (o que ela faz)

`GardenBagItem` (`flowerdisease:garden_bag`, ícone reaproveitado da textura do Bundle). Clique direito
no ar abre a tela de configuração (`GardenBagMenu`/`GardenBagScreen`); clique direito num bloco planta uma
Diseased Flower raiz já configurada com o que estiver nos slots NO MOMENTO do clique. A bag NÃO é
consumida ao plantar — é reutilizável, pode plantar quantas vezes quiser, e pode ser reconfigurada a
qualquer momento entre plantios (sem nenhum tipo de "selar"/travar — ver "Feature removida" abaixo).

### Como os dados são guardados

Nada de BlockEntity ou capability própria pro item — a bag usa o mesmo componente `DataComponents.CONTAINER`
(`ItemContainerContents`) que o Bundle do vanilla já usa pra guardar uma lista de ItemStacks dentro de um
item. O `GardenBagMenu` lê esse componente pra montar seus slots ao abrir, e regrava nele (via
`SimpleContainer.setChanged()` sobrescrito) toda vez que um slot muda — a bag em si é a única fonte de
verdade, o menu não guarda estado próprio.

### Feature removida: "selar" a bag (`GARDEN_BAG_LOCKED`)

A versão original exigia clicar em "Seal Bag" (irreversível) antes de poder plantar, pra evitar plantar
com uma bag "esquecida" sem querer. O dono do projeto pediu pra tirar isso: atrapalhava testar/ajustar a
configuração repetidamente. Removido por completo (não só desativado): o data component
`GARDEN_BAG_LOCKED`, o botão "Seal Bag"/"Sealed" da tela, o `clickMenuButton`/`LOCK_BUTTON_ID` do menu, e
as checagens de trava nos slots (`GardenBagMenu`'s slots agora são um simples `FilteredSlot`, sem estado
de bag nenhum). Hoje: clique direito no ar SEMPRE abre a tela (`use()`); clique direito num bloco SEMPRE
tenta plantar (`useOn()`) — a distinção "configurar vs. plantar" é feita só pelo alvo do clique (ar vs.
bloco), não por um estado da bag. Pode voltar no futuro como uma opção opt-in, mas por enquanto a bag é
sempre editável.

### Layout de slots (`GardenBagMenu`)

6 slots dedicados de item único (cada um só aceita o item certo): Bone Meal, Sculk, Nether Star,
Slime Ball, Feather, Fence (`ItemTags.FENCES` — qualquer cerca, madeira ou nether brick). Mais uma grade
3x3 (9 slots, `SPECIES_SLOTS_START=6`) pra flores/grama/decorativos — qualquer item listado em
`FlowerDisease.bagOutcomeItems()` (34 entradas: 16 espécies de 1 bloco só "full" + 6 famílias de 2 blocos
× 3 variantes cada uma delas — Full/Top/Bottom, cada item com peso independente, e hoje as 34 são
igualmente espalháveis/plantáveis como raiz, ver "Espécies existentes" acima). Total `BAG_SLOTS = 15`.
`bagOutcomeItems()` é um método estático com cache preguiçoso (não um campo `static final` comum) porque
suas chaves incluem itens do próprio mod (`SUNFLOWER_TOP_ITEM.get()` etc.) que só existem depois que o
registro de itens roda — resolver isso direto no inicializador estático da classe `FlowerDisease` daria
`IllegalStateException` (registro ainda não populado nesse momento do carregamento do mod).
Todos os slots aceitam colocar/tirar item livremente a qualquer momento (sem trava — ver "Feature
removida" acima); cada slot só filtra o TIPO de item aceito (`GardenBagMenu.FilteredSlot`).

### Conversão item → parâmetro (`GardenBagItem#plant`)

| Slot | Vazio | Com N itens |
|---|---|---|
| Bone Meal | sem override (usa o `Config.FLOWER_MAX_GENERATIONS` da flor) | `N` gerações |
| Nether Star | (ignorado) | gerações infinitas, sobrepõe o Bone Meal |
| Sculk | sem override (usa `Config.FLOWER_SPREAD_CHANCE`) | `spreadChance = N/64` (linear, 1→~1.6%, 64→100%) |
| Slime Ball | sem override (usa `Config.FLOWER_MAX_NEARBY`/`densityCheckRadius`) | `N` flores desejadas por área 16x16 (convertido internamente pro raio fixo via `SettleTable.densityTargetToMaxNearby`) |
| Feather | sem override (usa `Config.FLOWER_SPREAD_DISTANCE`) | `min(N, 32)` blocos de distância por salto |
| Fence | modo territorial desligado (só compete com a própria espécie/família) | modo territorial LIGADO (qualquer quantidade) — conta QUALQUER planta próxima como lotação, não só a mesma espécie |
| Grade de flores | nenhuma espécie plantável configurada → **planta nada, comando falha** | cada slot não-vazio vira uma entrada `"<id> <peso>"` (peso = quantidade no slot; `id` é sempre um bloco FINAL — vanilla pra espécie completa, ou um dos 12 blocos Top/Bottom, cada um sua própria espécie); esse pool inteiro é `speciesWeights`, usado pro sorteio de RAIZ (`GardenBagItem#plant`) e pro sorteio de espécie de um FILHO ao espalhar (`DiseasedPlantLogic`) — todas as 34 entradas têm contraparte Doente (`FlowerDisease.diseasedByFallback()`), então todas são igualmente elegíveis pros dois. Settle NUNCA consulta esse pool (ver "Sistema de settle" acima) |

### Limitações conhecidas

Nenhuma conhecida no momento — a restrição de categoria/família no espalhamento e a limitação de Top/Bottom
nunca poderem ser raiz foram ambas corrigidas (ver "Sem lógica de cadeia" e "Espécies existentes" acima).

### Limitação conhecida de implementação (transparência sobre o que não pude verificar)

**Não consigo ver a tela renderizada** (não tenho como tirar screenshot do Minecraft rodando), então o
layout exato de `GardenBagScreen` (posições dos 15 slots, tamanho do painel,
textos curtos tipo "Gens"/"Speed" embaixo de cada slot) foi calculado matematicamente, não visualmente
conferido. É bem provável que precise de ajuste fino depois que você testar e me disser o que ficou
errado (texto cortado, slot fora do lugar, etc). Não usei nenhuma textura customizada (não consigo
desenhar pixel art) — o painel e os slots são retângulos desenhados na hora (`GuiGraphics.fill`), não uma
imagem de fundo tipo baú. A explicação completa de cada slot está no tooltip do item (passe o mouse em
cima da bag), não só na tela.

### Ordem de implementação (todos os passos originais)

1. ~~Contador de gerações no config global~~ — **feito**.
2. ~~Wither Rose como espécie~~ — **feito**.
3. ~~BlockEntity leve pra flores "com dono"~~ — **feito** (`SpreadProfileBlockEntity`).
4. ~~`MenuType`/`AbstractContainerMenu`/`Screen` da Garden Bag~~ — **feito**.
5. ~~Lógica de leitura dos slots → parâmetros → grava no `SpreadProfileBlockEntity` ao plantar~~ — **feito**.
6. ~~Lock permanente~~ — **feito, e depois REMOVIDO** a pedido do dono do projeto (atrapalhava testar) —
   ver "Feature removida: selar a bag" acima. Pode voltar no futuro como opt-in.
7. ~~Ligar os itens de proporção ao sorteio de espécie~~ — **feito**.

Tudo que estava planejado pra Garden Bag está implementado (menos o lock, removido de propósito). Falta só
testar em jogo e ajustar o que não ficar bom visualmente ou no balanceamento das conversões
item→parâmetro acima.

## Como testar agora

- `./gradlew runClient` pra abrir o jogo.
- `/gamerule randomTickSpeed 500` (ou mais alto) pra acelerar o teste sem esperar dias reais; voltar pra
  `3` depois.
- **Ctrl+P** limpa flores/grama em tudo que estiver carregado ao redor de você, sem precisar teleportar.
  Pra limitar a uma área menor, use `/cleargarden <radius>` (ou `<radius> <verticalRange>`) direto.
- Pra testar o sistema de perfil (gerações sem teto, velocidade/distância/densidade/territorial por-planta,
  outcomes) sem precisar montar uma bag: mire numa Diseased Flower e rode, por exemplo,
  `/diseasedflower profile set -1 0.5 5 20 false` (infinito, chance alta, alcance 5, ~20 flores por 16x16,
  territorial desligado) ou adicione outcomes no final SEM aspas (o argumento é "greedy", pega o resto da
  linha como está, aspas virariam parte do texto) — usando ids VANILLA pra espécie completa e ids
  decorativos pra Top/Bottom: `/diseasedflower profile set 15 -1 -1 -1 false minecraft:poppy 70,flowerdisease:rose_bush_top 30`.
  `/diseasedflower profile clear` volta a flor pro comportamento padrão (settle em si mesma, sem bag).
- Editar `run/config/flowerdisease-common.toml` e salvar já basta — o NeoForge observa o arquivo e recarrega
  sozinho, não precisa de `/reload` (isso só recarrega datapack: loot table, receitas, tags). Mudanças em
  knobs globais como `maxGenerations` só valem pra flores plantadas DEPOIS da edição, já que o valor é
  lido no momento de plantar, não recalculado nas que já existem.
