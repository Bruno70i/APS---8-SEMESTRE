# ViaAgora — Travessias da Baixada Santista

Projeto educacional da APS de Desenvolvimento de Sistemas Distribuídos (UNIP). Um aplicativo Android mostra **em tempo real o tempo de espera das balsas** Santos ⇄ Guarujá, Santos ⇄ Vicente de Carvalho e Bertioga ⇄ Guarujá, apoiado por um servidor distribuído.

- Site com a explicação da arquitetura: https://sedda.com.br
- APK: https://sedda.com.br/downloads/ViaAgora.apk (abre conectado ao servidor oficial)

O sistema combina duas fontes:

- **Painel oficial** das Travessias Litorâneas (SEMIL-SP): um coletor lê o painel a cada minuto e transforma os dados em uma API própria.
- **Comunidade**: quem está na fila conta quanto está esperando ou avisa de fila longa, embarque lento, travessia parada, neblina ou acidente.

## Rodar no seu computador

Você precisa de **Git** e **Docker Desktop** (ou Docker com Compose).

```sh
git clone https://github.com/Bruno70i/APS---8-SEMESTRE.git
cd APS---8-SEMESTRE
docker compose up --build -d
```

Na primeira vez leva alguns minutos. Depois, abra http://localhost:8080: o site do projeto passa a rodar no seu computador, e `http://localhost:8080/v1/system` mostra o estado de cada processo.

Para ligar o aplicativo ao seu computador, abra **Ajustes → Outro servidor** e use:

- no emulador Android: `http://10.0.2.2:8080`;
- no celular: `http://IP_DO_COMPUTADOR:8080`, com o celular na mesma rede Wi-Fi. No Windows, permita a porta 8080 na rede privada se o firewall perguntar.

Para parar: `docker compose stop`. Para voltar: `docker compose start`. Os dados ficam guardados entre reinícios.

O guia [docs/execucao-local.md](docs/execucao-local.md) mostra como observar os processos e simular falhas.

## O que sobe no Docker

| Serviço | Papel |
| --- | --- |
| `nginx` | Porta de entrada (8080): mostra o site e reparte os celulares entre as duas APIs. |
| `api` e `api-2` | Duas réplicas iguais da API (HTTP e WebSocket). |
| `database` | PostgreSQL: relatos, leituras do painel e a outbox de eventos. |
| `worker` | Publica os eventos da outbox no RabbitMQ. |
| `broker` | RabbitMQ: entrega cada evento às duas réplicas. |
| `collector` | Lê o painel oficial a cada minuto. |

## Como funciona a distribuição

1. O **coletor** grava cada leitura no PostgreSQL. Na mesma transação, registra um evento na outbox quando algo visível mudou.
2. O app envia relatos pela API, que grava o relato e o evento na mesma transação.
3. O **worker** publica os eventos pendentes no RabbitMQ (exchange fanout).
4. Cada réplica da API recebe uma cópia e avisa seus celulares por WebSocket.
5. O app busca o estado atualizado. Ao reconectar, busca de novo.

Se a fonte oficial cair, a API continua servindo o último dado, marcado como atrasado. Se o RabbitMQ cair, os relatos continuam sendo salvos e os avisos saem quando ele voltar.

## Testes

Servidor (sobe um banco e um broker só para os testes):

```sh
docker compose -f compose.test.yaml up --build --abort-on-container-exit --exit-code-from tests
docker compose -f compose.test.yaml down --volumes
```

Aplicativo: requer JDK 17 e SDK Android 35. No Windows, `.\scripts\build-apk.ps1` roda os testes, compila e grava `ViaAgora.apk` na raiz. No Linux/macOS, dentro de `android/`: `sh gradlew testDebugUnitTest assembleDebug`.

## Organização

- `android/`: aplicativo Kotlin.
- `server/`: API, coletor, worker, testes e migração SQL.
- `site/`: site do projeto (HTML, CSS e JavaScript).
- `compose.yaml` e `nginx.conf`: ambiente local.
- `compose.test.yaml`: ambiente dos testes automatizados.
- `docs/`: proposta, arquitetura, guias e o trabalho escrito da APS.
- `Doc/`: enunciado da APS.

## Atribuições

Dados das travessias: painel público das Travessias Litorâneas — SEMIL-SP. Mapa: MapLibre com dados do OpenStreetMap. Os relatos são colaborativos e não representam confirmação oficial.
