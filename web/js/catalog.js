// Ported from the Android app's GameCatalog / Model.kt — keep values in sync by hand.

// The app frame is letterboxed to this aspect ratio (see resizeAppFrame in main.js) so
// canvasHeight/canvasWidth is a fixed constant — that's what lets render.js precompute
// obstacle hitboxes below instead of hand-tuning them per sprite.
const GAME_ASPECT = 16 / 9;

const CITIES = [
  {
    id: 'seoul', name: '서울', nameEn: 'SEOUL',
    skyTop: '#2E1A47', skyBottom: '#FF8C69',
    roadColor: '#241830', roadLineColor: '#FFD166',
    sprite: 'assets/cities/city_seoul.png',
  },
  {
    id: 'tokyo', name: '도쿄', nameEn: 'TOKYO',
    skyTop: '#05021A', skyBottom: '#1B0F3D',
    roadColor: '#0D0620', roadLineColor: '#FF2E92',
    sprite: 'assets/cities/city_tokyo.png',
  },
  {
    id: 'newyork', name: '뉴욕', nameEn: 'NEW YORK',
    skyTop: '#FF9A56', skyBottom: '#FFD56B',
    roadColor: '#3A2E28', roadLineColor: '#FFF3B0',
    sprite: 'assets/cities/city_newyork.png',
  },
  {
    id: 'paris', name: '파리', nameEn: 'PARIS',
    skyTop: '#BFE3FF', skyBottom: '#F6E7D8',
    roadColor: '#4A4038', roadLineColor: '#FFFFFF',
    sprite: 'assets/cities/city_paris.png',
  },
];

const CARS = [
  { id: 'suv', name: 'SUV', nameEn: 'SUV', sprite: 'assets/cars/car_gen1.png' },
  { id: 'sports_car', name: '스포츠카', nameEn: 'SPORTS CAR', sprite: 'assets/cars/car_gen2.png' },
  { id: 'sedan', name: '세단', nameEn: 'SEDAN', sprite: 'assets/cars/car_gen3.png' },
  { id: 'van', name: '승합차', nameEn: 'VAN', sprite: 'assets/cars/car_gen4.png' },
];

// hitWidthScale / heightScale mirror ObstacleVariant in Model.kt (post size-tuning) and
// control how big each obstacle is DRAWN. They don't directly control collision anymore:
// drawFittedSprite fits each sprite by width first, then shrinks to fit by height if it
// would overflow — so a tall, narrow sprite (a cone) can render much narrower on screen
// than hitWidthScale alone suggests. Collision uses hitHalfWidthUnits instead, which
// main.js computes from each sprite's actual rendered size once its image is loaded
// (see computeObstacleHitHalfWidthUnits in render.js). The values below are just a
// reasonable synchronous fallback in case a game starts before that async pass finishes.
const OBSTACLE_VARIANTS = {
  CONE: { kind: 'CONE', hitWidthScale: 0.3, heightScale: 0.45, sprite: 'assets/obstacles/obstacle_cone.png', hitHalfWidthUnits: 0.46 },
  BARRIER: { kind: 'BARRIER', hitWidthScale: 1.0, heightScale: 0.55, sprite: 'assets/obstacles/obstacle_barrier.png', hitHalfWidthUnits: 0.70 },
  // Vehicle obstacles (sedan/suv/bus/truck) scaled another 0.7x ahead of release.
  SEDAN: { kind: 'SEDAN', hitWidthScale: 0.63, heightScale: 0.595, sprite: 'assets/obstacles/obstacle_sedan.png', hitHalfWidthUnits: 1.253 },
  SUV: { kind: 'SUV', hitWidthScale: 0.735, heightScale: 0.7, sprite: 'assets/obstacles/obstacle_suv.png', hitHalfWidthUnits: 1.456 },
  BUS: { kind: 'BUS', hitWidthScale: 0.958, heightScale: 0.655, sprite: 'assets/obstacles/obstacle_bus.png', hitHalfWidthUnits: 1.897 },
  TRUCK: { kind: 'TRUCK', hitWidthScale: 0.833, heightScale: 0.539, sprite: 'assets/obstacles/obstacle_truck.png', hitHalfWidthUnits: 1.652 },
};

// Small/common obstacles spawn more often than big rare ones like buses.
const WEIGHTED_OBSTACLE_VARIANTS = [
  ...Array(3).fill(OBSTACLE_VARIANTS.CONE),
  ...Array(2).fill(OBSTACLE_VARIANTS.BARRIER),
  ...Array(3).fill(OBSTACLE_VARIANTS.SEDAN),
  ...Array(2).fill(OBSTACLE_VARIANTS.SUV),
  ...Array(1).fill(OBSTACLE_VARIANTS.BUS),
  ...Array(1).fill(OBSTACLE_VARIANTS.TRUCK),
];

function cityById(id) { return CITIES.find(c => c.id === id) || CITIES[0]; }
function carById(id) { return CARS.find(c => c.id === id) || CARS[0]; }
