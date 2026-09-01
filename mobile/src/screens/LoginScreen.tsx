import React, { useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button } from '../components/Button';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../api/client';
import { colors, radius, spacing, typography } from '../theme/theme';

export function LoginScreen() {
  const { login, twoFactorMessage } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = email.trim().length > 0 && password.length > 0 && !isSubmitting;

  const handleSubmit = async () => {
    setError(null);
    setIsSubmitting(true);
    try {
      await login(email.trim(), password);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Login failed. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <View style={styles.container}>
        <Text style={styles.wordmark}>AetherOps</Text>
        <Text style={[typography.caption, styles.subtitle]}>Incident response, on the go</Text>

        <View style={styles.form}>
          <Text style={styles.label}>Email</Text>
          <TextInput
            style={styles.input}
            value={email}
            onChangeText={setEmail}
            placeholder="you@company.com"
            placeholderTextColor={colors.textSecondary}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="email-address"
            textContentType="emailAddress"
          />

          <Text style={styles.label}>Password</Text>
          <TextInput
            style={styles.input}
            value={password}
            onChangeText={setPassword}
            placeholder="••••••••"
            placeholderTextColor={colors.textSecondary}
            secureTextEntry
            textContentType="password"
          />

          {(error || twoFactorMessage) && (
            <Text style={styles.error}>{error ?? twoFactorMessage}</Text>
          )}

          <View style={styles.buttonSpacer}>
            <Button label="Log in" onPress={handleSubmit} loading={isSubmitting} disabled={!canSubmit} />
          </View>
        </View>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: colors.background },
  container: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
  wordmark: {
    fontSize: 32,
    fontWeight: '800',
    color: colors.textPrimary,
    textAlign: 'center',
  },
  subtitle: {
    textAlign: 'center',
    marginTop: spacing.xs,
    marginBottom: spacing.xl,
  },
  form: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
  },
  label: {
    ...typography.caption,
    marginBottom: spacing.xs,
    marginTop: spacing.md,
  },
  input: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm + 2,
    color: colors.textPrimary,
    fontSize: 15,
  },
  error: {
    color: colors.danger,
    marginTop: spacing.md,
    fontSize: 13,
  },
  buttonSpacer: {
    marginTop: spacing.lg,
  },
});
