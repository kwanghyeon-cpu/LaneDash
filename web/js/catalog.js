// Ported from the Android app's GameCatalog / Model.kt — keep values in sync by hand.

const CITIES = [
  {
    id: 'seoul', name: '서울', nameEn: 'SEOUL',
    skyTop: '#2E1A47', skyBottom: '#FF8C69',
    roadColor: '#241830', roadLineColor: '#FFD166',
    sprite: 'assets/cities/city_seoul.png',
    // Haze matches each skyline sprite's horizon color (the sky gradient colors above are only a
    // fallback and don't match the sprites) — used for the road haze in art.js.
    haze: '#D39A8E',
  },
  {
    id: 'tokyo', name: '도쿄', nameEn: 'TOKYO',
    skyTop: '#05021A', skyBottom: '#1B0F3D',
    roadColor: '#0D0620', roadLineColor: '#FF2E92',
    sprite: 'assets/cities/city_tokyo.png',
    haze: '#A3B8DA',
  },
  {
    id: 'newyork', name: '뉴욕', nameEn: 'NEW YORK',
    skyTop: '#FF9A56', skyBottom: '#FFD56B',
    roadColor: '#3A2E28', roadLineColor: '#FFF3B0',
    sprite: 'assets/cities/city_newyork.png',
    haze: '#9DCBE6',
  },
  {
    id: 'paris', name: '파리', nameEn: 'PARIS',
    skyTop: '#BFE3FF', skyBottom: '#F6E7D8',
    roadColor: '#4A4038', roadLineColor: '#FFFFFF',
    sprite: 'assets/cities/city_paris.png',
    haze: '#2B556B',
  },
];

const CARS = [
  { id: 'suv', name: 'SUV', nameEn: 'SUV', rearSprite: 'assets/rear/car_rear_1.png' },
  { id: 'sports_car', name: '스포츠카', nameEn: 'SPORTS CAR', rearSprite: 'assets/rear/car_rear_2.png' },
  { id: 'sedan', name: '세단', nameEn: 'SEDAN', rearSprite: 'assets/rear/car_rear_3.png' },
  { id: 'van', name: '승합차', nameEn: 'VAN', rearSprite: 'assets/rear/car_rear_4.png' },
];

function cityById(id) { return CITIES.find(c => c.id === id) || CITIES[0]; }
function carById(id) { return CARS.find(c => c.id === id) || CARS[0]; }
