# Handoff — estado do projeto e próximo passo

> Última sessão: 2026-10-18 · branch `master`, 14 commits, CI verde.
> Plano completo e TDs: [`ROADMAP.md`](ROADMAP.md) · decisões: [`DECISIONS.md`](DECISIONS.md)

## 1. Onde estamos (e o que já foi provado)

| Frente | Estado |
|---|---|
| Build reprodutível | ✅ CI gera `.so` (NDK r28c, ARMv9 `sve2+i8mm+bf16`, page size 16 KB) e APK debug; artifacts `native-libs` e `forgeai-debug-apk` |
| Assinatura | ✅ `keystore/debug.keystore` versionada → atualiza por cima sem desinstalar |
| Modelos | ✅ sugestões = Bonsai 2 2B/4B/9B (`PQ2_0` + `mmproj-Q8_0`), mmproj pareado automaticamente |
| Motor | ✅ fork `PrismML-Eng/llama.cpp` tag `prism-b10777-e3de715` (ADR-006) |
| Medições no Z Fold 8 | ✅ texto **30,9 tok/s** (434 tok) · com imagem 23,4 tok/s · pico **1,3 GB** |
| Fit badge | ✅ calibrado (pesos + KV híbrido + compute + mmproj + 15%) |
| Testes | ✅ 70 testes JVM (HfResolver, ToolSupport, ModelInfo, ModelFit, SuggestedModels, `<think>`) |
| Visão | 🔬 funciona, mas 2B ternary é fraco em OCR e a codificação esquenta |

## 2. O que espero do teste de amanhã

Checklist curto (reportar o que divergir):

1. **Update instala por cima?** (deve instalar sem desinstalar; se recusar, é a última vez)
2. **Badge** do 2B com visão deve mostrar `~1,1 GB`. Texto real medido: 1,3 GB com imagem.
3. **Ajustes → Ajustes do modelo → Detalhe da imagem**:
   - `512 tok` → mais rápido/esquenta menos; imagem um pouco pior.
   - `1536/2048 tok` → melhor para texto em screenshot (testar OCR de verdade).
   - lembrar de **recarregar o modelo** após mudar.
4. **Aquecimento**: comparar temperatura com `threads = 4` vs `6` vs `8` (Ajustes do modelo).
5. Se quiser, testar **4B** (`PQ2_0`, 1,15 GB): deve ser o melhor custo/benefício para imagem.

## 3. Próximas TDs (ordem sugerida)

| # | TD | Por quê agora | Arquivos-alvo |
|---|---|---|---|
| 1 | **TD-08 + TD-02** adaptativo Fold 8 | o app ainda **trava em retrato** (`AndroidManifest.xml:23`); é o maior ganho de UX no Fold. Subir `targetSdk` 36 junto (Android 16 ignora o lock em telas grandes) | `MainActivity.kt`, `ChatScreen.kt`, `ModelsScreen.kt`, manifest, `build.gradle.kts` |
| 2 | **TD-18** visão nativa | usar `llama_chat_apply_template` + marcador de mídia em vez do ChatML manual; múltiplas imagens; reduzir calor | `llama_jni.cpp` (vision_path), `AppViewModel.buildHistory` |
| 3 | **TD-11 + TD-19** Vulkan e MTP | tira prefill/visão do CPU (menos calor) e o MTP do Bonsai 2 dá 1,5–2× no decode | `build_native.sh` (`FORGE_VULKAN=1`), Ajustes |
| 4 | **TD-13 + TD-14** gerenciador/download | histórico de tok/s e RSS por modelo, benchmark in-app, download em `ForegroundService` com SHA256 | `ModelsScreen.kt`, `ModelDownloader.kt` |
| 5 | **TD-04 + TD-06** sessão nativa + Room | base para tudo que é multi-modelo/histórico; fazer antes de features grandes | `LlamaEngine.kt`, `llama_jni.cpp`, `AppViewModel.kt` |

## 4. Dívidas e armadilhas conhecidas

- **Termux não lista `Android/data/dev.forgeai`** (scoped storage) — para inspecionar modelos, só pelo app. `~/storage` deve ser criado com `termux-setup-storage` (não com `mkdir`).
- **`libcommon` está OFF** no CMake → tool calling é injeção de prompt (`ToolSupport.kt`, 415 linhas). TD-16 resolve.
- **Sem `onTrimMemory`/OOM handling** (TD-05 restante): modelos 9B + imagem podem pressionar o LMKD.
- **Keystore de debug é pública** (por definição) — nunca usar em release; para Play, gerar chave própria.
- **Números do CI não valem para o aparelho**: o runner ARM tem 4 núcleos e sem thermal throttling.

## 5. Comandos úteis

```bash
# último APK (CI) direto para Downloads
gh run download --repo wbrasilsousa/ForgeAI -n forgeai-debug-apk   # (gh não instalado no Termux)

# build local do .so com NDK (host x86_64)
FORGE_NDK=$HOME/android-ndk-r28c FORGE_TUNE=v9 ./scripts/build_native.sh

# spike de qualidade (upstream vs fork)
gh workflow run spike-bonsai-quality.yml
```

No Termux, sem `gh`: baixar o artifact pela API (`/actions/artifacts/{id}/zip`) ou direto em
**Actions → run → Artifacts**.

## 6. Decisões registradas

ADR-001 (histórica: upstream + Q2_0) · ADR-002 APK lateral · ADR-003 CI-first ·
ADR-004 sem quantização in-app · ADR-005 QNN não é caminho crítico ·
**ADR-006 fork PrismML + PQ2_0** (substitui ADR-001).
