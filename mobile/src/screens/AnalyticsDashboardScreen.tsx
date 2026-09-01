import React, { useCallback, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { Screen } from '../components/Screen';
import { LoadingState, ErrorState } from '../components/StateViews';
import { useAuth } from '../auth/AuthContext';
import { ApiError, getAnalyticsDashboard } from '../api/client';
import { AnalyticsDashboardResponse } from '../types/api';
import { colors, radius, spacing, typography } from '../theme/theme';

function formatMinutes(minutes: number | null): string {
  if (minutes === null) return '—';
  if (minutes < 60) return `${Math.round(minutes)}m`;
  const hours = Math.floor(minutes / 60);
  const remainder = Math.round(minutes % 60);
  return remainder > 0 ? `${hours}h ${remainder}m` : `${hours}h`;
}

export function AnalyticsDashboardScreen() {
  const { session } = useAuth();
  const [dashboard, setDashboard] = useState<AnalyticsDashboardResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (refresh = false) => {
      if (!session) return;
      refresh ? setIsRefreshing(true) : setIsLoading(true);
      setError(null);
      try {
        const result = await getAnalyticsDashboard(session.tenantId);
        setDashboard(result);
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Failed to load analytics.');
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

  if (isLoading) {
    return (
      <Screen scrollable={false}>
        <LoadingState label="Loading analytics…" />
      </Screen>
    );
  }

  if (error && !dashboard) {
    return (
      <Screen scrollable={false}>
        <ErrorState message={error} onRetry={() => load()} />
      </Screen>
    );
  }

  if (!dashboard) return null;

  const maxTrendCount = Math.max(1, ...dashboard.trend.map((d) => d.count));

  return (
    <Screen refreshing={isRefreshing} onRefresh={() => load(true)}>
      <Text style={typography.title}>Analytics</Text>
      <Text style={[typography.caption, styles.subtitle]}>Last 30 days · read-only</Text>

      <View style={styles.statRow}>
        <StatTile label="Total incidents" value={String(dashboard.totalIncidents)} />
        <StatTile label="Resolved" value={String(dashboard.resolvedCount)} />
      </View>
      <View style={styles.statRow}>
        <StatTile label="MTTR" value={formatMinutes(dashboard.mttrMinutes)} hint="not enough data" />
        <StatTile label="MTBF" value={formatMinutes(dashboard.mtbfMinutes)} hint="not enough data" />
      </View>

      <View style={styles.section}>
        <Text style={typography.subtitle}>Incident trend</Text>
        {dashboard.trend.length === 0 ? (
          <Text style={[typography.caption, styles.emptyTrend]}>No incidents in this period.</Text>
        ) : (
          <View style={styles.chart}>
            {dashboard.trend.map((point) => (
              <View key={point.date} style={styles.chartColumn}>
                <View
                  style={[
                    styles.chartBar,
                    { height: Math.max(4, (point.count / maxTrendCount) * 100) },
                  ]}
                />
                <Text style={styles.chartValue}>{point.count}</Text>
                <Text style={styles.chartLabel} numberOfLines={1}>
                  {point.date.slice(5)}
                </Text>
              </View>
            ))}
          </View>
        )}
      </View>

      <View style={styles.section}>
        <Text style={typography.subtitle}>Team performance</Text>
        {dashboard.teamPerformance.length === 0 ? (
          <Text style={[typography.caption, styles.emptyTrend]}>No team data available.</Text>
        ) : (
          dashboard.teamPerformance.map((team) => (
            <View key={team.team} style={styles.teamRow}>
              <View style={styles.teamNameCol}>
                <Text style={styles.teamName}>{team.team}</Text>
                <Text style={typography.caption}>
                  {team.incidentCount} incident{team.incidentCount === 1 ? '' : 's'}
                </Text>
              </View>
              <Text style={styles.teamMttr}>{formatMinutes(team.mttrMinutes)} MTTR</Text>
            </View>
          ))
        )}
      </View>
    </Screen>
  );
}

function StatTile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <View style={styles.tile}>
      <Text style={styles.tileValue}>{value}</Text>
      <Text style={typography.caption}>{label}</Text>
      {value === '—' && hint ? <Text style={styles.tileHint}>{hint}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  subtitle: {
    marginTop: spacing.xs,
    marginBottom: spacing.lg,
  },
  statRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginBottom: spacing.sm,
  },
  tile: {
    flex: 1,
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.md,
  },
  tileValue: {
    fontSize: 26,
    fontWeight: '800',
    color: colors.accent,
    marginBottom: spacing.xs,
  },
  tileHint: {
    fontSize: 10,
    color: colors.textSecondary,
    marginTop: 2,
    fontStyle: 'italic',
  },
  section: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.md,
    marginTop: spacing.md,
  },
  emptyTrend: {
    marginTop: spacing.sm,
  },
  chart: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    height: 140,
    marginTop: spacing.md,
    gap: spacing.xs,
  },
  chartColumn: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'flex-end',
  },
  chartBar: {
    width: '70%',
    backgroundColor: colors.accent,
    borderRadius: radius.sm,
    minHeight: 4,
  },
  chartValue: {
    fontSize: 10,
    color: colors.textSecondary,
    marginTop: spacing.xs,
  },
  chartLabel: {
    fontSize: 9,
    color: colors.textSecondary,
    marginTop: 2,
  },
  teamRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: spacing.sm,
    borderTopWidth: 1,
    borderTopColor: colors.border,
    marginTop: spacing.sm,
  },
  teamNameCol: {
    flex: 1,
  },
  teamName: {
    color: colors.textPrimary,
    fontSize: 14,
    fontWeight: '600',
  },
  teamMttr: {
    color: colors.textSecondary,
    fontSize: 12,
  },
});
