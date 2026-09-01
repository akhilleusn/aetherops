import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { radius, spacing } from '../theme/theme';

export function Badge({ label, color }: { label: string; color: string }) {
  return (
    <View style={[styles.badge, { backgroundColor: `${color}26`, borderColor: color }]}>
      <Text style={[styles.text, { color }]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    alignSelf: 'flex-start',
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    borderRadius: radius.sm,
    borderWidth: 1,
  },
  text: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
});
