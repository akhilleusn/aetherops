import Constants from 'expo-constants';

/**
 * Default comes from app.json's `expo.extra.apiBaseUrl`. Override at runtime by editing that
 * value (or, for a dev client, setting EXPO_PUBLIC_API_BASE_URL and reading it here) — no
 * rebuild needed for a config-only change since Expo re-reads app.json on every start.
 */
const configuredUrl = Constants.expoConfig?.extra?.apiBaseUrl as string | undefined;

export const API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL ?? configuredUrl ?? 'http://localhost:8080';
