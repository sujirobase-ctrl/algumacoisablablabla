# Passo a passo: código, APK e Releases

## 1. Preparação do código público

Foi criado um checkout separado contendo somente a fonte Java, recursos Android, manifesto, licença, teste de títulos, dependências de compilação e scripts. A investigação e os dados do emulador ficaram fora do repositório. O `.gitignore` exclui APKs, chaves, logs, arquivos de ambiente e saídas de compilação.

O endpoint da fonte é `https://prod-api.tomatoanimes.com`. O token é lido das preferências do usuário, sem credencial embutida. O código normaliza os nomes, verifica correspondência e interpreta temporadas para buscar o título base e selecionar os grupos correspondentes. Não há fallback deliberado para um anime aleatório. A disponibilidade de nomes alternativos depende dos metadados retornados pelo catálogo.

## 2. Assinatura e Secrets

Para permitir atualização sobre a versão instalada, a automação utiliza a mesma chave de assinatura existente. Ela é armazenada como Secret criptografado no GitHub, não como arquivo Git ou asset de Release. Faça backup privado da chave: perdê-la impede atualizações com a mesma assinatura.

Em Settings → Secrets and variables → Actions do repositório, configure:

| Secret | Conteúdo |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Arquivo keystore convertido integralmente para Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Senha do keystore |
| `ANDROID_KEY_PASSWORD` | Senha da chave |
| `ANDROID_KEY_ALIAS` | Alias da chave |

Os Secrets de assinatura foram configurados via `gh secret set` com entrada padrão, sem colocá-los em argumentos ou arquivos publicados. Não configure um token pessoal do Tomato na automação. Somente código confiável deve entrar em `main`, pois a automação dessa branch tem acesso aos Secrets e pode publicar Releases.

## 3. Como o build funciona

1. O Actions lista os commits novos do push e atribui uma versão a cada um.
2. Faz checkout do SHA exato do commit a compilar.
3. Instala Temurin Java 21, Android platform 36 e build-tools 36.0.0.
4. `scripts/build.py` verifica o SHA-256 das quatro dependências.
5. Compila e executa `TitleMatcherTest` (53 verificações).
6. Compila a fonte Java com `--release 8`, usando `android.jar` antes dos stubs no classpath.
7. O D8 transforma somente as classes da extensão em `classes.dex`. Dependências e stubs não entram no APK.
8. Gera um manifesto temporário com a versão daquele commit.
9. O AAPT empacota manifesto/recursos; o script adiciona `classes.dex` e executa zipalign.
10. Decodifica a chave em arquivo temporário privado, assina com apksigner e verifica a assinatura. As senhas são passadas por variáveis de ambiente.
11. Remove a chave temporária ao terminar a etapa, inclusive em caso de falha.
12. Publica somente o APK e `SHA256SUMS` como artifact e assets da Release, vinculada ao SHA compilado.

As actions externas estão fixadas por SHA, e o checkout não persiste credenciais Git.

## 4. Publicar uma alteração

```sh
git clone https://github.com/sujirobase-ctrl/algumacoisablablabla.git
cd algumacoisablablabla
# Edite os arquivos necessários.
git add src test
git commit -m "Melhora a correspondência de títulos"
git push origin main
```

Consulte [Actions](https://github.com/sujirobase-ctrl/algumacoisablablabla/actions) para acompanhar os testes/build. O job publica em [Releases](https://github.com/sujirobase-ctrl/algumacoisablablabla/releases). Um build que falha não publica um novo APK. A mera criação de um commit local não inicia o Actions: é necessário enviá-lo ao GitHub.

## 5. Compilar localmente

Instale Python 3, Java 21 e o SDK Android. Instale `platforms;android-36` e `build-tools;36.0.0` com sdkmanager. Configure `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_KEYSTORE_PATH`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD` e `ANDROID_KEY_PASSWORD` no ambiente local; mantenha a chave fora do Git.

```sh
python scripts/build.py --version-code 1005 --version-name 14.1005
```

Escolha uma versão maior que a instalada para atualizar. A saída é `dist/aniyomi-pt.tomatobr-v14.1005.apk` e `dist/SHA256SUMS`. O manifesto versionado não é alterado pelo build; a automação determina a versão final. A chave local precisa ser a mesma usada nas Releases para atualização direta.

## 6. Validação

O build valida dependências, executa testes de regressão e verifica a assinatura. Os testes de correspondência não comprovam disponibilidade de conteúdo ou autenticação ao vivo. Depois de instalar, use Extension Test no Dantotsu para validar busca, episódios e reprodução com a sua sessão.

Referência de numeração: [variáveis do GitHub Actions](https://docs.github.com/en/actions/reference/workflows-and-actions/variables). Segurança de credenciais: [Secrets no GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
