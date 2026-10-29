# Roteiro de demonstração acadêmica

## Contexto e arquitetura

Apresente o problema das travessias, a origem dos dados oficiais e a participação dos relatos. Mostre no site o percurso entre celular, Nginx, APIs, PostgreSQL/outbox, worker, RabbitMQ e coletor.

Os serviços hospedados compartilham uma única VPS. A replicação dos processos permite estudar a falha de uma API; a máquina inteira continua sendo um ponto de falha física.

## APK hospedado

1. Instale e abra o APK. A conexão com a VPS é automática.
2. Confira as três travessias, a origem e os horários das leituras.
3. Abra Mapa e Relatos.
4. Em Bastidores, observe o canal do celular e os sinais de vida dos processos.
5. Acompanhe os eventos registrados e use o passo a passo para explicar os conceitos.

Os eventos anteriores à instrumentação não possuem registros retroativos de entrega. Novos rastros aparecem quando há uma mudança na fonte ou um relato real.

## Laboratório distribuído

Siga [execução local](execucao-local.md) e conecte um cliente à porta 4310 e outro à 4311.

1. Envie um relato de teste no ambiente local.
2. Acompanhe sua chegada ao outro cliente: outbox → worker → RabbitMQ → APIs → WebSocket.
3. Pare o broker local e observe uma nova pendência após outro envio.
4. Inicie o broker e acompanhe a recuperação.
5. Pare uma API e observe o outro cliente. No acesso local direto, o cliente da API parada precisa usar a porta disponível.

Use `/v1/system` e os logs como evidências. Preserve os volumes durante os experimentos.

## Interpretação dos resultados

O dado externo pode ficar indisponível. A idade da leitura e o estado da coleta ajudam a interpretar o que aparece. Relatos são identificados como colaborativos.

O modo demonstração apresenta dados simulados explicitamente identificados. A avaliação da comunicação distribuída utiliza os serviços hospedados ou o laboratório ativo.
