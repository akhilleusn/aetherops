import React, { useCallback, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useFocusEffect } from '@react-navigation/native';
import { Screen } from '../components/Screen';
import { Badge } from '../components/Badge';
import { Button } from '../components/Button';
import { LoadingState, ErrorState } from '../components/StateViews';
import { useAuth } from '../auth/AuthContext';
import { ApiError, acknowledgeIncident, getIncident, resolveIncident } from '../api/client';
import { IncidentResponse } from '../types/api';
import { colors, radius, severityColors, spacing, statusColors, typography } from '../theme/theme';
import type { IncidentStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<IncidentStackParamList, 'IncidentDetail'>;

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString();
}

export function IncidentDetailScreen({ route }: Props) {
  const { incidentId } = route.params;
  const { session } = useAuth();
  const [incident, setIncident] = useState<IncidentResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isActing, setIsActing] = useState<'ack' | 'resolve' | null>(null);

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const result = await getIncident(incidentId);
      setIncident(result);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load incident.');
    } finally {
      setIsLoading(false);
    }
  }, [incidentId]);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load])
  );

  const handleAcknowledge = async () => {
    setIsActing('ack');
    try {
      const updated = await acknowledgeIncident(incidentId);
      setIncident(updated);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to acknowledge incident.');
    } finally {
      setIsActing(null);
    }
  };

  const handleResolve = async () => {
    if (!session) return;
    setIsActing('resolve');
    try {
      const updated = await resolveIncident(incidentId, session.tenantId);
      setIncident(updated);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to resolve incident.');
    } finally {
      setIsActing(null);
    }
  };

  if (isLoading) {
    return (
      <Screen scrollable={false}>
        <LoadingState label="Loading incident…" />
      </Screen>
    );
  }

  if (error && !incident) {
    return (
      <Screen scrollable={false}>
        <ErrorState message={error} onRetry={load} />
      </Screen>
    );
  }

  if (!incident) {
    return null;
  }

  const canAcknowledge = incident.status === 'OPEN';
  const canResolve = incident.status === 'OPEN' || incident.status === 'INVESTIGATING';
  const tagEntries = Object.entries(incident.tags ?? {});

  return (
    <Screen>
      <View style={styles.badgeRow}>
        <Badge label={incident.severity} color={severityColors[incident.severity] ?? colors.textSecondary} />
        <Badge label={incident.status} color={statusColors[incident.status] ?? colors.textSecondary} />
      </View>

      <Text style={styles.title}>{incident.title}</Text>
      {incident.description ? <Text style={styles.description}>{incident.description}</Text> : null}

      {error ? <Text style={styles.inlineError}>{error}</Text> : null}

      <View style={styles.actionRow}>
        {canAcknowledge && (
          <View style={styles.actionButton}>
            <Button label="Acknowledge" onPress={handleAcknowledge} loading={isActing === 'ack'} />
          </View>
        )}
        {canResolve && (
          <View style={styles.actionButton}>
            <Button
              label="Resolve"
              onPress={handleResolve}
              loading={isActing === 'resolve'}
              variant={canAcknowledge ? 'secondary' : 'primary'}
            />
          </View>
        )}
      </View>

      <View style={styles.section}>
        <Row label="Service" value={incident.serviceId} />
        <Row label="Environment" value={incident.environment ?? '—'} />
        <Row label="Event count" value={String(incident.eventCount)} />
        <Row label="First seen" value={formatDate(incident.firstSeenAt)} />
        <Row label="Last seen" value={formatDate(incident.lastSeenAt)} />
        {incident.resolvedAt && <Row label="Resolved" value={formatDate(incident.resolvedAt)} />}
      </View>

      {tagEntries.length > 0 && (
        <View style={styles.section}>
          <Text style={typography.subtitle}>Tags</Text>
          <View style={styles.tagWrap}>
            {tagEntries.map(([key, value]) => (
              <View key={key} style={styles.tag}>
                <Text style={styles.tagText}>
                  {key}: {value}
                </Text>
              </View>
            ))}
          </View>
        </View>
      )}
    </Screen>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <Text style={typography.caption}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badgeRow: {
    flexDirection: 'row',
    gap: spacing.xs,
    marginBottom: spacing.md,
  },
  title: {
    ...typography.title,
    marginBottom: spacing.sm,
  },
  description: {
    ...typography.body,
    color: colors.textSecondary,
    marginBottom: spacing.md,
  },
  inlineError: {
    color: colors.danger,
    marginBottom: spacing.md,
    fontSize: 13,
  },
  actionRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginBottom: spacing.lg,
  },
  actionButton: {
    flex: 1,
  },
  section: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.md,
    marginBottom: spacing.md,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: spacing.xs + 2,
  },
  rowValue: {
    color: colors.textPrimary,
    fontSize: 14,
    fontWeight: '500',
  },
  tagWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    marginTop: spacing.sm,
  },
  tag: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
  },
  tagText: {
    color: colors.textSecondary,
    fontSize: 12,
  },
});
