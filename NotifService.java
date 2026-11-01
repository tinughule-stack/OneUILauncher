package com.oneui.launcher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.HashMap;
import java.util.Map;

public class NotifService extends NotificationListenerService {
    static volatile Map<String, Integer> counts = new HashMap<>();
    static volatile Runnable onChange;

    @Override public void onListenerConnected() { refresh(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { refresh(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { refresh(); }

    private void refresh() {
        Map<String, Integer> m = new HashMap<>();
        try {
            StatusBarNotification[] arr = getActiveNotifications();
            if (arr != null) {
                for (StatusBarNotification n : arr) {
                    if (n.isOngoing()) continue;
                    if ((n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    String p = n.getPackageName();
                    Integer c = m.get(p);
                    m.put(p, c == null ? 1 : c + 1);
                }
            }
        } catch (Exception ignored) {}
        counts = m;
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
