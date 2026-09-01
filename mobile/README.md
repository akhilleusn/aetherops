# AetherOps Mobile

Expo (React Native + TypeScript) client for the AetherOps incident-response backend. On-call
lookup, incident triage with acknowledge/resolve, push paging alerts, and a read-only analytics
dashboard.

## Running it

```
cd mobile
npm install
npx expo start
```

Scan the QR code with Expo Go (iOS/Android), or press `i` / `a` for a simulator/emulator. Push
notifications require a physical device — simulators/emulators skip registration silently (see
`src/notifications/registerPushToken.ts`).

## Pointing at a different backend

The API base URL defaults to `http://localhost:8080` (`app.json` → `expo.extra.apiBaseUrl`).
Two ways to change it:

- **Edit `app.json`** — change `expo.extra.apiBaseUrl` and restart `expo start`.
- **Environment variable** — set `EXPO_PUBLIC_API_BASE_URL` before starting; it takes priority
  over `app.json`. E.g. on a physical device testing against a machine on your LAN:
  `EXPO_PUBLIC_API_BASE_URL=http://192.168.1.50:8080 npx expo start`.

Do not point this at `localhost` when testing on a physical device — `localhost` on the phone
means the phone itself, not your dev machine.

## Screens

- **Login** (`src/screens/LoginScreen.tsx`) — email/password against `POST /api/v1/auth/login`.
  Registration, password reset, Google, and SSO are web-only; a 2FA-required account shows a
  message pointing the user to the web dashboard rather than attempting the flow here.
- **Incidents** (`src/screens/IncidentListScreen.tsx`, `IncidentDetailScreen.tsx`) — filterable,
  pull-to-refresh incident list; tapping in shows full detail with **Acknowledge** (OPEN →
  INVESTIGATING) and **Resolve** actions.
- **On-Call** (`src/screens/OnCallStatusScreen.tsx`) — look up the current on-call engineer
  (phone masked to last 4 digits) by service ID. The backend has no "list services" endpoint yet,
  so this is a remembered free-text field rather than a picker.
- **Analytics** (`src/screens/AnalyticsDashboardScreen.tsx`) — read-only MTTR/MTBF tiles, a
  30-day incident trend bar chart, and team performance, all sourced from the same dashboard
  endpoint the web app uses.

Push registration (`src/notifications/registerPushToken.ts`) runs once after login and again
whenever the Expo push token rotates, POSTing to `/api/v1/users/{userId}/push-token` — the same
endpoint the backend's `PushNotificationService` fans paging alerts out to.

## Type-checking

```
npm run typecheck
```
