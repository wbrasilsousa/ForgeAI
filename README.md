# ForgeAI ⚡

IA generativa **100% local** no seu Android, com download direto do HuggingFace.

ForgeAI roda modelos GGUF (llama.cpp) no próprio dispositivo — seus dados nunca
saem do celular e funciona **sem internet** depois que o modelo é baixado.

> 🔧 **Web access (opt-in):** em Ajustes → Ferramentas você pode ligar a
> navegação na web. Isso **envia consultas para a internet** (só quando ativo e
> só com modelos que suportam ferramentas, ex.: Qwen2.5).

## Funcionalidades

- 🧠 **Motor llama.cpp (b10333)** embutido — roda qualquer modelo GGUF (Llama 3, Qwen, Gemma, Phi, SmolLM, LFM2.5…)
- 🖼️ **Modelos com visão (VL)** — modelos que têm `mmproj-*.gguf` (ex.: LFM2.5-VL-3B) ganham badge de olho 👁, o botão de anexo libera o upload de imagem na conversa (Photo Picker) e o encoder SigLIP2 roda localmente via a lib `mtmd`
- 🎤 **Ditado por voz por press-and-hold** — segure o campo de mensagem para falar; solte para enviar o texto (o botão de microfone virou anexo)
- ⬇️ **Baixe modelos só com a URL do HuggingFace** — cole uma URL de repositório, de arquivo (`/resolve/main/...`) ou `hf://`, e o app lista os arquivos .gguf com tamanho
  - Modelos VL baixam o `mmproj` (encoder de visão) junto, automaticamente
  - Downloads com **progresso, velocidade, cancelamento e retomada**
  - 6 sugestões curadas para celular (0.3B–3.8B)
- 💬 **Chat completo** com streaming token a token, regeneração, parar, limpar conversa e histórico persistido
  - Template de chat do próprio modelo aplicado nativamente (multi-turn correto)
  - Contexto com KV-cache reutilizado entre turnos (rápido), trimmagem automática do histórico
- 🔧 **Ferramentas (web access)** — modelos com template de tools ganham um badge de martelo na barra superior; com web access ativo em Ajustes, o modelo pode **pesquisar na internet** (`web_search` → DuckDuckGo, sem API key, com fallback Wikipédia) e **abrir páginas** (`web_access` → OkHttp, HTML→texto), em loop de até 5 ferramentas por turno (busca → acesso → resposta). Formato detectado pelo template: **Qwen2.5** (JSON `<tool_call>`), **Qwen3/Qwen3.5** (XML `<function=...>` + `<tool_response>`) e **LFM2.5/LFM2.5-VL** (Pythonic `<|tool_call_start|>[web_search(query="...")]<|tool_call_end|>`); retry com correção quando a chamada sai quebrada
- 🎛️ **Ajustes de inferência**: temperatura, top-P, top-K, min-P, penalidade de repetição, máximo de tokens, seed, contexto, threads de CPU e (em builds Vulkan) camadas na GPU
- 🎨 **Design moderno**: Material 3, tema escuro AMOLED/claro, cores dinâmicas (Material You), tela cheia imersiva
- 🔍 Inspeção de metadados GGUF nativa (arquitetura, quantização, parâmetros, contexto treinado)

## Estrutura

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt     # build do llama.cpp + libllamaforge.so
│   └── llama_jni.cpp      # ponte JNI (carga, geração streaming, inspeção GGUF)
├── java/dev/forgeai/
│   ├── LlamaEngine.kt     # wrapper JNI + eventos
│   ├── MainActivity.kt    # edge-to-edge, imersivo, navegação
│   ├── core/              # preferências/configurações
│   ├── data/              # repositório de modelos, resolver HF, downloader
│   └── ui/                # ViewModel, tema, telas (Chat/Modelos/Ajustes)
└── res/                   # tema, splash, ícone adaptativo
scripts/
├── build_native.sh        # compila libllamaforge.so → app/src/main/jniLibs/
├── build_apk.sh           # build_native + Gradle APK
└── termux-toolchain.cmake # toolchain para compilar no Termux (aarch64)
```

## Como compilar

> ⚠️ **Se for distribuir o APK para outras pessoas**: o build padrão é
> otimizado para o CPU deste aparelho (kernels dotprod) e não roda em
> celulares sem esse suporte. Reconstrua com `FORGE_ARM_DOTPROD=0` para
> gerar uma lib portátil — detalhes no [checklist de publicação](PUBLISHING.md).

### No celular (Termux)

```bash
pkg install git cmake ninja gradle openjdk-21
# Android SDK: coloque platforms/android-34 (android.jar) em ~/android-sdk/platforms/ 
# (aapt2, aapt e zipalign vêm do pacote "aapt"/"aapt2" do Termux)

export ANDROID_HOME=$HOME/android-sdk
./scripts/build_apk.sh          # debug APK
# APK em: app/build/outputs/apk/debug/app-debug.apk
```

O script `build_native.sh`:
1. clona o llama.cpp pinado (`b10333`) se necessário (`LLAMA_CPP_DIR` para apontar um checkout existente)
2. compila `libllamaforge.so` com o clang do Termux (aarch64/bionic)
3. copia o `.so` e o `libc++_shared.so` para `app/src/main/jniLibs/arm64-v8a/`

### No PC (Android Studio / Linux)

```bash
# Android SDK + NDK + cmake + ninja + clang/lld instalados
./scripts/build_native.sh                 # ou LLAMA_CPP_DIR=... para offline
ANDROID_HOME=/caminho/sdk ./gradlew assembleDebug
```

> Observação: o app usa `compileSdk 34` e o `.so` é produzido apenas para `arm64-v8a`.
> Em um PC, para builds Vulkan (GPU): `cmake -DFORGE_VULKAN=ON ...` via `build_native.sh`
> (requer o SDK Vulkan/glslang) e ajuste `GGML_VULKAN`.

## Uso

1. Aba **Modelos** → **Adicionar** → cole a URL do HuggingFace (ou escolha uma sugestão)
2. Espere o download (o progresso aparece na tela; pode ser retomado depois)
3. Toque em **Carregar** no cartão do modelo
4. Vá para **Chat** e converse!

### Modelos recomendados para este aparelho (Exynos 9820 / Mali-G76)

| Modelo | Tamanho | Nota |
|---|---|---|
| SmolLM2-360M-Instruct (Q8_0) | ~270 MB | super rápido |
| Qwen2.5-0.5B-Instruct (Q4_K_M) | ~400 MB | ideal |
| TinyLlama-1.1B-Chat (Q4_K_M) | ~670 MB | equilibrado |
| SmolLM2-1.7B-Instruct (Q4_K_M) | ~1.1 GB | melhor custo-benefício |
| Qwen2.5-1.5B-Instruct (Q4_K_M) | ~1 GB | ótimo em PT |
| Phi-3.5-mini (Q4_K_M) | ~2.3 GB | qualidade máxima (lento) |

## Notas técnicas

- **Inferência em CPU**: com threads configuráveis (padrão 4). Em builds com Vulkan,
  ative "Camadas na GPU" nos Ajustes para aceleração real (Mali-G76 suporta).
- **Contexto**: padrão 2048 tokens; aumente se o aparelho aguentar (KV cache em RAM).
- **Downloads**: usam `Range` para retomada; arquivos `.part` são mantidos após cancelar.
- **Chat**: o prompt é formatado com o *chat template* do modelo via
  `llama_chat_apply_template`; o KV-cache persiste entre turnos; regenerar/limpar
  zera o contexto corretamente.

## Desempenho (medido neste aparelho)

O build default (`FORGE_ARM_DOTPROD=1`) compila os kernels CPU para
`armv8.2-a+dotprod` (SDOT/UDOT). Medido com o harness `build/bench.cpp`
(LFM2.5-1.2B-Q4_K_M, 4 threads):

| Config | Prompt (tok/s) | Geração (tok/s) |
|---|---|---|
| baseline (portátil) | ~14 | ~5–6 |
| dotprod (`armv8.2-a+dotprod`) | ~24 (+66%) | ~4.6 (neutro) |
| dotprod+fp16 ❌ | ~24 | ~2.2 (−55%) |

> ⚠️ **Não adicione `+fp16` ao march**: acelera o prompt um pouco mais, mas
> reduz a geração token-a-token em ~2,5× em modelos Q4_K_M neste SoC.
> Em modelos IQ/Q1 (ex.: Bonsai-1.7B-Q1_0) o dotprod acelera a geração em
> ~2,5× — o ganho varia por quantização.

Outras otimizações já embutidas:

- **Streaming em lote**: o JNI acumula texto e libera a cada ~32 ms / 96 bytes
  (antes: 1 chamada JNI + 1 alocação por token).
- **Threads do prompt automáticas**: o decode (limitado por memória) usa as
  threads configuradas; o processamento do prompt (paralelo) usa até 8 threads
  automaticamente.
- **KV-cache reaproveitado entre turnos** (prefixo verificado token a token).

### GPU (Vulkan) — opt-in

A GPU Mali-G76 costuma dar o maior salto em modelos 1B+: compile com

```bash
pkg install vulkan-headers shaderc
FORGE_VULKAN=1 ./scripts/build_native.sh
```

e depois use "Camadas na GPU" nos Ajustes (comece com ~80% das camadas).
O build Vulkan também é compatível com o dotprod da CPU (camadas não
offloadadas continuam rápidas).

### Trocar o alvo CPU

- `FORGE_ARM_DOTPROD=0 ./scripts/build_native.sh` → build portátil (roda em
  qualquer ARMv8, sem kernels dotprod).
- O `.so` com dotprod **não roda** em CPUs sem suporte (Cortex-A73 e anteriores).

## Licenças

- App: código aberto (MIT-style, veja LICENSE).
- [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) — pinado em `b10333`.
- Modelos: consulte a licença de cada repositório no HuggingFace.

> 📦 Quer publicar o app? Veja o [checklist de publicação](PUBLISHING.md).
