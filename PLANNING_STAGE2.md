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
- **Próximo passo: Fase 1 (escalada)**, ainda não iniciada.

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

### 1.5 Recursos (gerados por script, como os 48 da etapa anterior)

- 2 modelos-pai novos: `flowerdisease:block/tilted_cross` e `.../tilted_tinted_cross` — o `cross` com os
  elementos rotacionados ~45° e deslocados encostando no suporte (o "tipo tocha" pedido). Rotação de 45°
  precisa estar no MODELO; blockstate só rotaciona em múltiplos de 90°.
- 28 modelos de bloco inclinado (3 linhas cada, `{"parent": "...tilted_cross", "textures": {...}}`).
- 28 blockstates reescritos: `multipart` com `when: {facing: up}` → modelo normal, e uma entrada por
  direção horizontal → modelo inclinado + rotação `y`.
- 1 arquivo de tag (`#flowerdisease:climbable`).

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
