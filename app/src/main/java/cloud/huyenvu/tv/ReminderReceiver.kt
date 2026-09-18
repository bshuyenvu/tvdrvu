package cloud.huyenvu.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelName = intent.getStringExtra("channel_name") ?: "chương trình đã hẹn"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("watch_reminders", "Nhắc lịch xem", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            channelName.hashCode(),
            NotificationCompat.Builder(context, "watch_reminders")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Đến giờ xem TV")
                .setContentText("$channelName đang chờ bạn")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }
}
