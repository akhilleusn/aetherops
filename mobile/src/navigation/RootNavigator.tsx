import React, { useEffect, useRef } from 'react';
import { NavigationContainer, DarkTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { LoginScreen } from '../screens/LoginScreen';
import { MainTabNavigator } from './MainTabNavigator';
import { useAuth } from '../auth/AuthContext';
import { LoadingState } from '../components/StateViews';
import { setupPushNotifications, watchPushTokenChanges } from '../notifications/registerPushToken';
import { colors } from '../theme/theme';
import type { RootStackParamList } from './types';

const Stack = createNativeStackNavigator<RootStackParamList>();

const navigationTheme = {
  ...DarkTheme,
  colors: {
    ...DarkTheme.colors,
    background: colors.background,
    card: colors.surface,
    border: colors.border,
    primary: colors.accent,
    text: colors.textPrimary,
  },
};

export function RootNavigator() {
  const { session, isLoading } = useAuth();
  const pushSetupDone = useRef<string | null>(null);

  useEffect(() => {
    if (!session) {
      pushSetupDone.current = null;
      return;
    }
    if (pushSetupDone.current === session.userId) return;
    pushSetupDone.current = session.userId;

    setupPushNotifications(session.userId, session.tenantId);
    const unsubscribe = watchPushTokenChanges(session.userId, session.tenantId);
    return unsubscribe;
  }, [session]);

  if (isLoading) {
    return <LoadingState label="Starting AetherOps…" />;
  }

  return (
    <NavigationContainer theme={navigationTheme}>
      <Stack.Navigator screenOptions={{ headerShown: false }}>
        {session ? (
          <Stack.Screen name="Main" component={MainTabNavigator} />
        ) : (
          <Stack.Screen name="Login" component={LoginScreen} />
        )}
      </Stack.Navigator>
    </NavigationContainer>
  );
}
