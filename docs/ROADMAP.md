# ForgeAI — Análise de arquitetura e roadmap técnico

> Documento de trabalho. Análise do estado atual (commit `64057d3`, 1.0.8) +
> lista priorizada de TDs para os objetivos: gerenciamento de modelos, UI moderna,
> multimodal real, aceleração por hardware e família Bonsai 2 (PrismML) —
> tudo com **Z Fold 8 como dispositivo de referência**.

---

## 0. Dispositivo alvo (verificado no próprio aparelho)

| Item | Valor |
|---|---|
| Modelo | SM-F971B (Z Fold 8) |
| SoC | SM8850 / `canoe` — Snapdragon 8 Elite Gen 5 |
| CPU | 8 núcleos ARMv9.2: `sve2`, `svei8mm`, `i8mm`, `bf16`, `sme`, `asimdfhm`, `sha512`; prime a 4.74 GHz |
| GPU | Adreno (`vulkan.adreno.so`, `libOpenCL_adreno.so`, kgsl max 1.3 GHz) |
| NPU | Hexagon: `/dev/fastrpc-cdsp`, `libSnpeHtpV81Stub.so` no vendor — **sem `libQnnHtp*.so`/Skel** |
| RAM | 11,35 GB (≈4,7 GB disponíveis em repouso) + swap/zram |
| Android | 17 (SDK 37) |
| Telas | interna 2448×1848 @420dpi (4:3) · capa 1248×1972 |
| Páginas | 4 KB (`KernelPageSize: 4 kB`) |

**Consequência direta:** o projeto foi calibrado para outro aparelho (README fala em
"Exynos 9820 / Mali-G76") e o build nativo é `armv8.2-a+dotprod`
(`scripts/build_native.sh:33,86`, `app/src/main/cpp/CMakeLists.txt:71`). Isso é,
hoje, a maior perda de desempenho do app — e a mais barata de corrigir.

---

## 1. Retrato do código

```
8.257 linhas · 1 commit · 0 testes · 0 CI · 0 documentação de API
Kotlin 2.2.21 · AGP 8.13.2 · Compose BOM 2024.12.01 (defasada)
compileSdk 34 · targetSdk 34 · minSdk 26 · versionCode 9 (1.0.8)
nativo: llama.cpp b10333 + mtmd · JNI 988 linhas · .so NÃO versionado
```

Camadas reais:

```
MainActivity (440)  ──►  AppViewModel (1031)  ──►  data/* (4 arquivos, ~850)
                                   │                    │
                                   └──────────────►  LlamaEngine (object global, 203)
                                                            │
                                                     llama_jni.cpp (988, JNI + llama.cpp)
ui/screens (2688)  ──►  mesmas VMs, estado lido direto dos `mutableStateOf` do VM
```

### O que está bem feito (não mexer)

- **Engenharia nativa acima da média**: reuso de KV-cache validado token-a-token
  (`llama_jni.cpp:700+`), streaming em lote com segurança UTF-8, `mtmd` para visão,
  decodificação do prompt em chunks de `n_batch`, cópia de strings JNI na thread
  chamadora (evita UB), join da thread antes de recarregar.
- **Downloader resiliente**: `Range`/resume, retry com backoff, watchdog de 60 s,
  shards `-00001-of-00002`, sidecar `.part.meta.json` para retomar após morte do app.
- **Download por URL do HuggingFace** com resolução de repositório/arquivo, merge de
  shards e escolha do `mmproj` (prefere Q8_0). Boa UX para o problema.
- **Documentação** (README + PUBLISHING) com medições reais e _pitfalls_ — raro.
- **Superfície de dependências mínima** (5 libs). Fácil de evoluir.

### O que está mal (e vai doer em cada feature nova)

| Problema | Evidência |
|---|---|
| ViewModel-deus: 17 estados + 40 funções de 3 domínios (chat, modelos, settings) | `ui/AppViewModel.kt` (1.031 linhas) |
| Persistência em SharedPreferences com JSON gigante reescrito a cada mensagem | `AppViewModel.writeChats()` + `core/Settings.kt:90-136` |
| Engine como `object` global (1 modelo, 1 contexto, 1 thread, canal global) | `LlamaEngine.kt` |
| Tools implementadas como injeção de prompt (4 parsers manuais) porque `libcommon` está OFF | `ToolSupport.kt` (415) · `CMakeLists.txt:37` |
| Telas monolíticas, tokens visuais inline, strings hardcoded | `ChatScreen.kt` (1141), `ModelsScreen.kt` (832), `SettingsScreen.kt` (715) |
| Layout único, sem adaptatividade e **retrato travado** | `AndroidManifest.xml:23` |
| Retrabalho de layout por token no streaming | `ChatScreen.kt:245-247` (`scrollToItem` em cada update) |
| Zero testes / zero CI / sem lint / sem release automatizado | — |
| Sem medição de RAM, sem estimativa de carga, sem tratamento de OOM | nenhuma leitura de `VmRSS`/`MemoryInfo` no projeto |

### Veredito

**Não reescrever.** 8,2 mil linhas bem comentadas são um ativo; o problema não é o
tamanho, é a **ausência de fronteiras**. Nota atual: **6,5/10 como protótipo**,
**3/10 para o roadmap pedido** — porque cada item do roadmap (RAM, quantizar, áudio,
QNN, foldables) hoje passa pelo mesmo `AppViewModel` e pelas mesmas 3 telas.

Recomendação: **refatoração em 3 ondas**, entregando valor em cada uma:

1. **Onda 0 — fundação de build** (2–3 d): NDK/16 KB, SDK alvo, CI, `.so` como artefato.
2. **Onda 1 — fronteiras** (~1 semana): módulos, sessão nativa, Room/DataStore,
   ViewModels separados, eventos com backpressure.
3. **Onda 2 — features** (roadmap), **Onda 3 — plataforma** (foldable, NPU).

---

## 2. Diagnóstico por objetivo

### 2.1 Aceleração por hardware (CPU/GPU/NPU)

**Hoje:** CPU-only (Vulkan existe mas é opt-in e desligado por padrão:
`build_native.sh:46`), kernels compilados para `armv8.2-a+dotprod`, 4 threads fixas
(`core/Settings.kt:24`) em um SoC de 8 núcleos ARMv9.2.

**Ganhos disponíveis, em ordem de custo/benefício:**

1. `GGML_CPU_ARM_ARCH` = ARMv9 (`i8mm`, `bf16`, `sve2`) + `GGML_CPU_ALL_VARIANTS`
   para o APK distribuído (portátil **e** rápido). Ganho esperado no *prompt*:
   alto (i8mm/SVE batem dotprod com folga em Q4_K/Q8_0); no *decode*: dependente de
   banda de memória, mas os kernels SVE/i8mm tendem a ganhar 10–30%.
2. **Vulkan ligado por padrão com fallback automático** (Adreno + driver presente).
   É o que entrega o salto real em modelos 2B+; hoje está escondido atrás de
   `FORGE_VULKAN=1` e de um slider manual de camadas.
3. `threads` automáticas de verdade (6–8 no decode; `n_threads_batch` já usa 8).
4. **OpenCL (Adreno)**: alternativa ao Vulkan para pré-processamento de prompt;
   comparar antes de decidir (TD-22).
5. **NPU/QNN — realidade:** seu aparelho tem Hexagon + fastrpc, mas **não** tem as
   libs QNN (`libQnnHtpV81Skel.so`, `libQnnHtp.so`) no vendor — elas vêm do
   Qualcomm AI Engine Direct SDK e precisam ser empacotadas no APK, com aceite de
   licença e skel assinado. E o llama.cpp **não tem backend QNN estável**; o caminho
   oficial em desenvolvimento é `ggml-hexagon`. Alternativa madura para NPU é
   **ONNX Runtime + QNN EP** (outra stack, outro formato de modelo — não GGUF).
   **Não colocar QNN no caminho crítico do v1**: spike com meta mensurável (TD-21)
   enquanto Vulkan entrega o ganho concreto.

**Risco de plataforma:** `targetSdk 34` em aparelho Android 17 obriga correr atrás de
comportamentos (edge-to-edge, predictive back, page size de 16 KB exigido pelo Play
desde 2025, `dataSync` FGS). Resolver na Onda 0.

### 2.2 Modelos Bonsai 2 (PrismML) — viabilidade verificada

Família no Hub (`prism-ml/Ternary-Bonsai-2-{2B,4B,9B,27B}-gguf`), base Qwen3.5,
ternary, 262k de contexto, com torre de visão:

| Packing | 2B | 4B | 9B | Observação |
|---|---:|---:|---:|---|
| `PTQ1_0` | 0,43 GB | 1,15 GB | 2,42 GB | tipo privado (`GGML_TYPE_PTQ1_0 = 143`) |
| `PQ2_0` | 0,52 GB | 1,27 GB | 2,42 GB | tipo privado (`GGML_TYPE_PQ2_0 = 142`) |
| `Q2_0` | 0,55 GB | — | 2,56 GB | tipo **42, já existente no upstream** |
| `MTP` (draft head) | — | 1,58 GB | 1,57 GB | spec decoding integrado |
| `mmproj-Q8_0` (visão) | 0,37 GB | — | 0,62 GB | existe Q8_0 (o app prefere quantizado ✅) |

**Fato decisivo:** a base pinada (`b10333`, ago/2026) **já tem** `GGML_TYPE_Q2_0`,
arquitetura `QWEN35`, o caminho de **Hadamard** (11 referências em
`src/llama-graph.cpp`) e `mtmd` com chunks de **áudio**. Já `PQ2_0`/`PTQ1_0` e o
`--spec-type draft-mtp` vivem no fork `PrismML-Eng/llama.cpp` (branch `prism`).

Ou seja: **talvez não seja necessário trocar de base** — o packing `Q2_0` pode carregar
no upstream. Isso muda o risco do objetivo de "migrar para um fork de terceiro" para
"validar em 1 dia de spike". Prioridade máxima de investigação (TD-03).

### 2.3 Gerenciamento de modelos

**Existe:** listar (scan + sidecar `.meta.json`), baixar (resume/retry/shards),
carregar/descarregar, excluir, inspecionar metadados GGUF, URL do HF com escolha de arquivo.

**Falta:** RAM em tempo real (zero instrumentação), estimativa de memória antes de
carregar (modelo + KV), aviso de OOM/`onTrimMemory`, quantização in-app
(`llama_model_quantize` não exposto), comparação de quants, benchmark integrado,
download em `ForegroundService` (hoje `viewModelScope` → morre com o app; resume é
paliativo), verificação SHA256, perfis por dispositivo, escolha de KV cache
(type_k/type_v), presets de contexto, "aquecimento" (warmup) e troca de modelo sem
perder a conversa.

### 2.4 Interface

**Hoje:** Material 3 + tema AMOLED/violeta, drawer, 3 abas, bolhas com gradiente,
markdown próprio (325 linhas), indicadores de tool/visão. Não é "app de 2015" no
visual — mas é "app de 2024" na estrutura: **um único painel esticado**.

No Fold 8 (2448×1848) isso é o pior cenário: o app **trava em retrato**
(`AndroidManifest.xml:23`) na tela interna. Falta:

- `WindowSizeClass` + `FoldingFeature` (postura, dobradiça, tabletop) e **dois painéis**
  (conversas | chat) na tela grande, mantendo um painel na capa;
- `NavigationSuiteScaffold` (rail na tela grande, barra na capa) em vez de drawer;
- streaming suave (sem `scrollToItem` por token), `LazyColumn` com âncora e
  `animateItem`, cópia/seleção de mensagem, regeneração por mensagem;
- design system (tokens em `Theme`), i18n real (`values-en` vazio), acessibilidade,
  háptica, transições;
- Compose BOM atualizada (2024.12 → atual) para Material3 recente/Expressive;
- 16 KB de page size e `targetSdk` 36.

### 2.5 Multimodal

- **Imagem:** funciona bem (mtmd, downscale 1024 px, 1 imagem por mensagem). Falta
  múltiplas imagens, câmera, controle de tiles/resolução, mmproj na GPU.
- **Áudio:** o "ditado" usa `SpeechRecognizer` do sistema — que em Samsung/Google
  normalmente é **na nuvem**. Isso contradiz a proposta "100% local" e não é áudio
  nativo do modelo. A base nativa já tem `MTMD_INPUT_CHUNK_TYPE_AUDIO` → dá para
  suportar modelos de áudio; e `whisper.cpp` resolve STT local para qualquer modelo.
- **Tool calling:** injeção manual de prompt + 4 parsers frágeis, porque `libcommon`
  está desligado. O correto é tool nativo do template + **gramática JSON-schema**
  (`llama_sampler_init_grammar`) — elimina os parsers e o "retry de correção".

---

## 3. TDs priorizadas

Legenda de esforço: **P** = ≤1 dia · **M** = 2–4 dias · **G** = 5–10 dias.
Toda TD tem critério de aceite; nenhuma entra sem teste ou medição.

### P0 — fundação (bloqueiam todo o resto)

| ID | Tarefa | Por quê | Esf. |
|---|---|---|---|
| TD-01 | Build nativo reprodutível: NDK r28 + `-Wl,-z,max-page-size=16384`, CMake externo, `.so` como artefato de CI (não no git) | hoje o `.so` sai do clang do Termux + `libc++_shared` do Termux → ABI/16 KB e "só compila nesta máquina" | M |
| TD-02 | `compileSdk`/`targetSdk` 36, edge-to-edge e predictive back sem hack manual, `dataSync` FGS | Android 17 no alvo + exigência do Play | M |
| TD-03 | **Spike Bonsai 2**: carregar `Q2_0` no `b10333` atual vs fork `prism` (`PQ2_0`/`PTQ1_0`/MTP); medir tok/s, RAM, qualidade de tool/visão | decide se o roadmap depende de fork de terceiro | M |
| TD-04 | `NativeSession` em C++ (handle, sem globais) + JNI por sessão + eventos tipados com backpressure | libera trocar modelo sem recarregar, duas sessões, testes, memória | G |
| TD-05 | Instrumentação de memória: RSS nativo (`/proc/self/status`), `Debug.MemoryInfo`, estimativa `modelo + KV` antes do load, `onTrimMemory` | sem isso "ver uso de RAM em tempo real" é impossível | M |
| TD-06 | Persistência: Room para conversas + DataStore para settings/imutáveis; migração dos JSONs atuais | fim do JSON gigante reescrito por token; base para busca/histórico | M |
| TD-07 | Testes + CI: unidade em `HfResolver`, `ToolSupport`, shards, título, open-think; lint; build de PR | 0 testes hoje; refatorar sem rede de segurança é roleta | M |

### P1 — experiência Fold 8 e desempenho

| ID | Tarefa | Por quê | Esf. |
|---|---|---|---|
| TD-08 | Adaptatividade: remover retrato travado, `WindowSizeClass`, `FoldingFeature`/postura, dois painéis (conversas\|chat), rail, tabletop | requisito "suporte total ao Fold 8" | G |
| TD-09 | Perfil de dispositivo: detectar SoC/núcleos/RAM → preset de threads, `n_ctx`, GPU layers, KV; expor "Auto" | hoje 4 threads e 2048 de contexto fixos num SoC de 8 núcleos e 11 GB | P |
| TD-10 | Build ARMv9 (`i8mm`/`bf16`/`sve2`) + `GGML_CPU_ALL_VARIANTS` no APK distribuído | maior ganho de CPU por linha de código trocada | M |
| TD-11 | Vulkan padrão + fallback + benchmark in-app (prompt/decode tok/s) e slider de camadas com "auto" | Adreno ocioso hoje | M |
| TD-12 | Streaming suave: scroll ancorado, sem relayout por token, seleção/cópia, regenerar por mensagem | jank visível hoje (`ChatScreen.kt:245`) | M |
| TD-13 | Tela de gerenciamento: RAM/tok-s por modelo em tempo real, barra de memória, presets de contexto, benchmark, comparar quants, excluir/limpar cache | o pedido "gerenciamento decente" | G |
| TD-14 | Download em `ForegroundService`/WorkManager, notificação, fila, SHA256, retry exponencial | hoje download morre em background | M |
| TD-15 | ~~Quantização in-app~~ **BACKLOG** (decisão 4: Bonsai 2 já vem pronto em `Q2_0`; re-quantizar só se faltar um packing no Hub) | evitar horas de CPU/térmica para ganho marginal | — |
| TD-25 | **Fit badge / estimador de RAM**: pesos + KV + mmproj + overhead, cruzado com RAM livre real (`ActivityManager` + `StatFs`) | evita baixar modelo que não roda; usa metadados nativos que já existem | P |

### P2 — multimodal e agente

| ID | Tarefa | Por quê | Esf. |
|---|---|---|---|
| TD-16 | Tool calling nativo: `libcommon` ligado, tools no template, `llama_sampler_init_grammar` (JSON-schema), remover parsers manuais | qualidade + menos código (415 → ~80 linhas) | G |
| TD-17 | STT local (whisper.cpp) com fallback para o sistema; depois áudio via `mtmd` (Qwen3.5-Omni etc.) | "100% local" de verdade | G |
| TD-18 | Visão: múltiplas imagens, câmera, política de tiles/resolução, mmproj na GPU, miniaturas no histórico | Bonsai 2 é VL; hoje só 1 imagem | M |
| TD-19 | Speculative decoding MTP/DFlash (Bonsai 2 traz o head) | 1,5–2× de decode, se o spike TD-03 aprovar | M |
| TD-20 | KV cache quantizado (`type_k/type_v` q8_0/q4_0) + presets de contexto (2k/4k/8k/32k) com estimativa de RAM | 262k de contexto é inútil sem isso | M |

### P3 — pesquisa e polimento

| ID | Tarefa | Por quê | Esf. |
|---|---|---|---|
| TD-21 | Spike NPU: `ggml-hexagon` vs ORT-QNN EP com um 2B w4a16; meta: ≥2× decode ou ½ do consumo vs Vulkan | QNN exige SDK/skel/licença; medir antes de prometer | G |
| TD-22 | Bench OpenCL(Adreno) vs Vulkan para prompt/decode | decisão baseada em dados | M |
| TD-23 | Design system, Material3 atual/Expressive, i18n, acessibilidade, háptica, animações | "não parecer app de 2015" | G |
| TD-24 | RAG local opcional (embeddings + índice) e tools extras (arquivo, cálculo, MCP-like) | próximo salto de utilidade | G |
| TD-26 | Browser do HuggingFace embutido: busca, filtro por tamanho/quant, badge de compatibilidade, token opcional p/ repos gated | descoberta melhor que colar URL | M |
| TD-27 | Skills declarativas (`skill.json` + JS em QuickJS, capabilities explícitas) sobre TD-16 | extensibilidade sem aceitar Intents/APK arbitrários | G |
| TD-28 | **Épico separado**: geração de imagem local (SD1.5-QNN ou Turbo destilado), swap de LLM, FGS, galeria | vertical novo; não reusa GGUF/mtmd | G |

---

## 4. Sequência sugerida

```
Semana 1   TD-01 TD-02 TD-07         (fundação: build + CI + testes)
Semana 2   TD-03 TD-05 TD-09         (spike Bonsai 2, memória, perfis)
Semana 3   TD-08 TD-12               (Fold 8: adaptativo + streaming suave)
Semana 4   TD-04 TD-06               (sessão nativa + Room/DataStore)
Semana 5   TD-10 TD-11 TD-20         (ARMv9, Vulkan, KV quantizado)
Semana 6+  TD-13 TD-14 TD-15         (gerenciador + download + quantizar)
depois     TD-16 TD-17 TD-18 TD-19   (tools nativos, áudio, visão, MTP)
por último TD-21 TD-23 TD-24         (NPU, polimento, RAG)
```

Regra: **nenhuma TD de feature antes de TD-04/TD-06**; as duas mudam as fronteiras
onde as features serão penduradas.

---

## 5. Decisões tomadas (ADR resumido — detalhes em `docs/DECISIONS.md`)

| # | Decisão | Data |
|---|---|---|
| 1 | Base nativa: **upstream `b10333`**; Bonsai 2 via packing `Q2_0`. Fork `PrismML` só se o spike TD-03 falhar | 2026-10-17 |
| 2 | Distribuição: **APK lateral**, otimizado para o Fold 8 (sem exigências do Play por ora) | 2026-10-17 |
| 3 | Começar por **fundação de build + CI** (TD-01/02/07) | 2026-10-17 |
| 4 | **Quantização in-app descartada**: Bonsai 2 já vem pronto (`Q2_0`, `mmproj-Q8_0`). TD-15 vai para backlog | 2026-10-17 |
| 5 | Build **CI-first** no GitHub Actions (NDK r28 + artefato APK) | 2026-10-17 |
| 6 | Repositório **público** (Actions ilimitado) | 2026-10-17 |

Pendentes: áudio (STT local vs modelo nativo `mtmd`) — decidir na entrada da Onda 4.

---

## 6. Revisão das ideias externas (QNN/LiteRT, fit badge, skills, geração de imagem)

### 6.1 Premissa a corrigir

As sugestões ("aproveite a NPU para gerar imagens", "Z-Image-Turbo", "Genie-SD1.5")
partem de um app de **geração de imagem**. O ForgeAI é **chat LLM local em GGUF**
(llama.cpp + mtmd) — não existe pipeline de difusão aqui. Geração de imagem seria
**épico separado**, com runtime, modelos e UX próprios, e **não reusa** o stack atual
(além de tema/insets/downloads). Isso não invalida as ideias: só muda o lugar delas.

### 6.2 O que é real (verificado hoje)

| Item | Verificação | Veredito |
|---|---|---|
| LiteRT + delegate QNN (HTP) | LiteRT-LM é real (6,7k ★); `litert-community/{Gemma3-1B-IT, Qwen3-0.6B, embeddinggemma}` existem | **Viável como fast-path separado** |
| Modelos SD para QNN | `qualcomm/Stable-Diffusion-v1.5` e `v2.1` no Hub; comunidade publica `*-SD1.5-qnn2.28` | **Viável** (ver 6.4) |
| `litert-community/Z-Image-Turbo-LiteRT` | Existe; DiT grande fatiado em subgrafos <1 GB | **Risco alto** (encoder de texto falha em GPU/NPU segundo o próprio card) |
| `ggml-hexagon` no llama.cpp | `ggml/src/ggml-hexagon/CMakeLists.txt` existe no master | **Experimental** — mesmo stack, mas exige Hexagon SDK |
| Litert QNN delegate em Kotlin | Exemplos existem, mas para `.tflite`/`.litertlm` | Não serve para GGUF |

### 6.3 Por que QNN não é o primeiro passo

1. **Números citados não transferem.** 0,3 ms vs 2,8 ms (MobileNetV2) e 24,9 vs
   481,7 ms são **visão**. Em LLM o gargalo é o *decode* (1 token por passo, limitado
   por banda de DRAM compartilhada com o HTP), não o prefill. Realista para 1–3B com
   offload completo: **prefill 3–10×**, **decode 1,2–2×** — e prefill já é rápido com
   `i8mm`/SVE2.
2. **É outro stack.** LiteRT-LM/QNN não carrega GGUF: exige modelos convertidos
   (`.litertlm`/`.tflite` + context binaries), perde chat template, mtmd, tools, KV
   prefix cache e o downloader por URL do HF que já funcionam. Vira um "modo turbo"
   só para uma lista fechada de modelos.
3. **Custo de licença/empacotamento.** `libQnnHtp.so` + `libQnnHtpV81Skel.so` vêm do
   QAIRT/QNN SDK (licença Qualcomm) e **não existem no vendor deste aparelho** (só
   `libSnpeHtpV81Stub.so`). O SoC é compatível; o app só será depois de empacotar as
   libs assinadas e validar *unsigned PD*.

**Ordem de spike (TD-21 revisada):** medir Vulkan/OpenCL → `ggml-hexagon` →
LiteRT-LM+QNN. Meta para manter o QNN: **≥3× prefill e ≥1,3× decode** vs Vulkan no
mesmo modelo, sem perder visão/tools. Senão, descartar sem culpa.

### 6.4 Geração de imagem (se virar produto)

- **SD1.5-QNN** (`qualcomm/Stable-Diffusion-v1.5`): 512×512 em segundos no HTP;
  qualidade datada, mas é o caminho de menor risco.
- **Z-Image-Turbo-LiteRT**: qualidade atual, porém DiT grande + encoder problemático;
  no Fold 8 caberia com swap, não com fluidez.
- **Conflito de RAM**: 4B (1,3 GB) + mmproj (0,6 GB) + SD (~1,2 GB) + KV/ativas ≈
  5–6 GB de pico. Só é seguro **com** TD-05/TD-13 (estimador + swap de modelo).
- Escopo mínimo honesto: 512×512, 4–8 steps, fila em `ForegroundService`, galeria,
  seed, descarregar LLM antes. **2–3 semanas** e um vertical novo de produto.

### 6.5 Ideias aceitas (baratas e alinhadas)

- **Fit badge / estimador de RAM** (estilo AnythingLLM): dá para fazer **hoje**, porque
  o `nativeInspectModel` já entrega `n_params`, `n_layer`, `n_embd`, `n_vocab`,
  `n_ctx_train` e quantização. Estimativa = pesos + KV (`2·n_layer·n_embd·n_ctx·bytes`)
  + mmproj + overhead → badge "roda bem / aperta / não cabe". **Aceitar (TD-25).**
- **Browser do HuggingFace embutido**: já resolvemos URL; falta busca/lista com filtro
  por tamanho/quant. **Aceitar como P2 (TD-26)**, com token opcional p/ repos gated.
- **Skills/plugins** (padrão AI Edge Gallery): só **depois** de TD-16 (tools nativos com
  gramática JSON-schema). Formato declarativo (`skill.json` + JS opcional em QuickJS,
  capabilities explícitas). **Não** aceitar Intents/APK arbitrários no v1. TD-27 (P3).

### 6.6 Ordem recomendada (diferente da sugerida no texto)

```
0) CI + testes (em andamento)                    <- TD-01/02/07
1) Fold 8 + fit badge + perf medível             <- TD-08/09/12/25/10/11
2) spike QNN isolado, com meta numérica          <- TD-21
3) tools nativos -> skills sandbox               <- TD-16/27
4) geração de imagem (épico próprio, se for produto) <- TD-28
```

Começar pelo QNN seria construir um segundo motor de inferência antes de o primeiro
compilar de forma reprodutível — e trocaria os recursos que já funcionam (GGUF, mtmd,
tools, HF) por um benchmark.

---

## 7. Progresso

| TD | Status | Evidência |
|---|---|---|
| TD-01 build NDK/16 KB | ✅ feito | `.github/workflows/android.yml` + `build_native.sh` (NDK r28c; `p_align=16384` verificado no artefato) |
| TD-02 targetSdk | ⏳ pendente | hoje `compileSdk/targetSdk 34`; subir junto com TD-08 (Android 16 ignora lock de orientação em telas grandes) |
| TD-03 spike Bonsai 2 | ✅ feito (ver §8) | `.github/workflows/spike-bonsai.yml`: `Q2_0` carrega no upstream (`qwen35 2B Q2_0`), visão codifica, bench em ARM medido |
| TD-07 testes/CI | ✅ feito | de 0 → 66 testes JVM; CI verde (native + apk) |
| TD-25 fit badge | ✅ feito | `data/ModelFit.kt` + badge na tela Modelos + `n_head_kv`/`n_kv_layers` no JNI |
| Sugestões | ✅ trocadas | `SuggestedModels` = Bonsai 2 2B/4B/9B (`Q2_0` + `mmproj-Q8_0`) |

Descobertas do caminho (já corrigidas):

- o `mmproj` da PrismML fica no **meio** do nome (`<modelo>-mmproj-Q8_0.gguf`); o app
  só reconhecia prefixo → a visão do Bonsai 2 nunca ligaria;
- packings ternários (`Q2_0`/`PQ2_0`/`PTQ1_0`/`Q1_0`) não eram reconhecidos como
  quantização (some o badge e o pareamento do encoder);
- `attention.head_count_kv` é **array por camada** nos híbridos (Qwen3.5): sem ler o
  array, o KV cache seria superestimado em ~4× (e o fit badge diria “não cabe”);
- os três tamanhos do Bonsai 2 têm `Q2_0` **e** `mmproj-Q8_0` → o caminho upstream
  (ADR-001) cobre 2B, 4B e 9B.

---

## 8. Resultados do spike Bonsai 2 (TD-03)

Workflow `spike-bonsai` (`b10333`, build `0865990`):

```
x86_64, 4 threads (runner padrão)   pp64 2,77 t/s · tg32 1,97 t/s
ARM64,  4 threads (ubuntu-24.04-arm) pp64 28,44 t/s · tg32 19,06 t/s
baseline Qwen2.5-1.5B Q4_K_M (ARM)   pp64 105,24 t/s · tg32 33,27 t/s
```

Leituras:

1. **ADR-001 confirmado**: o llama.cpp upstream carrega o packing `Q2_0`
   (`qwen35 2B Q2_0`, 510 MiB, 1,88B parâmetros). Não precisamos do fork PrismML
   para 2B/4B/9B — só para `PQ2_0`/`PTQ1_0` (arquivos ~6% menores) e MTP.
2. **Os kernels ternários são ~10× mais rápidos em ARM que em x86**, e ainda
   assim **~0,6× a velocidade** de um Q4_K_M de 1,5B no mesmo hardware. Ou seja:
   o valor do Bonsai 2 é **tamanho + visão + 262k de contexto**, não tok/s bruto.
   No Fold 8 (8 núcleos, prime 4,7 GHz, mais banda) a expectativa é bem melhor —
   medir com o app instalado (TD-13) antes de prometer número.
3. **Visão funciona estruturalmente** (mmproj Q8_0 reconhecido, imagem
   codificada em ~5 s), mas o texto gerado saiu sem sentido no smoke test: o
   mtmd avisa que **Qwen-VL exige ≥1024 tokens de imagem** e o default é 247.
   → requisito novo para o TD-18: configurar `image_min_tokens` no `mtmd` e
   revisar a política de downscale (hoje fixa em 1024 px) e o prompt ChatML
   manual do caminho de visão.

