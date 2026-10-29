# Proposta de projeto — APS 7º/8º semestre CC (DSD)

**Tema da APS:** Desenvolvimento de uma aplicação de sistema distribuído para dispositivo móvel, sobre gerenciamento de informações ambientais urbanas.

**Projeto:** ViaAgora — tempo de espera das travessias litorâneas da Baixada Santista.

## Problema

As balsas Santos ⇄ Guarujá, Santos ⇄ Vicente de Carvalho e Bertioga ⇄ Guarujá são transporte público estadual usado diariamente por moradores e turistas. Filas longas geram congestionamento nas avenidas de acesso, emissão de poluentes com motores ligados e perda de tempo. A espera muda ao longo do dia e com as condições ambientais (maré baixa, neblina, mar agitado), que chegam a impedir o transporte de veículos pesados.

O painel oficial informa a espera atual, mas não guarda histórico, não avisa quando a fila diminui e não recebe a visão de quem está na fila.

## Enquadramento no tema

O documento da APS cita, entre os exemplos de informações ambientais urbanas, "trânsito (inclusive transportes públicos)". O projeto trata:

- **transporte público**: as travessias são um serviço público de transporte;
- **trânsito**: as filas de veículos nos acessos às balsas;
- **condições ambientais**: tempo, maré e neblina, que afetam a operação e aparecem no app.

## Solução

- **Aplicativo Android nativo (Kotlin)** com as travessias, a espera em cada lado, mapa dos terminais, relatos e avisos.
- **Servidor distribuído** com seis processos independentes: coletor de dados oficiais, duas réplicas da API, worker de eventos, broker de mensagens (RabbitMQ) e banco de dados (PostgreSQL).
- **Coletor (scraper)**: varre o site das Travessias Litorâneas (SEMIL-SP), encontra o serviço do painel e transforma os dados em uma API própria, com histórico.
- **Crowdsourcing**: relatos de espera, fila, embarque lento, travessia parada, clima e acidente, combinados com o dado oficial (mediana das esperas informadas).
- **Tempo real**: alterações chegam a todos os celulares por WebSocket, passando pelo broker entre as réplicas.

## Conceitos de sistemas distribuídos aplicados

- Comunicação assíncrona por mensagens (publish/subscribe com exchange fanout).
- Replicação da camada de API, com cada réplica recebendo todos os eventos.
- Padrão transactional outbox: consistência entre banco e mensagens, com tolerância à queda do broker.
- Idempotência e reenvio seguro de requisições (Idempotency-Key).
- Tolerância a falhas: dado atrasado sinalizado, backoff exponencial do coletor, reconexão e ressincronização dos clientes.
- Integração com fonte externa de dados, protegida por um único coletor.

## Funções extras (impacto na nota, item 3 das observações)

- Relatório de **melhor horário para atravessar**, com o histórico por hora do dia.
- **Avisos** quando a espera fica abaixo do limite escolhido.
- Mapa com os terminais coloridos pela espera.
- Modo demonstração sem servidor.
- Testes automatizados: 24 no servidor e 15 no Android.

## Interdisciplinaridade

Sistemas distribuídos, redes e protocolos (HTTP, WebSocket, AMQP), banco de dados (transações e consultas agregadas), engenharia de software (testes e arquitetura), programação para dispositivos móveis, estatística (mediana e médias por hora) e meio ambiente urbano (mobilidade, emissões e condições do mar).
