# 도시 배경 이미지 — 제미나이 프롬프트 (서울·도쿄·뉴욕·파리)

게임 화면 위쪽에 깔리는 도시 스카이라인 배경 4장을 새로 만들기 위한 프롬프트입니다.
프롬프트 본문은 영어로 두었고(이미지 생성 품질이 더 안정적), 설명은 한국어입니다.

## 0. 이 배경이 게임에서 쓰이는 방식 (프롬프트에 반영한 제약)

| 사실 | 프롬프트에 어떻게 반영했나 |
|---|---|
| 배경은 화면 위쪽 약 25%(세로 화면 기준 1080×480 정도) 띠로 그려지고, **그림의 높이가 그 띠에 맞춰집니다.** | 건물·랜드마크는 이미지 **아래쪽 55%** 안에 모으고, 위쪽은 하늘만 둡니다. 위아래를 조금 잘라도 안전합니다. |
| 가로로 **끝없이 이어 붙입니다**(타일링). 도로가 휘면 좌우로 살짝(최대 화면 폭의 약 7%) 움직입니다. | 왼쪽 끝과 오른쪽 끝이 이어지는 **끊김 없는 파노라마**를 요구합니다. |
| 기존 이미지는 3584×1184(약 3:1). 화면에는 그림의 **왼쪽 약 74%**가 보이고 오른쪽 26%는 배경이 움직일 때만 나옵니다. | 대표 랜드마크는 왼쪽 15~35% 지점, 보조 랜드마크는 55~70% 지점에 둡니다. |
| **새 도로는 수평선에서도 폭이 있습니다**(화면 폭의 약 1/3, 가운데). 도로 끝이 배경 아래쪽 가운데에 맞닿습니다. | 가운데(25~50% 지점)는 스카이라인을 **낮고 단순하게** 비워 도로가 도시 안으로 들어가는 느낌을 줍니다. |
| 도로 끝에는 도시별 안개색(`haze`)이 겹쳐집니다. 수평선 바로 아래는 잔디색입니다. | 이미지 맨 아래 띠를 **잔디색과 같은 평평한 색**으로 요구해 경계가 보이지 않게 합니다. |
| 차량 이미지는 밝고 또렷한 픽셀아트입니다. | 배경은 **채도·대비를 낮추고 디테일을 줄여** 차량과 장애물이 먼저 보이게 합니다. 사람·차·글자는 넣지 않습니다. |

도시별 기준 색(코드의 `CityTheme.haze`와 맞춰 둔 값):

| 도시 | 분위기 | 수평선(안개) 색 | 맨 아래 띠(잔디) 색 |
|---|---|---|---|
| 서울 | 노을 | `#D58E86` | `#4F8043` |
| 도쿄 | 맑은 오후 | `#9EC4E6` | `#448B56` |
| 뉴욕 | 맑은 낮 | `#8EBDE8` | `#418A56` |
| 파리 | 밤 | `#1B2641` | `#2A6C35` |

분위기를 바꾸고 싶으시면(예: 도쿄를 네온 야경으로) 프롬프트의 하늘 줄만 바꾸시고, 알려 주시면 코드의 안개색 값은 제가 맞추겠습니다.

## 1. 사용 방법

1. 제미나이에서 **이미지 생성**을 열고, 아래 **공통 스타일 블록**과 **도시별 블록**을 이어 붙여 한 번에 입력합니다.
2. 스타일을 맞추려면 참고 이미지를 같이 첨부하세요: `store-assets/screenshots-3.0/01_gameplay.png` (게임의 도로·차량 픽셀아트 톤).
3. 비율은 **16:9(가능한 가장 넓은 것)**, 해상도는 가장 높게 선택합니다. 제미나이는 3:1을 직접 못 만드는 경우가 많아서, 받은 이미지를 제가 다음 순서로 가공합니다.
   - 아래·위를 잘라 약 3:1로 맞춤(하늘 위쪽만 잘리도록 건물은 아래 55%에 두게 했습니다)
   - 좌우 이음매를 겹쳐 섞어서 끊김 없이 이어지게 함
   - 맨 아래 띠 색을 잔디색에 정확히 맞춤
   - 크기를 3584×1184로 맞추고 용량을 줄여 앱(`app/src/main/res/drawable-nodpi/city_*.png`)과 웹(`web/assets/cities/city_*.png`)에 넣음
4. 마음에 드는 결과가 나오면 파일을 알려 주세요. 가공과 적용은 제가 하겠습니다.

## 2. 공통 스타일 블록 (네 도시 모두 맨 앞에 붙입니다)

```text
Create a wide horizontal panorama, aspect ratio 16:9 (the widest available), highest resolution.

STYLE: 16-bit retro arcade racing-game background, clean modern pixel art (OutRun / Rad Racer era feel, but tidy and readable). A straight-on, side-view skyline seen from far away at ground level, exactly at the horizon line. Flat, low-detail shapes with crisp 1-pixel edges, no anti-aliasing blur, no painterly texture, no gradients except a banded pixel-art sky gradient (dithering allowed only in the sky). Limited palette of about 24 colors. Muted, slightly desaturated buildings with a soft atmospheric haze toward the horizon (farther layers lighter and closer to the horizon color), so bright foreground game sprites will stand out against it. Light comes from the sky color; consistent shading.

COMPOSITION (very important):
- Seamless horizontal tiling: the far-left edge must continue naturally into the far-right edge (same skyline height, same sky colors, no landmark cut in half at the borders).
- Put ALL buildings and landmarks in the LOWER 55% of the image. The UPPER 45% is open sky only: a smooth banded gradient with a few simple flat pixel clouds (and stars only if the scene is night).
- Around 25%-50% of the width keep the skyline LOW and SIMPLE (low buildings, trees, an open avenue gap) — a road will visually run into the city here.
- Hero landmark at about 15%-30% of the width; one or two secondary landmarks at about 55%-70%; the rest is a rhythm of small and medium buildings. Do not repeat the same landmark twice.
- The BOTTOM 8% of the image is a plain, uninterrupted, perfectly flat horizontal strip of a single solid ground color (given below). Nothing crosses it.
- Nothing in the foreground: no road, no cars, no people, no animals, no lamp posts, no fences in front of the skyline.

DO NOT INCLUDE: any readable text, logos, signage letters, watermarks, UI, borders, frames, people, vehicles, birds close to the camera, lens flare, 3D render look, photo look, heavy outlines.
```

## 3. 도시별 블록 (공통 블록 뒤에 하나만 이어 붙입니다)

### 서울 — 노을

```text
CITY: Seoul, South Korea, at sunset.
SKY: warm dusk gradient from deep purple at the top, through magenta and coral, to a soft salmon-orange at the horizon (horizon color about #D58E86). A few flat peach-pink clouds lit from below.
GROUND STRIP (bottom 8%): flat muted green #4F8043.
HERO (15%-30% width): Namsan hill with N Seoul Tower, drawn as a small dark purple-green silhouette hill with the slim tower and its round observation deck on top.
SECONDARY (55%-70% width): Lotte World Tower — a very tall, slender, gently tapering glass tower — beside a few mid-rise office blocks; far right edge: the tiered wooden roof of a traditional gate (Sungnyemun/Namdaemun style) with upswept eaves.
FILL: rows of Korean apartment blocks (simple tall slab blocks with small window grids), low hanok houses with dark gray tiled curved roofs in the low middle section (25%-50% width), a few round-canopy trees. Windows are tiny warm-yellow dots, only a few lit. Colors: dusty purple, plum and warm gray buildings.
```

### 도쿄 — 맑은 오후

```text
CITY: Tokyo, Japan, clear late afternoon.
SKY: calm light-blue gradient, deeper blue at the top fading to pale blue-white at the horizon (horizon color about #9EC4E6). A few flat white clouds.
GROUND STRIP (bottom 8%): flat fresh green #448B56.
HERO (15%-30% width): Tokyo Tower — red-orange and white lattice tower with two observation decks.
SECONDARY (55%-70% width): Tokyo Skytree (tall slim gray-silver tower) at the far right-center; a cluster of modern glass skyscrapers with a distinctive tall pointed-top tower and a curved cocoon-shaped tower; snow-capped Mount Fuji far away as a pale blue-lavender triangle low between buildings (very faint, behind everything).
FILL: dense medium-height Japanese city blocks, flat rooftops with small water tanks, a small pagoda-style roof and a few sakura trees with pale pink blossoms in the low middle section (25%-50% width). Colors: cool grays, blue-whites, soft teal glass, small red accents.
```

### 뉴욕 — 맑은 낮

```text
CITY: New York City, USA, clear sunny day.
SKY: bright blue gradient, deeper at the top to a light cyan-blue at the horizon (horizon color about #8EBDE8). A few flat white clouds.
GROUND STRIP (bottom 8%): flat green #418A56.
HERO (15%-30% width): the Empire State Building (stepped Art Deco tower with a tall spire) with the Chrysler Building (silver sunburst crown) just behind it at slightly smaller size.
SECONDARY (55%-70% width): One World Trade Center (tall tapered glass tower with spire) and a cluster of glass skyscrapers; at the far right edge the stone towers and cables of the Brooklyn Bridge, low and simple.
FILL: brownstone row houses and mid-rise brick buildings with rooftop water towers in the low middle section (25%-50% width), a few round trees. Colors: warm tan, brick red-brown, steel blue glass, cream stone — slightly muted.
```

### 파리 — 밤

```text
CITY: Paris, France, at night.
SKY: deep night gradient from near-black navy at the top to dark blue-teal at the horizon (horizon color about #1B2641), a sprinkling of small single-pixel stars in the upper half, a thin crescent moon, very few flat dark clouds.
GROUND STRIP (bottom 8%): flat deep green #2A6C35.
HERO (15%-30% width): the Eiffel Tower, lit warm gold, lattice detail kept simple, with a faint soft glow.
SECONDARY (55%-70% width): the Arc de Triomphe (cream stone, lit from below) and the twin towers of Notre-Dame cathedral at the far right edge, low and simple.
FILL: Haussmann-style cream stone apartment buildings with gray zinc mansard roofs and chimney pots; many small warm-yellow lit windows; a low row of round street trees and a pale lit glow of a street in the low middle section (25%-50% width). Colors: navy, slate blue, cream, warm yellow lights — muted, not neon.
```

## 4. 결과 점검표 (받은 이미지를 볼 때)

- [ ] 맨 아래 8%가 평평한 한 가지 색이고, 그 위로 아무것도 걸치지 않았다
- [ ] 건물과 랜드마크가 **아래 55%** 안에 있고, 위쪽은 하늘뿐이다
- [ ] 글자·간판 글씨·사람·자동차가 없다
- [ ] 가운데(25~50%)가 낮고 단순하다 (도로가 들어가는 자리)
- [ ] 같은 랜드마크가 두 번 나오지 않는다
- [ ] 채도와 대비가 과하지 않다 (밝은 차량이 배경보다 먼저 보인다)
- [ ] 좌우 가장자리의 하늘색·건물 높이가 비슷하다 (이음매 가공이 쉬워진다)

한 번에 마음에 드는 결과가 안 나오면, 점검표에서 어긋난 항목 한 줄만 프롬프트 끝에 덧붙여 다시 생성하는 편이 효과적입니다.
예) `The ground strip at the bottom must be one single flat color with no objects on it.`
