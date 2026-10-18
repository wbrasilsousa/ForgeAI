# Decisões de arquitetura (ADR curto)

Registro cronológico das decisões que mudam o rumo do projeto. Cada uma tem
contexto, decisão e consequência. O plano executável fica em `docs/ROADMAP.md`.

---

## ADR-001 — Base nativa: upstream `llama.cpp` + packing `Q2_0`

**Data:** 2026-10-17 · **Status:** **validada em 2026-10-18** pelo spike TD-03
(`qwen35 2B Q2_0` carrega e roda no `b10333`; ARM64: 28,4 t/s prefill, 19,1 t/s decode)

**Contexto.** A família Bonsai 2 (PrismML) publica três packings ternários:
`PTQ1_0` (tipo ggml 143), `PQ2_0` (tipo 142) e `Q2_0` (tipo 42). Os dois primeiros
só existem no fork `PrismML-Eng/llama.cpp` (branch `prism`). O projeto está pinado
em `ggml-org/llama.cpp` `b10333` (ago/2026), que **já** tem `GGML_TYPE_Q2_0`, a
arquitetura `QWEN35`, o caminho de *Hadamard* e `mtmd` com áudio.

**Decisão.** Manter o upstream e validar o `Q2_0` do Bonsai 2 no spike TD-03. O fork
`prism` (que traz `PQ2_0`/`PTQ1_0` menores e MTP speculative decoding) só entra se o
spike falhar ou se o MTP se provar decisivo.

**Consequência.** Sem dependência de fork de terceiro; perde-se, por ora, o packing
de 0,43 GB e o MTP. Reavaliar com números.

---

## ADR-002 — Distribuição: APK lateral

**Data:** 2026-10-17 · **Status:** aceita

**Contexto.** O alvo é um único aparelho (Z Fold 8 / SM-F971B). Play Store exige
16 KB de page size, `targetSdk` recente e `ForegroundService` tipado.

**Decisão.** Priorizar desempenho e iteração: build com ISA do aparelho
(`armv9-a+sve2+i8mm+bf16`), sem pipeline de assinatura/release.

**Consequência.** Mesmo assim, o build já sai com page size de 16 KB (NDK r28),
para não virar dívida se a distribuição mudar.

---

## ADR-003 — CI-first no GitHub Actions

**Data:** 2026-10-17 · **Status:** aceita

**Contexto.** O Termux do aparelho não tem clang/cmake/ninja/gradle/java e o NDK
oficial só existe para host `x86_64`.

**Decisão.** O artefato oficial (`.so` + APK) é produzido no CI com NDK r28c:
job `native` (llama.cpp → `libllamaforge.so`) e job `apk` (testes de unidade +
`assembleDebug`). Repositório público para Actions ilimitado.

**Consequência.** Ciclo de iteração passa por push (15–25 min). O
`android.aapt2FromMavenOverride` (específico do Termux) saiu do `gradle.properties`
e virou configuração local em `~/.gradle/gradle.properties`.

---

## ADR-004 — Quantização in-app fora de escopo

**Data:** 2026-10-17 · **Status:** aceita

**Contexto.** Quantizar 4B/9B no celular custa horas de CPU e térmica. Os modelos
desejados (Bonsai 2, 2B/4B/9B) já existem prontos em `Q2_0`/`PQ2_0` + `mmproj-Q8_0`.

**Decisão.** Não expor `llama_model_quantize`. No lugar, investir em **escolha de
packing + estimador de RAM/fit badge** (TD-25) e gerenciamento de modelo (TD-13).

**Consequência.** TD-15 vai para backlog. Re-quantizar só se algum dia faltar um
packing no Hub.

---

## ADR-005 — QNN/LiteRT não é caminho crítico

**Data:** 2026-10-17 · **Status:** aceita (revisável pelo TD-21)

**Contexto.** O SoC (SM8850) tem Hexagon e `/dev/fastrpc-cdsp`, mas o vendor não traz
`libQnnHtp.so`/`libQnnHtpV81Skel.so`; LiteRT+QNN exige outro runtime e modelos
convertidos (`.litertlm`/`.tflite`), abandonando GGUF/mtmd/tools nessa rota. Os
números de “24,9 ms vs 481,7 ms” são de visão, não de decode de LLM.

**Decisão.** Primeiro medir Vulkan/OpenCL e `ggml-hexagon`; LiteRT-LM+QNN só como
*fast-path* opcional se entregar ≥3× prefill e ≥1,3× decode sem perder features.

**Consequência.** NPU vira pesquisa (TD-21), não bloqueia o roadmap.
