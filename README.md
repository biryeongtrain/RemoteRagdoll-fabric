# RemoteRagdoll

Minecraft 26.3 (Paper 계열) 서버용 **Jolt 물리 래그돌**. 같은 물리 엔진([jolt-jni](https://github.com/stephengold/jolt-jni) 1.0.0)
위에서 모델을 그리는 방식이 다른 두 구현이 들어 있다.

> **Fabric 판**: bettermodel 방식을 Fabric 서버 모드로 옮긴 것이 [`fabric/`](fabric) 에 있다 (macOS·Windows·Linux, x64·ARM64).

| | [`modelengine/`](modelengine) | [`bettermodel/`](bettermodel) |
|---|---|---|
| 그리는 것 | ModelEngine 모델의 본 → PacketEvents 아이템 디스플레이 | BetterModel 이 플레이어 스킨을 부위별로 자른 조각 → BetterModel 패킷 |
| 몸 구조 | 모델 본마다 강체, 절단·허리 분리·코스튬 | 머리·몸통·양팔·양다리 **6조각 + 관절 5개**(SwingTwist) |
| 쓰는 법 | Skript(skript-reflect) 함수 `Physics_*` / `Ragdoll_*` ([`scripts/Physics.sk`](modelengine/scripts/Physics.sk)) | Java API(`Corpses`, `Shoves`, `PhysicsWorld`) + 바로 쓰는 데모 플러그인 |
| 필요한 플러그인 | GCBAPI, ModelEngine R4, packetevents | BetterModel 3.5.1 (소프트 의존, 없으면 꺼짐) |
| 충돌 지형 | 물체 주변 블록을 정적 충돌체로 | 시체 주변에서 공기와 닿은 블록만 정적 박스로 |
| 출처 | GCB legal14/legal15 서버 (2026-09~10) | TerritoryWar → Gazadry → Graywater (2026-11-16 판) |

## bettermodel 방식의 내력

1. **TerritoryWar** (2026-09): 죽은 유닛의 BetterModel 스켈레톤에서 **본 하나에 강체 하나**. 본 좌표는
   `bone.getGroup().getPosition()`(설계 스켈레톤)에서 읽고, 물리 결과는 절대값이 아니라 변위로 되먹인다.
2. **Gazadry** (2026-10): **팔 하나에 강체 하나**로 바꿈(6조각·관절 5개). 스킨 조각을 패킷으로만 그림,
   던전 블록 그대로 충돌, 걸어서 밀치기·휘둘러 걷어차기, 마지막 충격에 따라 관절이 끊김, 물에 가라앉기.
3. **Graywater** (2026-11-16, 이 저장소의 원본): Gazadry 에서 그 게임 전용 부분(성별 체형, 갑옷 세트, 다시 일어서기)을
   걷어낸 판. 이 저장소에서는 BetterModel 포크 전용 API 호출 두 줄을 빼서 **원본 BetterModel 3.5.1** 로 빌드된다.

## 공통 요구 사항

- Java 25, Minecraft 26.3 (Paper / Purpur / DivineMC)
- 서버 JVM 옵션 `--enable-native-access=ALL-UNNAMED` (Jolt 네이티브 라이브러리)
- Windows x64 또는 Linux x64 (두 네이티브가 jar 에 들어 있고 첫 실행 때 `plugins/<플러그인>/natives/` 로 풀린다)

빌드는 각 폴더에서 `./gradlew build` (결과: `build/libs/*.jar`). 자세한 내용은 각 폴더의 README.

## 함께 쓰는 것

- [Jolt Physics](https://github.com/jrouwe/JoltPhysics) / [jolt-jni](https://github.com/stephengold/jolt-jni) — MIT
- [BetterModel](https://github.com/toxicity188/BetterModel) — MIT (bettermodel 방식)
- ModelEngine, packetevents, GCBAPI — modelengine 방식 (jar 는 이 저장소에 없음)
