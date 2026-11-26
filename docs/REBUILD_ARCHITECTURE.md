# Launcher Lite — reconstrução do núcleo (v1)

## Princípios

1. O Android é a autoridade sobre o papel HOME. O app consulta e solicita a seleção; não disputa foco por conta própria.
2. Eventos de acessibilidade não podem iniciar MainActivity. Janelas do System UI, painéis OEM e telas do launcher original podem aparecer durante ações legítimas do controle remoto.
3. O botão físico de configurações e os painéis do sistema continuam sob controle do firmware. A launcher não intercepta nem simula esses comandos.
4. O retorno de outra Activity deve preservar o estado transitório da interface. onResume() não deve reconstruir a tela inteira.
5. Inicialização no boot será implementada somente depois de medir o comportamento real do dispositivo, com HOME já configurada.
6. Acessibilidade e listener de notificações são recursos opcionais e isolados. Nenhum deles pode ser requisito para abrir HOME.

## Primeira etapa nesta branch

- HomeRoleController: leitura centralizada e sem efeitos colaterais do papel HOME.
- LauncherLifecycleCoordinator: registro passivo das transições da Activity e do intervalo entre pausa e retomada.
- LauncherAccessibilityService: observador de diagnóstico; não chama startActivity() nem tenta trazer a launcher para frente.
- Testes unitários do registro de ciclo de vida.

## Próximas etapas

1. Extrair navegação e ações do sistema da MainActivity.
2. Separar catálogo de aplicativos, favoritos/canais, clima, notificações e telas transitórias.
3. Tornar a restauração de foco explícita e baseada em IDs estáveis, nunca em reconstrução geral da tela.
4. Implementar política de recuperação limitada e verificável, desligada por padrão.
5. Testar no Realme Stick: HOME, engrenagem/painel OEM, abrir/fechar app, suspensão/retorno e reinicialização.
6. Só então decidir se serviço de boot ou acessibilidade é realmente necessário.

## Fora do escopo desta etapa

- Não remover, desativar ou ocultar a Google TV.
- Não usar WRITE_SECURE_SETTINGS para trocar HOME automaticamente.
- Não reiniciar o processo para roubar foco.
- Não adicionar BOOT_COMPLETED antes dos testes.
