# Flower Disease — Plano da Etapa 2 (escalada, creeping e corrupção)

Plano de implementação das três features novas, decidido antes de escrever código. Conforme cada fase for
implementada, o conteúdo relevante migra pro `PLANNING.md` (que descreve o que EXISTE) e esta seção some.

Branch: `feature/climbing-creeping-corruption` (criada a partir de `main` no commit `64b8d09`, já publicada
no GitHub). A etapa 1 fica isolada em `main`.

## Status

- **Fase 0 (fundação) — feita e commitada** nessa branch. `./gradlew build` passa; nenhum teste em jogo
  ainda. Resumo do que mudou (detalhes completos já migrados pro `PLANNING.md`):
  - Gerações saíram do blockstate, vivem só em `SpreadProfileBlockEntity#generationsRemaining` (0.1).
  - Nova property `SettleTable.SETTLED` + `isRandomlyTicking` sobrescrito em toda classe que se espalha;
    `DiseasedPlantLogic#settle` agora vira vanilla SE sobreviver ali, senão assenta no lugar (0.2).
  - `PlantSupport.java` + 3 tags (`climbable`/`convertible`/`conversion_immune`) criadas, só `isClimbable`
    tem usuário ainda — `isConvertible` só entra em uso na Fase 3 (0.3).
  - `GardenBagContents` virou `record` de 8 campos (+ `climbing`/`spawnsFlowerBlocks`); `configure`/
    `copyFrom` da `SpreadProfileBlockEntity` viraram um `configure(GardenBagContents)` só. Itens novos
    (Twisting Vines, Moss Block) já são aceitos na bag e aparecem no preview, mas **sem efeito nenhum
    ainda** — nada lê `profile.climbing()`/`profile.spawnsFlowerBlocks()` até a Fase 1/3 (0.4).
  - Comando de debug `/diseasedflower profile set` ganhou os 2 argumentos booleanos novos.
- **Fase 1 (escalada) — feita e commitada** nessa branch, **corrigida depois de um teste em jogo que achou
  2 bugs reais** (ver "Correções pós-teste" abaixo). Resumo do que mudou:
  - `PlantSupport.FACING` (novo `DirectionProperty`, 5 valores — UP + as 4 horizontais, sem DOWN) nas 5
    classes de bloco de 1 bloco. `PlantSupport.canStandOn`/`canClingTo` implementam a regra de
    `canSurvive` (sempre permitida estruturalmente, nunca olha o BlockEntity — ver decisão nova abaixo);
    `PlantSupport.tiltedShape` dá uma caixa de colisão aproximada pra cada direção horizontal.
  - `getStateForPlacement` voltou nas 5 classes (tinha sumido na Fase 0.1 junto com `generation`): clicar
    na lateral de um bloco escalável tenta inclinar, cai pra em-pé se não sobreviver.
  - `DiseasedPlantLogic`: `findSpreadTarget`/`followTerrain` agora tentam, além de UP, as 4 direções
    horizontais (só quando `profile.climbing()` E a espécie sorteada é de 1 bloco) — ordem sorteada pra
    não enviesar sempre pro mesmo lado. Alcance vertical vira `max(spreadVerticalRange, spreadDistance)`
    quando escalando. `SpreadTarget` (record `pos`+`facing`) substitui o `BlockPos` solto que os métodos
    de busca retornavam.
  - 2 modelos-pai novos (`tilted_cross`/`tilted_tinted_cross`) + 28 modelos de bloco inclinados — no fim
    das contas, IGUAL ao previsto originalmente na seção 1.5 (ver "Correções pós-teste" pro motivo de eu
    ter tentado um atalho primeiro e ele não ter funcionado).
- **Correção pós-teste #4 (2026-09-18): crowding não acompanhava o alcance vertical da escalada.** Reportado
  como "com Fermented Spider Eye, a bag parece ignorar as próprias flores e nunca para de espalhar". Causa
  raiz: `DiseasedPlantLogic#countNearbyFieldFlowers` sempre usava `Config.FLOWER_SPREAD_VERTICAL_RANGE` fixo
  pra escanear vizinhos, enquanto `findSpreadTarget` já alargava esse alcance pra
  `max(spreadVerticalRange, spreadDistance)` quando `profile.climbing()` está ligado (pra subir um tronco
  alto). Resultado: uma flor escalando conseguia se afastar verticalmente da própria fila mais rápido do que
  a checagem de lotação enxergava, então nunca contava as próprias irmãs como vizinhas e nunca assentava.
  Corrigido calculando `climbing`/`maxSpreadDistance`/`verticalRange` uma única vez em `randomTick` e
  passando pros dois lados (`countNearbyFieldFlowers` ganhou parâmetro `verticalRange`; `findSpreadTarget`
  parou de recalcular e recebe os 3 valores prontos) — os dois agora são fisicamente incapazes de divergir
  de novo. Validado só com `compileJava` limpo; ainda não testado em jogo.
- **Limpeza (2026-09-18): Diseased Flower deixou de existir como item.** Pedido do dono do projeto: agora
  que a bag é o único jeito de criar uma, os 34 `BlockItem` "Diseased X" (22 espécies + 12 Top/Bottom
  diseased) não fazem mais sentido no inventário criativo/JEI — o bloco é só um conceito temporário, nunca
  algo que o jogador coleta. Removidos os 34 registros de item, as entradas correspondentes em
  `addCreative`, e os 34 modelos de item órfãos; as 34 loot tables desses blocos foram redirecionadas pra
  dropar a espécie vanilla real (ex: `diseased_poppy` → `minecraft:poppy`) ou, pros 12 Top/Bottom, pro
  decorativo simples equivalente que continua existindo (`flowerdisease:sunflower_top`, etc.). Os blocos em
  si continuam registrados normalmente (só nunca aparecem como item).
- **Integração opcional com Jade (2026-09-18): `JadeCompat.java`.** Como a flor nunca mais tem item/nome
  próprio visível, o único lugar em que o jogador vê algum nome ao olhar pra ela é o overlay do Jade — então
  esse plugin (só carregado pelo próprio Jade, dependência `compileOnly`/`localRuntime` opcional no
  `build.gradle`, ver `neoforge.mods.toml`) troca a linha do nome: enquanto `SETTLED=false` mostra
  "Diseased Flower" genérico; assim que assenta (`SETTLED=true`) mostra o nome real da espécie vanilla (ou
  do decorativo Top/Bottom equivalente), via `FlowerDisease.fallbackByDiseased()` (novo, inverso de
  `diseasedByFallback()`). Usa `ITooltip#replace(JadeIds.CORE_OBJECT_NAME, ...)`, o mecanismo padrão de
  addon do Jade pra sobrescrever só a linha de nome sem mexer no resto do tooltip. Testado com
  `runClient` real (Jade 15.10.6+neoforge baixado via Modrinth maven): carrega sem erro depois de eu
  descobrir que Jade exige uma chave de lang `config.jade.plugin_<modid>.<uid>` pra CADA provider
  registrado (usada no menu de config dele) — sem ela o cliente lança `AssertionError` ao abrir a tela
  inicial. Ainda não visto em jogo (preciso que você olhe pra uma flor de verdade pra confirmar o texto).
- **Fase 2 (creeping) — feita e commitada** nessa branch, ainda **não testada em jogo** (só `compileJava` e
  `runClient` limpos até aqui). Resumo do que mudou:
  - `CreepingFlowerBlock extends MultifaceBlock implements EntityBlock` (novo) — mesma base do Glow
    Lichen/Sculk Vein do vanilla. 4 instâncias registradas: `sunflower_creeper`, `lilac_creeper`,
    `rose_bush_creeper`, `peony_creeper`. Um bloco só por espécie (não bloco terminal + bloco Diseased) —
    `diseasedByFallback()` mapeia cada creeper pra ele mesmo, então `DiseasedPlantLogic#settle` sempre cai
    no "assenta no lugar" (nunca vira outra coisa).
  - **Único caso em que o item da espécie É pra aparecer no criativo/JEI**: como não existe item vanilla
    equivalente pra selecionar "creeping" na bag (diferente das 22 espécies normais, que reusam o item
    vanilla), os 4 itens `*_creeper` ficam visíveis — mesmo raciocínio que já valia pros 12 decorativos
    Top/Bottom antes da limpeza do início desta sessão.
  - `DiseasedPlantLogic`: `Shape` ganhou `CREEPING`; `shapeOf` reconhece `MultifaceBlock`;
    `findSpreadTarget` bifurca pra `findCreepingTarget` nesse caso — sorteia uma posição vazia dentro de um
    cubo `±spreadDistance` (as 3 eixos, alcance vertical sempre igual ao horizontal, independente do toggle
    de escalada) e tenta grudar numa das 6 faces via `MultifaceBlock.canAttachTo`, em ordem aleatória. Sem
    `MultifaceSpreader`/bonemeal do vanilla — motor próprio, igual ao resto do mod, como decidido no plano.
  - `placeChild` ganhou um terceiro ramo no switch por `childShape`: pra `CREEPING`,
    `MultifaceBlock.getFaceProperty(target.facing())` é setada `true` — `facing` aqui significa "direção do
    NOVO bloco até o vizinho sólido" (convenção do `MultifaceBlock`, o OPOSTO da convenção de
    `PlantSupport.FACING` usada pelas espécies de 1 bloco inclinadas — documentado no comment do
    `SpreadTarget`).
  - Checagem de lotação: o alcance vertical do scan (`countNearbyFieldFlowers`) passou a levar em conta a
    forma da PRÓPRIA planta (`selfShape`), não só o toggle de escalada — uma planta `CREEPING` sempre usa
    `maxSpreadDistance` no scan vertical, senão ela cairia exatamente no mesmo bug corrigido acima pra
    escalada (nunca enxergar as próprias irmãs por causa do alcance vertical divergente).
  - `SettleTable.isAnyPlant` (modo territorial) passou a reconhecer `CreepingFlowerBlock` especificamente
    (não `MultifaceBlock` genérico — Glow Lichen/Sculk Vein do vanilla não contam como "planta" pra essa
    mecânica).
  - `/cleargarden`: adiantado (fora de ordem, a Fase 4 original previa isso junto com o flower block) pra
    também remover `CreepingFlowerBlock` — senão todo teste desta fase deixaria resíduo que o Ctrl+P não
    tocaria.
  - Recursos: 1 modelo-pai `flowerdisease:block/creeping_flower` (quad único, `render_type: minecraft:cutout`
    explícito no JSON — o vanilla define isso em Java pro Glow Lichen, nós não temos esse hook, então
    precisa ir no modelo) espelhando `minecraft:block/glow_lichen`; 4 modelos de bloco (só trocam a
    textura), 4 blockstates `multipart` (as 12 entradas do glow lichen vanilla, uma por face + o fallback
    "todas as faces false", ignorando `SETTLED`), 4 modelos de item (`item/generated` + `layer0`, igual o
    item do glow lichen vanilla, não o modelo do bloco), 4 loot tables (dropam a si mesmas), 4 entradas de
    lang. Textura placeholder = `minecraft:block/<espécie>_bottom`, como decidido desde o início da etapa.
- **Fase 3 (flower block) — feita e commitada** nessa branch, ainda **não testada em jogo** (só
  `compileJava` e `runClient` limpos até aqui). Resumo do que mudou:
  - `FlowerMassBlock extends Block implements EntityBlock` (novo) — cubo cheio, visual de Moss Block
    (placeholder), `SETTLED` + random tick igual ao resto do mod.
  - `FlowerMassBlockEntity extends SpreadProfileBlockEntity` (novo) — só acrescenta o `BlockState`
    substituído (`replacedState`), serializado via `NbtUtils.writeBlockState`/`readBlockState`. Reusa o
    `BlockEntityType` já existente (`SPREAD_PROFILE_BLOCK_ENTITY`) em vez de criar um novo — a fábrica
    interna do tipo nunca é chamada de verdade (todo bloco aqui constrói seu próprio BE via
    `EntityBlock#newBlockEntity`), então uma subclasse satisfaz a checagem de compatibilidade bloco↔tipo do
    mesmo jeito que a classe base já fazia.
  - `FlowerBlockLogic.java` (novo) — dois pontos de entrada:
    - `maybeSpawn(...)`, chamado de dentro do `DiseasedPlantLogic#randomTick` de QUALQUER flor (independente
      da forma — SINGLE/TALL/CREEPING), logo depois de calcular `generationsLeft`: se
      `profile.spawnsFlowerBlocks()` e ainda há geração sobrando, rola `Config.flowerBlockChance` (0.5%
      default) pra converter `pos.below()` — independente do resultado do espalhamento normal da flor no
      mesmo tick (os dois não competem).
    - `randomTick(...)`, o tick do PRÓPRIO Flower Block: `spreadChance` herdado do pai ×
      `Config.flowerBlockSpreadFactor` (25% default, então mais lento que a flor); só as 6 faces
      adjacentes, sorteadas em ordem aleatória; sem checagem de lotação (não prevista no plano). Sem
      alvo elegível ou sem geração → assenta (`SETTLED = true`), mesma regra do resto do mod.
  - Conversão só passa em blocos que batem `PlantSupport.isConvertible` (tags `convertible`/
    `conversion_immune`, já populadas desde a Fase 0.3, mais "sem BlockEntity" e "destrutível" — que
    automaticamente impede um Flower Block de corromper OUTRO Flower Block, já que ele tem BlockEntity).
  - **Kill-switch adiantado**: `Config.flowerBlockConversion` (default ligado) — desligar impede criação de
    novos Flower Blocks E assenta (`SETTLED`) qualquer um já existente na próxima vez que ele tickar, em
    vez de só ignorar silenciosamente pra sempre. O plano original listava isso só na Fase 4; entrou junto
    porque é exatamente o tipo de coisa que não devia esperar — é uma feature destrutiva de terreno pensada
    pra modpack.
  - **Reversibilidade (proposta do plano, mantida)**: `/cleargarden` restaura o `BlockState` original salvo
    no BE em vez de virar ar, quando encontra um `FlowerMassBlock` — adiantado da Fase 4 pelo mesmo motivo
    do `/cleargarden` da Fase 2 (testar a feature sem deixar buraco/resíduo irreversível pra trás).
  - `flowerdisease:flower_block` entra na tag vanilla `#minecraft:dirt` (chão válido pra flor continuar em
    cima dele, igual o Moss Block real) e em `#minecraft:mineable/hoe`, ambas como extensão de datapack em
    `data/minecraft/tags/block/`.
  - Recursos: 1 blockstate simples (variant único), 1 modelo (`cube_all` + textura `minecraft:block/
    moss_block` como placeholder), 1 modelo de item, 1 loot table (dropa a si mesmo), 1 entrada de lang.
    Item aparece no criativo (ao lado do Moss Block) — ao contrário das flores, é um bloco standalone de
    verdade, não só um seletor de espécie pra bag.
- **Revisão de código completa (2026-09-21)**, pedida pelo dono do projeto — bugs, más práticas e
  compatibilidade com outros mods, sobre TODO o código (não só o diff recente). Achados e correções:
  1. **Bug real, reportado em teste**: `GardenBagItem#plant` (plantar direto pela bag, clicando no chão)
     nunca tratava `Shape.CREEPING` — sempre construía `block.defaultBlockState()`, que pra um
     `MultifaceBlock` vem com todas as 6 faces desligadas, e `canSurvive` de um `MultifaceBlock` sem
     nenhuma face ligada é sempre `false`. Resultado: toda vez que o sorteio da bag caía numa creeping
     flower como planta raiz, plantar falhava com "chão inválido" nesse E EM QUALQUER OUTRO lugar, mesmo
     válido. Corrigido: `plant` agora usa `DiseasedPlantLogic.shapeOf` (exposto pro pacote) e, pra
     `CREEPING`, liga a face oposta à face clicada pelo jogador (`clickedFace.getOpposite()`) — mesma
     convenção do resto do motor (ver comment do `SpreadTarget`).
  2. **Inconsistência de flags encontrada no mesmo método**: `GardenBagItem#plant` usava `Block.UPDATE_ALL`
     pra colocar a planta raiz, em vez do `SettleTable.PLACEMENT_FLAGS` que o resto do mod usa em toda
     colocação própria (a lição documentada desde antes desta etapa: `UPDATE_NEIGHBORS` pode fazer o motor
     achar que a segunda metade de uma planta de 2 blocos ainda não colocada deixa a primeira "inválida" e
     destruir com drop). Trocado por consistência e segurança, mesmo sem reprodução confirmada do bug aqui.
  3. **Ineficiência**: `DiseasedPlantLogic#pickAttachableFace` chamava `Direction.values()` (que aloca um
     array novo a cada chamada) até 3 vezes por iteração do laço. Cacheado numa constante `ALL_FACINGS`,
     mesmo padrão já usado por `HORIZONTAL_FACINGS`.
  4. **Feature pedida**: Flower Block agora só se espalha (Fase 3.3) pra vizinhos que tenham pelo menos um
     dos 6 lados tocando ar ou um bloco não-cheio (flor, slab, escada...) — `FlowerBlockLogic#hasExposedFace`,
     usando `Block.isShapeFullBlock` no `getCollisionShape` de cada vizinho. Sem isso a corrupção podia
     tunelar por rocha sólida indefinidamente, enterrada e nunca visível. Não afeta `maybeSpawn` (a criação
     do primeiro Flower Block debaixo da flor): essa posição já tem uma face exposta garantida — o próprio
     topo, onde a flor está em pé — então o mesmo problema não existe ali.
  5. **Feature pedida**: creeping flowers ganharam textura por categoria de face (topo/lado/base) em vez de
     uma textura só pras 6 direções — 3 modelos por espécie (`<espécie>_creeper_top/side/bottom.json`)
     apontando pro mesmo `creeping_flower.json`, blockstate atualizado pra escolher o modelo certo por
     direção (mesma estrutura `multipart` de antes, só trocando qual modelo cada face usa). Placeholder
     atual: topo usa a textura `_top` (cabeça da flor), lado E base usam `_bottom` (só existem 2 texturas
     vanilla por espécie) — os 3 slots já existem independentes pra quando a arte real entrar.
  - Achados que NÃO exigiram mudança de código (documentados aqui pra não perder o contexto da revisão):
    - `SpreadProfileBlockEntity`/random tick nunca disparam `BlockEvent` do NeoForge - mods de proteção de
      área (claims) não conseguem vetar o espalhamento ou a corrupção de terreno via seus hooks normais.
      Isso já era verdade desde antes da Stage 2; só ficou mais relevante agora que existe uma feature que
      literalmente destrói bloco de outro dono. Vale documentar pro README/instruções de modpack, não
      resolver em código (adicionar os eventos certos é uma mudança maior, fora do escopo pedido aqui).
    - As tags `#minecraft:dirt`/`#minecraft:mineable/hoe` (Fase 3) e as tags próprias (`climbable`/
      `convertible`/`conversion_immune`) já usam `"replace": false` — múltiplos mods/datapacks reivindicando
      a mesma tag fazem merge em vez de se sobrescreverem. Confirmado, não é um problema.
    - O painel de preview da bag (`GardenBagScreen`) mostra "Creates flower blocks: yes" sempre que o Moss
      Block está na bag, mesmo que `flowerBlockConversion` esteja desligado no config do servidor (o cliente
      não tem como saber esse valor do config do lado do servidor). Cosmético, não corrigido.

### Correção pós-revisão: face `up`/`down` trocada nos blockstates de creeper (2026-09-22)

Você criou a arte de verdade pra `rose_bush_creeper_top` e reportou que ela não aparecia em jogo, mesmo
plantando a creeping flower crescendo em cima do chão (o caso comum). Causa: os 4 blockstates gerados na
revisão anterior mapeavam a face `up=true` pro modelo `_top` e `down=true` pro `_bottom` — mas a convenção
do `MultifaceBlock` (a mesma do Glow Lichen vanilla) é "a direção É pra onde está o suporte", o oposto da
posição visual da planta. Uma flor **em pé no chão** (suporte embaixo dela) ativa a face `down`, não `up` -
`up=true` só acontece quando ela está **pendurada no teto** (suporte em cima dela). Ou seja: o mapeamento
estava exatamente invertido - a textura `_top` só aparecia no caso raro (pendurada), e o caso comum (em pé)
sempre mostrava `_bottom`, então a arte nova parecia "não aparecer".

Corrigido nos 4 `blockstates/*_creeper.json`: `down=true` → modelo `_top` (em pé no chão = visualmente em
cima de um bloco); `up=true` → modelo `_bottom` (pendurada = visualmente embaixo de um bloco). `side`
continua igual (as 4 direções horizontais). Validado com `runClient` - carrega sem erro nenhum ligado aos
creepers.

**Achado à parte, não relacionado ao bug acima**: o log do `runClient` mostrou 4 arquivos soltos em
`textures/block/` com nomes inválidos pro Minecraft (`Gemini_Generated_Image_....jpeg/.jpg/.png`) - letra
maiúscula não é permitida em caminho de resource pack, e `.jpeg`/`.jpg` não são formato de textura válido
pro jogo (só `.png` funciona). Provavelmente arquivos de arte gerada que ainda não foram renomeados/
convertidos - não estão quebrando nada agora porque nenhum model ainda referencia esses nomes, mas não vão
funcionar do jeito que estão se algo passar a apontar pra eles.

### Correção pós-teste: sprite da flor inclinada distorcida (2026-09-23)

Você reportou que, ao nascer inclinada, a sprite não parecia só rotacionar - parecia esticada/distorcida.
Causa raiz, confirmada comparando com o `cross.json` real do vanilla: o `tilted_cross.json`/
`tilted_tinted_cross.json` (Fase 1, modelo compartilhado por todas as 28 espécies de 1 bloco inclinadas)
tem 2 elementos - o plano "de frente" (`north`/`south`) e o plano perpendicular (`west`/`east`). No vanilla,
os dois têm exatamente o mesmo tamanho (14.4 unidades, de 0.8 a 15.2). Nas rodadas de ajuste de posição da
Fase 1 (evitar a flor flutuando longe do suporte), o elemento perpendicular foi encolhido de 14.4 unidades
pra só 5 (`"from":[8,0,11]` até `"to":[8,16,16]`) enquanto o UV continuou mapeando a textura inteira
(`[0,0,16,16]`) nele - ou seja, a arte inteira sendo espremida numa fatia 3x menor que o normal. Isso sim é
distorção de verdade, não só a rotação em si (a rotação nunca deforma, só reorienta).

**Primeira tentativa (errada)**: só devolvi as 14.4 unidades ao elemento perpendicular mantendo a ponta
encostada na parede (`"from":[8,0,1.6]` até `"to":[8,16,16]`) - a textura ficou certa, mas você reportou que
aí o plano perpendicular saía do plano frontal, desalinhado, pior que antes. Causa: eu só corrigi o TAMANHO
do elemento, sem entender por que o desalinhamento existia. Comparei com o próprio `template_torch_wall.json`
do vanilla (a tocha de parede - a referência real de "sprite inclinado encostado numa parede") pra entender a
técnica certa: TODOS os elementos de um objeto que vai inclinar precisam sofrer exatamente a MESMA translação
e a MESMA rotação, preservando entre eles a MESMA posição relativa que tinham no objeto parado - nunca um
elemento reposicionado/redimensionado com uma lógica diferente do outro. Foi exatamente isso que a primeira
tentativa (e a implementação original da Fase 1) violava: o elemento frontal recebeu um deslocamento
consistente (+8 no Z, de "centralizado" pra "encostado"), mas o elemento perpendicular recebia um valor
arbitrário e diferente daquele mesmo deslocamento - por isso os dois paravam de se cruzar corretamente depois
da rotação (só ficam alinhados quando a MESMA transformação rígida é aplicada aos dois).

**Correção de verdade**: apliquei o mesmíssimo deslocamento (+8 no Z) que o elemento frontal já tinha também
no elemento perpendicular, sem cortar nem redimensionar - `"from":[8,0,8.8]` até `"to":[8,16,23.2]` (note que
23.2 passa de 16 - "fora dos limites" do bloco é normal e esperado nesse tipo de modelo antes da rotação
entrar em ação; a própria tocha de parede do vanilla faz o mesmo, com elementos chegando a x=-8 antes da
rotação). Matematicamente isso garante que os dois planos continuam se cruzando na mesma linha compartilhada
(x=8, z=16) depois da rotação, exatamente como se cruzavam em x=8,z=8 no cross original do vanilla -
alinhados por construção, não por tentativa e erro. Validado com `runClient` (sem erro), ainda não visto em
jogo - essa é a versão que preciso que você confirme.
- **Redesenho do ciclo de vida (2026-09-23) — bloco 1 implementado, ver a seção "Ciclo de vida" mais
  abaixo.** Substitui o espalhamento por "salto aleatório numa caixa" + settle por sorteio de chance fixa.
  Blocos 2 em diante (ferramentas `stats`/`day`, registro de perfis compartilhados, crescimento em mancha do
  creeper) ainda por fazer.
- **Próximo passo: Fase 4 (compatibilidade, debug, documentação)** — já com boa parte adiantada (ver
  `/cleargarden` acima nas Fases 2 e 3).

### Correções pós-teste da Fase 1 (2026-09-18)

Você testou e reportou 2 bugs:

1. **Todas as diseased flowers (inclusive as paradas no chão, sem nada a ver com escalada) apareceram como
   o cubo rosa/preto de textura faltando.** Causa raiz: o primeiro commit da Fase 1 usava rotação
   `"x": 25` direto no blockstate (`multipart apply`), achando (errado) que blockstate aceitava qualquer
   grau. **Não aceita** — `"x"`/`"y"` no blockstate só aceita múltiplos de 90 (0/90/180/270); ângulos livres
   só são válidos DENTRO de um modelo, no `rotation` de um `element` (e mesmo lá, só -45/-22.5/0/22.5/45).
   Um valor inválido no blockstate faz o Minecraft rejeitar o blockstate inteiro, daí o cubo de erro
   aparecer pra QUALQUER estado do bloco, não só os inclinados. **Correção**: voltei pro desenho original
   da seção 1.5 — `tilted_cross.json`/`tilted_tinted_cross.json` são cópias fiéis do `block/cross`/
   `tinted_cross` da própria vanilla (extraídos do jar do cliente pra copiar exatamente), só trocando a
   `rotation` dos 2 elementos de `{origin:[8,8,8], axis:"y", angle:45}` pra `{origin:[8,0,16], axis:"x",
   angle:-22.5}` (pivô na base, encostado na parede de suporte). Cada uma das 28 espécies ganhou um
   `<id>_tilted.json` (`{"parent": ".../tilted_cross", "textures": {"cross": "<mesma textura de sempre>"}}`)
   e os blockstates voltaram a usar só `"y"` (0/90/180/270, valores legais) pra girar esse modelo já
   inclinado nas 4 direções — a inclinação em si mora inteira no modelo, não no blockstate. Validei com um
   `runClient` de verdade em background: o log mostrou o `ResourceManager` recarregando sem nenhum erro de
   modelo/blockstate (esse tipo de erro apareceria alto e claro no log), então a causa raiz está corrigida
   — mas o ÂNGULO/SINAL da inclinação continua um chute (agora dentro do modelo, não do blockstate),
   ainda pendente de confirmação visual seguinte.
2. **Escalava na lateral de troncos mas não de folhas.** Causa raiz: `PlantSupport.canClingTo` exigia
   `support.isFaceSturdy(...)` além de `isClimbable`. A vanilla retorna `false` pra `isFaceSturdy` em
   blocos de folha (é por isso que nada normalmente "gruda" numa folha) — isso excluía silenciosamente
   folha da tag `climbable`, mesmo com folha estando explicitamente na lista da tag. **Correção**: removi
   o `isFaceSturdy` de `canClingTo` — a tag `climbable` já é uma lista curada/opt-in (`PlantSupport.java`),
   exigir sturdiness geométrico em cima disso é redundante, não uma proteção a mais. Ficar em cima
   (`canStandOn`) nunca teve esse problema (só checa a tag, sempre checou), então não devia estar quebrado
   pra folha — só o lado é que precisava do fix.

### Segunda rodada de correções pós-teste (mesmo dia)

Você confirmou que a inclinação já estava na direção certa (ângulo/sinal aprovados, não mexi mais neles) e
pediu 2 ajustes:

3. **Flor inclinada flutuando na frente do bloco de apoio em vez de encostar nele.** Causa: os 2 elementos
   de `tilted_cross`/`tilted_tinted_cross` ficavam centrados em z=8 (meio do bloco) antes da rotação — o
   pivô (em z=16, na parede) girava essa geometria, mas ela nunca chegava PERTO da parede porque começava
   longe demais dela. **Correção**: desloquei a geometria pra perto da parede antes da rotação (elemento
   "largura" de z=8 pra z=13; elemento "profundidade" de `[0.8, 15.2]` pra `[8, 15.2]`, encurtando o quanto
   ele avança pro meio do quarto) — mesma rotação de antes (`origin:[8,0,16], axis:x, angle:-22.5`),
   intocada. A caixa de colisão (`PlantSupport.tiltedShape`) já assumia proximidade da parede desde o
   início, então só o modelo visual precisava desse ajuste.
4. **Flores grandes (2 blocos) não conseguiam nem ficar em cima de bloco escalável** — só as 5 classes de 1
   bloco tinham ganhado esse tratamento na Fase 1; as 2 classes de 2 blocos (`DiseasedTallFlowerBlock`,
   `DiseasedTallGrassBlock`) continuavam com o `canSurvive` 100% vanilla. Pedido específico: elas devem
   poder nascer EM CIMA de um tronco/árvore, mas **nunca** inclinadas na lateral (continuam sem property
   `FACING` nenhuma — a regra "espécies de 2 blocos nunca inclinam" continua valendo). **Correção**: as
   duas ganharam um `canSurvive` igual ao caso `UP` das classes de 1 bloco
   (`super.canSurvive(...) || PlantSupport.canStandOn(...)`), sem nenhuma outra mudança (sem `getShape`,
   sem `getStateForPlacement`, sem `FACING`). Em `DiseasedPlantLogic#findSpreadTarget`, o alcance vertical
   ampliado (`max(spreadVerticalRange, spreadDistance)`) agora vale pras DUAS formas quando
   `profile.climbing()` está ligado (antes só valia pra `Shape.SINGLE`) — sem isso a busca nunca alcançaria
   o topo de uma árvore alta mesmo depois do `canSurvive` permitir. `tryFacings` não mudou: o ramo `TALL`
   já ignorava o parâmetro `climbing` (só faz o teste `UP`), então passar `climbing=true` pra ele nunca
   arrisca inclinar uma flor grande — o flag só importa mesmo pro ramo `SINGLE`.

Validado com `runClient` real em background de novo (log limpo, sem erro de modelo/blockstate) depois de
cada rodada.

### Terceira rodada de correções pós-teste (mesmo dia) — commitada em `<próximo commit>`

Você reportou mais 4 coisas depois de testar de novo:

5. **Flor inclinada ainda flutuava pra algumas posições e encostava certinho pra outras.** Causa: o
   `offsetType(XZ)` herdado da vanilla (o mesmo jitter que faz flores no chão parecerem espalhadas
   organicamente) desloca o modelo renderizado em até ±0.25 bloco (±4px) em X/Z, num valor determinístico
   por posição (`Mth.getSeed(x,0,z)`) — mas SEMPRE em cima da geometria já ajustada, então dependendo da
   posição, esse deslocamento empurrava a flor OU pra mais perto OU pra mais longe da parede. **Investiguei
   desabilitar isso condicionalmente** (só quando `facing != UP`) via um `getOffset` sobrescrito — não
   existe mais esse método na 1.21.1 (conferido no `neoforge-21.1.250-sources.jar`, que o Gradle já baixa
   junto): o offset agora é uma `BlockBehaviour.OffsetFunction` guardada em `Properties`, só configurável
   via `.offsetType(OffsetType)` (enum, sem overload que aceite uma função customizada) — sem reflection
   (fora de cogitação), não tem gancho por-blockstate nem por-classe pra isso nessa versão. **Correção**:
   fiz exatamente o que você sugeriu — "dar mais shift" — empurrei a geometria dos 2 elementos ainda mais
   pra dentro da parede (elemento "largura" de z=13 pra z=16, bem na fronteira; elemento "profundidade" de
   `[8,15.2]` pra `[11,16]`), grande o suficiente pra absorver o pior caso de ±4px do jitter puxando pra
   longe. Não precisou mexer em nenhuma classe Java pra isso, só nos 2 modelos-pai.
6. **Sem jeito de ver quais flores ainda estão se reproduzindo, pra achar uma travada num loop.** Novo
   comando `/diseasedflower debug <true|false>` (campo estático `FlowerDiseaseCommands#debugParticlesEnabled`,
   só nesse servidor, não persiste entre reinícios). Ligado, `DiseasedPlantLogic#randomTick` solta uma
   partícula `HAPPY_VILLAGER` na posição TODA VEZ que o método roda de verdade — como uma planta assentada
   (`SETTLED=true`) para de ser sorteada pro random tick (`isRandomlyTicking` retorna `false`), a partícula
   já para de aparecer sozinha no instante em que a planta assenta, sem precisar guardar nenhum estado
   extra. Emitida ANTES até do teste de `spreadChance`, de propósito — mostra QUALQUER planta ainda
   elegível pra espalhar, não só as que vão ter sucesso nesse tick específico.
7. **Tall flower "Full" subindo em cima de árvore às vezes só mostrava a metade de baixo, com ar em cima
   mesmo tendo espaço.** Bug real, não só visual — achado ao investigar o código. Causa raiz:
   `DiseasedPlantLogic#settle`, no ramo "assenta no lugar" (quando o vanilla não sobrevive ali), sempre
   fazia só `level.setBlock(pos, ...)` — um `setBlock` SÓ na metade de baixo. Isso é inofensivo quando a
   planta já existia ativa (a metade de cima já está lá, só precisa ligar `SETTLED`), mas quando um FILHO
   nasce JÁ sem orçamento de gerações (`placeChild`, `childGenerations == 0`) numa posição que ainda não
   tinha NADA (ar nas duas células), esse mesmo código só colocava a metade de baixo e nunca chegava a
   colocar a de cima — antes da Fase 1, essa combinação (filho tall settando em lugar onde o vanilla não
   sobrevive) nunca acontecia de verdade, porque vanilla e diseased tinham exatamente as mesmas regras de
   chão; a escalada foi o que abriu essa divergência. **Correção**: o ramo "assenta no lugar" agora usa
   `DoublePlantBlock.placeAt` pra formas `TALL` (coloca as duas metades) em vez de `setBlock` cru — pro
   caso de planta já existente isso é um no-op idempotente na metade de cima (já está correta), pro caso de
   filho novo agora coloca as duas metades de verdade.
8. **Twisting Vines devia ligar tanto "em cima" quanto "do lado", não só "do lado".** Antes, como
   `canSurvive` já permite estruturalmente ficar em cima de bloco escalável (pra planta já existente nunca
   sumir), a BUSCA por novos alvos (`findSpreadTarget`) também aceitava incidentalmente um topo escalável
   mesmo com a bag sem Twisting Vines — só a busca pelos LADOS já era condicionada. **Correção**: novo
   `DiseasedPlantLogic#isValidUpSpot`, usado tanto pelo ramo `TALL` quanto pelo caso `UP` do ramo `SINGLE`
   em `tryFacings` — depois de confirmar que a posição é válida (`isValidSpot`), checa se o suporte
   (`pos.below()`) está na tag `climbable`; se estiver, só aceita quando `profile.climbing()` também está
   ligado (chão comum nunca é bloqueado, só chão que dependia da tag). Pra essa checagem funcionar sem
   ambiguidade, limpei a tag `climbable.json`: tirei `minecraft:moss_block` e `minecraft:muddy_mangrove_roots`
   — ambos já estão na tag vanilla `#minecraft:dirt`, ou seja, plantas JÁ CRESCEM neles hoje por regra
   comum, sem escalada nenhuma envolvida; deixá-los em `climbable` faria o novo gate bloquear
   incorretamente um chão que sempre foi válido. `mangrove_roots` (sem lama) continua, já que não é chão
   comum plantável.

Os 4 ainda não testados em jogo nessa rodada — validados só com build + `runClient` em background (log
limpo).

## Ciclo de vida (redesenho de 2026-09-23)

Pedido do dono do projeto em 8 tópicos, discutidos e travados antes de codar. Substitui, nesta ordem: a busca por
alvo "aleatório numa caixa" (`spreadDistance`), a checagem de densidade fixa (`maxNearbyFlowers`), o "settle por
sorteio" e a velocidade do Sculk. **Onde este texto contradiz o `PLANNING.md` (tabela de modificadores da bag,
`Config`), este vale.**

### Fluxo de UM random tick de uma planta doente

1. **Chance de reprodução** `c(g) = c0 / (1 + g/H)`, com `g` = profundidade da linhagem (0 = a planta que a bag
   plantou). Falhou → o tick é ignorado (nada acontece, **não** assenta). `c0` = `spreadChance` do config (100% por
   padrão; nenhum item da bag mexe nisso). `H = decayHalfGenerations / (1 + sculk/4)`, padrão 16 — o Sculk acelera o
   decaimento. Nether Star = sem decaimento (`c = c0` sempre). Hiperbólica de propósito: nunca chega a zero, o
   jardim segue crescendo cada vez mais devagar (raio ∝ √tempo) em vez de parar num tamanho finito como uma
   exponencial faria.
2. **Teste de vida útil**: a planta continua com probabilidade `N/(N+1)` (`N` = Rabbit's Foot na bag, padrão
   `defaultLifetimeAttempts` = 8). Falhou → **assenta**. Distribuição geométrica: em média `N` filhos por planta.
3. **Orçamento de gerações**: `restante = cap − g` (`cap` = Bone Meal na bag; sem Bone Meal = `maxGenerations` do
   config, padrão −1 = ilimitado). `restante == 0` → assenta. Filho nascido com orçamento 0 já nasce assentado.
4. **Reproduzir**: busca em anéis (abaixo) e coloca **um** filho. Espécie do filho: com probabilidade
   `speciesInheritance` (0.6) copia a espécie do pai se ela ainda está na pool da bag, senão sorteia da pool por
   peso. Se a busca não acha lugar pra forma sorteada (1 bloco / 2 blocos / creeper), tenta as outras formas da
   pool antes de desistir. Nada achado → **assenta**.

"Assentar" sempre mantém a espécie da própria planta (vira a versão vanilla se sobrevive ali, senão só trava no
lugar) — nunca sorteia outra. `maxDepth` (config, 0 = sem limite) é uma válvula: profundidade ≥ ele assenta na hora.

### Densidade, janela e alcance máximo

`D` = Slime Ball na bag (padrão `defaultDensity` = 16), em flores por 16×16.

| D | alcance máx. (auto) | janela | limite `K` |
|---|---|---|---|
| 1 | 16 | 17×17 | 1 |
| 2 | 16 | 17×17 | 2 |
| 4 | 12 | 17×17 | 5 |
| 8 | 9 | 13×13 | 5 |
| 16 | 6 | 9×9 | 5 |
| 32 | 5 | 7×7 | 6 |
| 64 | 3 | 5×5 | 6 |
| 128 | 2 | 5×5 | 13 |

- Alcance máx. automático `= clamp(ceil(autoSpreadReach · 16/√D), 2, spreadDistance)` (reach 1.5, teto 16). Feather
  na bag **substitui** o automático (1..32) — vira o "alcance máximo" manual, não um salto fixo.
- Janela `R = clamp(round((16·√(6/D) − 1)/2), 2, 8)` (ou `densityCheckRadius` manual ≠ 0) — cresce quando a
  densidade cai, pra o limite continuar estatisticamente significativo (~6 flores) em vez de virar "1" pra qualquer
  densidade baixa. `K = max(1, round(D·(2R+1)²/256))`.
- A densidade é avaliada **no destino** (no candidato), não no pai: um pai numa área cheia ainda pode saltar por
  cima da multidão. Caso especial: se `contagem(pai)+1 ≥ K`, nenhum anel dentro da janela serve — a busca começa no
  anel `R+1` (ou desiste se o alcance ≤ `R`).
- Território (padrão; Fermented Spider Eye desliga): qualquer planta conta como vizinha, não só da mesma família.

### Busca em anéis (`SpreadSearch`)

Anéis de distância 1, 2, … até o alcance máximo (distância euclidiana arredondada, então a frente é redonda), do
mais perto pro mais longe — o jardim preenche as lacunas antes de avançar. Anéis 1–2 são verificados por completo; os
seguintes, `spreadAttempts` amostras cada (início aleatório + passo coprimo, sem alocar). No máx. 8 varreduras de
densidade por busca. `spreadVerticalRange` segue valendo (acompanhar degraus); com Twisting Vines sobe pra
`max(spreadVerticalRange, alcance)`. Creeper busca em cascas 3D (até 16) e agarra a primeira face com suporte sólido.

### Flower block: só no assentamento

- Cada planta rola **uma vez**, quando assenta (não a cada tick). Chance por Moss Block `n` na bag:
  `0.001^((64−n)/63)` — 1 → 0,1%; 8 → 0,2%; 16 → 0,5%; 32 → 3%; 48 → 17%; 56 → 42%; 64+ → 100%.
- Converte o bloco em que a planta se apoia (embaixo se em pé; atrás se inclinada; face ativa se creeper), se
  `PlantSupport.isConvertible`. `PlantSupport.isSupport` passou a aceitar o próprio flower block como suporte, senão
  uma flor inclinada perderia o apoio no instante em que o tronco virasse flower block.
- O flower block sorteia o **próprio** orçamento de gerações, uniforme em 0..`flowerBlockMaxGenerations` (4), e também
  obedece ao teste de vida útil. Só se espalha pra vizinhos convertíveis com face exposta.
- Peças de creeper **não** rolam (decisão conservadora minha — só a planta "de verdade" corrompe).

### Explosão de plantio

Logo depois de a bag plantar a raiz, 2 gerações (`plantingBurstGenerations`) surgem na hora: 2–4 filhos, depois 1–3 de
cada um, no máx. `plantingBurstMaxPlants` (32) no total. Respeita orçamento de gerações, densidade e terreno; ignora a
chance e o teste de vida útil.

### Bag: o que cada item faz agora

| Item | Efeito |
|---|---|
| Bone Meal | teto de gerações (sem ele: `maxGenerations`, ilimitado) |
| Sculk | decaimento mais rápido: `H = 16/(1+sculk/4)` |
| Nether Star | sem decaimento |
| Rabbit's Foot | vida útil média em filhos (`N`, padrão 8) |
| Slime Ball | densidade `D` por 16×16 |
| Feather | alcance máximo manual (1..32) |
| Fermented Spider Eye | ignora outras espécies |
| Twisting Vines | também cresce/inclina em blocos `#flowerdisease:climbable` |
| Moss Block | chance de virar flower block ao assentar (1..64) |

`GardenBagContents` virou `(generations, spreadChance, spreadDistance, densityPer16x16, respectAllSpecies, climbing,
mossBlocks, decayStrength, noDecay, lifetimeAttempts, speciesWeights)`. `SpreadProfileBlockEntity` guarda o perfil +
`depth`; o orçamento restante **não** é mais gravado (é `cap − depth`). O NBT só escreve o que difere do padrão e lê as
chaves antigas (`GenerationsRemaining`, `SpreadChanceOverride`, `SpawnsFlowerBlocks`, …) pra mundos já salvos.

`Config.java` foi reescrito: sumiram `maxNearbyFlowers`/`flowerBlockChance`; entraram `decayHalfGenerations`,
`defaultLifetimeAttempts`, `maxDepth`, `defaultDensity`, `autoSpreadReach`, `speciesInheritance`,
`plantingBurstGenerations`, `plantingBurstMaxPlants`, `flowerBlockMaxGenerations`. O `flowerdisease-common.toml` antigo
foi apagado (autorizado) — o jogo regenera com os novos padrões.

### Comandos de debug (agora exigem op nível 2)

`/cleargarden`, `/diseasedflower debug`, `/diseasedflower profile show` (profundidade, chance atual, janela, alcance…
da planta que você está olhando), `/diseasedflower profile set <chave> <valor>` (um campo por vez: generations,
density, distance, decay, nodecay, lifetime, ignoreothers, climbing, moss, species) e `/diseasedflower profile clear`.

### Ritmo (medido com os testes headless, ver "Bloco 2" abaixo)

Tempo entre tentativas de uma planta, com `c0 = 100%` e o `randomTickSpeed` padrão (17,6 random ticks/planta/dia = um a
cada ~68 s reais): ~1 min na geração 0, ~2,3 min na 16, ~4,5 min na 48, ~8 min na 100. **A frente do jardim anda bem mais
rápido do que eu havia estimado** (eu chutei "raio ~20 em ~40 min"): jardim padrão (Poppy/Dandelion, sem modificadores),
chão que nunca acaba, 1 "dia" = 20 min reais de random ticks:

| dia | plantas | raio da frente | ainda ativas | profundidade média (das ativas) |
|---|---|---|---|---|
| 1 | 1002 | 65 | 328 | 14 |
| 2 | 2876 | 112 | 550 | 25 |
| 3 | 5029 | 143 | 749 | 33 |
| 4 | 6424 | 161 | 441 | 41 |
| 5 | 6864 | 180 | 155 | 49 |

(a região de teste acabou por volta do dia 6–7, então o fim da tabela é limite da região, não do mecanismo). Cada geração
avança ~4 blocos (o filho vai pro anel livre mais próximo, que na borda do jardim é o 5º–6º); o tempo por geração cresce
linearmente com a profundidade (`c(g)`), então a velocidade cai, mas devagar. O botão pra desacelerar é `spreadChance`
(`c0`) no config e o Sculk na bag.

**Densidade efetiva**: o limite `K` só protege a janela do candidato NO MOMENTO da colocação, então o campo satura acima
do alvo — medido: D=4 → ~2,4× (≈10 por 16×16), D=16 → ~1,9× (≈30, média de 5,5 vizinhos na janela 9×9 contra o limite 5),
D=64 → ~1,5×. É o mesmo comportamento do mecanismo antigo (`maxNearbyFlowers`), só com a janela agora escalando com D.
Não foi recalibrado (seria mudar o visual que já foi aprovado em jogo); se quiser que "D por 16×16" seja literal, o
caminho é escalar `K` por ~0,5.

**Tamanho em disco** (NBT serializado, sem compressão): planta comum ~136 bytes, com todos os modificadores ~284 bytes,
flower block ~184. `CompoundTag#sizeInBytes` (contabilidade em memória) dá ~6× isso — não confundir. Como uma planta que
assenta vira flor vanilla e perde o BE, só existem BEs pra plantas ativas, creepers e flower blocks. Isso enfraquece o
argumento de peso do registro compartilhado (bloco 3): 100 mil BEs seriam ~14 MB hoje.

### Bloco 2 — ferramentas de debug e testes headless (feito)

- **`/diseasedflower stats`** (op 2): censo dos blocos do mod nos chunks ao redor dos jogadores (`GardenStats`): ativas,
  assentadas no lugar, peças de creeper, flower blocks, profundidade média/máxima, nº de BEs e tamanho amostrado. Só vê o
  que ainda É bloco do mod — planta que virou flor vanilla é indistinguível de uma flor qualquer do mundo.
- **`/diseasedflower day [n]`** e **Alt+D** (`DayAdvance`): avança `n` dias (1–30) ticando SÓ as plantas do mod, na mesma
  taxa que o vanilla daria (`24000·randomTickSpeed/4096` por posição por dia, distribuição de Poisson), em 120 passos por
  dia pros filhos nascidos cedo também tickarem depois. Fatiado em 40 ms/tick de servidor, progresso na action bar, resumo
  com o antes/depois no fim; partículas de debug ficam desligadas enquanto roda; `/diseasedflower day stop` cancela; para
  sozinho depois de 20 exceções. Um job por vez, estado descartado em `ServerStoppedEvent`. É o motor reaproveitável pro
  futuro bone meal "pula um dia". Alt e não Ctrl+D porque Ctrl+D é sprint+strafe.
- **Guarda de chunks não carregados** (achado ao medir o alcance): a busca chega a `alcance + janela` blocos (até 24 por
  padrão, 40 com Feather), o que passa do anel de chunks que o vanilla garante carregado ao redor de um chunk que tica —
  ler um bloco lá forçaria o servidor a carregar/gerar chunks dentro do random tick. Agora `randomTick` e a explosão de
  plantio pulam o tick (sem assentar — assentar congelaria pra sempre toda planta na borda) quando
  `level.isAreaLoaded(pos, SpreadMath.searchReach(perfil))` é falso.
- **Testes headless** (`FlowerDiseaseGameTests`, roda com `./gradlew runGameTestServer`, ~1 min, sem GUI; só existe em
  ambiente de desenvolvimento). Usa o framework de GameTest do NeoForge com uma arena vazia de 48×20×48
  (`data/flowerdisease/structure/arena.nbt`). Cobre: plantio+explosão pelo mesmo código da bag; média de filhos por planta
  = número de Rabbit's Foot (1440 tentativas, N=4 → 4,10); Moss 64 corrompe o chão de cada planta ao assentar e os flower
  blocks se espalham até assentar; jardins padrão/esparso/denso por 3 dias via `DayAdvance`; teste de fumaça com parede,
  troncos, alta, creeper, escalada e Moss 64; ida-e-volta de NBT (perfil completo, perfil padrão = 0 bytes, chaves antigas,
  BE de verdade com profundidade); creeper plantável no chão, na parede e no teto; e a linha do tempo de 8 dias acima.
  Todo teste termina checando invariantes: nenhuma planta que não sobrevive onde está, nenhuma metade de planta alta
  solta, todo bloco doente com BlockEntity. 10/10 passando, 0 exceções.

### Ainda por fazer

- **Bloco 3 — registro compartilhado de perfis** (`SavedData` + `gardenId` no BE; BEs antigos migram no primeiro tick).
- **Bloco 4 — creeper em mancha**: energia da semente 0–5 (pesos 1,2,3,3,2,1), cresce via `MultifaceSpreader`
  ignorando densidade, peças estéreis. Vinhas pendentes e "corrosão de cavernas" ficam pra depois.

## Decisão nova (durante a implementação, não estava no plano original)

- **`canSurvive` NUNCA lê o perfil/BlockEntity, em nenhuma das 5 classes.** Ficou explícito ao implementar:
  ficar em pé numa "ponta de bloco escalável" (`PlantSupport.canStandOn`) ou colado numa parede escalável
  (`canClingTo`) é sempre estruturalmente permitido, independente de `profile.climbing()`. Só a BUSCA por
  novos alvos de espalhamento (`DiseasedPlantLogic#findSpreadTarget`) checa o profile. Isso é o que a
  seção 1.2 do plano original já previa ("regra crítica"), só reforçando que foi seguido à risca.

## Decisões travadas (perguntadas ao dono do projeto antes do plano)

1. **Flower block converte o bloco DE BAIXO** (o suporte), não a própria flor. A planta continua viva e se
   reproduzindo em cima do flower block novo.
2. **Contador de gerações sai do blockstate e vai pro BlockEntity.** Com as propriedades novas (FACING,
   SETTLED, 6 faces do creeping), manter `generation` como propriedade levaria o mod a ~70.000 blockstates
   (o vanilla 1.21 inteiro tem ~26.000). Movendo pro BE: ~1.100 no total — menos que os ~2.600 de hoje.
3. **Creeping só pras 4 flores grandes**: Sunflower, Lilac, Rose Bush, Peony (sem Tall Grass/Large Fern).
4. **Twisting Vines** é o item que liga a escalada na bag. Moss Block (já definido pelo dono) liga o flower
   block.

## Fase 0 — Fundação (refactor + infraestrutura compartilhada)

Nada visível em jogo; é o alicerce que as 3 features usam. Testar depois dela é só confirmar que o
comportamento atual não mudou.

### 0.1 Gerações: blockstate → BlockEntity

- Remover `SettleTable.GENERATION` (`IntegerProperty` 0-64) e as chamadas `setValue(GENERATION, ...)` /
  `getValue(GENERATION)` nas 8 classes de bloco e no `DiseasedPlantLogic`.
- A semântica já existe no `SpreadProfileBlockEntity`: `generationsRemaining` com
  `NO_GENERATIONS_OVERRIDE = -2` significando "sem override → usa `Config.FLOWER_MAX_GENERATIONS`". Depois
  da remoção, é essa a única fonte.
- `DiseasedPlantLogic#placeChild` passa a SEMPRE gravar a geração do filho no BE dele (hoje só grava quando
  `profile.hasOverride()`), senão a linhagem plantada na mão perde o contador.
- Uma flor colocada por `/setblock`, worldgen ou outro mod cai no default (`Config.FLOWER_MAX_GENERATIONS`),
  igual a plantada na mão hoje.
- **Risco aceito**: mundos de teste antigos têm a propriedade `generation` salva; o Minecraft descarta
  propriedades desconhecidas ao carregar e as flores voltam pro estado default. Como é mod em
  desenvolvimento e o fluxo de teste já usa Ctrl+P pra limpar tudo, não vale escrever migração.

### 0.2 Propriedade `SETTLED` e nova regra de settle

Hoje `settle()` sempre coloca o bloco vanilla (`fallbackBlock`). Isso quebra nas features novas: uma flor
inclinada num tronco ou uma creeping numa parede não têm equivalente vanilla que sobreviva ali — o bloco
vanilla apareceria e o primeiro update de vizinho o destruiria (com drop).

- Nova `BooleanProperty SETTLED` (default `false`) nas classes que se espalham.
- `isRandomlyTicking(BlockState)` sobrescrito → `!state.getValue(SETTLED)`: planta assentada para de custar
  random tick, sem precisar de um bloco terminal separado.
- Nova regra em `DiseasedPlantLogic#settle(...)`:
  1. Se o `fallbackBlock` é o PRÓPRIO bloco atual (caso das creeping, ver Fase 2) → assenta no lugar
     (`SETTLED = true`), preservando orientação/faces.
  2. Senão, se `fallbackBlock.defaultBlockState().canSurvive(level, pos)` → coloca o vanilla
     (comportamento de hoje, inalterado pro caso comum de flor no chão).
  3. Senão (flor inclinada num tronco, etc.) → assenta no lugar (`SETTLED = true`).
- Efeito colateral bom: some o risco histórico do bug #3 ("flor presa pra sempre tentando") sem precisar
  colocar um bloco inválido.
- Como os blocos Diseased já reaproveitam a textura vanilla, uma flor assentada "no lugar" é visualmente
  idêntica à flor vanilla — o jogador não percebe a diferença.

### 0.3 `PlantSupport.java` (novo) — classificação de suporte por tag

Um utilitário estático só com as perguntas "esse bloco serve de apoio?" e "esse bloco pode ser corrompido?",
todas resolvidas por **tag de datapack** pra que modpack/outros mods funcionem sem código:

| Tag | Uso | Conteúdo default |
|---|---|---|
| `#flowerdisease:climbable` | onde flor pode crescer em cima/inclinada (Fase 1) | `#minecraft:logs`, `#minecraft:leaves`, `minecraft:moss_block`, `minecraft:mangrove_roots` |
| `#flowerdisease:convertible` | o que o flower block pode comer (Fase 3) | `#minecraft:dirt`, `#minecraft:sand`, `minecraft:gravel`, `#minecraft:base_stone_overworld`, `#minecraft:logs`, `#minecraft:leaves` |
| `#flowerdisease:conversion_immune` | veto explícito, avaliado depois | `minecraft:bedrock`, `minecraft:obsidian`, blocos de portal/spawner |

Regras em código, além das tags (defesa em profundidade pra modpack):

- nunca converter bloco que tenha `BlockEntity` (baús, máquinas de outros mods);
- nunca converter bloco com `getDestroySpeed() < 0` (indestrutível);
- conversão inteira atrás de um toggle de config (`flowerBlockConversion`, default ligado).

### 0.4 Perfil por planta + itens novos na bag

- `SpreadProfileBlockEntity` ganha dois campos booleanos: `climbing` e `spawnsFlowerBlocks` (com
  save/load NBT e propagação em `copyFrom`).
- **Refactor de qualidade junto**: `configure(...)` já tem 6 parâmetros posicionais e iria pra 8. Trocar por
  um `record SpreadProfile(long generations, double spreadChance, int spreadDistance, int densityPer16x16,
  boolean respectAllSpecies, boolean climbing, boolean spawnsFlowerBlocks, List<String> speciesWeights)`,
  que o `GardenBagContents` já produz pronto e o BE só copia. Remove a chance de trocar dois booleanos de
  posição sem o compilador reclamar.
- `GardenBagContents`: `CLIMBING_ITEM = Items.TWISTING_VINES`, `FLOWER_BLOCK_ITEM = Items.MOSS_BLOCK`, ambos
  booleanos por presença (igual ao Fermented Spider Eye).
- `GardenBagMenu.isBagItem` aceita os dois itens novos.
- `GardenBagScreen`: o painel vai de 6 pra 8 linhas ("Climbs logs/leaves: yes/no", "Creates flower blocks:
  yes/no") → ajustar `GRID_TOP_Y`, `inventoryLabelY`, `imageHeight` e as posições dos slots no menu.
- Lang: 2 tooltips novos no item da bag.

## Fase 1 — Escalada em blocos orgânicos

Alvo: as 5 classes de bloco de 1 bloco (`DiseasedFlowerBlock`, `DiseasedWitherRoseBlock`,
`DiseasedGrassBlock`, `DiseasedDeadBushBlock`, `DiseasedDecorativeFlowerBlock`) = 28 blocos registrados.

### 1.1 Propriedade `FACING`

- `DirectionProperty.create("facing", UP, NORTH, EAST, SOUTH, WEST)` — 5 valores. Sem `DOWN` por enquanto
  (flor pendurada embaixo de um bloco não foi pedida).
- Semântica igual à do Amethyst Cluster do vanilla: `FACING` é pra onde a flor APONTA; o suporte fica em
  `pos.relative(facing.getOpposite())`. `FACING = UP` reproduz exatamente o comportamento de hoje.

### 1.2 `canSurvive`

- Helper compartilhado em `PlantSupport`, chamado pelo override de cada classe:
  - `FACING = UP` → regra vanilla da espécie (`super.canSurvive`) **OU** bloco de baixo está em
    `#flowerdisease:climbable`;
  - `FACING` horizontal → bloco do lado oposto precisa estar em `#flowerdisease:climbable` **e** ter face
    firme (`isFaceSturdy`) voltada pra flor.
- **Regra crítica**: `canSurvive` NÃO pode consultar o `BlockEntity`. Ele roda em carregamento de chunk e
  update de vizinho, momentos em que o BE pode não existir ainda — se a permissão de escalar dependesse do
  perfil, flores sumiriam sozinhas ao recarregar o mundo. Portanto: **escalar é regra de ONDE a flor procura
  se espalhar (perfil da bag), não de onde ela consegue sobreviver** (sempre permitido). Flor já colocada
  num tronco nunca cai por causa disso.
- Consequência aceita: se as folhas em que a flor está decaírem, ou o jogador quebrar o tronco, a flor cai
  como qualquer planta vanilla sem suporte.

### 1.3 `getShape` e `getStateForPlacement`

- `getShape`: mapa estático `Direction → VoxelShape` (a caixa da flor rotacionada); `UP` delega pro shape
  vanilla da superclasse.
- `getStateForPlacement`: clicar na lateral de um bloco escalável coloca inclinado (FACING = face clicada);
  clicar no topo coloca em pé. Se o estado resultante não sobrevive, cai pra `UP` ou recusa.

### 1.4 Motor de espalhamento

- `DiseasedPlantLogic#findSpreadTarget` ganha consciência de escalada, **só quando `profile.climbing()`**:
  - além do alvo normal (célula vazia com chão plantável embaixo), aceita (a) célula vazia em cima de bloco
    escalável e (b) célula vazia encostada na LATERAL de um bloco escalável → filho nasce com `FACING`
    apontando pro ar.
  - **Alcance vertical**: `spreadVerticalRange` (default 2) é baixo demais pra subir uma árvore. Quando a
    escalada está ligada, o alcance vertical passa a ser `max(spreadVerticalRange, spreadDistance)` — ou
    seja, a Feather na bag também controla quão alto sobe. Sem knob de config novo; documentar no tooltip.
- `placeChild` grava o `FACING` sorteado no estado do filho.
- **Espécies de 2 blocos nunca inclinam**: elas podem nascer EM CIMA de tronco/folha, mas não na lateral
  (uma planta de 2 blocos pendurada na parede precisaria de duas metades inclinadas e fica quebrada). Quem
  cobre "flor grande subindo parede" é a versão creeping da Fase 2.

### 1.5 Recursos — **SUPERADA, ver "Status" no topo do documento pro que foi feito de verdade**

~~- 2 modelos-pai novos: `flowerdisease:block/tilted_cross` e `.../tilted_tinted_cross` — o `cross` com os
  elementos rotacionados ~45° e deslocados encostando no suporte (o "tipo tocha" pedido). Rotação de 45°
  precisa estar no MODELO; blockstate só rotaciona em múltiplos de 90°.
- 28 modelos de bloco inclinado (3 linhas cada, `{"parent": "...tilted_cross", "textures": {...}}`).
- 28 blockstates reescritos: `multipart` com `when: {facing: up}` → modelo normal, e uma entrada por
  direção horizontal → modelo inclinado + rotação `y`.~~ (premissa errada: blockstate ACEITA rotação `x`/`y`
  em qualquer grau, não só múltiplos de 90 — só a ROTAÇÃO EM TORNO DE `y` pra encaixar em texturas
  não-simétricas é que costuma ficar estranha fora de 90 em 90; pra um `cross` simétrico não tem esse
  problema. Isso tornou os modelos novos desnecessários, ver "Status".
- 1 arquivo de tag (`#flowerdisease:climbable`) — isso continua igual, feito na Fase 0.3.

## Fase 2 — Versão creeping (Sunflower, Lilac, Rose Bush, Peony)

### 2.1 Blocos

- `CreepingFlowerBlock extends MultifaceBlock implements EntityBlock` — mesma base do Glow Lichen/Sculk
  Vein do vanilla: 6 propriedades booleanas de face, ocupa várias faces do mesmo bloco, gruda em qualquer
  face firme (orgânica ou não, como pedido). Mais a `SETTLED` da Fase 0 e o `SpreadProfileBlockEntity`.
- 4 blocos + 4 itens: `sunflower_creeper`, `lilac_creeper`, `rose_bush_creeper`, `peony_creeper`.
- **Um bloco só por espécie, não dois.** O padrão atual do mod é "bloco terminal + bloco Diseased que se
  espalha"; aqui o terminal não existe no vanilla, então usar `SETTLED` no mesmo bloco evita 4 blocos e ~16
  arquivos de recurso extras. Consequência: `diseasedByFallback()` mapeia o creeper pra ele mesmo, e o
  settle cai na regra 1 da Fase 0.2 (assenta no lugar). Isso quebra a invariante antiga "o bloco Diseased é
  sempre diferente do fallback" — está previsto e tratado explicitamente.
- Verificar na implementação a API exata de `MultifaceBlock` no 1.21.1 (`getFaceProperty`, `canAttachTo`,
  `getSpreader`).

### 2.2 Motor: `Shape.CREEPING`

- `shapeOf(block)`: `MultifaceBlock` → `CREEPING`; `DoublePlantBlock` → `TALL`; senão `SINGLE`.
- Busca de alvo pro filho creeping: posição aleatória dentro do `spreadDistance` (com alcance vertical
  também = `spreadDistance`, já que a graça é subir), procurando uma célula vazia encostada numa face firme;
  o filho nasce com a face correspondente ligada.
- Sem lógica de cadeia, como o resto do mod: uma Poppy pode gerar um Rose Bush Creeper e vice-versa — o
  formato sorteado do FILHO é que decide como a posição é procurada (já é assim desde a etapa 1).
- O parâmetro de distância continua valendo (ela "pula" dentro do raio e gruda onde couber), diferente do
  glow lichen vanilla que só anda de face em face. É mais coerente com o resto do mod; se ficar estranho em
  teste, trocar por `MultifaceSpreader` (adjacência pura).

### 2.3 Integrações

- `SettleTable.isAnyPlant` (modo territorial) hoje só reconhece `BushBlock` — passa a reconhecer também
  `MultifaceBlock` nosso, senão um jardim creeping não conta como lotação pra ninguém.
- Recursos: 1 modelo-pai (plano/face única, `render_type: cutout`, espelhando o do glow lichen), 4
  blockstates multipart (uma entrada por face, ignorando `SETTLED`), 4 modelos, 4 modelos de item, 4 loot
  tables, 4 entradas de lang. Textura = `minecraft:block/<espécie>_bottom` (placeholder até a arte final).

## Fase 3 — Flower block (corrupção do terreno)

### 3.1 Bloco

- Classe `FlowerMassBlock` (nome evita colisão com o `FlowerBlock` do vanilla, que o mod já importa), id
  `flowerdisease:flower_block`, nome de exibição "Flower Block". Cubo cheio com aparência de Moss Block
  (textura `minecraft:block/moss_block` como placeholder), `SETTLED` + `SpreadProfileBlockEntity` +
  random tick.
- Precisa ser chão válido pra flor (a planta continua em cima dele!). Solução: adicionar
  `flowerdisease:flower_block` à tag vanilla `#minecraft:dirt` via datapack — exatamente o que o vanilla faz
  com o próprio Moss Block. Sem isso, a flor em cima morre no primeiro update.
- Também entra em `#minecraft:mineable/hoe` (como o musgo) pra ferramenta de qualquer mod se comportar.

### 3.2 Criação (flor → converte o bloco de baixo)

- No random tick, se `profile.spawnsFlowerBlocks()` e a planta ainda é "reprodutiva" (tem gerações
  sobrando), rola uma chance BAIXA (`Config.FLOWER_BLOCK_CHANCE`, default ~0.005) de converter
  `pos.below()`.
- Converte só se passar em `PlantSupport.isConvertible(...)` (tags + sem BlockEntity + destrutível).
- O flower block nasce com `geração do pai - 1` e uma cópia do perfil; se `geração - 1 == 0`, nasce já
  `SETTLED` (inerte).
- A flor em cima **continua viva e se reproduzindo** (decisão 1).

### 3.3 Espalhamento do flower block

- Random tick próprio, com chance menor que a da flor: `spreadChance * Config.FLOWER_BLOCK_SPREAD_FACTOR`
  (default ~0.25), atendendo o "ritmo de reprodução mais lento que o da flor".
- **Só vizinhos adjacentes** (as 6 faces), como pedido: sorteia uma face, converte se for elegível, filho
  com `geração - 1` e perfil copiado.
- Sem alvo válido ou sem geração → `SETTLED = true` (para de tickar), mesma regra do resto do mod.

### 3.4 Reversibilidade (proposta minha, não pedida)

O bloco convertido some pra sempre — em modpack isso é assustador e `/cleargarden` transformaria a
corrupção em cratera. Proposta: o BE do flower block guarda o `BlockState` que ele substituiu (é um campo
NBT pequeno), e:

- `/cleargarden` restaura o bloco original em vez de virar ar;
- abre caminho pra um futuro item de "cura" que reverte a corrupção.

Custo baixo, evita destruição irreversível. Se preferir sem isso, é só cortar — mas recomendo manter.

## Fase 4 — Compatibilidade, debug e documentação

- `/cleargarden`: hoje limpa `instanceof BushBlock`; estender pros creeping (`MultifaceBlock` nosso) e pros
  flower blocks (restaurando o bloco original, ver 3.4).
- Aba criativa: inserir os 4 creepers e o flower block em posições coerentes.
- Config novo: `flowerBlockConversion` (toggle), `flowerBlockChance`, `flowerBlockSpreadFactor`, e talvez
  `climbVerticalRange` se o `spreadDistance` não for suficiente em teste.
- `PLANNING.md` atualizado com o que virou realidade (e este arquivo removido no fim da etapa).

## Checklist de compatibilidade com outros mods (modpack)

1. **Tudo por tag** (`#minecraft:logs`, `#minecraft:leaves`): madeiras/folhas de outros mods funcionam sem
   uma linha de código, e o pack pode ajustar sem recompilar.
2. **Tags próprias** (`climbable`, `convertible`, `conversion_immune`) pra o autor do pack restringir ou
   ampliar o que a corrupção come.
3. **Nunca converter bloco com BlockEntity nem indestrutível** — protege baús, máquinas, bedrock.
4. **Kill-switch de config** pra desligar a conversão de blocos inteira.
5. **Orçamento de blockstates** fica em ~1.100 (menos que os ~2.600 de hoje) por causa da decisão 2.
6. **Sem mixin, sem coremod, sem substituir classe vanilla** — superfície de conflito mínima.
7. **Só random tick nativo**, nenhum ticker/scheduler próprio: carga de servidor previsível e proporcional
   ao `randomTickSpeed` do pack.
8. **Não adicionar nossas flores a `#minecraft:small_flowers`** (mudaria comportamento de abelhas/aldeões e
   receitas de corante em packs) — decisão deliberada.
9. **`#minecraft:dirt`** ganha só o flower block, espelhando o Moss Block do vanilla.
10. Tudo namespaced em `flowerdisease:`; nenhuma id vanilla sobrescrita.

## Riscos conhecidos

- **Visual das flores inclinadas**: não consigo ver o jogo renderizando, então o ângulo/offset do modelo
  `tilted_cross` é chute matemático. Esperar uma ou duas rodadas de ajuste depois do seu teste.
- **API do `MultifaceBlock`** no 1.21.1 precisa ser conferida na hora de implementar (assinaturas mudaram
  entre versões).
- **Mundos de teste antigos** perdem o `generation` salvo (ver 0.1).
- **Conversão de terreno é destrutiva** — mitigada por tags, config e pela proposta 3.4.

## Ordem de execução e pontos de parada pra teste

| Fase | O que testar | Commit |
|---|---|---|
| 0 | Nada deve mudar em jogo: plantar pela bag, espalhar, assentar | `refactor: generation no BE + settle in place` |
| 1 | Bag com Twisting Vines perto de uma árvore: flores subindo tronco/folhas, inclinadas na lateral | `feat: escalada em blocos orgânicos` |
| 2 | Bag com um creeper: espalhamento subindo parede de pedra e de tronco | `feat: flores creeping` |
| 3 | Bag com Moss Block: corrupção aparecendo embaixo das flores e se espalhando devagar | `feat: flower block` |
| 4 | Ctrl+P limpando tudo, inclusive corrupção; aba criativa | `chore: debug/compat/docs` |

Cada fase é um commit separado na branch, então dá pra voltar atrás em qualquer ponto sem perder as outras.
