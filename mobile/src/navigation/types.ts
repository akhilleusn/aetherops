import type { NavigatorScreenParams } from '@react-navigation/native';

export type MainTabParamList = {
  Incidents: NavigatorScreenParams<IncidentStackParamList>;
  OnCall: undefined;
  Analytics: undefined;
};

export type IncidentStackParamList = {
  IncidentList: undefined;
  IncidentDetail: { incidentId: string };
};

export type RootStackParamList = {
  Login: undefined;
  Main: NavigatorScreenParams<MainTabParamList>;
};
