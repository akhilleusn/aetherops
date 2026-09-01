export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  error: string | null;
  timestamp: string;
}

export interface AuthResponse {
  token: string;
  expiresAt: string;
  userId: string;
  tenantId: string;
  email: string;
  role: string;
  requiresTwoFactor: boolean | null;
  partialToken: string | null;
}

export interface OnCallStatus {
  serviceId: string;
  rotationName: string;
  currentOnCallMasked: string | null;
}

export type IncidentStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED' | 'CLOSED';

export type IncidentSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export interface IncidentResponse {
  id: string;
  tenantId: string;
  projectId: string | null;
  title: string;
  description: string | null;
  status: IncidentStatus;
  severity: IncidentSeverity;
  serviceId: string;
  environment: string | null;
  fingerprint: string | null;
  eventCount: number;
  firstSeenAt: string;
  lastSeenAt: string;
  latestEventId: string | null;
  createdAt: string;
  updatedAt: string;
  resolvedAt: string | null;
  tags: Record<string, string>;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export interface UpdateIncidentRequest {
  title?: string;
  description?: string;
  status?: IncidentStatus;
  severity?: IncidentSeverity;
  tags?: Record<string, string>;
}

export interface DailyCount {
  date: string;
  count: number;
}

export interface TeamPerformance {
  team: string;
  incidentCount: number;
  mttrMinutes: number | null;
}

export interface AnalyticsDashboardResponse {
  tenantId: string;
  periodStart: string;
  periodEnd: string;
  totalIncidents: number;
  resolvedCount: number;
  mttrMinutes: number | null;
  mtbfMinutes: number | null;
  trend: DailyCount[];
  teamPerformance: TeamPerformance[];
}

export interface PushDeviceToken {
  id: string;
  userId: string;
  tenantId: string;
  expoPushToken: string;
  platform: string | null;
  createdAt: string;
}
