package cloud.huyenvu.tv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    companion object { const val EXTRA_ID = "reminder_id" }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Khởi động lại máy / cập nhật app: các báo thức đã bị hệ thống xóa -> đặt lại từ danh sách đã lưu
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Reminders.restore(context)
                return
            }
        }
        if (intent.hasExtra(EXTRA_ID)) Reminders.consume(context, intent.getIntExtra(EXTRA_ID, 0))

        val channelName = intent.getStringExtra("channel_name") ?: "chương trình đã hẹn"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("watch_reminders", "Nhắc lịch xem", NotificationManager.IMPORTANCE_HIGH)
        )
        val openUrl = intent.getStringExtra(MainActivity.EXTRA_OPEN_URL)
        val open = PendingIntent.getActivity(
            context, (openUrl ?: channelName).hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_URL, openUrl)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
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

/** Lịch nhắc được lưu bền vững để đặt lại sau khi khởi động máy. */
object Reminders {
    private const val PREFS = "tv_dr_vu_reminders"
    private const val KEY = "items"

    private data class Item(val id: Int, val at: Long, val name: String, val url: String)

    fun schedule(context: Context, name: String, url: String, triggerAt: Long) {
        val item = Item((url + triggerAt).hashCode(), triggerAt, name.replace('\t', ' '), url)
        save(context, load(context).filterNot { it.id == item.id } + item)
        setAlarm(context, item)
    }

    fun consume(context: Context, id: Int) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        val pending = load(context).filter { it.at > now }
        save(context, pending)
        pending.forEach { setAlarm(context, it) }
    }

    private fun setAlarm(context: Context, item: Item) {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_ID, item.id)
            .putExtra("channel_name", item.name)
            .putExtra(MainActivity.EXTRA_OPEN_URL, item.url)
        val pending = PendingIntent.getBroadcast(
            context, item.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pending)
    }

    private fun load(context: Context): List<Item> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull { line ->
                val p = line.split('\t', limit = 4)
                if (p.size < 4) return@mapNotNull null
                val id = p[0].toIntOrNull() ?: return@mapNotNull null
                val at = p[1].toLongOrNull() ?: return@mapNotNull null
                Item(id, at, p[2], p[3])
            }

    private fun save(context: Context, items: List<Item>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, items.map { "${it.id}\t${it.at}\t${it.name}\t${it.url}" }.toSet()).apply()
    }
}
