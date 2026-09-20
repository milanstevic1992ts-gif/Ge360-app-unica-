package it.edilmilan.ge360.giornata

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceStore
import it.edilmilan.ge360.spese.FinanceType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object SlobodanWeeklyPay {
    const val PERSON_NAME = "Slobodan"
    const val WEEKLY_AMOUNT = 550.0
    const val CATEGORY = "Collaboratori"
    private const val PAYMENT_PREFIX = "weekly-pay"

    fun ensurePersonnel(dailyStore: DailyStore): PersonnelRecord {
        dailyStore.ensurePersonnelCatalog()
        return dailyStore.personnelByName(PERSON_NAME) ?: PersonnelRecord(
            name = PERSON_NAME,
            role = "Collaboratore",
            note = "Compenso settimanale €550"
        ).also { dailyStore.savePersonnel(it) }
    }

    fun weekStart(date: LocalDate = LocalDate.now()): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekEnd(date: LocalDate = LocalDate.now()): LocalDate = weekStart(date).plusDays(6)

    fun paymentId(personnel: PersonnelRecord, weekStart: LocalDate = weekStart()): String =
        "$PAYMENT_PREFIX:${personnel.ge360Id}:$weekStart"

    fun paymentForWeek(
        financeStore: FinanceStore,
        personnel: PersonnelRecord,
        weekStart: LocalDate = weekStart()
    ): FinanceRecord? = financeStore.getByGe360Id(paymentId(personnel, weekStart))

    fun isPaid(
        financeStore: FinanceStore,
        personnel: PersonnelRecord,
        weekStart: LocalDate = weekStart()
    ): Boolean = paymentForWeek(financeStore, personnel, weekStart)?.amount?.let { it > 0.0 } == true

    fun savePayment(
        financeStore: FinanceStore,
        personnel: PersonnelRecord,
        amount: Double,
        note: String = "",
        paidDate: LocalDate = LocalDate.now(),
        weekStart: LocalDate = weekStart(paidDate)
    ): FinanceRecord {
        require(amount > 0.0) { "Importo non valido" }
        val existing = paymentForWeek(financeStore, personnel, weekStart)
        val record = existing ?: FinanceRecord(
            ge360Id = paymentId(personnel, weekStart),
            type = FinanceType.EXPENSE,
            scope = FinanceScope.BUSINESS,
            category = CATEGORY
        )
        record.type = FinanceType.EXPENSE
        record.scope = FinanceScope.BUSINESS
        record.amount = amount
        record.category = CATEGORY
        record.date = paidDate.toString()
        record.note = buildString {
            append("${personnel.name} · paga settimanale · ${periodLabel(weekStart)}")
            if (note.isNotBlank()) append(" · ${note.trim()}")
        }
        record.jobsiteId = ""
        record.jobsiteCode = ""
        record.jobsiteTitle = ""
        financeStore.save(record)
        return record
    }

    fun history(financeStore: FinanceStore, personnel: PersonnelRecord, limit: Int = 12): List<FinanceRecord> {
        val prefix = "$PAYMENT_PREFIX:${personnel.ge360Id}:"
        return financeStore.all()
            .filter { it.ge360Id.startsWith(prefix) && it.type == FinanceType.EXPENSE }
            .sortedWith(compareByDescending<FinanceRecord> { it.date }.thenByDescending { it.updatedAt })
            .take(limit)
    }

    fun periodLabel(start: LocalDate = weekStart()): String {
        val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.ITALY)
        return "${start.format(formatter)} – ${start.plusDays(6).format(formatter)}"
    }
}

object CollaboratorPaymentReminderScheduler {
    const val ACTION_REMINDER = "it.edilmilan.ge360.giornata.action.SLOBODAN_WEEKLY_PAY_REMINDER"
    private const val REQUEST_CODE = 55019

    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            Intent(app, WeeklyPaymentReminderReceiver::class.java).setAction(ACTION_REMINDER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = ZonedDateTime.now()
        var next = now
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            .withHour(19)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            next.toInstant().toEpochMilli(),
            pending
        )
    }
}

class WeeklyPaymentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            CollaboratorPaymentReminderScheduler.schedule(context)
            return
        }

        if (action != CollaboratorPaymentReminderScheduler.ACTION_REMINDER) return

        val dailyStore = DailyStore(context.applicationContext)
        val financeStore = FinanceStore(context.applicationContext)
        try {
            val personnel = SlobodanWeeklyPay.ensurePersonnel(dailyStore)
            if (!SlobodanWeeklyPay.isPaid(financeStore, personnel)) {
                showNotification(context)
            }
        } finally {
            dailyStore.close()
            financeStore.close()
        }
        CollaboratorPaymentReminderScheduler.schedule(context)
    }

    private fun showNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Pagamenti collaboratori",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Promemoria settimanali per pagamenti collaboratori"
                }
            )
        }

        val openIntent = PendingIntent.getActivity(
            context,
            55020,
            Intent(context, CollaboratorsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val amount = String.format(Locale.ITALY, "%.0f €", SlobodanWeeklyPay.WEEKLY_AMOUNT)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_payment_reminder)
                .setContentTitle("Slobodan non risulta pagato")
                .setContentText("Settimana ${SlobodanWeeklyPay.periodLabel()}: registra il pagamento di $amount.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Non risulta registrato il pagamento settimanale di Slobodan. Apri Collaboratori e premi PAGATO per registrare l'importo."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "collaborator_weekly_pay"
        private const val NOTIFICATION_ID = 550
    }
}
