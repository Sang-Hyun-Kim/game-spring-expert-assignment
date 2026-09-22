# syntax=docker/dockerfile:1

# 빌드 단계
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# 변경이 드문 파일을 먼저 COPY 해야 레이어 캐시가 산다
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY src src

# 캐시 마운트로 Gradle 배포판·의존성 재다운로드를 막는다
# chmod: Windows 체크아웃에는 실행 비트가 없다
# *-SNAPSHOT.jar: -plain.jar(실행 불가)를 제외하는 패턴
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew --no-daemon bootJar && \
    cp build/libs/*-SNAPSHOT.jar app.jar

# 실행 단계
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl: compose healthcheck 용
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/*

# 기본값 UTC 로 두면 @CreationTimestamp 가 9시간 밀린다
ENV TZ=Asia/Seoul
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=builder --chown=appuser:appuser /workspace/app.jar /app/app.jar
USER appuser

EXPOSE 8080

# 배열 형태라야 java 가 PID 1 이 되어 SIGTERM 을 받는다
# MaxRAMPercentage 는 compose 의 mem_limit 과 한 쌍이다
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=75.0", \
            "-Duser.timezone=Asia/Seoul", \
            "-jar", "/app/app.jar"]
