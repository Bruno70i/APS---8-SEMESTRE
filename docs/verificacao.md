# Verificação técnica

Última verificação: 29/10/2026.

## Backend

Foram executados 23 testes, com PostgreSQL e RabbitMQ reais e separados do ambiente da aplicação. O comando reproduzível está em `compose.test.yaml`.

Cobertura principal:

- validação dos relatos e autorização de seu autor;
- idempotência e gravação transacional na outbox;
- leitura e normalização da fonte oficial, incluindo avisos de travessia parada;
- persistência do último dado em falhas da fonte;
- entrega aos clientes de duas réplicas;
- recuperação de publicação pendente e ressincronização;
- rejeição de quadros WebSocket excessivos;
- sinais de vida e rastros de eventos.

Os 23 testes passaram no ambiente isolado definido pelo repositório.

## Android

O comando de compilação executou testes, `assembleDebug` e `lintDebug`, com JDK 17 e SDK 35 externos à pasta do projeto. Os 12 testes passaram, a compilação foi concluída e o lint não apresentou erros.

O APK publicado é a versão 1.4 (versionCode 5), com SHA-256 `552798b88ecde9f5498f6694f0d7361eaa41112bb233655070e34213b4216cf3`. O arquivo baixado de `https://sedda.com.br/downloads/ViaAgora.apk` é idêntico ao compilado.

## Ambiente local

O projeto foi executado do zero numa cópia limpa do repositório, apenas com `docker compose up --build -d`. Em cerca de 30 segundos todos os serviços estavam no ar e o coletor leu o painel oficial. Verificações feitas:

- o Nginx alterna as requisições entre a API 1 e a API 2;
- com a API 1 parada, todas as requisições passam para a API 2 (atraso máximo de 2 segundos);
- o aplicativo no emulador, ligado a `http://10.0.2.2:8080`, recebeu os dados ao vivo e publicou um relato.

## Dados e interpretação

O coletor usa somente dados oficiais. Os testes usam bancos e brokers próprios. As leituras e os sinais de vida variam com o tempo; a disponibilidade atual deve ser consultada no aplicativo ou em `/v1/system`.
