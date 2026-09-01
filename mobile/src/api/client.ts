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
