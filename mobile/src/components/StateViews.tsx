import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { colors, spacing, typography } from '../theme/theme';

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <View style={styles.center}>
      <ActivityIndicator color={colors.accent} size="large" />
      <Text style={[typography.caption, styles.spaced]}>{label}</Text>
    </View>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <View style={styles.center}>
      <Text style={styles.errorTitle}>Something went wrong</Text>
      <Text style={[typography.caption, styles.spaced, styles.errorMessage]}>{message}</Text>
      {onRetry && <Text style={styles.retry} onPress={onRetry}>Tap to retry</Text>}
    </View>
  );
}

export function EmptyState({ message }: { message: string }) {
  return (
    <View style={styles.center}>
      <Text style={[typography.body, styles.emptyMessage]}>{message}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  center: {
    flexGrow: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: spacing.xl,
    paddingHorizontal: spacing.lg,
  },
  spaced: {
    marginTop: spacing.sm,
  },
  errorTitle: {
    color: colors.danger,
    fontSize: 16,
    fontWeight: '700',
  },
  errorMessage: {
    textAlign: 'center',
  },
  retry: {
    color: colors.accent,
    marginTop: spacing.md,
    fontWeight: '600',
  },
  emptyMessage: {
    color: colors.textSecondary,
    textAlign: 'center',
  },
});
