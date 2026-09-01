import React, { useCallback, useState } from 'react';
import { FlatList, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect } from '@react-navigation/native';
import { Badge } from '../components/Badge';
import { Button } from '../components/Button';
import { LoadingState, ErrorState, EmptyState } from '../components/StateViews';
import { useAuth } from '../auth/AuthContext';
import { ApiError, listSuppressionRules, muteAlert, unmuteAlert } from '../api/client';
import { AlertSuppressionRuleResponse } from '../types/api';
import { colors, radius, spacing, typography } from '../theme/theme';

function formatMutedUntil(mutedUntil: string | null): string {
  return mutedUntil ? `Until ${new Date(mutedUntil).toLocaleString()}` : 'Indefinite';
}

export function AlertSuppressionScreen() {
  const { session } = useAuth();
  const [rules, setRules] = useState<AlertSuppressionRuleResponse[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unmutingId, setUnmutingId] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [serviceId, setServiceId] = useState('');
  const [alertName, setAlertName] = useState('');
  const [reason, setReason] = useState('');
  const [durationMode, setDurationMode] = useState<'indefinite' | 'minutes'>('indefinite');
  const [durationMinutes, setDurationMinutes] = useState('30');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const load = useCallback(
    async (refresh = false) => {
      if (!session) return;
      refresh ? setIsRefreshing(true) : setIsLoading(true);
      setError(null);
      try {
        const result = await listSuppressionRules(session.tenantId);
        setRules(result);
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Failed to load suppression rules.');
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [session]
  );

  useFocusEffect(
    useCallback(() => {
      load();
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [session])
  );

  const handleUnmute = async (ruleId: string) => {
    if (!session) return;
    setUnmutingId(ruleId);
    try {
      await unmuteAlert(session.tenantId, ruleId);
      setRules((prev) => prev.filter((r) => r.id !== ruleId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to unmute.');
    } finally {
      setUnmutingId(null);
    }
  };

  const handleSubmit = async () => {
    if (!session) return;
    const minutes = parseInt(durationMinutes, 10);
    if (durationMode === 'minutes' && (Number.isNaN(minutes) || minutes <= 0)) {
      setError('Enter a positive number of minutes.');
      return;
    }
    setIsSubmitting(true);
    setError(null);
    try {
      await muteAlert(session.tenantId, {
        serviceId: serviceId.trim() || undefined,
        alertName: alertName.trim() || undefined,
        reason: reason.trim() || undefined,
        mutedUntil: durationMode === 'minutes' ? new Date(Date.now() + minutes * 60_000).toISOString() : undefined,
        createdBy: session.email,
      });
      setServiceId('');
      setAlertName('');
      setReason('');
      setDurationMode('indefinite');
      setDurationMinutes('30');
      setShowForm(false);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to mute alert.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <SafeAreaView style={styles.safeArea} edges={['top']}>
      <View style={styles.header}>
        <Text style={typography.title}>Alert Suppression</Text>
        <Pressable style={styles.addButton} onPress={() => setShowForm((s) => !s)}>
          <Text style={styles.addButtonText}>{showForm ? '✕' : '+'}</Text>
        </Pressable>
      </View>

      {showForm && (
        <View style={styles.formCard}>
          <TextInput
            style={styles.input}
            placeholder="Service ID (optional — blank matches all)"
            placeholderTextColor={colors.textSecondary}
            value={serviceId}
            onChangeText={setServiceId}
            autoCapitalize="none"
          />
          <TextInput
            style={[styles.input, styles.spacedTop]}
            placeholder="Alert name (optional — blank matches all)"
            placeholderTextColor={colors.textSecondary}
            value={alertName}
            onChangeText={setAlertName}
            autoCapitalize="none"
          />
          <TextInput
            style={[styles.input, styles.spacedTop]}
            placeholder="Reason"
            placeholderTextColor={colors.textSecondary}
            value={reason}
            onChangeText={setReason}
          />

          <View style={[styles.filterRow, styles.spacedTop]}>
            <Pressable
              onPress={() => setDurationMode('indefinite')}
              style={[styles.filterChip, durationMode === 'indefinite' && styles.filterChipActive]}
            >
              <Text style={[styles.filterText, durationMode === 'indefinite' && styles.filterTextActive]}>Indefinite</Text>
            </Pressable>
            <Pressable
              onPress={() => setDurationMode('minutes')}
              style={[styles.filterChip, durationMode === 'minutes' && styles.filterChipActive]}
            >
              <Text style={[styles.filterText, durationMode === 'minutes' && styles.filterTextActive]}>For a duration</Text>
            </Pressable>
          </View>

          {durationMode === 'minutes' && (
            <TextInput
              style={[styles.input, styles.spacedTop]}
              placeholder="Minutes"
              placeholderTextColor={colors.textSecondary}
              keyboardType="number-pad"
              value={durationMinutes}
              onChangeText={setDurationMinutes}
            />
          )}

          <View style={styles.spacedTop}>
            <Button label="Mute" onPress={handleSubmit} loading={isSubmitting} />
          </View>
        </View>
      )}

      {error ? <Text style={styles.inlineError}>{error}</Text> : null}

      {isLoading && !isRefreshing ? (
        <LoadingState label="Loading suppression rules…" />
      ) : rules.length === 0 && !showForm ? (
        <EmptyState message="No active suppression rules." />
      ) : (
        <FlatList
          data={rules}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.listContent}
          refreshing={isRefreshing}
          onRefresh={() => load(true)}
          renderItem={({ item }) => (
            <View style={styles.card}>
              <View style={styles.cardTop}>
                <Text style={styles.cardTitle}>{item.serviceId ?? 'All services'}</Text>
                {item.autoGenerated && <Badge label="AUTO" color={colors.warning} />}
              </View>
              <Text style={typography.caption}>{item.alertName ?? 'All alerts'}</Text>
              <Text style={[typography.caption, styles.spacedTop]}>{formatMutedUntil(item.mutedUntil)}</Text>
              {item.reason && <Text style={[typography.caption, styles.spacedTop]}>{item.reason}</Text>}
              <View style={styles.spacedTop}>
                <Button
                  label="Unmute"
                  onPress={() => handleUnmute(item.id)}
                  variant="secondary"
                  loading={unmutingId === item.id}
                />
              </View>
            </View>
          )}
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: colors.background },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    paddingTop: spacing.sm,
  },
  addButton: {
    width: 36,
    height: 36,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  addButtonText: {
    color: colors.accent,
    fontSize: 20,
    fontWeight: '700',
    lineHeight: 22,
  },
  formCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.md,
    marginHorizontal: spacing.md,
    marginTop: spacing.md,
  },
  input: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.sm,
    color: colors.textPrimary,
    fontSize: 14,
  },
  spacedTop: {
    marginTop: spacing.sm,
  },
  inlineError: {
    color: colors.danger,
    marginHorizontal: spacing.md,
    marginTop: spacing.sm,
    fontSize: 13,
  },
  filterRow: {
    flexDirection: 'row',
    gap: spacing.xs,
  },
  filterChip: {
    paddingHorizontal: spacing.sm + 2,
    paddingVertical: spacing.xs,
    borderRadius: radius.md,
    backgroundColor: colors.surfaceAlt,
    borderWidth: 1,
    borderColor: colors.border,
  },
  filterChipActive: {
    backgroundColor: colors.accent,
    borderColor: colors.accent,
  },
  filterText: {
    color: colors.textSecondary,
    fontSize: 12,
    fontWeight: '600',
  },
  filterTextActive: {
    color: colors.background,
  },
  listContent: {
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
    paddingBottom: spacing.xl,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  cardTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.xs,
  },
  cardTitle: {
    ...typography.subtitle,
  },
});
