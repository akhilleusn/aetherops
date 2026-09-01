import * as SecureStore from 'expo-secure-store';

export interface StoredSession {
  token: string;
  tenantId: string;
  userId: string;
  email: string;
  role: string;
}

const KEY = 'aetherops.session';

export async function saveSession(session: StoredSession): Promise<void> {
  await SecureStore.setItemAsync(KEY, JSON.stringify(session));
}

export async function loadSession(): Promise<StoredSession | null> {
  const raw = await SecureStore.getItemAsync(KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as StoredSession;
  } catch {
    return null;
  }
}

export async function clearSession(): Promise<void> {
  await SecureStore.deleteItemAsync(KEY);
}
