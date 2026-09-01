import { API_BASE_URL } from './config';
import type {
  ApiResponse,
  AuthResponse,
  OnCallStatus,
  IncidentResponse,
  Page,
  IncidentStatus,
  UpdateIncidentRequest,
  AnalyticsDashboardResponse,
  PushDeviceToken,
  WarRoomResponse,
  ParticipantRole,
  CostSummaryResponse,
  CostEntryResponse,
  EscalationExecutionResponse,
  AlertSuppressionRuleResponse,
  MuteAlertRequest,
} from '../types/api';

/**
 * Set by the auth layer on login/logout/session-restore. Kept outside React so non-component
 * code (e.g. the push-notification listener) can make authenticated calls without a hook.
 */
let bearerToken: string | null = null;

export function setAuthToken(token: string | null): void {
  bearerToken = token;
}

export class ApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (bearerToken) {
    headers.Authorization = `Bearer ${bearerToken}`;
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  } catch (networkError) {
    throw new ApiError(
      `Could not reach the AetherOps server at ${API_BASE_URL}. Check the URL and your connection.`,
      0
    );
  }

  let body: ApiResponse<T> | null = null;
  try {
    body = (await response.json()) as ApiResponse<T>;
  } catch {
    // Non-JSON body (e.g. a proxy error page) — fall through to status-based error below.
  }

  if (!response.ok || !body || body.success === false) {
    const message = body?.error ?? `Request failed with status ${response.status}`;
    throw new ApiError(message, response.status);
  }

  return body.data as T;
}

export function login(email: string, password: string): Promise<AuthResponse> {
  return request<AuthResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export function getOnCallStatus(tenantId: string, serviceId: string): Promise<OnCallStatus> {
  return request<OnCallStatus>(
    `/api/v1/tenants/${tenantId}/oncall/${encodeURIComponent(serviceId)}`
  );
}

export function listIncidents(
  tenantId: string,
  options: { status?: IncidentStatus; page?: number; size?: number } = {}
): Promise<Page<IncidentResponse>> {
  const params = new URLSearchParams({ tenantId });
  if (options.status) params.set('status', options.status);
  params.set('page', String(options.page ?? 0));
  params.set('size', String(options.size ?? 20));
  return request<Page<IncidentResponse>>(`/api/v1/incidents?${params.toString()}`);
}

export function getIncident(id: string): Promise<IncidentResponse> {
  return request<IncidentResponse>(`/api/v1/incidents/${id}`);
}

export function updateIncident(id: string, patch: UpdateIncidentRequest): Promise<IncidentResponse> {
  return request<IncidentResponse>(`/api/v1/incidents/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  });
}

export function acknowledgeIncident(id: string): Promise<IncidentResponse> {
  return updateIncident(id, { status: 'INVESTIGATING' });
}

export function resolveIncident(id: string, tenantId: string): Promise<IncidentResponse> {
  const params = new URLSearchParams({ tenantId });
  return request<IncidentResponse>(`/api/v1/commands/incidents/${id}/resolve?${params.toString()}`, {
    method: 'POST',
  });
}

export function getAnalyticsDashboard(
  tenantId: string,
  range?: { from?: string; to?: string }
): Promise<AnalyticsDashboardResponse> {
  const params = new URLSearchParams();
  if (range?.from) params.set('from', range.from);
  if (range?.to) params.set('to', range.to);
  const query = params.toString();
  return request<AnalyticsDashboardResponse>(
    `/api/v1/tenants/${tenantId}/analytics/dashboard${query ? `?${query}` : ''}`
  );
}

export function registerPushToken(
  userId: string,
  tenantId: string,
  expoPushToken: string,
  platform: string
): Promise<PushDeviceToken> {
  return request<PushDeviceToken>(`/api/v1/users/${userId}/push-token`, {
    method: 'POST',
    body: JSON.stringify({ tenantId, expoPushToken, platform }),
  });
}

// ── War rooms ────────────────────────────────────────────────────────────

/** Idempotent: opens a war room for this incident, or returns the existing ACTIVE one. */
export function openWarRoom(tenantId: string, incidentId: string): Promise<WarRoomResponse> {
  return request<WarRoomResponse>(
    `/api/v1/tenants/${tenantId}/war-rooms/incidents/${incidentId}`,
    { method: 'POST' }
  );
}

export function joinWarRoom(
  tenantId: string,
  warRoomId: string,
  userId: string,
  role: ParticipantRole
): Promise<WarRoomResponse> {
  return request<WarRoomResponse>(`/api/v1/tenants/${tenantId}/war-rooms/${warRoomId}/participants`, {
    method: 'POST',
    body: JSON.stringify({ userId, role }),
  });
}

export function leaveWarRoom(tenantId: string, warRoomId: string, userId: string): Promise<WarRoomResponse> {
  return request<WarRoomResponse>(
    `/api/v1/tenants/${tenantId}/war-rooms/${warRoomId}/participants/${userId}`,
    { method: 'DELETE' }
  );
}

// ── Cost tracking ────────────────────────────────────────────────────────

export function getCostSummary(tenantId: string, incidentId: string): Promise<CostSummaryResponse> {
  return request<CostSummaryResponse>(`/api/v1/tenants/${tenantId}/incidents/${incidentId}/cost`);
}

export function addCostEntry(
  tenantId: string,
  incidentId: string,
  description: string,
  amountCents: number,
  recordedBy?: string
): Promise<CostEntryResponse> {
  return request<CostEntryResponse>(`/api/v1/tenants/${tenantId}/incidents/${incidentId}/cost/entries`, {
    method: 'POST',
    body: JSON.stringify({ description, amountCents, recordedBy }),
  });
}

export function getCostEstimate(tenantId: string, incidentId: string): Promise<{ estimatedCents: number }> {
  return request<{ estimatedCents: number }>(`/api/v1/tenants/${tenantId}/incidents/${incidentId}/cost/estimate`);
}

export function recordCostEstimate(
  tenantId: string,
  incidentId: string,
  recordedBy?: string
): Promise<CostEntryResponse> {
  return request<CostEntryResponse>(`/api/v1/tenants/${tenantId}/incidents/${incidentId}/cost/estimate/record`, {
    method: 'POST',
    body: JSON.stringify({ recordedBy }),
  });
}

// ── Escalation history ──────────────────────────────────────────────────

export function getEscalationHistory(
  tenantId: string,
  incidentId: string
): Promise<EscalationExecutionResponse[]> {
  return request<EscalationExecutionResponse[]>(
    `/api/v1/tenants/${tenantId}/incidents/${incidentId}/escalations`
  );
}

// ── Alert suppression ───────────────────────────────────────────────────

export function listSuppressionRules(tenantId: string): Promise<AlertSuppressionRuleResponse[]> {
  return request<AlertSuppressionRuleResponse[]>(`/api/v1/tenants/${tenantId}/alert-suppression`);
}

export function muteAlert(
  tenantId: string,
  muteRequest: MuteAlertRequest
): Promise<AlertSuppressionRuleResponse> {
  return request<AlertSuppressionRuleResponse>(`/api/v1/tenants/${tenantId}/alert-suppression`, {
    method: 'POST',
    body: JSON.stringify(muteRequest),
  });
}

export function unmuteAlert(tenantId: string, ruleId: string): Promise<void> {
  return request<void>(`/api/v1/tenants/${tenantId}/alert-suppression/${ruleId}`, {
    method: 'DELETE',
  });
}
