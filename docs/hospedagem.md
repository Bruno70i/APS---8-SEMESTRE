# Ambiente hospedado

A versão disponível em `https://sedda.com.br` mantém seis serviços Docker em uma VPS: duas APIs, PostgreSQL, RabbitMQ, worker e coletor. O Nginx instalado no servidor atende HTTPS e os arquivos estáticos de `site/`.

## Entrada dos pedidos

- `/`: apresentação acadêmica em HTML, CSS e JavaScript.
- `/downloads/ViaAgora.apk`: APK de avaliação.
- `/downloads/SHA256SUMS`: hash do arquivo publicado.
- `/v1/`: pedidos da API, incluindo o WebSocket em `/v1/events`.
- `/health`: saúde do backend.

O Nginx encaminha os pedidos às APIs, que escutam apenas em loopback. O banco e o broker se comunicam com os outros serviços pela rede interna do Docker. Credenciais de infraestrutura ficam no servidor, fora do repositório e do aplicativo.

## Fluxo dos dados

O coletor consulta a fonte oficial e compartilha sua leitura com todos os clientes. Uma alteração visível ou um relato gera um evento na outbox. O worker publica o evento no RabbitMQ; cada API recebe uma cópia e notifica seus próprios clientes. Depois do aviso, o aplicativo busca o estado persistido.

## Observação e limites

`/v1/system` informa a réplica da consulta, os sinais de vida dos processos, as pendências e os rastros registrados. A tela Bastidores e a consulta do site usam esse endpoint.

Os serviços estão em processos separados, mas na mesma máquina. A continuidade após a falha de uma API não representa redundância física da VPS. O ambiente local permite reproduzir a comunicação e os experimentos sem contratar uma hospedagem.

As instruções do laboratório estão em [execução local](execucao-local.md).
