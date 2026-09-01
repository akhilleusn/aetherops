import React, { useCallback, useState } from 'react';
import { FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useFocusEffect } from '@react-navigation/native';
import { Badge } from '../components/Badge';
import { LoadingState, ErrorState, EmptyState } from '../components/StateViews';
import { useAuth } from '../auth/AuthContext';
import { ApiError, listIncidents } from '../api/client';
import { IncidentResponse, IncidentStatus } from '../types/api';
import { colors, radius, severityColors, spacing, statusColors, typography } from '../theme/theme';
import type { IncidentStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<IncidentStackParamList, 'IncidentList'>;

const STATUS_FILTERS: Array<IncidentStatus | 'ALL'> = ['ALL', 'OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED'];

export function IncidentListScreen({ navigation }: Props) {
  const { session } = useAuth();
  const [incidents, setIncidents] = useState<IncidentResponse[]>([]);
  const [filter, setFilter] = useState<IncidentStatus | 'ALL'>('OPEN');
  const [isLoading, setIsLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (status: IncidentStatus | 'ALL', refresh = false) => {
      if (!session) return;
      refresh ? setIsRefreshing(true) : setIsLoading(true);
      setError(null);
      try {
        const page = await listIncidents(session.tenantId, {
          status: status === 'ALL' ? undefined : status,
          size: 50,
        });
        setIncidents(page.content);
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Failed to load incidents.');
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [session]
  );

  useFocusEffect(
    useCallback(() => {
      load(filter);
      // Re-fetch whenever this screen regains focus (e.g. returning from detail after ack/resolve).
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filter, session])
  );

  return (
    <SafeAreaView style={styles.safeArea} edges={['top']}>
      <View style={styles.header}>
        <Text style={typography.title}>Incidents</Text>
      </View>

      <View style={styles.filterRow}>
        {STATUS_FILTERS.map((s) => (
          <Pressable
            key={s}
            onPress={() => setFilter(s)}
            style={[styles.filterChip, filter === s && styles.filterChipActive]}
          >
            <Text style={[styles.filterText, filter === s && styles.filterTextActive]}>{s}</Text>
          </Pressable>
        ))}
      </View>

      {isLoading && !isRefreshing ? (
        <LoadingState label="Loading incidents…" />
      ) : error ? (
        <ErrorState message={error} onRetry={() => load(filter)} />
      ) : incidents.length === 0 ? (
        <EmptyState message="No incidents match this filter." />
      ) : (
        <FlatList
          data={incidents}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.listContent}
          refreshing={isRefreshing}
          onRefresh={() => load(filter, true)}
          renderItem={({ item }) => (
            <Pressable
              style={styles.card}
              onPress={() => navigation.navigate('IncidentDetail', { incidentId: item.id })}
            >
              <View style={styles.cardTop}>
                <Badge label={item.severity} color={severityColors[item.severity] ?? colors.textSecondary} />
                <Badge label={item.status} color={statusColors[item.status] ?? colors.textSecondary} />
              </View>
              <Text style={styles.cardTitle} numberOfLines={2}>
                {item.title}
              </Text>
              <Text style={typography.caption}>{item.serviceId}</Text>
            </Pressable>
          )}
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: colors.background },
  header: {
    paddingHorizontal: spacing.md,
    paddingTop: spacing.sm,
  },
  filterRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
  },
  filterChip: {
    paddingHorizontal: spacing.sm + 2,
    paddingVertical: spacing.xs,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
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
    paddingBottom: spacing.xl,
    gap: spacing.sm,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  cardTop: {
    flexDirection: 'row',
    gap: spacing.xs,
    marginBottom: spacing.sm,
  },
  cardTitle: {
    ...typography.subtitle,
    marginBottom: spacing.xs,
  },
});
