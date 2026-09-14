# Flower Disease — Planejamento e Arquitetura

Este documento existe pra qualquer pessoa (ou IA) conseguir continuar o desenvolvimento do mod
sem precisar reconstruir o contexto do zero. Ele descreve o que já existe, por quê, e o que está
planejado a seguir. Sempre que uma decisão de arquitetura relevante for tomada, atualize este arquivo.

## Visão geral do mod

Minecraft 1.21.1, NeoForge `21.1.250`. A ideia central: uma "Diseased Flower" é uma versão doente de
cada flor do vanilla que, plantada, se espalha lentamente pelo terreno ao redor com o tempo, criando um
campo florido de forma orgânica. Quando não consegue mais se reproduzir (lotação ou terreno sem espaço),
ela se estabiliza virando uma flor normal (ou outra espécie, configurável).

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
  parser/sorteio do `SettleTable`, filtrando por compatibilidade — uma flor de 1 bloco só sorteia entre
  espécies de 1 bloco, e uma de 2 blocos só entre as de 2 blocos; misturar categoria ainda não é
  suportado, ver "Limitações conhecidas"). Ao espalhar com um perfil ativo, o filho recebe uma CÓPIA do
  perfil do pai (via `SpreadProfileBlockEntity#copyFrom`), com o contador de gerações já decrementado —
  é assim que a herança pelos filhos funciona.
- **A lista de espécies do perfil também manda no "settle" (não só no espalhamento)**: bug relatado pelo
  dono do projeto — plantar só Poppy pela bag também estava assentando em Rose Bush (peso vindo do
  `Config.java` global, que é só pra teste solo, não pro uso real com a bag). Causa raiz: o settle sempre
  consultava a settleWeights global da espécie, mesmo com um perfil ativo. Corrigido: quando o perfil tem
  QUALQUER espécie configurada (`speciesOptions` não vazio), essa lista passa a mandar tanto no
  espalhamento QUANTO no settle daquela planta especificamente (`effectiveSettleOptions` em
  `DiseasedFlowerLogic`/`DiseasedTallPlantLogic` — se vazia, cai de volta na settleWeights global igual
  antes). Flor plantada na mão nunca tem espécies no perfil, então não muda em nada pra ela.
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
soil — mas usa exatamente a mesma lógica de espalhamento/settle que as outras, via `DiseasedFlowerLogic`).

`DiseasedFlowerLogic.java` é onde mora a lógica de espalhamento/settle compartilhada entre
`DiseasedFlowerBlock` e `DiseasedWitherRoseBlock` (extraída num utilitário estático porque as duas
classes não podem compartilhar uma superclasse Java comum, já que uma estende `FlowerBlock` e a outra
`WitherRoseBlock`).

4 flores altas de 2 blocos (`DiseasedTallFlowerBlock`): Sunflower, Lilac, Rose Bush, Peony. Só a metade
de baixo (`HALF=LOWER`) age no random tick — a de cima é ignorada pra não duplicar a taxa de
espalhamento. Ao espalhar, usa `DoublePlantBlock.placeAt` pra colocar as duas metades corretamente.
Mantém sua própria lógica separada (não usa `DiseasedFlowerLogic`) porque um alvo válido pra ela é
diferente (precisa da célula de cima livre também).

4 blocos decorativos de bloco único (`DecorativeFlowerBlock`): Sunflower Top, Lilac Top, Rose Bush Top,
Peony Top. Não se espalham, não têm random tick, não têm `SpreadProfileBlockEntity` — existem só pra
servir de resultado do modo `upper` do settle (ver abaixo).

Grama/textura adicional, pra dar mais variedade visual às plantações (pedido do dono do projeto, não são
"flores" no sentido de densidade cruzada com as de cima por padrão — cada família só compete com ela
mesma, a não ser no modo territorial):

- 3 de bloco único: Short Grass (`DiseasedGrassBlock extends TallGrassBlock`, delega pra
  `DiseasedFlowerLogic`), Fern (mesma classe `DiseasedGrassBlock` — vanilla usa `TallGrassBlock` pras
  duas), Dead Bush (`DiseasedDeadBushBlock extends DeadBushBlock`, delega pra `DiseasedFlowerLogic`).
- 2 de dois blocos: Tall Grass, Large Fern (mesma classe `DiseasedTallGrassBlock extends DoublePlantBlock`
  — delega pra `DiseasedTallPlantLogic`, ver abaixo).

`DiseasedTallPlantLogic.java` é uma cópia/extração de `DiseasedTallFlowerBlock` como utilitário estático
(mesmo motivo do `DiseasedFlowerLogic`: `DiseasedTallGrassBlock` estende `DoublePlantBlock` diretamente,
enquanto `DiseasedTallFlowerBlock` estende `TallFlowerBlock` — não dá pra compartilhar superclasse Java, só
lógica). Tem as mesmas duas correções descritas acima (`effectiveSettleOptions`, territorial).

Tintura de bioma (verde da grama): as classes novas não herdam o registro `BlockColors`/`ItemColors` do
vanilla (é vinculado à instância exata do `Block` vanilla, não à classe). Registrado manualmente em
`FlowerDiseaseClient#onRegisterBlockColors`/`onRegisterItemColors`, espelhando exatamente o que o vanilla
faz pra `SHORT_GRASS`/`FERN`/`TALL_GRASS`/`LARGE_FERN` (`BiomeColors.getAverageGrassColor` pro bloco no
mundo, `GrassColor.getDefaultColor()`/`.get(0.5, 1.0)` pro ícone do item). Os modelos usam
`"parent": "minecraft:block/tinted_cross"` (não `cross` puro) pra habilitar a tintura; Dead Bush usa
`cross` puro porque não é tingido no vanilla também.

### Sistema de "settle" (`SettleTable.java`)

Quando uma flor não consegue mais se reproduzir, ela sorteia um resultado numa tabela configurável
(`Config.java`, seção `[settleWeights]`, uma lista por espécie). Cada entrada é
`"<block id> <peso> [full|lower|upper]"`:

- **full** (padrão se omitido): resultado completo — se for uma planta de 2 blocos, coloca as duas
  metades corretamente.
- **lower**: só a metade de baixo da planta de 2 blocos, no lugar onde a flor original estava — um
  visual "achatado" de 1 bloco de altura. É estável sozinho porque o `canSurvive` da metade de baixo só
  olha o chão embaixo, igual qualquer flor normal.
- **upper**: em vez de colocar a metade de cima de verdade (que É frágil — precisa da metade de baixo
  correspondente embaixo dela pra sobreviver, e pode ser destruída com drop na primeira atualização de
  vizinho por perto), coloca o bloco decorativo dedicado (`FlowerDisease.DECORATIVE_TOPS`, mapa
  `Block vanilla → DeferredBlock<DecorativeFlowerBlock>`). Isso faz o resultado se comportar como uma
  flor normal de verdade, do jeito que o dono do projeto queria.

Se a lista de settle acabar sem nenhuma entrada válida, cai no `fallbackBlock` (a versão vanilla normal
da própria flor), garantindo que nunca trava numa configuração inválida.

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
  (exceto gerações, onde `-1` = infinito de verdade). `espécies` é opcional, uma string tipo
  `"flowerdisease:diseased_poppy 50,flowerdisease:diseased_cornflower 50"` (mesmo formato de uma linha
  de `settleWeights`, separado por vírgula em vez de espaço entre entradas).

## Arquivos principais

| Arquivo | Responsabilidade |
|---|---|
| `FlowerDisease.java` | Registro de blocos/itens/block entity types, creative tab, config, comandos |
| `FlowerDiseaseClient.java` | Config screen, keybind de debug (client-only) |
| `FlowerDiseaseCommands.java` | Comandos `/cleargarden` e `/diseasedflower profile` |
| `DiseasedFlowerBlock.java` | Flores de 1 bloco que se espalham (delega pra `DiseasedFlowerLogic`) |
| `DiseasedWitherRoseBlock.java` | Wither Rose doente (delega pra `DiseasedFlowerLogic`) |
| `DiseasedFlowerLogic.java` | Lógica de espalhamento/settle compartilhada entre as duas classes acima |
| `DiseasedTallFlowerBlock.java` | Flores de 2 blocos que se espalham (fino wrapper, delega pra `DiseasedTallPlantLogic`) |
| `DiseasedTallPlantLogic.java` | Lógica de espalhamento/settle compartilhada entre `DiseasedTallFlowerBlock` e `DiseasedTallGrassBlock` |
| `DiseasedGrassBlock.java` | Short Grass/Fern doentes, 1 bloco (delega pra `DiseasedFlowerLogic`) |
| `DiseasedDeadBushBlock.java` | Dead Bush doente, 1 bloco (delega pra `DiseasedFlowerLogic`) |
| `DiseasedTallGrassBlock.java` | Tall Grass/Large Fern doentes, 2 blocos (delega pra `DiseasedTallPlantLogic`) |
| `DecorativeFlowerBlock.java` | Bloco decorativo de 1 bloco (resultado do modo `upper`) |
| `SpreadProfileBlockEntity.java` | Overrides opcionais por-planta (gerações/velocidade/distância/densidade/espécies/territorial) |
| `SettleTable.java` | Lógica compartilhada: parsing da settleWeights, sorteio ponderado, colocação de blocos, flags de placement, `GENERATION` property, conversão de densidade, `isAnyPlant` (territorial) |
| `GardenBagItem.java` | Item da bag: abre o menu, tooltip, lógica de plantio (`useOn`) |
| `GardenBagMenu.java` | Container da bag (slots, ligação com `ItemContainerContents`, botão de lock) |
| `GardenBagScreen.java` | Tela da bag (client-only, sem textura própria) |
| `Config.java` | Todo o `ModConfigSpec` — knobs globais + settleWeights por espécie |

Recursos (`src/main/resources`): cada espécie tem blockstate + modelo(s) de bloco + modelo de item + loot
table, todos reaproveitando texturas vanilla via referência cross-namespace (`minecraft:block/...`), sem
duplicar nenhum asset. Blockstates das flores que se espalham usam **`multipart`** (não `variants`)
justamente pra ignorar o `generation` property sem precisar de uma variante por valor.

## Config atual (`run/config/flowerdisease-common.toml`)

Knobs globais (aplicam a todas as espécies, flor plantada na mão):
`maxNearbyFlowers`, `densityCheckRadius`, `spreadDistance`, `spreadVerticalRange`, `spreadAttempts`,
`spreadChance`, `maxGenerations`.

Seção `[settleWeights]`: uma lista por espécie (22 no total — 13 pequenas + 4 altas + Short Grass + Fern +
Dead Bush + Tall Grass + Large Fern), formato `"<block id> <peso> [full|lower|upper]"`.

## Garden Bag — implementada (passos 4-7)

### Visão (o que ela faz)

`GardenBagItem` (`flowerdisease:garden_bag`, ícone reaproveitado da textura do Bundle). Clique direito
no ar abre a tela de configuração (`GardenBagMenu`/`GardenBagScreen`); clique no botão "Seal Bag" trava
**permanentemente** (irreversível — pra mudar a configuração, é preciso montar uma bag nova); com a bag
travada, clique direito num bloco planta uma Diseased Flower raiz já configurada com tudo que estava nos
slots. A bag NÃO é consumida ao plantar — é reutilizável, pode plantar quantas vezes quiser com a mesma
configuração.

### Como os dados são guardados

Nada de BlockEntity ou capability própria pro item — a bag usa o mesmo componente `DataComponents.CONTAINER`
(`ItemContainerContents`) que o Bundle do vanilla já usa pra guardar uma lista de ItemStacks dentro de um
item. O `GardenBagMenu` lê esse componente pra montar seus slots ao abrir, e regrava nele (via
`SimpleContainer.setChanged()` sobrescrito) toda vez que um slot muda — a bag em si é a única fonte de
verdade, o menu não guarda estado próprio. O "travado" é um segundo componente customizado, booleano,
`FlowerDisease.GARDEN_BAG_LOCKED` (persistente + sincronizado de rede).

### Layout de slots (`GardenBagMenu`)

6 slots dedicados de item único (cada um só aceita o item certo): Bone Meal, Sculk, Nether Star,
Slime Ball, Feather, Fence (`ItemTags.FENCES` — qualquer cerca, madeira ou nether brick). Mais uma grade
3x3 (9 slots, `SPECIES_SLOTS_START=6`) pra flores/grama — qualquer item listado em
`FlowerDisease.SPECIES_SEED_ITEMS` (22 espécies: 13 pequenas + 4 altas + Wither Rose + Short Grass + Fern
+ Dead Bush + Tall Grass + Large Fern). Total `BAG_SLOTS = 15`.
Uma vez travada, TODOS os slots da bag ficam somente-leitura (nem colocar nem tirar item) — a checagem
de "travado" é lida ao vivo do item (`GardenBagItem.isLocked`), não travada num snapshot, então o efeito
do botão "Seal Bag" é imediato.

### Conversão item → parâmetro (`GardenBagItem#plant`)

| Slot | Vazio | Com N itens |
|---|---|---|
| Bone Meal | sem override (usa o `Config.FLOWER_MAX_GENERATIONS` da flor) | `N` gerações |
| Nether Star | (ignorado) | gerações infinitas, sobrepõe o Bone Meal |
| Sculk | sem override (usa `Config.FLOWER_SPREAD_CHANCE`) | `spreadChance = N/64` (linear, 1→~1.6%, 64→100%) |
| Slime Ball | sem override (usa `Config.FLOWER_MAX_NEARBY`/`densityCheckRadius`) | `N` flores desejadas por área 16x16 (convertido internamente pro raio fixo via `SettleTable.densityTargetToMaxNearby`) |
| Feather | sem override (usa `Config.FLOWER_SPREAD_DISTANCE`) | `min(N, 32)` blocos de distância por salto |
| Fence | modo territorial desligado (só compete com a própria espécie/família) | modo territorial LIGADO (qualquer quantidade) — conta QUALQUER planta próxima como lotação, não só a mesma espécie |
| Grade de flores | nenhuma espécie configurada → **planta nada, comando falha** | cada slot não-vazio vira uma entrada `"<id> <peso>"` (peso = quantidade no slot), igual uma linha de `settleWeights`; sorteio ponderado decide a espécie da flor raiz **e também passa a mandar no settle dessa planta** (substitui a settleWeights global — ver "Perfil de espalhamento por planta" acima) |

### Limitação conhecida (herdada do `SpreadProfileBlockEntity`)

Igual documentado antes: o sorteio de espécie ao ESPALHAR (não ao plantar a raiz) só troca dentro da
mesma categoria (1 bloco ↔ 1 bloco, 2 blocos ↔ 2 blocos) — misturar Poppy e Rose Bush na mesma bag
funciona pra ESCOLHER a raiz, mas depois que plantada como Poppy, ela só vai continuar espalhando como
flores de 1 bloco (ignorando silenciosamente as opções de 2 blocos da lista, e vice-versa).

### Limitação conhecida de implementação (transparência sobre o que não pude verificar)

**Não consigo ver a tela renderizada** (não tenho como tirar screenshot do Minecraft rodando), então o
layout exato de `GardenBagScreen` (posições dos 14 slots, tamanho do painel, posição do botão "Seal Bag",
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
6. ~~Lock permanente~~ — **feito** (`GARDEN_BAG_LOCKED`, data component booleano).
7. ~~Ligar os itens de proporção ao sorteio de espécie~~ — **feito**.

Tudo que estava planejado pra Garden Bag está implementado. Falta só testar em jogo e ajustar o que não
ficar bom visualmente ou no balanceamento das conversões item→parâmetro acima.

## Como testar agora

- `./gradlew runClient` pra abrir o jogo.
- `/gamerule randomTickSpeed 500` (ou mais alto) pra acelerar o teste sem esperar dias reais; voltar pra
  `3` depois.
- **Ctrl+P** limpa flores/grama em tudo que estiver carregado ao redor de você, sem precisar teleportar.
  Pra limitar a uma área menor, use `/cleargarden <radius>` (ou `<radius> <verticalRange>`) direto.
- Pra testar o sistema de perfil (gerações sem teto, velocidade/distância/densidade por-planta, sorteio
  de espécie) antes da Garden Bag existir: mire numa Diseased Flower e rode, por exemplo,
  `/diseasedflower profile set -1 0.5 5 20` (infinito, chance alta, alcance 5, ~20 flores por 16x16) ou
  adicione espécies no final SEM aspas (o argumento é "greedy", pega o resto da linha como está, aspas
  virariam parte do texto): `/diseasedflower profile set 15 -1 -1 -1 flowerdisease:diseased_poppy 50,flowerdisease:diseased_cornflower 50`.
  `/diseasedflower profile clear` volta a flor pro comportamento padrão (config global).
- Editar `run/config/flowerdisease-common.toml` e salvar já basta — o NeoForge observa o arquivo e recarrega
  sozinho, não precisa de `/reload` (isso só recarrega datapack: loot table, receitas, tags). Mudanças em
  knobs globais como `maxGenerations` só valem pra flores plantadas DEPOIS da edição, já que o valor é
  lido no momento de plantar, não recalculado nas que já existem.
