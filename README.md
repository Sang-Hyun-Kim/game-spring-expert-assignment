# webcraft

> 샌드박스 멀티플레이 게임의 백엔드 서버. Spring Boot 숙련 과제를 위해 제작되었습니다.

같은 월드에 여러 명이 접속해 돌아다니고 대화하는 게임입니다.
프론트엔드(완성본)는 엔진 jar 안에 들어 있고, **서버를 직접 구현**하는 것이 과제의 내용이었습니다.
서버를 띄우면 `http://localhost:8080` 에서 실제로 플레이할 수 있습니다.
또한 현재 DockerFile 및 docker-compose를 통해 앱 서버를 docker container화하여 접속할 수 있습니다. 

- 플레이어 등록 · 월드 생성(최대 3개) · 최근 채팅 조회 (MySQL + JPA)
- WebSocket 으로 이동 · 채팅 · 접속자 목록 · Ping/Pong 기반 Heartbeat 시스템을 통한 월드별 세션 관리
- Redis 로 접속 상태(TTL) · 최근 채팅 캐시 · 채팅 횟수 제한(Lua) · 서버 간 채팅 중계(Pub/Sub)
- 앱 서버 2대를 띄워도 같은 월드의 채팅이 서로에게 전달됩니다

## 실행

필요한 것: **JDK 21**, **Docker**

### 1. 단일 서버 (개발용)

```bash
docker compose -f docker/docker-compose.yml up -d mysql redis   # MySQL + Redis
./gradlew bootRun                                               # 서버
```

`http://localhost:8080` 접속.

### 2. 멀티 서버 2대 (Lv 20 확인용)

```bash
docker compose -f docker/docker-compose.yml up -d --build app1 app2
docker compose -f docker/docker-compose.yml ps                  # 전부 healthy 까지 대기
```

일반 창에서 `http://localhost:18080`, 시크릿 창에서 `http://localhost:18081` 로 **서로 다른 서버**에 접속한 뒤
같은 월드에 들어가면 채팅이 양쪽에 한 번씩 표시됩니다.

| 항목 | 값 |
| --- | --- |
| 런타임 | Spring Boot 4.1.0 · Java 21 · Gradle 9.5.1 |
| 엔진 | `io.github.f-api:webcraft-engine:2.2.6` (게임 프론트 포함) |
| DB | MySQL 8.0 (Docker) — 호스트 포트 **3309** → 컨테이너 3306 |
| 캐시·상태 | Redis 7.0 (Docker) — 호스트 포트 **6380** → 컨테이너 6379 |
| 앱 포트 | 로컬 실행 **8080** · 컨테이너 2대 **18080 / 18081** |
| 스키마 | `webcraft` (`ddl-auto=update`) |
| 접속 정보 | `docker/.env` |

> 3306·3307·6379 는 이 PC의 다른 프로젝트가 쓰고 있어 3309·6380 을 씁니다.
> 따라서 다른 환경에서 실행할 때는 `src/main/resources/application.properties` 의 포트를 환경에 맞게 바꾸면 됩니다.
> 컨테이너로 띄울 때는 `docker/docker-compose.yml` 의 환경변수가 이 파일을 사용합니다.

---
### 제약 · 인덱스

| 테이블 | 종류 | 이름 / 컬럼 | 왜                                                                     |
| --- | --- | --- |------------------------------------------------------------------------|
| `players` | UNIQUE | `nickname` | 닉네임 중복 등록 방지 → `409 DUPLICATE_NICKNAME`                       |
| `chat_messages` | INDEX | `idx_chat_world_created_at (world_id, created_at)` | 최근 채팅 조회·커서 페이징이 `world_id` 로 걸러 `created_at` 으로 정렬 |
| `world_trial_sites` | UNIQUE | `uk_world_trial_id (world_id, trial_id)` | 같은 월드에 같은 시련이 두 번 저장되지 않게                            |
| `world_trial_sites` | UNIQUE | `uk_world_trial_position (world_id, block_x, block_y, block_z)` | 같은 좌표에 두 개가 서지 않게                                          |
| `world_trial_sites` | INDEX | `idx_world_trial_pending_reward (world_id, reward_pending)` | 보상 미지급분만 골라 읽는 조회용                                       |
| `world_trial_sites` | `@Version` | `revision` | 동시 저장 시 갱신 손실 방지(Lv 16)                                     |

---

## API 명세

### 엔드포인트 요약

| 메서드 | 경로 | 성공 | 설명                                                            |
| :--- | :--- | :---: |:----------------------------------------------------------------|
| `POST` | `/players` | `201` | 플레이어 등록                                                   |
| `GET` | `/worlds` | `200` | 월드 목록 — `onlineCount` 는 Redis presence 기준                |
| `POST` | `/worlds` | `201` | 월드 생성 (전체 최대 3개)                                       |
| `GET` | `/worlds/{worldId}/chats` | `200` | 최근 채팅 조회 (오래된 순)                                      |
| `GET` | `/worlds/{worldId}/chats/history` | `200` | 과거 채팅 커서 페이징 (최신 순)                                 |
| `POST` | `/practice/worlds/{worldId}/chats/rollback` | `204` | 캐시 무효화 격리 검증용 |
| `WS` | `/ws/worlds/{worldId}?nickname=` | `101` | WebSocket 연결                                                  |

### 공통 오류 응답

```json
{ "error": "WORLD_NOT_FOUND" }
```

| error | HTTP | 발생 조건                                                     |
| :--- | :---: |:--------------------------------------------------------------|
| `VALIDATION_FAILED` | 400 | 필수 값 누락, 길이·패턴 위반, 숫자가 아닌 파라미터            |
| `INVALID_REQUEST_BODY` | 400 | 잘못된 JSON, DTO에 없는 알 수 없는 필드, 지원하지 않는 난이도 |
| `PLAYER_NOT_FOUND` | 404 | 등록되지 않은 닉네임으로 월드 생성                            |
| `WORLD_NOT_FOUND` | 404 | 존재하지 않는 월드 ID                                         |
| `DUPLICATE_NICKNAME` | 409 | 이미 등록된 닉네임                                            |
| `WORLD_LIMIT_REACHED` | 409 | 월드가 이미 3개                                               |
| `INTERNAL_ERROR` | 500 | 예기치 못한 서버 오류                                         |
| `WORLD_BASELINE_INITIALIZING` | 503 | 기동 직후 월드 준비 전                                        |

시각 필드는 서버 로컬 시간대 기준 ISO 8601, 시간대 표기 없음입니다 (`2026-07-16T12:34:56`).

### `POST /players` — 플레이어 등록

```json
// 요청
{ "nickname": "steve" }     // 2~12자, ^[a-zA-Z0-9_]+$
// 201 Created (본문 없음)
```
오류: `400 VALIDATION_FAILED` · `409 DUPLICATE_NICKNAME`

### `GET /worlds` — 월드 목록

```json
// 200 OK
[
  { "id": 1, "name": "내 첫 월드", "seed": -1734829183, "onlineCount": 3, "difficulty": "normal" }
]
```
`onlineCount` 는 DB가 아니라 Redis presence 에서 읽은 현재 접속 수입니다.

### `POST /worlds` — 월드 생성

```json
// 요청 — seed 는 요청받지 않고 서버가 정한다
{ "name": "내 첫 월드", "difficulty": "normal", "nickname": "steve" }
// 201 Created
{ "id": 4, "name": "내 첫 월드", "seed": -1734829183, "difficulty": "normal", "ownerNickname": "steve" }
```
- `name` 필수 1~30자(공백만 불가) · `difficulty` 선택(기본 `normal`) · `nickname` 선택(생략 시 `ownerNickname: null`)
- 오류: `400 VALIDATION_FAILED` / `INVALID_REQUEST_BODY` · `404 PLAYER_NOT_FOUND` · **`409 WORLD_LIMIT_REACHED`**
- 동시에 여러 생성 요청이 와도 상한 3개를 넘지 않습니다.

### `GET /worlds/{worldId}/chats` — 최근 채팅

`limit` 기본 50. 1 미만·100 초과는 오류가 아니라 1~100 으로 보정하고, 최신 `limit` 건을 고른 뒤 오래된 순서로 돌려줍니다.

```json
// 200 OK
[
  { "sender": "steve", "content": "안녕하세요", "createdAt": "2026-07-16T12:30:00" },
  { "sender": "alex",  "content": "반가워요",   "createdAt": "2026-07-16T12:30:05" }
]
```

### `GET /worlds/{worldId}/chats/history` — 커서 페이징

`createdAt DESC, id DESC` 정렬. 첫 요청은 커서를 생략하고, 다음 요청에 응답의 `nextCreatedAt`·`nextId` 를
`beforeCreatedAt`·`beforeId` 로 함께 넘깁니다. `limit` 기본 20 (1~100).

```json
// 200 OK
{
  "items": [
    { "id": 105, "sender": "steve", "content": "이전 대화", "createdAt": "2026-07-16T12:29:50" }
  ],
  "hasNext": true,
  "nextCreatedAt": "2026-07-16T12:29:50",
  "nextId": 105
}
```
커서 파라미터는 쌍으로 보내야 합니다. 하나만 보내면 `400 VALIDATION_FAILED` 오류를 반환합니다.

---

## WebSocket 프로토콜

### 핸드셰이크

```
ws://localhost:8080/ws/worlds/{worldId}?nickname={nickname}
```

성공하면 `101 Switching Protocols`. 닉네임은 핸드셰이크에서 한 번 검사해 세션 속성에 담고,
이후 그 연결의 모든 메시지는 이미 식별된 상태로 처리합니다.

| 종료 코드 | 의미 |
| :---: | --- |
| `4000` | 닉네임 누락 또는 등록되지 않은 닉네임 |
| `4001` | 접속할 수 없거나 존재하지 않는 월드 |
| `4002` | 같은 월드에 동일 닉네임이 이미 접속 중 (기존 세션 유지) |
| `1000` | 정상 종료 |

허용되지 않은 Origin 은 `403`, 월드 준비 전이면 `503` 으로 핸드셰이크 자체가 거절됩니다.

### 클라이언트 → 서버

모든 메시지는 최상위 `type` 을 가진 JSON 텍스트입니다 (`data` 로 감싸지 않습니다).

| type | 본문 | 응답 |
| --- | --- | --- |
| `ping` | — | `pong` |
| `chat` | `content` 1~200자, 공백만 불가 | 같은 월드 **전원**에게 `chat` (송신자 포함) |
| `move` | `x` `y` `z` `yaw` `pitch` `crouching` `gliding` (전부 필수) | **별도 성공 응답 없음** — 엔진의 상태 전송으로 확인 |
| `onlineUsers` | — | 요청한 연결에만 `onlineUsers` |

```json
{ "type": "chat", "content": "여기 다이아 있어요!" }
```

### 서버 → 클라이언트

```json
{ "type": "pong" }

{ "type": "chat", "sender": "steve", "content": "여기 다이아 있어요!",
  "timestamp": "2026-07-16T12:30:00" }

{ "type": "onlineUsers", "users": ["Alice", "Bob"], "count": 2 }

{ "type": "error", "code": "CHAT_COOLDOWN" }
```

> 같은 시각 필드가 REST 응답에서는 `createdAt`, WebSocket 응답에서는 `timestamp` 입니다.

| WS error code | 의미 |
| --- | --- |
| `INVALID_JSON` | 텍스트를 JSON 으로 읽을 수 없음 |
| `INVALID_MESSAGE` | JSON 객체가 아니거나 필수 필드가 잘못됨 |
| `UNKNOWN_TYPE` | `type` 이 없거나 처리할 핸들러가 없음 |
| `INTERNAL_ERROR` | 처리 중 예기치 못한 오류 |
| `CHAT_COOLDOWN` | 10초당 5회 초과 (저장도 전송도 하지 않음) |

에러 응답만으로 연결을 끊지는 않습니다.

---

## 구조

```
com.gameexpert
├── player/     controller · service · repository · entity · dto
├── world/      controller · service · repository · entity · dto
├── chat/       controller · service · repository · entity · dto
│   ├── service/    ChatService · RecentChatCache · ChatRateLimitService · ChatDelivery · LocalChatSender
│   └── relay/      ChatRelay · ChatSubscriptionConfig        ← Redis Pub/Sub (Lv 20)
├── ws/         GameWebSocketHandler · MessageRouter · WorldSessionRegistry · WorldBroadcaster
│   ├── handler/    Ping · Chat · Move · OnlineUsers
│   └── NicknameHandshakeInterceptor                          ← 핸드셰이크에서 사용자 식별
├── presence/   PresenceService                               ← Redis ZSet (TTL)
├── trial/      entity · repository · service                 ← 낙관적 락 (Lv 16)
├── config/     WebSocketConfig · DomainStorageConfiguration
└── common/     전역 예외 처리 · 공통 오류 응답
```

Controller · Service · Repository 를 분리한 3 Layer 구조입니다.
WebSocket 쪽은 Controller 대신 `MessageRouter` 가 `type` 으로 핸들러를 고르고, 핸들러가 Service 를 부릅니다
— 바깥에서 들어온 요청을 해석해 Service 에 넘긴다는 역할은 Controller 와 같습니다.

### 상태 저장소

| 저장소       | 내용                                                   |
|--------------|--------------------------------------------------------|
| **MySQL**    | players · worlds · chat_messages · world_trial_sites   |
| **JVM 힙**   | 열려 있는 WebSocket 세션 (`WorldSessionRegistry`)      |
| **Redis**    | presence ZSet · 최근 채팅 캐시 · 채팅 횟수 · 채팅 채널 |
---

## 과제 진행 순서

뼈대 코드와 `TODO` 가 주어지고, 비활성화된 테스트를 하나씩 풀어 통과시키는 방식입니다.

| Lv | 주제 | 내용                                              |
| :---: | --- |---------------------------------------------------|
| 1 | 환경 구성 | Docker MySQL·Redis, `application.properties`      |
| 2 | SQL을 JPA 인덱스로 | 조회 패턴에 맞는 복합 인덱스를 `@Index` 로 표현   |
| 3 | 플레이어 등록 | Bean Validation, 중복 닉네임 409                  |
| 4 | 월드 생성 | 최대 3개 제약을 원자적으로 적용                   |
| 5 | 채팅 저장과 내역 조회 | 트랜잭션 경계, 엔티티 직접 반환 금지              |
| 6 | 최근 채팅 조회 | 정렬 규칙과 `limit` 보정                          |
| 7 | 핸드셰이크 | 사용자 식별과 종료 코드 4000·4001·4002            |
| 8 | 인터셉터 등록 | `WebSocketConfig` 에 인터셉터 연결                |
| 9 | 세션 레지스트리 | 월드별 인메모리 세션 관리 (CAS)                   |
| 10 | Redis presence | ZSet score 를 만료 시각으로 (TTL 90초)            |
| 11 | 라우팅 · 핑/퐁 | `type` 별 핸들러 위임, heartbeat 로 presence 연장 |
| 12 | 이동 처리 | 엔진 큐에 액션 전달                               |
| 13 | 채팅 요청 | 응답 DTO 조립 (`timestamp`)                       |
| 14 | 월드 브로드캐스트 | 같은 월드 전원에게 전달 (송신자 포함)             |
| 15 | 접속자 목록 | 열린 세션의 닉네임을 정렬해 요청자에게만          |
| 16 | 낙관적 락 `[도전]` | `@Version` 으로 갱신 손실 방지                    |
| 17 | 커서 페이징 `[도전]` | `limit+1` 을 커서로 쓰지 않기                     |
| 18 | 최근 채팅 캐시 `[도전]` | TTL 5초, `null`(미스)과 `[]`(0건) 구분            |
| 19 | 레이트 리밋 `[도전]` | Redis Lua Script로 조회·증가를 원자적으로         |
| 20 | 멀티 서버 `[도전]` | Redis Pub/Sub 으로 서버 간 채팅 중계              |

---

## 검증

```bash
./gradlew build --rerun-tasks
```

| 무엇 | 결과 |
| --- | --- |
| 전체 빌드·테스트 | **BUILD SUCCESSFUL · tests=51 · failures=0 · errors=0 · skipped=0** |
| 멀티 서버 교차 확인 | app1(18080) ↔ app2(18081), 같은 월드에서 **양방향 수신** |
| 구독자 수 | `redis-cli PUBSUB NUMSUB webcraft:chat` → **2** |
| 발행 횟수 | 채팅 2건에 `cmdstat_publish:calls=2` → **이중 발행 없음** |
| 저장 횟수 | `chat_messages` **+2행** → **수신 측 재저장 없음** |

과제에서 말하는 "구현"은 코드를 작성한 상태가 아니라 실행하고 테스트해 정상 작동을 검증한 상태를 뜻합니다.

---