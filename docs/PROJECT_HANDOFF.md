# Launcher Lite — Registro oficial do projeto e guia de continuidade

> Documento vivo. Atualizar em toda mudança relevante de código, arquitetura, teste, build ou decisão.
> Não apagar entradas antigas: acrescentar novas entradas no topo da seção **Histórico de atualizações**.
> Este arquivo existe para permitir a retomada do trabalho em outra conversa sem depender do histórico do chat.

## 1. Objetivo do produto

Construir o Launcher Lite como um produto Android TV comercial, confiável e bem acabado — não como um protótipo que apenas compila.

Princípios obrigatórios:
- Priorizar funcionamento real, estabilidade, previsibilidade e experiência de uso.
- Não declarar uma correção concluída apenas porque o APK compilou.
- Não prometer ausência absoluta de bugs; usar critérios de validação, testes de regressão e testes no dispositivo real.
- Preservar o design e os recursos existentes, salvo solicitação explícita do usuário.
- Desenvolver a lógica principal sob controle do próprio projeto, evitando dependências desnecessárias de terceiros.
- Pesquisar e testar antes de adotar soluções que possam afetar HOME, foco ou janelas do sistema.
- Nunca comprometer o painel lateral de configurações do fabricante.
- Manter o Google TV instalado e habilitado.
- Manter a branch estável anterior intacta; trabalhar em branches separadas.
- O tamanho do APK, isoladamente, não é medida de qualidade. O produto deve conter implementação completa, segura e testada.

## 2. Ambiente de referência

- Repositório: https://github.com/whitewhites129-ctrl/minimal-tv-launcher
- Pacote Android: `br.com.launcher.tv`
- Dispositivo principal de teste: Realme Stick
- Android: 14 / SDK 34
- Firmware informado: `realme/HP4607_realme/YYT:14/UKG3.250826.001/C3.2.8_20260408:user/release-keys`
- Ferramenta de conexão do usuário: Bugjaeger (ADB)
- Launcher de referência: Monet Launcher 1.1.2 (`com.klevico.monet`), que funciona corretamente no dispositivo.
- Google TV deve permanecer instalado e habilitado.

## 3. Comportamento esperado

1. Tecla HOME: abrir o Launcher Lite de forma consistente.
2. Botão/tecla de configurações (engrenagem): continuar abrindo o painel lateral original do sistema/fabricante.
3. O Launcher Lite não pode fechar, cobrir ou roubar o foco desse painel.
4. Ao abrir outro aplicativo e retornar, restaurar foco de forma previsível.
5. Inicialização, retomada e retorno do modo de espera devem ser confiáveis.
6. Não usar loops de relançamento, recuperação agressiva ou roubo de foreground.
7. Acessibilidade não é requisito para o funcionamento básico até que uma abordagem segura seja comprovada.
8. Preservar o layout e o design aprovados.

## 4. Histórico técnico e diagnóstico

### Problema HOME / Google TV
- Em versões anteriores, HOME abria Google TV em algumas condições.
- Com o AccessibilityService antigo ativo, o Google TV aparecia rapidamente e o serviço tentava trazer o Launcher Lite para frente.
- Esse comportamento poderia causar disputa de foco e interferir no painel lateral do sistema.
- Sem o serviço de acessibilidade, HOME também chegou a abrir Google TV em testes anteriores.
- Em um teste, `cmd role get-role-holders android.app.role.HOME` indicou o Lite como titular, mas a atividade em primeiro plano observada depois era Google TV.
- `cmd package resolve-activity ... CATEGORY_HOME` retornou Google TV mesmo em cenário no qual Monet funcionava. Nesse firmware, esse comando isolado não deve ser tratado como prova definitiva do launcher efetivamente acionado.
- Logs anteriores registraram tanto dispatch HOME para Monet quanto mensagens de foreground do sistema. É necessário correlacionar os eventos no aparelho, sem inferir causalidade por uma única linha.

### Acessibilidade e Monet
- A listagem pública do Monet descreve uso opcional de AccessibilityService para detectar pressionamentos HOME e mudanças de janela/launcher.
- O APK Lite auditado tinha configuração de acessibilidade inscrita em mudanças de janela (`typeWindowStateChanged`), mas não declarava `canRequestFilterKeyEvents` e não implementava `onKeyEvent()`.
- Portanto, o Lite anterior não usava o mecanismo documentado de filtragem de teclas globais do AccessibilityService.
- A API Android exige declarar a capacidade de filtrar eventos de tecla na metadata do serviço para solicitar esse mecanismo.
- Só um serviço de acessibilidade habilitado pode filtrar eventos de tecla de cada vez; outro serviço pode impedir a recepção.
- Isso não prova que o Realme encaminhará KEYCODE_HOME ao serviço, mesmo com a capacidade declarada. Precisa ser medido.
- Não ativar essa capacidade na configuração padrão sem protótipo isolado e testes.
- O repositório público de distribuição do Monet não inclui o código-fonte. Strings/nomes encontrados no DEX não demonstram por si só o algoritmo completo. Não afirmar que o Monet foi integralmente reproduzido ou revertido.

### Foco e ciclo de vida
- Perder o foco da janela não significa necessariamente que outra Activity foi aberta; painéis do sistema podem tomar foco enquanto a Activity do launcher continua RESUMED.
- Restaurar foco automaticamente em todo retorno de foco pode fechar ou competir com UI do sistema. Essa lógica precisa ser auditada e validada no dispositivo antes de mudanças.
- A MainActivity continua grande e concentra UI, ciclo de vida, configurações, notificações, clima, screensaver, catálogo de apps, navegação e foco. A modularização deve ser incremental, com testes e sem redesenho involuntário.
- A grade de apps recria views durante renderização e usa índices/âncoras para foco; isso é um risco para restauração quando a ordem muda.
- A renderização dos canais também remove e recria views.
- O carregamento de clima usa coordenadas fixas e conexão de rede direta; requer revisão de produto antes de ser considerado produção.
- O targetSdk atual foi identificado como 28, apesar de compileSdk 35. Não elevar sem análise deliberada de compatibilidade e permissões.

## 5. Branches e estado conhecido

### Branch anterior preservada
- `rebuild/launcher-core-v1`
- Commit base conhecido: `7edb7d3c275bbe3c8eaab3a14088593879f08e8f`
- Build anterior: run `45742775139`, sucesso.
- Artefatos anteriores foram beta técnico, não release aprovado.

### Branch de trabalho atual
- `rebuild/product-grade-foundation`
- URL: https://github.com/whitewhites129-ctrl/minimal-tv-launcher/tree/rebuild/product-grade-foundation
- Base inicial: `7edb7d3c275bbe3c8eaab3a14088593879f08e8f`
- Commits relevantes:
  - `0fe4dac7ac25e450fbc1ba1acff856f6368248a3` — carta de qualidade do produto.
  - `ae6ba44d61396fec93dae6ece945adc1365e5cf9` — auditoria técnica inicial.
  - `f4fd301fcfcd6b2ca5539606e69840b2bba1f06c` — máquina de estados passiva de sessão.
  - `a1a21a243de887587f0ec4780735d377342d3fc7` — testes da máquina de estados.
  - `df2d43b6c4e017933f6058f064e86c8088864154` — pesquisa HOME/acessibilidade.
  - `7dd588d0b351702d09de8bd32cedcb9ac3d52c2e` — workflow habilitado para a branch.
  - `d5c2da5bb03b24f65a6e5d3203dcaa1155402b83` — integração do registro passivo de sessão na MainActivity.
- URL do branch: https://github.com/whitewhites129-ctrl/minimal-tv-launcher/tree/rebuild/product-grade-foundation

## 6. Implementações feitas nessa branch

- `docs/PRODUCT_QUALITY_CHARTER.md`: princípios e gates de qualidade.
- `docs/INITIAL_TECHNICAL_AUDIT.md`: problemas arquiteturais e riscos encontrados.
- `docs/HOME_ACCESSIBILITY_RESEARCH.md`: fatos verificados sobre HOME/acessibilidade e decisão de não ativar filtragem global sem teste.
- `LauncherSessionStateMachine.kt`: reducer puro que registra estágio da Activity e foco de janela; não inicia Activities nem altera HOME/acessibilidade.
- `LauncherSessionStateMachineTest.kt`: testes da máquina de estados.
- `MainActivity.kt`: passou a enviar eventos de ciclo de vida e foco para a máquina de estados e registrar logs com tag `LauncherLite.Session`.
- O registro novo é observacional: não foi criado para tomar decisões automáticas, relançar o launcher ou alterar o foco.
- A lógica preexistente de restauração de foco continua existindo e ainda precisa de auditoria específica. A integração passiva não prova que o painel OEM está protegido.

## 7. Compilações e evidências

### Workflow run 45745066350
- Branch: `rebuild/product-grade-foundation`
- Commit: `7dd588d0b351702d09de8bd32cedcb9ac3d52c2e`
- Resultado: sucesso.
- Validou a etapa inicial da branch e workflow; não é evidência de teste no Realme.

### Workflow run 45745487684
- Branch: `rebuild/product-grade-foundation`
- Commit: `d5c2da5bb03b24f65a6e5d3203dcaa1155402b83`
- URL: https://github.com/whitewhites129-ctrl/minimal-tv-launcher/actions/runs/45745487684
- Resultado final consultado: workflow concluído com sucesso; jobs `validate` e `build` concluídos com sucesso.
- O job construiu APK debug de validação e APK release assinado.
- Artefato publicado: `minimal-tv-launcher-apk`, ID `15083062311`, tamanho ZIP `695533` bytes, SHA-256 `56da6e4df66378e40be8545ace7042ae22bfc0d61c1d563fd78c2e954deaef7b`, expira em 2027-02-24.
- A etapa `Upload test APK` apareceu como skipped; a etapa `Upload signed APK` concluiu com sucesso. O artefato publicado é o pacote de APK assinado.
- Isso confirma build/empacotamento, não instalação nem teste funcional no Realme Stick.
- Etapas esperadas no job build: compilar APK debug de validação Realme, publicar artefato de teste, preparar assinatura persistente, compilar APK release assinado e publicar artefato assinado.
- O APK publicado ainda precisa ser baixado, inspecionado e instalado/testado no dispositivo real.
- Não tratar sucesso do workflow como aprovação funcional de release.

## 8. Próximos passos técnicos

1. Consultar o estado final do workflow run `45745487684`; inspecionar falhas e logs se necessário.
2. Se o APK de teste for publicado, fornecer link correto do artefato e instruções simples para instalação no Realme Stick, sem substituir a versão estável sem necessidade.
3. Fazer teste de regressão manual no dispositivo: HOME repetido, engrenagem/painel OEM, abrir app e voltar, standby/retomada, reboot e foco do controle remoto.
4. Coletar logs ADB com tag `LauncherLite.Session` e eventos do sistema; comparar janela/foco com as ações executadas.
5. Auditar a restauração de foco atual antes de alterá-la; não supor que o callback de foco identifica o dono da janela externa.
6. Só depois desenhar um protótipo isolado de AccessibilityService com filtragem de tecla, preferencialmente em build/variant experimental; não habilitar por padrão. Testar conflito com outros serviços e se HOME chega ao callback.
7. Modularizar MainActivity por responsabilidades em etapas pequenas, com testes e sem mudança visual.
8. Rever foco estável por identidade de app/canal, não apenas índice.
9. Revisar clima, permissões, targetSdk, tratamento de erros e persistência antes de release.
10. Só declarar uma versão candidata a release depois de testes automatizados, inspeção dos artefatos, testes no dispositivo real e regressão das funções existentes.

## 9. Procedimento obrigatório para cada atualização

Em toda alteração relevante:
1. Registrar data/hora UTC e branch/commit.
2. Descrever arquivos e comportamentos alterados.
3. Explicar o motivo técnico e os riscos.
4. Registrar testes executados e resultado real (não presumido).
5. Registrar links de workflow/build e artefatos quando existirem.
6. Distinguir claramente: compilou, passou testes automatizados, foi instalado, foi testado no dispositivo, foi aprovado.
7. Anotar pendências e próximo passo.
8. Atualizar este arquivo acrescentando uma entrada no topo da seção de histórico; não sobrescrever entradas antigas.
9. Manter também o histórico de commits do Git como trilha de auditoria.

## 10. Como retomar em uma nova conversa

Começar dizendo:
“Vamos continuar o Launcher Lite. Leia primeiro o arquivo docs/PROJECT_HANDOFF.md na branch rebuild/product-grade-foundation do repositório whitewhites129-ctrl/minimal-tv-launcher. Esse arquivo é o registro oficial do projeto. Confira também o estado atual do GitHub Actions e os últimos commits antes de fazer alterações. Siga os critérios de qualidade e não altere HOME, acessibilidade ou restauração de foco sem plano de teste. Mantenha Google TV instalado e habilitado, preserve o painel lateral de configurações e atualize o registro em toda mudança relevante.”

Não confiar cegamente no estado de build registrado neste documento: consultar GitHub novamente, pois a execução pode ter terminado após a última atualização.

## 11. Histórico de atualizações

### 2026-11-26 — Instrumentação para diagnosticar a abertura da Google TV no boot
- Problema: no boot a Google TV aparece primeiro e, alguns segundos depois, a Launcher Lite abre via `BOOT_COMPLETED`. Não é possível executar ADB durante a janela curta porque a depuração ainda não está disponível.
- Auditoria: `BootReceiver` enviava um `PendingIntent` para abrir MainActivity, mas descartava silenciosamente todas as exceções e não registrava recebimento do broadcast nem tentativa/resultado do envio.
- Alteração em `BootReceiver.kt`: logs `LauncherLite.Boot` no recebimento do broadcast, ação recebida, tempo desde boot, envio do PendingIntent e exceções completas.
- Alteração em `MainActivity.kt`: log `LauncherLite.Boot` em `onCreate` com action/categorias do Intent e tempo desde boot.
- Commits: `9f99235cddf46d17f5b389cd9decf63415fe0ea6` e `3b333d3caf32d835ef44e97527d2e81c8be0b6f6`.
- Objetivo: após o próximo boot, recuperar os registros via logcat quando a depuração voltar e correlacionar os tempos com eventos ActivityTaskManager/Google TV. O log confirma eventos do app, não identifica sozinho quem iniciou a Google TV.
- Estado: instrumentação enviada ao branch; build CI e teste no Realme Stick pendentes. Nenhuma lógica de foco, HOME, seleção de launcher ou abertura automática foi alterada nesta etapa. Google TV permanece instalada e habilitada.


### 2026-11-26 — Teste de inicialização a frio no Realme Stick
- Usuário desligou completamente o Realme Stick, aguardou cerca de 20 segundos sem alimentação e ligou novamente.
- Resultado observado: Google TV apareceu primeiro; alguns segundos depois, Launcher Lite abriu automaticamente.
- Interpretação: o caminho de inicialização via `BOOT_COMPLETED` está produzindo a abertura da Launcher Lite, mas não impede que a Google TV seja exibida antes. A experiência ainda apresenta uma troca visual/demora após o boot.
- Estado: teste de inicialização automática parcialmente positivo; não considerar inicialização direta/sem transição aprovada. Próxima investigação: verificar seleção/resolução do HOME padrão no firmware e ordem dos eventos de boot antes de alterar o mecanismo. Manter Google TV instalado e habilitado; não introduzir loops ou tentativas repetidas de roubar o foreground.
- Nenhuma alteração funcional ou novo APK nesta etapa.


### 2026-11-26 — Retorno positivo nos testes de regressão iniciais
- Usuário informou: “Até agora tudo certo”, após a instalação do APK com a correção de Voltar na raiz.
- Interpretação registrada: não foram relatados novos problemas nos testes realizados até o momento, incluindo o comportamento de navegação que motivou a correção e as verificações seguintes solicitadas (retorno de aplicativos e painel OEM/engrenagem).
- Estado: validação inicial positiva no Realme Stick. Ainda não equivale à aprovação completa de release; permanecem importantes os testes de reboot, standby/retomada, HOME repetido e uso prolongado.
- Nenhum código ou APK foi alterado nesta atualização; apenas o registro de continuidade foi atualizado.


### 2026-11-26 — Retorno inicial do usuário: Voltar na raiz
- Usuário instalou o APK do commit `a1964d5262dd3e2132edaa14d8ac7b2c35fb8ace` (workflow run `45751771780`, conclusão success).
- Feedback: “Parece que funcionou”, após testar Voltar repetidamente na tela inicial.
- Estado: validação inicial positiva, ainda não considerada regressão completa aprovada. Pendentes: testar Voltar dentro de aplicativos, HOME, engrenagem/painel OEM e retorno à Launcher.


### 2026-11-26 — Correção do botão Voltar na raiz da Launcher
- Relato confirmado no Realme Stick: HOME abre a Launcher Lite, mas pressionar Voltar repetidamente na tela inicial encerra a Launcher e expõe a Google TV.
- Auditoria do método `MainActivity.onBackPressed()`: os painéis internos já tinham tratamento próprio, mas o fluxo final chamava `super.onBackPressed()`, permitindo que a Activity raiz fosse finalizada.
- Alteração: quando não há protetor de tela, painel de notificações, painel rápido, página de clima ou modo de organização ativo, Voltar agora mantém a MainActivity aberta e solicita restauração do foco na raiz. O tratamento de Voltar dentro dos aplicativos não foi alterado; o AccessibilityService continua filtrando exclusivamente HOME.
- Arquivo: `app/src/main/java/br/com/launcher/tv/MainActivity.kt`.
- Commit: `a1964d5262dd3e2132edaa14d8ac7b2c35fb8ace`.
- Estado: alteração enviada ao branch. Build CI e teste no Realme Stick ainda pendentes. Validar Voltar na tela inicial, dentro de menus/painéis da Launcher e dentro de aplicativos; confirmar também que HOME e engrenagem/painel OEM continuam funcionando.


### 2026-11-26 — HOME detectado e roteamento experimental implementado
- Teste real no Realme Stick: com o serviço Launcher Lite habilitado, `dumpsys accessibility` mostrou `capabilities=8`, correspondente a `CAPABILITY_CAN_FILTER_KEY_EVENTS`.
- Após limpar o log, pressionar HOME produziu `action=0, keyCode=3` e `action=1, keyCode=3` na tag `LauncherLite.Accessibility`. Isso confirma que o serviço recebe o pressionamento e a soltura de HOME.
- Alterado `LauncherAccessibilityService.kt`: agora trata exclusivamente `KEYCODE_HOME`; inicia `MainActivity` no primeiro ACTION_DOWN, ignora repetições para evitar múltiplas aberturas e consome o ACTION_UP. Outras teclas, inclusive a tecla de configurações/engrenagem, continuam retornando `false` para o sistema.
- A abertura usa `NEW_TASK | CLEAR_TOP | SINGLE_TOP`, compatível com o `launchMode="singleTask"` já declarado para MainActivity. Não foi introduzido loop de relançamento, polling ou ação em `onWindowFocusChanged`.
- Em falha de ActivityNotFoundException ou SecurityException, o serviço registra o erro e devolve HOME ao sistema.
- Commit: `c69b50a19d018793e10f258bb7705e57fc2b2412`.
- Estado: código enviado ao branch; build e teste no dispositivo ainda pendentes. Não afirmar que HOME foi corrigido até instalar o APK e validar HOME, engrenagem/painel OEM, retorno de apps e repetição de HOME. Google TV permanece instalado e habilitado; nenhum teste pediu sua remoção.
 

### 2026-11-26 — Protótipo experimental de captura de teclas e boot
- Teste no Realme Stick confirmou: role HOME pertence a `br.com.launcher.tv`, serviço Launcher Lite está habilitado, mas após pressionar HOME a atividade em primeiro plano é `com.google.android.apps.tv.launcherx/.home.HomeActivity`.
- `dumpsys accessibility` mostrou Launcher Lite com `capabilities=0`, ouvindo somente `TYPE_WINDOW_STATE_CHANGED`; Button Mapper também está habilitado. Não há evidência suficiente para atribuir a falha ao Button Mapper.
- Alterado `launcher_accessibility_service.xml` para solicitar `canRequestFilterKeyEvents` e `flagRequestFilterKeyEvents`.
- Alterado `LauncherAccessibilityService.kt` para registrar eventos de tecla em `LauncherLite.Accessibility` e sempre retornar `false`; nenhum evento é consumido e nenhuma Activity é iniciada.
- Alterado `AndroidManifest.xml` para declarar `RECEIVE_BOOT_COMPLETED` e registrar o `BootReceiver` existente.
- Commits: `9d4a24d6bcad60d39e953f73f4a2533599c25b41`, `43b2c6a68ae47b09bd4a294f28935678a5debf25`, `e5041e146f309417d85ead67a684d736fe660644`.
- Risco/limite: Android/OEM pode não entregar KEYCODE_HOME ao serviço; outro serviço de acessibilidade pode ocupar a filtragem de teclas. O receiver de boot pode continuar sujeito às restrições de início em segundo plano do Android 14/firmware.
- Ainda não compilado/testado neste dispositivo. Build CI e teste de captura são pendentes. Painel OEM não foi alterado; o serviço não consome teclas.
 

### 2026-11-26 — Artefato baixado e inspecionado localmente
- Baixado o artefato GitHub Actions `minimal-tv-launcher-apk` (artifact ID `15083062311`) e extraído o ZIP.
- ZIP continha `app-release.apk`; tamanho do APK: 703.020 bytes.
- SHA-256 do APK: `e893dd463d830dc422a44df304a4e05627301c90776aff9c82c7321a136ec8bc`.
- O APK contém `classes.dex`, `AndroidManifest.xml`, recursos e bloco de assinatura; a verificação básica com `jarsigner` identificou certificado com validade até 2054-04-12. Essa verificação não substitui `apksigner verify` nem a instalação no dispositivo.
- Ainda não houve instalação nem teste funcional no Realme Stick. Não considerar release aprovada até concluir esses testes.

### 2026-11-26 — Atualização: build concluído e artefato publicado
- Workflow run `45745487684` terminou com sucesso; validação e build concluídos.
- APK release assinado publicado no artefato `minimal-tv-launcher-apk` (ID `15083062311`, ZIP 695.533 bytes; SHA-256 registrado na seção de builds).
- APK ainda não foi instalado nem testado no Realme Stick; não é release funcional aprovada.
- Registro atualizado para refletir o resultado final do workflow.

### 2026-11-26 — Registro inicial criado
- Criado este documento de continuidade a pedido do usuário.
- Consolidado o histórico conhecido do projeto, requisitos do Realme Stick, diagnóstico HOME/acessibilidade, branches, commits e workflows.
- Estado de execução anotado conforme última consulta: run `45745487684`, validação concluída com sucesso e job de build em andamento.
- Nenhuma alteração funcional adicional foi feita nesta entrada.
