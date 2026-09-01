import React, { useCallback, useEffect, useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { Screen } from '../components/Screen';
import { Button } from '../components/Button';
import { EmptyState, ErrorState } from '../components/StateViews';
import { useAuth } from '../auth/AuthContext';
import { ApiError, getOnCallStatus } from '../api/client';
import { OnCallStatus } from '../types/api';
import { colors, radius, spacing, typography } from '../theme/theme';
import * as SecureStore from 'expo-secure-store';

const LAST_SERVICE_KEY = 'aetherops.lastServiceId';

export function OnCallStatusScreen() {
  const { session, logout } = useAuth();
  const [serviceId, setServiceId] = useState('');
  const [status, setStatus] = useState<OnCallStatus | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    SecureStore.getItemAsync(LAST_SERVICE_KEY).then((saved) => {
      if (saved) setServiceId(saved);
    });
  }, []);

  const lookup = useCallback(
    async (id: string) => {
      if (!session || !id.trim()) return;
      setIsLoading(true);
      setError(null);
      setNotFound(false);
      setStatus(null);
      try {
        const result = await getOnCallStatus(session.tenantId, id.trim());
        setStatus(result);
        await SecureStore.setItemAsync(LAST_SERVICE_KEY, id.trim());
      } catch (err) {
        if (err instanceof ApiError && err.status === 404) {
          setNotFound(true);
        } else {
          setError(err instanceof ApiError ? err.message : 'Failed to load on-call status.');
        }
      } finally {
        setIsLoading(false);
      }
    },
    [session]
  );

  useEffect(() => {
    if (serviceId) {
      lookup(serviceId);
    }
    // Only auto-lookup once, on mount, using the restored last-used service id.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Screen>
      <Text style={typography.title}>On-Call Status</Text>
      <Text style={[typography.caption, styles.intro]}>
        Look up who is currently on-call for a service.
      </Text>

      <View style={styles.searchRow}>
        <TextInput
          style={styles.input}
          value={serviceId}
          onChangeText={setServiceId}
          placeholder="Service ID (e.g. checkout-api)"
          placeholderTextColor={colors.textSecondary}
          autoCapitalize="none"
          autoCorrect={false}
          returnKeyType="search"
          onSubmitEditing={() => lookup(serviceId)}
        />
        <View style={styles.searchButton}>
          <Button label="Look up" onPress={() => lookup(serviceId)} loading={isLoading} disabled={!serviceId.trim()} />
        </View>
      </View>

      {error && <ErrorState message={error} onRetry={() => lookup(serviceId)} />}
      {notFound && <EmptyState message={`No on-call rotation found for "${serviceId.trim()}".`} />}

      {status && (
        <View style={styles.card}>
          <Text style={typography.subtitle}>{status.rotationName}</Text>
          <Text style={[typography.caption, styles.serviceLabel]}>{status.serviceId}</Text>

          <View style={styles.divider} />

          <Text style={typography.caption}>Currently on-call</Text>
          <Text style={styles.phone}>
            {status.currentOnCallMasked ?? 'No engineer currently assigned'}
          </Text>
        </View>
      )}

      <View style={styles.footer}>
        <Text style={styles.signedInAs}>Signed in as {session?.email}</Text>
        <Text style={styles.logout} onPress={logout}>
          Log out
        </Text>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  intro: {
    marginTop: spacing.xs,
    marginBottom: spacing.lg,
  },
  searchRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  input: {
    flex: 1,
    backgroundColor: colors.surface,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.md,
    color: colors.textPrimary,
    fontSize: 15,
  },
  searchButton: {
    minWidth: 96,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
    marginTop: spacing.md,
  },
  serviceLabel: {
    marginTop: spacing.xs,
  },
  divider: {
    height: 1,
    backgroundColor: colors.border,
    marginVertical: spacing.md,
  },
  phone: {
    fontSize: 24,
    fontWeight: '700',
    color: colors.accent,
    marginTop: spacing.xs,
    letterSpacing: 1,
  },
  footer: {
    marginTop: spacing.xl,
    alignItems: 'center',
  },
  signedInAs: {
    color: colors.textSecondary,
    fontSize: 12,
    marginBottom: spacing.xs,
  },
  logout: {
    color: colors.danger,
    fontWeight: '600',
    fontSize: 13,
  },
});
