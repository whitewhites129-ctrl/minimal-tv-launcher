# Monet Launcher 1.1.2 — notas da inspeção do APK

## Arquivo analisado

- Arquivo recebido: XAPK da Monet Launcher 1.1.2.
- Pacote principal dentro do XAPK: com.klevico.monet.apk.
- O XAPK também contém APKs de configuração/idioma/densidade e ABI.

## Evidências encontradas na tabela de strings do DEX

O binário contém identificadores e mensagens associados a:

- Retorno à HOME: HomeReturnSupportFacts, PendingHomeReclaim, pending_home_reclaim_delay_millis.
- Inicialização: BootHomeRecoveryState, BootAutostartRequest e BOOT_COMPLETED.
- Acessibilidade: AccessibilityBindingMarkers, isAccessibilityServiceEnabled e Accessibility lifecycle repair.
- Reinício: LauncherRestart, RESTART_HANDOFF, RESTART_STARTED, além de mensagens sobre preparar/confirmar a reinicialização sem roubar o papel HOME.
- Janela e foco: LauncherForegroundWindow, HOME_AWAY, IN_WINDOW, input_session_ended_by_stock_home e input_session_kept_for_stock_home.

## O que podemos concluir

A Monet contém uma estrutura interna com conceitos explícitos para retorno à HOME, inicialização, ciclo de vida da acessibilidade, estado de janelas e reinicialização controlada. Isso é mais elaborado que a regra simples da Lite que observava o pacote do Google TV e chamava startActivity(MainActivity).

## Limite desta inspeção

A tabela de strings do DEX não revela, por si só, a lógica completa nem prova em que sequência cada trecho é executado. Não estamos tratando nomes de classes ou mensagens como prova de comportamento. Uma descompilação integral do fluxo e testes no dispositivo são necessários antes de reproduzir qualquer mecanismo.

## Decisão de engenharia

Usar a Monet como referência de requisitos e conceitos, mas manter uma implementação própria, sem copiar cegamente rotinas de recuperação que possam competir com a HOME ou com os painéis do firmware.
