# ✅ Checklist de publicação — ForgeAI

> ## ⚠️ IMPORTANTE — a lib nativa é feita para ESTE aparelho
>
> O `libllamaforge.so` atual é compilado com kernels **dotprod**
> (`armv8.2-a+dotprod`) e **só funciona em CPUs com esse suporte**
> (Cortex-A76+, Exynos M4+ etc.).
>
> **Antes de distribuir o APK para outras pessoas, reconstrua a lib em modo portátil:**
>
> ```bash
> FORGE_ARM_DOTPROD=0 ./scripts/build_native.sh
> ```
>
> Um APK com o `.so` dotprod **crasha** em aparelhos sem suporte
> (Cortex-A73 e anteriores, e vários celulares antigos).
>
> **Alternativa recomendada para distribuir SEM perder velocidade:**
> `FORGE_ALL_VARIANTS=1 ./scripts/build_native.sh` — o backend CPU é
> compilado em várias variantes (baseline, dotprod, fp16, i8mm, SVE) e o app
> escolhe a melhor para cada aparelho em runtime (detalhes na seção 1.1).

Guia passo a passo para publicar o ForgeAI (Play Store ou sideload).
Itens marcados com **BLOQUEANTE** impedem a publicação se não forem resolvidos.

---

## 0. Estado atual do projeto (referência)

| Item | Valor |
|---|---|
| applicationId | `dev.forgeai` |
| versionCode / versionName | `2` / `1.0.1` |
| minSdk / targetSdk / compileSdk | `26` / `34` / `34` |
| ABIs | apenas `arm64-v8a` |
| Permissões | `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO` (runtime) |
| Assinatura | **nenhuma configurada** (release sai sem assinatura) |
| Backup | regras já excluem **tudo** do backup na nuvem ✅ |

---

## 1. Build e assinatura

- [ ] **BLOQUEANTE — criar keystore de release** (uma única vez, guarde com segurança):
      ```bash
      keytool -genkeypair -v -keystore ~/forgeai-release.keystore \
        -alias forgeai -keyalg RSA -keysize 2048 -validity 10000
      ```
      > ⚠️ Faça backup do `.keystore` em 2 lugares. Sem ele, é impossível atualizar o app na Play Store.
- [ ] **BLOQUEANTE — configurar `signingConfig` no `app/build.gradle.kts`** usando variáveis de ambiente (nunca commitar a senha/keystore):
      ```kotlin
      signingConfigs {
          create("release") {
              storeFile = file(System.getenv("FORGE_KEYSTORE") ?: "")
              storePassword = System.getenv("FORGE_KEYSTORE_PASS")
              keyAlias = System.getenv("FORGE_KEY_ALIAS")
              keyPassword = System.getenv("FORGE_KEY_PASS")
          }
      }
      // buildTypes.release.signingConfig = signingConfigs.getByName("release")
      ```
- [ ] Subir `versionCode`/`versionName` (ex.: `3` / `1.0.2`) — a lib nativa mudou (dotprod, streaming em lote).
- [ ] **BLOQUEANTE — decidir o build da lib nativa para distribuição:**
      - Play Store / público geral → **`FORGE_ALL_VARIANTS=1 ./scripts/build_native.sh`**
        (recomendado: portátil E rápido, dispatch por CPU em runtime).
      - Ou **`FORGE_ARM_DOTPROD=0 ./scripts/build_native.sh`** (baseline portátil, mais simples, sem kernels otimizados).
      - Distribuição restrita a este aparelho (sideload pessoal) → pode manter `FORGE_ARM_DOTPROD=1`.
      - O `.so` com dotprod **crasha em aparelhos sem suporte** (Cortex-A73 e anteriores).

### 1.1 — Build ALL_VARIANTS (recomendado para distribuir)

Compila o backend CPU como **várias libs compartilhadas**, uma por ISA, e o
app escolhe em runtime a melhor para o aparelho (via `ggml_backend_score`):

```bash
FORGE_ALL_VARIANTS=1 ./scripts/build_native.sh
./gradlew assembleRelease -PforgeAllVariants=true   # APK/AAB
```

O que muda (já implementado no projeto):

- `jniLibs/arm64-v8a/` passa a conter, além de `libllamaforge.so`:
  `libllama.so`, `libggml.so`, `libggml-base.so`, `libggml-cpu.so`
  (fallback baseline) e `libggml-cpu-<isa>.so` (armv8.0_1, armv8.2_1=dotprod,
  armv8.2_2=dotprod+fp16, armv8.6_1=i8mm, ...). Todas **devem** ir no APK.
- O app passa `applicationInfo.nativeLibraryDir` para `nativeInit()`, que
  chama `ggml_backend_load_all_from_path()` antes do `llama_backend_init()`
  (sem isso o llama.cpp procuraria em `/system/bin`/cwd e não acharia nada).
- `useLegacyPackaging=true` (flag `-PforgeAllVariants=true`) — as libs precisam
  estar **extraídas** num diretório real para o ggml enumerar e dar dlopen.

Observações:

- Escolha em runtime: neste aparelho (M4+A75+A55, com dotprod e fp16) o
  dispatch escolhe a variante `armv8.2_2` (dotprod+fp16). Nos nossos testes
  a fp16 prejudicou o decode de Q4_K_M neste SoC (~2,2 vs ~5 tok/s) — ou
  seja, no SEU aparelho o build direto `FORGE_ARM_DOTPROD=1` ainda é o mais
  rápido. O ALL_VARIANTS brilha para o público geral (A73+ cai no baseline,
  A76+/M4+ ganham dotprod, aparelhos novos ganham i8mm/SVE).
- APK maior (~15–40 MB de libs extras).
- Se usar `FORGE_VULKAN=1` junto, `libggml-vulkan.so` também entra no pacote.
- **Testar antes de lançar**: instalar em aparelhos com e sem dotprod e
  conferir o log (variante escolhida) e a velocidade.
- [ ] Validar R8/minify: `./gradlew assembleRelease` e testar **carga de modelo + chat + inspeção GGUF** num APK release (as regras do `proguard-rules.pro` já preservam `LlamaEngine`, mas o release não foi testado com a lib nova).
- [ ] Conferir que `libc++_shared.so` e `libllamaforge.so` entram no bundle (já estão em `jniLibs/arm64-v8a/`).

## 2. Compatibilidade e plataforma

- [ ] **BLOQUEANTE — subir `compileSdk`/`targetSdk`** para o exigido pela Play hoje (35/36):
      - No Termux: baixar `platforms/android-35` (ou 36) para `~/android-sdk/platforms/`.
      - Testar no aparelho após a mudança (comportamento de edge-to-edge mudou no 35).
- [ ] Testar em aparelho com **pouca RAM** (o app usa ~2–3 GB com modelos 1B+; avaliar limite mínimo suportado e, se quiser, avisar na listagem).
- [ ] Testar em aparelho **sem dotprod** (se distribuir o build portátil) — confirmar que carrega.
- [ ] Conferir o warning do Play sobre dispositivos sem `arm64-v8a` (não há problema: 64 bits é obrigatório e estamos ok; aparelhos 32-bit não verão o app).

## 3. Privacidade e segurança

- [ ] Revisar permissões:
      - `INTERNET` / `ACCESS_NETWORK_STATE` — necessárias para download de modelos.
      - `RECORD_AUDIO` — runtime, pedida ao tocar no microfone ✅ (já tratada). Incluir justificativa no formulário de permissões da Play.
- [ ] (Opcional, recomendado) **Restringir downloads a `huggingface.co`** — hoje o app aceita URL arbitrária colada pelo usuário; isso pode levantar bandeiras na revisão da Play. Ex.: bloquear hosts fora do HF.
- [ ] Confirmar que o backup na nuvem continua excluindo o histórico de chat (`backup_rules.xml` / `data_extraction_rules.xml` já excluem tudo ✅). Documentar isso no app (promessa "dados nunca saem do celular").
- [ ] Verificar que nenhum dado sai do dispositivo durante a inferência (é 100% local; downloads só de modelos).
- [ ] Conformidade de exportação: usa apenas HTTPS; sem criptografia customizada — ok.

## 4. Políticas da Play Store

- [ ] **BLOQUEANTE — formulário "Data safety"** (Ficha de dados): declarar
      - Nenhum dado é coletado/enviado; inferência 100% local;
      - Downloads de modelo: dados do usuário (URLs) processados no dispositivo;
      - Permissões usadas (microfone — entrada de voz opcional).
- [ ] **BLOQUEANTE — Política de IA Generativa** (aplica-se a apps com recursos de IA):
      - Declarar o uso de IA generativa no Console;
      - Adicionar **divulgação clara no app** de que as respostas são geradas por IA local (não por humano);
      - Mecanismo no app para **reportar/bloquear conteúdo gerado** (a Play exige canal de feedback para conteúdo de IA) — criar uma tela simples "Reportar resposta" que guarde o texto localmente/exporte;
      - Preencher o formulário de autoavaliação (não há servidores; sem uso de dados para treinar modelos).
- [ ] Questionário de classificação etérica (Content rating) — IA generativa e conteúdo de usuário podem exigir classificação maior/aviso.
- [ ] Política de **conteúdo gerado pelo usuário** (chats): sem moderação remota, mas com o mecanismo de reportar acima fica mais defensável.
- [ ] Revisar políticas de publicidade: o app não tem anúncios — ok.
- [ ] (Opcional) Play Integrity / licenciamento — não necessário para este app.

## 5. Listagem na loja

- [ ] **Política de privacidade online** (obrigatória mesmo sem coleta) — hospedar em página simples (GitHub Pages, etc.) e colar a URL no Console.
- [ ] Nome: ForgeAI (verificar disponibilidade/nome parecido).
- [ ] Descrição curta/longa em PT-BR (e EN):
      - Destaques: 100% offline, privado, modelos GGUF, download direto do HuggingFace, sem anúncios.
- [ ] Ícone 512×512 (usar o launcher adaptive), **feature graphic** 1024×500, screenshots (chat + modelos + ajustes) em PT-BR.
- [ ] Categoria: "Ferramentas" ou "Educação"; tags: offline, IA, LLM, privacidade.
- [ ] Formulário de permissões: justificar microfone.

## 6. Licenças e atribuições

- [ ] Adicionar **tela "Licenças" no app** (hoje só constam no README):
      - llama.cpp (MIT), OkHttp (Apache-2.0), AndroidX/Compose (Apache-2.0), kotlinx-coroutines (Apache-2.0), ícones Material (Apache-2.0).
- [ ] Na listagem: informar que **modelos GGUF têm licenças próprias** baixadas do HuggingFace (avisar o usuário de verificar a licença de cada modelo).
- [ ] Incluir o texto MIT do llama.cpp no APK (arquivo `NOTICE`/`LICENSES` em `res/raw` ou assets) — R8 não remove assets.

## 7. QA final (antes do upload)

- [ ] Instalação limpa + primeiro uso sem internet (só depois de baixar o modelo).
- [ ] Download de modelo com pausa/retomada (matar o app no meio).
- [ ] Chat multi-turno (KV cache), regenerar, parar, limpar conversa, histórico persistido.
- [ ] Trocar de modelo com o app em geração (deve negar ou parar com segurança).
- [ ] Mic: conceder/negar permissão e testar os erros de `SpeechRecognizer`.
- [ ] Tema escuro/claro, Material You, rotação (travada em portrait — ok).
- [ ] Teste de calor/bateria: geração longa (300+ tokens) — já observamos throttling; verificar se não derruba o app.
- [ ] Medir tok/s e registrar na release notes (o app mostra `⚡ tok/s` no chat).
- [ ] Testar o **APK release (R8)** — a lib nativa não pode ser ofuscada; confirmar que `System.loadLibrary` e os callbacks JNI funcionam.

## 8. Upload e rollout (Play Store)

- [ ] Gerar **App Bundle**: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
- [ ] Criar conta de desenvolvedor (US$ 25, única).
- [ ] Subir em **Internal testing** → testar no aparelho via Play.
- [ ] Aprovar **Closed testing** (14 dias com no mínimo 12 testadores ativos é obrigatório para contas novas — planejar isso antes do lançamento público).
- [ ] Depois do teste fechado: **Production** com rollout gradual (10% → 25% → 100%).
- [ ] Conferir o relatório "Pre-launch report" (crash em outros aparelhos, acessibilidade).

## 9. Sideload (alternativa sem Play)

- [ ] Gerar APK assinado: `./gradlew assembleRelease` + assinar com o keystore (ou usar o `scripts/build_apk.sh` para debug).
- [ ] Publicar no GitHub Releases com: APK, checksum (SHA-256), e aviso de compatibilidade da lib nativa (dotprod vs portátil).
- [ ] Listar licenças e instruções de uso no release.

## 10. Pós-lançamento

- [ ] Monitorar crashes: o app não tem crash reporting — considerar adicionar (ex.: Play SDK nativo é o mais simples, sem backend próprio) ou um logger local exportável.
- [ ] Acompanhar avaliações e relatos de bateria/calor.
- [ ] Manter o llama.cpp pinado (b10333) atualizado de tempos em tempos — testar o JNI a cada bump.
- [ ] Revisar a lista de modelos sugeridos (podem sair do ar / mudar de licença).
