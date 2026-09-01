import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text } from 'react-native';
import { colors, radius, spacing } from '../theme/theme';

interface ButtonProps {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'danger';
  loading?: boolean;
  disabled?: boolean;
}

export function Button({ label, onPress, variant = 'primary', loading, disabled }: ButtonProps) {
  const isDisabled = disabled || loading;
  return (
    <Pressable
      onPress={onPress}
      disabled={isDisabled}
      style={({ pressed }) => [
        styles.base,
        variantStyles[variant],
        isDisabled && styles.disabled,
        pressed && !isDisabled && styles.pressed,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={colors.white} size="small" />
      ) : (
        <Text style={[styles.label, variant === 'secondary' && styles.secondaryLabel]}>{label}</Text>
      )}
    </Pressable>
  );
}

const variantStyles = StyleSheet.create({
  primary: { backgroundColor: colors.accent },
  secondary: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.border },
  danger: { backgroundColor: colors.danger },
});

const styles = StyleSheet.create({
  base: {
    paddingVertical: spacing.sm + 2,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 44,
  },
  label: {
    color: colors.white,
    fontWeight: '700',
    fontSize: 15,
  },
  secondaryLabel: {
    color: colors.textPrimary,
  },
  disabled: {
    opacity: 0.5,
  },
  pressed: {
    opacity: 0.85,
  },
});
