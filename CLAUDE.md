# Lane Dash

세로 화면 아웃런 스타일 레이싱 게임. 차 뒤에서 바라보는 5차선 도로를 좌우 버튼으로 한 차선씩 옮겨 가며 차량·장애물을 피한다.
Android 앱(Kotlin, Jetpack Compose)과 웹 버전(HTML5/JS)이 같은 엔진 값을 쓴다.

- 패키지 `com.khcompany.lanedash`, Google Play 출시 중. 버전은 `app/build.gradle.kts`(현재 3.0.5, versionCode 21)
- GitHub 공개 저장소 `kwanghyeon-cpu/LaneDash`, 웹은 master의 `web/`가 GitHub Pages로 자동 배포된다:
  https://kwanghyeon-cpu.github.io/LaneDash/web/
- 3.0부터 클래식(가로) 모드는 없앴다. 하이웨이 한 가지 모드뿐이다.
- 이 앱을 복제한 별도 게임 "요리조리 런"(`C:\Users\innocean\AndroidStudioProjects\YorijoriRun`)이 있다. **이 저장소에서는 그 게임을 건드리지 않는다.**

## 구조

- 엔진(순수 로직, 화면과 무관): `app/.../game/highway/HighwayEngine.kt`·`HighwayRoad.kt` ↔ `web/js/highway-engine.js`
  **숫자나 규칙을 바꾸면 앱과 웹을 같이 바꾸고, 양쪽 테스트를 같이 맞춘다.**
- 렌더링: `ui/screens/HighwayScreen.kt`·`HighwayFx.kt` ↔ `web/js/highway-render.js`, 공용 그림 함수는 `ui/components/GameArt.kt` ↔ `web/js/art.js`
- 화면 흐름: 타이틀 → 도시 선택 → 자동차 선택 → 게임 → 순위표 (`LaneDashApp.kt` ↔ `web/js/main.js`)
- 도로 투영: 거리 → 화면 높이는 `tForDz`/`dzForT`(완만한 원근), 도로 폭은 `roadHalfWidth`(수평선에서도 폭이 남음).
  화면 맨 아래 도로는 항상 중앙에 고정되고, 차가 그 위를 좌우로 움직인다.
- 난이도: 초반 램프(`RAMP_SECONDS`, `DENSITY_RAMP_SECONDS`) + 후반 `late`(`LATE_START_SECONDS`부터 속도·밀도·정지 장애물·게이트·연료 소모 증가).
  장애물은 공정성 규칙(`requiredGapSeconds`)과 레인 분산(`obstacleLaneOrder`, 같은 레인 추가 간격)을 지켜 생성된다.
- 아이템: 연료통, 방어막(6초), 자석(8초). 근접 회피 보너스. 게임오버 전면광고(10판마다), 보상형 "광고 보고 계속하기"(판당 1회).

## 계정·외부 연결 (값 바꾸지 말 것)

- Firebase 프로젝트 `lanedash-2cc5d`. 순위표 컬렉션 `scores_highway`(옛 클래식 `scores` 규칙도 구버전 앱 때문에 남겨 둠). 규칙은 `firestore.rules`.
- `app/google-services.json`, `keystore.properties`, `keystore/`는 로컬 전용(gitignore). 저장소가 공개라 절대 커밋하지 않는다.
- AdMob: `AndroidManifest.xml`의 앱 ID와 `ads/AdManager.kt`의 실제 광고 단위 ID. 디버그 빌드는 Google 샘플 ID를 쓴다.
- 개인정보처리방침: `privacy-policy.md`(공개 주소 https://kwanghyeon-cpu.github.io/LaneDash/privacy-policy, 스토어에 등록된 주소).
  **수집하는 정보나 제3자 서비스(SDK·Firebase 사용 범위 등)가 바뀌면 이 문서와 시행일자를 함께 고친다.**

## 검증

- 앱 테스트: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDebugUnitTest`
- 웹 테스트: `node --test web/test/highway.test.mjs` (폴더가 아니라 파일을 지정해야 한다)
- 난이도 측정: `RUNS=30 CAP=600 REACTIONS=0.2,0.35,0.5 node tools/difficulty-planner.mjs` — 앞을 내다보는 봇의 생존 시간(값이 클수록 약한 플레이어)
- 웹 화면 확인: `web/`를 로컬 정적 서버로 띄워 브라우저에서 본다. 안드로이드 기기나 에뮬레이터가 없어서 앱 화면은 직접 확인하지 못한다는 점을 결과 보고에 밝힌다.

## 배포 순서

1. 작업은 `feature/...` 또는 `fix/...` 브랜치에서 하고 푸시한다.
2. 사용자가 "마스터에 합치고 웹 배포해줘"라고 하면 master에 fast-forward로 합치고 푸시한다. Pages 빌드가 끝났는지 `gh api repos/kwanghyeon-cpu/LaneDash/pages/builds/latest`로 확인한다.
   웹 파일을 바꾸면 `web/index.html`의 `?v=` 캐시 번호를 올린다(이미지는 `main.js`의 `ASSET_VERSION`).
3. 앱 번들: `app/build.gradle.kts`의 versionCode·versionName을 올리고 `./gradlew.bat :app:bundleRelease`, 결과를 `D:\lanedash-<버전>-<코드>.aab`로 복사한다.
4. 출시 노트는 `store-listing.md`에 한국어·영어로 추가한다(언어별 500자 이내, 글자 수 표기). 올리지 않은 버전은 다음 버전 노트에 합친다.

## 도구와 자산

- 스토어 스크린샷(1080×1920): `node tools/store-screenshots/serve.mjs screenshots-3.1` 후 `http://127.0.0.1:8145/shots.html?save=1`
- 도시 배경 가공: `node design/city-backdrops/make-backdrops.cjs` (제미나이 원본 → 지면 띠 정리·좌우 이음·용량 축소), 프롬프트 `design/city-backdrops/gemini-prompts.md`
- 아이콘: Phosphor 글리프를 `tools/gen-icons.mjs`로 앱·웹에 생성. 새 아이콘은 Iconify에서 가져오고 라이선스를 확인해 `NOTICE.md`에 적는다.
- 블로그 개발기(`blog/`)와 카드뉴스(`store-assets/instagram*`)는 로컬에만 두고 커밋하지 않는다.
