# Execução local

## Requisitos

- Git e Docker Desktop (ou Docker com Compose).
- Celular Android 8.0+ ou emulador, para usar o aplicativo.

## Subir o sistema

```sh
git clone https://github.com/Bruno70i/APS---8-SEMESTRE.git
cd APS---8-SEMESTRE
docker compose up --build -d
docker compose ps
```

O banco e o RabbitMQ sobem primeiro; as APIs, o worker, o coletor e o Nginx sobem em seguida. As tabelas são criadas automaticamente. Não é preciso configurar nada: as senhas de desenvolvimento já vêm no `compose.yaml` (para trocá-las, copie `.env.example` para `.env`).

| O que | Endereço |
| --- | --- |
| Site do projeto | http://localhost:8080 |
| Estado de cada processo | http://localhost:8080/v1/system |
| Saúde da API | http://localhost:8080/health |
| Painel do RabbitMQ | http://localhost:15689 (usuário e senha `viaagora` / `viaagora_local_only`) |
| PostgreSQL | `127.0.0.1:15439` |

## Ligar o aplicativo

No app, abra **Ajustes → Outro servidor**:

- Emulador Android: `http://10.0.2.2:8080`.
- Celular: `http://IP_DO_COMPUTADOR:8080`, na mesma rede Wi-Fi. `localhost` no celular é o próprio celular, por isso use o IP do computador. No Windows, permita a porta 8080 na rede privada.

O Nginx alterna os celulares entre a API 1 e a API 2. A aba **Bastidores** mostra a qual réplica o seu celular está ligado. Ao terminar, volte ao **Servidor oficial**.

## Observar e provocar falhas

Ver o que cada processo está fazendo:

```sh
docker compose logs -f collector worker api api-2
```

**Queda de uma réplica:** `docker compose stop api`. O Nginx passa a mandar tudo para a API 2 e os celulares reconectam sozinhos. Volte com `docker compose start api`.

**Queda do broker:**

1. Execute `docker compose stop broker`.
2. Envie um relato pelo aplicativo: ele é salvo e o evento fica esperando na outbox (Bastidores → "esperando na outbox").
3. Execute `docker compose start broker` e veja o worker publicar o evento guardado.

## Parar

```sh
docker compose stop
```

`docker compose start` volta de onde parou. Os dados ficam guardados nos volumes do Docker. Para apagar tudo e começar do zero: `docker compose down --volumes`.

## Testes automatizados

```sh
docker compose -f compose.test.yaml up --build --abort-on-container-exit --exit-code-from tests
docker compose -f compose.test.yaml down --volumes
```

Os testes usam um banco e um broker próprios, sem mexer nos dados do ambiente local.

## Compilar o aplicativo

Requer JDK 17 e SDK Android 35. No Windows (PowerShell), com `JAVA_HOME` e `ANDROID_HOME` configurados:

```powershell
.\scripts\build-apk.ps1
```

O script roda os testes, compila e grava `ViaAgora.apk` na raiz. No Linux/macOS, dentro de `android/`:

```sh
sh gradlew testDebugUnitTest assembleDebug
```
