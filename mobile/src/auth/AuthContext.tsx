import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import * as api from '../api/client';
import { setAuthToken } from '../api/client';
import { clearSession, loadSession, saveSession, StoredSession } from './storage';

interface AuthContextValue {
  session: StoredSession | null;
  isLoading: boolean;
  /** Non-null when the last login attempt requires 2FA, which this app does not support yet. */
  twoFactorMessage: string | null;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<StoredSession | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [twoFactorMessage, setTwoFactorMessage] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      const stored = await loadSession();
      if (stored) {
        setAuthToken(stored.token);
        setSession(stored);
      }
      setIsLoading(false);
    })();
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    setTwoFactorMessage(null);
    const auth = await api.login(email, password);

    if (auth.requiresTwoFactor) {
      setTwoFactorMessage(
        'This account has two-factor authentication enabled. The AetherOps mobile app does not support completing 2FA yet — please sign in on the web dashboard.'
      );
      return;
    }

    const stored: StoredSession = {
      token: auth.token,
      tenantId: auth.tenantId,
      userId: auth.userId,
      email: auth.email,
      role: auth.role,
    };
    await saveSession(stored);
    setAuthToken(stored.token);
    setSession(stored);
  }, []);

  const logout = useCallback(async () => {
    await clearSession();
    setAuthToken(null);
    setSession(null);
  }, []);

  const value = useMemo(
    () => ({ session, isLoading, twoFactorMessage, login, logout }),
    [session, isLoading, twoFactorMessage, login, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
