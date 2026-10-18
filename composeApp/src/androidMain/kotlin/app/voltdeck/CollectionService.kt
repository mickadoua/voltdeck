package app.voltdeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.voltdeck.core.Metric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Garde le processus en vie écran éteint pour que [VoltdeckRuntime] continue de lire les équipements et d'enregistrer.
 * La collecte elle-même est dans le moteur : ce service ne fait qu'afficher la notification obligatoire.
 */
class CollectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BackgroundPrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Lecture des équipements en cours"), type)
        } catch (e: SecurityException) {
            BackgroundPrefs.recordServiceFailure(this, BootAttempt(Clock.System.now(), BootOutcome.REFUSED, e.javaClass.simpleName))
            stopSelf()
            return START_NOT_STICKY
        }
        val runtime = (application as VoltdeckApplication).runtime
        watching?.cancel()
        watching = scope.launch { watch(runtime) }
        return START_STICKY
    }

    @OptIn(FlowPreview::class)
    private suspend fun watch(runtime: VoltdeckRuntime) {
        runtime.latest.sample(30.seconds).collect { latest ->
            val soc = latest.values.firstNotNullOfOrNull { r -> r.samples.firstOrNull { it.metric == Metric.SOC }?.value }
            val pv = latest.values.mapNotNull { r -> r.samples.firstOrNull { it.metric == Metric.PV_POWER }?.value }.takeIf { it.isNotEmpty() }?.sum()
            val text = listOfNotNull(soc?.let { "Batterie ${frDecimal(it, 0)} %" }, pv?.let { "Solaire ${frDecimal(it, 0)} W" })
                .joinToString(" · ").ifEmpty { "En attente des équipements" }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "Collecte des mesures", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Indique que Voltdeck lit vos équipements en arrière-plan."
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CollectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Voltdeck lit vos équipements")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "collecte"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.voltdeck.STOP_COLLECTION"

        fun intent(context: Context) = Intent(context, CollectionService::class.java)
    }
}

internal object BackgroundPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("voltdeck-background", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("enabled", value).apply()
    }

    fun restartAfterBoot(context: Context) = prefs(context).getBoolean("restartAfterBoot", false)

    fun setRestartAfterBoot(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("restartAfterBoot", value).apply()
    }

    fun lastBootAttempt(context: Context) = read(context, "boot")

    fun recordBootAttempt(context: Context, attempt: BootAttempt) = write(context, "boot", attempt)

    fun lastServiceFailure(context: Context) = read(context, "serviceFailure")

    fun recordServiceFailure(context: Context, attempt: BootAttempt) = write(context, "serviceFailure", attempt)

    private fun write(context: Context, key: String, attempt: BootAttempt) {
        prefs(context).edit()
            .putLong("${key}At", attempt.at.toEpochMilliseconds())
            .putString("${key}Outcome", attempt.outcome.name)
            .putString("${key}Detail", attempt.detail)
            .apply()
    }

    private fun read(context: Context, key: String): BootAttempt? {
        val p = prefs(context)
        val outcome = p.getString("${key}Outcome", null)?.let { name -> BootOutcome.entries.firstOrNull { it.name == name } } ?: return null
        return BootAttempt(Instant.fromEpochMilliseconds(p.getLong("${key}At", 0)), outcome, p.getString("${key}Detail", null))
    }
}

internal class AndroidBackgroundCollection(private val context: Context, private val canConnect: () -> Boolean) : BackgroundCollection {
    override val supported = true

    override var enabled: Boolean
        get() = BackgroundPrefs.enabled(context)
        set(value) = BackgroundPrefs.setEnabled(context, value)

    override var restartAfterBoot: Boolean
        get() = BackgroundPrefs.restartAfterBoot(context)
        set(value) = BackgroundPrefs.setRestartAfterBoot(context, value)

    override val lastBootAttempt get() = BackgroundPrefs.lastBootAttempt(context)

    override val lastServiceFailure get() = BackgroundPrefs.lastServiceFailure(context)

    override fun sync(wanted: Boolean) {
        val intent = CollectionService.intent(context)
        if (wanted && enabled && canConnect()) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Démarrage refusé par le système (application en arrière-plan) : il sera retenté au prochain affichage.
            }
        } else {
            context.stopService(intent)
        }
    }
}
