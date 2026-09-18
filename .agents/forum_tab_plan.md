# Plano de implementação — aba Fórum

## Diagnóstico
- [x] A navegação principal usa `MainScreen.kt` e termina em Modo foco (tab 4).
- [x] Não existe backend, API, Firebase, Supabase, Retrofit ou Ktor no projeto.
- [x] O perfil local já fornece nome e avatar para identificar publicações.
- [x] SharedPreferences já é usado no projeto para estado persistente simples.

## Implementação
- [ ] Adicionar Fórum como tab 5, imediatamente após Modo foco.
- [ ] Mostrar título Fórum no topo quando a aba estiver ativa.
- [ ] Criar compositor recolhido que expande ao toque.
- [ ] Adicionar campo multilinha, limite/contador e botão Publicar.
- [ ] Persistir publicações localmente com nome, avatar, texto e horário.
- [ ] Exibir todas as publicações persistidas abaixo, mais recentes primeiro.
- [ ] Adicionar estado vazio e aviso transparente de armazenamento local nesta versão.
- [ ] Manter os quatro tabs existentes e seus fluxos inalterados.
- [ ] Manter paridade exata de strings inglês/português.
- [ ] Adicionar testes para política de texto e persistência.

## Validação
- [ ] Revisar diff por regressões de navegação e foco.
- [ ] Rodar unit tests.
- [ ] Rodar Android Lint.
- [ ] Rodar compilação do harness/variantes cobertas pela CI.
