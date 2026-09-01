import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { IncidentListScreen } from '../screens/IncidentListScreen';
import { IncidentDetailScreen } from '../screens/IncidentDetailScreen';
import { colors } from '../theme/theme';
import type { IncidentStackParamList } from './types';

const Stack = createNativeStackNavigator<IncidentStackParamList>();

export function IncidentStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: colors.surface },
        headerTintColor: colors.textPrimary,
        headerShadowVisible: false,
      }}
    >
      <Stack.Screen name="IncidentList" component={IncidentListScreen} options={{ headerShown: false }} />
      <Stack.Screen name="IncidentDetail" component={IncidentDetailScreen} options={{ title: 'Incident' }} />
    </Stack.Navigator>
  );
}
