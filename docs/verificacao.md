# Verificação técnica

Última verificação: 29/10/2026.

## Backend

Foram executados 24 testes, com PostgreSQL e RabbitMQ reais e separados do ambiente da aplicação. O comando reproduzível está em `compose.test.yaml`.

Cobertura principal:

- validação dos relatos e autorização de seu autor;
- idempotência e gravação transacional na outbox;
- leitura e normalização da fonte oficial;
- persistência do último dado em falhas da fonte;
- entrega aos clientes de duas réplicas;
- recuperação de publicação pendente e ressincronização;
- rejeição de quadros WebSocket excessivos;
- sinais de vida e rastros de eventos;
- coerência dos horários quando a entrega ocorre antes de persistir a confirmação.

Os 24 testes passaram no ambiente isolado definido pelo repositório.

## Android

O comando de compilação executou testes, `assembleDebug` e `lintDebug`, com JDK 17 e SDK 35 externos à pasta do projeto. Os 15 testes passaram, a compilação foi concluída e o lint não apresentou erros.

O APK permanece na versão 1.1, versionCode 2. A chave de desenvolvimento foi preservada fora do repositório e a assinatura foi verificada.

A configuração automática e a migração das preferências antigas foram verificadas no emulador durante a publicação da versão 1.1. A aba Bastidores conectou ao servidor hospedado e identificou a réplica do canal.

## Dados e interpretação

O coletor hospedado usa dados oficiais. Os testes e o modo de demonstração usam ambientes e dados identificados separadamente. Eventos anteriores à instrumentação não possuem rastros retroativos de entrega.

As leituras e os sinais de vida variam com o tempo; a disponibilidade atual deve ser consultada na aplicação ou nos endpoints do ambiente em avaliação.
