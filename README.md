# live_trans

유튜브, 크롬, 삼성인터넷 등에서 재생 중인 미디어 오디오를 실시간으로 캡처해 화면 위에 번역 자막을 띄워주는 안드로이드 앱입니다. 서버 없이 완전 오프라인으로 동작하고, 별도 계정이나 과금 없이 무료로 씁니다.

## 동작 원리

1. **캡처** — `AudioPlaybackCaptureConfiguration`(Android 10+)으로 다른 앱이 재생 중인 오디오를 가로챕니다.
2. **STT** — [Vosk](https://alphacephei.com/vosk/)로 오디오를 오프라인 음성인식합니다. 언어 모델은 최초 사용 시 기기에 자동 다운로드됩니다.
3. **번역** — Google ML Kit Translation으로 인식된 텍스트를 한국어로 온디바이스 번역합니다 (소스 언어가 한국어면 번역 생략).
4. **표시** — 번역 결과를 화면 위 오버레이 자막바와 알림에 실시간으로 띄웁니다.

## 주요 기능

- 화면 위 오버레이 자막 (다른 앱을 보면서 그대로 자막 확인 가능)
- 일본어 / 영어 / 한국어 소스 지원, 한국어로 번역
- 드래그 가능한 플로팅 버튼 → 언어 전환 팝업 (화면 캡처 재동의 없이 즉시 전환)
- 알림/팝업에서 바로 캡처 중지
- 마지막으로 선택한 언어를 기억해 다음 실행 시 자동 적용

## 사용법

1. 앱 실행 후 소스 언어(일본어/영어/한국어) 선택
2. "캡처 시작" → 마이크/알림/다른 앱 위에 표시 권한 허용 → 화면 캡처 동의
3. 유튜브나 브라우저에서 영상 재생 → 하단에 번역 자막 표시
4. 좌측 상단 "LT" 버튼으로 언어 변경 또는 중지

## 필요 권한

| 권한 | 용도 |
|---|---|
| `RECORD_AUDIO` | 미디어 오디오 캡처 (마이크 아님) |
| `SYSTEM_ALERT_WINDOW` | 자막/컨트롤 오버레이 표시 |
| `POST_NOTIFICATIONS` | 캡처 상태 알림 |
| `INTERNET` | STT/번역 모델 최초 다운로드 |
| `FOREGROUND_SERVICE*` | 캡처를 포그라운드 서비스로 유지 |

## 기술 스택

- Kotlin, Android SDK 29+ (minSdk 29, targetSdk 35)
- [Vosk Android](https://github.com/alphacep/vosk-api) — 오프라인 STT
- [ML Kit Translate](https://developers.google.com/ml-kit/language/translation) — 온디바이스 번역
- `AudioPlaybackCaptureConfiguration` + `MediaProjection` — 미디어 오디오 캡처
- `WindowManager` 오버레이 — 자막바/플로팅 버튼/설정 팝업 (별도 UI 프레임워크 없음)

## 빌드

```bash
./gradlew assembleDebug
```

## 알려진 한계

- ML Kit 번역 품질이 Google Cloud Translation/DeepL 대비 아쉬움 (무료 유지 목적상 감수)
- Vosk는 언어별로 정확도 높은 "big" 모델이 있지만 폰 RAM 한계상 small 모델만 사용 (한국어는 애초에 small만 존재)
- 캡처 중인 앱을 종료하면 캡처도 함께 끊김
