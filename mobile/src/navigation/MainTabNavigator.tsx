import React from 'react';
import { Text } from 'react-native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { IncidentStackNavigator } from './IncidentStackNavigator';
import { OnCallStatusScreen } from '../screens/OnCallStatusScreen';
import { AnalyticsDashboardScreen } from '../screens/AnalyticsDashboardScreen';
import { colors } from '../theme/theme';
import type { MainTabParamList } from './types';

const Tab = createBottomTabNavigator<MainTabParamList>();

const ICONS: Record<keyof MainTabParamList, string> = {
  Incidents: '🚨',
  OnCall: '📟',
  Analytics: '📊',
};

export function MainTabNavigator() {
  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.textSecondary,
        tabBarStyle: { backgroundColor: colors.surface, borderTopColor: colors.border },
        tabBarIcon: () => <Text style={{ fontSize: 18 }}>{ICONS[route.name]}</Text>,
      })}
    >
      <Tab.Screen name="Incidents" component={IncidentStackNavigator} />
      <Tab.Screen name="OnCall" component={OnCallStatusScreen} options={{ title: 'On-Call' }} />
      <Tab.Screen name="Analytics" component={AnalyticsDashboardScreen} />
    </Tab.Navigator>
  );
}
