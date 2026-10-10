# Tomato BR

Extensão de animes em português para Dantotsu e hosts compatíveis com a API 14 de extensões Aniyomi. Não é um aplicativo independente.

Busca com correspondência de títulos conservadora: se os nomes não corresponderem, retorna vazio. Consultas com temporadas podem localizar o título base no catálogo Tomato e selecionar seus grupos de episódios. Inclui 53 verificações de correspondência de nomes, temporadas e grupos.

## Instalação

1. Baixe o APK na [Release mais recente](https://github.com/sujirobase-ctrl/algumacoisablablabla/releases/latest).
2. Instale e habilite/confie na extensão no seu host.
3. Nas preferências da fonte Tomato BR, configure o token da sua própria conta Tomato. A sessão não vem no APK; acesso e expiração dependem do serviço.
4. Teste pela aba Extension Test do Dantotsu. Para títulos já associados incorretamente pelo host, revise a associação em Wrong Title.

## Builds automáticos

Cada commit novo enviado para `main` gera um APK assinado e uma Release. Um push com vários commits gera uma Release para cada commit. Commits em outras branches são publicados quando entram em `main`. Pushes com mais de 256 commits devem ser divididos.

O código de versão é `4 + número da execução × 1000 + posição do commit no push`; o nome é `14.<código>`. O prefixo 14 mantém a compatibilidade com a API do host. Há saltos intencionais na numeração. Reexecutar um job mantém sua versão; uma execução manual cria uma nova versão do commit atual. A Release mais recente aponta para a maior versão publicada. Não renomeie/recrie o workflow sem revisar a numeração; preserve também a chave de assinatura para continuar atualizando APKs instalados.

Veja o [passo a passo completo](PASSO_A_PASSO.md), incluindo compilação local e configuração dos Secrets.

## Arquivos

- `src/`: implementação da fonte e correspondência de nomes.
- `test/`: regressões de busca, sem rede e sem credenciais.
- `deps/`: bibliotecas usadas apenas para compilar, com SHA-256 verificado. Não são empacotadas no APK.
- `compile-stubs/`: declaração mínima de Okio usada apenas para resolver tipos na compilação.
- `scripts/`: compilação multiplataforma, versões e publicação.

O repositório não inclui APKs de terceiros, capturas, dados do emulador, tokens, backups ou chave privada de assinatura. Os APKs desta extensão ficam nos assets das Releases.
