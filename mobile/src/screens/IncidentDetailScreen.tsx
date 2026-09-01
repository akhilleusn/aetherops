import React, { useCallback, useState } from 'react';
import { Alert, StyleSheet, Text, TextInput, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useFocusEffect } from '@react-navigation/native';
import { Screen } from '../components/Screen';
import { Badge } from '../components/Badge';
import { Button } from '../components/Button';
import { LoadingState, ErrorState } from '../components/StateViews';
import { useAuth } from '../auth/AuthContext';
import {
  ApiError,
  acknowledgeIncident,
  addCostEntry,
  getCostEstimate,
  getCostSummary,
  getEscalationHistory,
  getIncident,
  joinWarRoom,
  leaveWarRoom,
  openWarRoom,
  recordCostEstimate,
  resolveIncident,
} from '../api/client';
import {
  CostSummaryResponse,
  EscalationExecutionResponse,
  IncidentResponse,
  ParticipantRole,
  WarRoomResponse,
} from '../types/api';
import { colors, radius, severityColors, spacing, statusColors, typography } from '../theme/theme';
import type { IncidentStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<IncidentStackParamList, 'IncidentDetail'>;

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString();
}

function formatCents(cents: number, currency: string): string {
  return `${currency === 'USD' ? '$' : currency + ' '}${(cents / 100).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

export function IncidentDetailScreen({ route }: Props) {
  const { incidentId } = route.params;
  const { session } = useAuth();
  const [incident, setIncident] = useState<IncidentResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isActing, setIsActing] = useState<'ack' | 'resolve' | null>(null);

  // War room
  const [warRoom, setWarRoom] = useState<WarRoomResponse | null>(null);
  const [warRoomLoading, setWarRoomLoading] = useState(false);
  const [showRoleChooser, setShowRoleChooser] = useState(false);
  const [warRoomActing, setWarRoomActing] = useState(false);

  // Cost tracking
  const [costSummary, setCostSummary] = useState<CostSummaryResponse | null>(null);
  const [showAddCostForm, setShowAddCostForm] = useState(false);
  const [costDescription, setCostDescription] = useState('');
  const [costAmountDollars, setCostAmountDollars] = useState('');
  const [costActing, setCostActing] = useState(false);

  // Escalation history
  const [escalations, setEscalations] = useState<EscalationExecutionResponse[]>([]);

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const result = await getIncident(incidentId);
      setIncident(result);

      if (session) {
        loadCost(result.id, session.tenantId);
        loadEscalations(result.id, session.tenantId);
        if (result.severity === 'CRITICAL') {
          loadWarRoom(result.id, session.tenantId);
        }
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load incident.');
    } finally {
      setIsLoading(false);
    }
  }, [incidentId, session]);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load])
  );

  const loadWarRoom = async (id: string, tenantId: string) => {
    setWarRoomLoading(true);
    try {
      const result = await openWarRoom(tenantId, id);
      setWarRoom(result);
    } catch {
      // Best-effort — the war room card just won't render if this fails.
    } finally {
      setWarRoomLoading(false);
    }
  };

  const loadCost = async (id: string, tenantId: string) => {
    try {
      const result = await getCostSummary(tenantId, id);
      setCostSummary(result);
    } catch {
      // Best-effort.
    }
  };

  const loadEscalations = async (id: string, tenantId: string) => {
    try {
      const result = await getEscalationHistory(tenantId, id);
      setEscalations(result);
    } catch {
      // Best-effort.
    }
  };

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

  const handleJoinWarRoom = async (role: ParticipantRole) => {
    if (!session || !warRoom) return;
    setWarRoomActing(true);
    setShowRoleChooser(false);
    try {
      const updated = await joinWarRoom(session.tenantId, warRoom.id, session.userId, role);
      setWarRoom(updated);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to join war room.');
    } finally {
      setWarRoomActing(false);
    }
  };

  const handleLeaveWarRoom = async () => {
    if (!session || !warRoom) return;
    setWarRoomActing(true);
    try {
      const updated = await leaveWarRoom(session.tenantId, warRoom.id, session.userId);
      setWarRoom(updated);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to leave war room.');
    } finally {
      setWarRoomActing(false);
    }
  };

  const handleAddCostEntry = async () => {
    if (!session || !incident) return;
    const dollars = parseFloat(costAmountDollars);
    if (!costDescription.trim() || Number.isNaN(dollars) || dollars <= 0) {
      Alert.alert('Invalid entry', 'Enter a description and a positive amount.');
      return;
    }
    setCostActing(true);
    try {
      await addCostEntry(session.tenantId, incident.id, costDescription.trim(), Math.round(dollars * 100), session.email);
      setCostDescription('');
      setCostAmountDollars('');
      setShowAddCostForm(false);
      await loadCost(incident.id, session.tenantId);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to add cost entry.');
    } finally {
      setCostActing(false);
    }
  };

  const handleEstimateAndRecord = async () => {
    if (!session || !incident) return;
    setCostActing(true);
    try {
      const { estimatedCents } = await getCostEstimate(session.tenantId, incident.id);
      Alert.alert(
        'Record estimated cost?',
        `Estimated responder time cost: ${formatCents(estimatedCents, costSummary?.currency ?? 'USD')}`,
        [
          { text: 'Cancel', style: 'cancel', onPress: () => setCostActing(false) },
          {
            text: 'Record',
            onPress: async () => {
              try {
                await recordCostEstimate(session.tenantId, incident.id, session.email);
                await loadCost(incident.id, session.tenantId);
              } catch (err) {
                setError(err instanceof ApiError ? err.message : 'Failed to record estimate.');
              } finally {
                setCostActing(false);
              }
            },
          },
        ]
      );
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to estimate cost.');
      setCostActing(false);
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
  const isParticipant = !!(session && warRoom?.participants.some((p) => p.userId === session.userId));

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

      {incident.severity === 'CRITICAL' && (warRoom || warRoomLoading) && (
        <View style={styles.section}>
          <View style={styles.sectionHeaderRow}>
            <Text style={typography.subtitle}>🏚️ War Room</Text>
            {warRoom && <Badge label={warRoom.status} color={warRoom.status === 'ACTIVE' ? colors.success : colors.textSecondary} />}
          </View>

          {warRoomLoading && !warRoom ? (
            <Text style={typography.caption}>Loading…</Text>
          ) : warRoom ? (
            <>
              {warRoom.slackChannelId && (
                <Text style={[typography.caption, styles.spacedTop]}>Slack: #{warRoom.slackChannelId}</Text>
              )}

              {warRoom.participants.length > 0 ? (
                <View style={styles.spacedTop}>
                  {warRoom.participants.map((p) => (
                    <View key={p.userId} style={styles.participantRow}>
                      <Text style={styles.participantId} numberOfLines={1}>
                        {p.userId === session?.userId ? 'You' : p.userId}
                      </Text>
                      <Text style={typography.caption}>{p.role === 'INCIDENT_COMMANDER' ? 'Commander' : 'Responder'}</Text>
                    </View>
                  ))}
                </View>
              ) : (
                <Text style={[typography.caption, styles.spacedTop]}>No one has joined yet.</Text>
              )}

              <View style={[styles.actionRow, styles.spacedTop]}>
                {isParticipant ? (
                  <View style={styles.actionButton}>
                    <Button label="Leave War Room" onPress={handleLeaveWarRoom} variant="secondary" loading={warRoomActing} />
                  </View>
                ) : showRoleChooser ? (
                  <>
                    <View style={styles.actionButton}>
                      <Button label="Commander" onPress={() => handleJoinWarRoom('INCIDENT_COMMANDER')} loading={warRoomActing} />
                    </View>
                    <View style={styles.actionButton}>
                      <Button label="Responder" onPress={() => handleJoinWarRoom('RESPONDER')} loading={warRoomActing} />
                    </View>
                  </>
                ) : (
                  <View style={styles.actionButton}>
                    <Button label="Join War Room" onPress={() => setShowRoleChooser(true)} />
                  </View>
                )}
              </View>
            </>
          ) : null}
        </View>
      )}

      <View style={styles.section}>
        <View style={styles.sectionHeaderRow}>
          <Text style={typography.subtitle}>💰 Cost Tracking</Text>
          <Text style={styles.costTotal}>
            {costSummary ? formatCents(costSummary.totalCents, costSummary.currency) : '—'}
          </Text>
        </View>

        {costSummary && costSummary.entries.length > 0 && (
          <View style={styles.spacedTop}>
            {costSummary.entries.map((entry) => (
              <View key={entry.id} style={styles.costEntryRow}>
                <View style={styles.costEntryText}>
                  <Text style={styles.rowValue}>{entry.description}</Text>
                  <Text style={typography.caption}>
                    {entry.source === 'AUTO_ESTIMATE' ? 'Auto-estimated' : entry.recordedBy ?? 'Manual'}
                  </Text>
                </View>
                <Text style={styles.rowValue}>{formatCents(entry.amountCents, entry.currency)}</Text>
              </View>
            ))}
          </View>
        )}

        {showAddCostForm && (
          <View style={styles.spacedTop}>
            <TextInput
              style={styles.input}
              placeholder="Description"
              placeholderTextColor={colors.textSecondary}
              value={costDescription}
              onChangeText={setCostDescription}
            />
            <TextInput
              style={[styles.input, styles.spacedTop]}
              placeholder="Amount (USD)"
              placeholderTextColor={colors.textSecondary}
              keyboardType="decimal-pad"
              value={costAmountDollars}
              onChangeText={setCostAmountDollars}
            />
          </View>
        )}

        <View style={[styles.actionRow, styles.spacedTop]}>
          <View style={styles.actionButton}>
            <Button
              label={showAddCostForm ? 'Save Entry' : 'Add Cost Entry'}
              onPress={showAddCostForm ? handleAddCostEntry : () => setShowAddCostForm(true)}
              variant="secondary"
              loading={costActing && showAddCostForm}
            />
          </View>
          <View style={styles.actionButton}>
            <Button label="Estimate & Record" onPress={handleEstimateAndRecord} variant="secondary" loading={costActing && !showAddCostForm} />
          </View>
        </View>
      </View>

      {escalations.length > 0 && (
        <View style={styles.section}>
          <Text style={typography.subtitle}>⏫ Escalation History</Text>
          <View style={styles.spacedTop}>
            {escalations.map((e) => (
              <Text key={e.id} style={[typography.caption, styles.spacedTop]}>
                Escalation level {e.escalationLevel} — {formatDate(e.escalatedAt)}
              </Text>
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
  sectionHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
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
  spacedTop: {
    marginTop: spacing.sm,
  },
  participantRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: spacing.xs,
  },
  participantId: {
    color: colors.textPrimary,
    fontSize: 13,
    flexShrink: 1,
    marginRight: spacing.sm,
  },
  costTotal: {
    color: colors.accent,
    fontSize: 16,
    fontWeight: '700',
  },
  costEntryRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    paddingVertical: spacing.xs + 2,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  costEntryText: {
    flex: 1,
    marginRight: spacing.sm,
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
});
