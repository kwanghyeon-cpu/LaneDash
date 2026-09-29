// Global Firestore leaderboard — mirrors app/src/main/java/.../game/FirestoreLeaderboardRepository.kt
// and Leaderboard.kt (same "scores" collection, same field names, same qualifying rule),
// so a score set from the web build lands in the same shared leaderboard as the Android app.
//
// This needs a Web app registered in the same Firebase project (lanedash-2cc5d) — the
// Android app's own config (google-services.json) is Android-only and can't be reused
// here. Get one at https://console.firebase.google.com -> Project settings -> your apps ->
// Add app -> Web, then fill in the values below from the snippet it gives you.
const FIREBASE_CONFIG = {
  apiKey: 'REPLACE_ME',
  authDomain: 'lanedash-2cc5d.firebaseapp.com',
  projectId: 'lanedash-2cc5d',
  storageBucket: 'lanedash-2cc5d.firebasestorage.app',
  messagingSenderId: '67067239684',
  appId: 'REPLACE_ME',
};

firebase.initializeApp(FIREBASE_CONFIG);
const leaderboardDb = firebase.firestore();
const SCORES_COLLECTION = 'scores';

async function fetchTopScores(limit = 10) {
  const snapshot = await leaderboardDb.collection(SCORES_COLLECTION)
    .orderBy('score', 'desc')
    .limit(limit)
    .get();
  const entries = [];
  snapshot.forEach(doc => {
    const data = doc.data();
    if (typeof data.name === 'string' && typeof data.score === 'number') {
      entries.push({ name: data.name, score: data.score });
    }
  });
  return entries;
}

async function submitScore(name, score) {
  const cleanName = name.trim().slice(0, 12) || 'PLAYER';
  await leaderboardDb.collection(SCORES_COLLECTION).add({ name: cleanName, score });
  return cleanName;
}

/** True if `score` would land inside the top `limit` entries — mirrors qualifiesForTop in Leaderboard.kt. */
async function qualifiesForTop(score, limit = 10) {
  const top = await fetchTopScores(limit);
  return top.length < limit || score > top[top.length - 1].score;
}
