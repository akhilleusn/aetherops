import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';
import { registerPushToken as registerPushTokenApi } from '../api/client';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

/**
 * Requests notification permission, fetches an Expo push token, and registers it with the
 * backend. Silently no-ops (with a console warning) on simulators/emulators or when permission
 * is denied — a paging app should never crash a user's session over a push token failure.
 */
export async function setupPushNotifications(userId: string, tenantId: string): Promise<void> {
  if (!Device.isDevice) {
    console.warn('[push] Skipping push registration — physical device required (simulator/emulator detected).');
    return;
  }

  try {
    const { status: existingStatus } = await Notifications.getPermissionsAsync();
    let finalStatus = existingStatus;
    if (existingStatus !== 'granted') {
      const { status } = await Notifications.requestPermissionsAsync();
      finalStatus = status;
    }
    if (finalStatus !== 'granted') {
      console.warn('[push] Notification permission was not granted — paging alerts will be missed.');
      return;
    }

    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync('paging-alerts', {
        name: 'Paging Alerts',
        importance: Notifications.AndroidImportance.MAX,
        sound: 'default',
      });
    }

    const { data: expoPushToken } = await Notifications.getExpoPushTokenAsync();
    await registerPushTokenApi(userId, tenantId, expoPushToken, Platform.OS);
  } catch (error) {
    console.warn('[push] Failed to register push token', error);
  }
}

/** Registers a listener that re-registers with the backend if the Expo push token rotates. */
export function watchPushTokenChanges(userId: string, tenantId: string): () => void {
  const subscription = Notifications.addPushTokenListener(({ data }) => {
    registerPushTokenApi(userId, tenantId, data, Platform.OS).catch((error) =>
      console.warn('[push] Failed to re-register rotated push token', error)
    );
  });
  return () => subscription.remove();
}
