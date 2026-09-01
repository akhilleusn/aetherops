export const colors = {
  background: '#0F172A',
  surface: '#1E293B',
  surfaceAlt: '#273449',
  border: '#334155',
  textPrimary: '#F1F5F9',
  textSecondary: '#94A3B8',
  accent: '#38BDF8',
  danger: '#F87171',
  warning: '#FBBF24',
  success: '#4ADE80',
  white: '#FFFFFF',
};

export const severityColors: Record<string, string> = {
  CRITICAL: '#F87171',
  HIGH: '#FB923C',
  MEDIUM: '#FBBF24',
  LOW: '#4ADE80',
};

export const statusColors: Record<string, string> = {
  OPEN: '#F87171',
  INVESTIGATING: '#FBBF24',
  RESOLVED: '#4ADE80',
  CLOSED: '#94A3B8',
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
};

export const radius = {
  sm: 6,
  md: 12,
  lg: 16,
};

export const typography = {
  title: { fontSize: 22, fontWeight: '700' as const, color: colors.textPrimary },
  subtitle: { fontSize: 16, fontWeight: '600' as const, color: colors.textPrimary },
  body: { fontSize: 14, fontWeight: '400' as const, color: colors.textPrimary },
  caption: { fontSize: 12, fontWeight: '400' as const, color: colors.textSecondary },
};
