import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitoringScheduler {
    private final ScheduledExecutorService exec;
    private final MonitoringStore store;
    private final MonitoringAnalyzer analyzer;

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private volatile boolean nyseMode = false;

    private volatile ZonedDateTime nextRunNy;
    private volatile ZonedDateTime lastRunNy;
    private volatile String lastError;

    public MonitoringScheduler(MonitoringStore store, MonitoringAnalyzer analyzer) {
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("monitoring-scheduler");
            return t;
        });
        this.store = store;
        this.analyzer = analyzer;
    }

    public void startNyseWeekdayEvery2Hours() {
        nyseMode = true;
        scheduleNextNyseRun();
    }

    public ZonedDateTime getNextRunNy() {
        return nextRunNy;
    }

    public ZonedDateTime getLastRunNy() {
        return lastRunNy;
    }

    public String getLastError() {
        return lastError;
    }

    public void startTwiceDaily(int hour1, int hour2) {
        scheduleAt(hour1);
        scheduleAt(hour2);
    }

    public void triggerNowAsync() {
        exec.execute(this::refreshAllSafe);
    }

    private void scheduleAt(int hour) {
        long initialDelayMs = computeDelayToNext(hour);
        long periodMs = TimeUnit.DAYS.toMillis(1);
        exec.scheduleAtFixedRate(this::refreshAllSafe, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private void refreshAllSafe() {
        lastError = null;
        lastRunNy = ZonedDateTime.now(NY);
        System.out.println("[MonitoringScheduler] refresh started at " + lastRunNy);
        try {
            List<String> tickers = store.loadTickers();
            for (String t : tickers) {
                try {
                    MonitoringSnapshot s = analyzer.analyze(t);
                    store.saveSnapshot(s);
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ignore) {
            lastError = ignore.getMessage();
            System.out.println("[MonitoringScheduler] refresh error: " + ignore.getMessage());
        }
        System.out.println("[MonitoringScheduler] refresh finished");
    }

    private void runNyseAndReschedule() {
        refreshAllSafe();
        scheduleNextNyseRun();
    }

    private void scheduleNextNyseRun() {
        if (!nyseMode) return;
        try {
            ZonedDateTime now = ZonedDateTime.now(NY);
            ZonedDateTime next = nextNyseRunTime(now);
            nextRunNy = next;
            long delayMs = Math.max(0L, Duration.between(now, next).toMillis());
            exec.schedule(this::runNyseAndReschedule, delayMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignore) {
            lastError = ignore.getMessage();
            // Fallback: retry in 10 minutes
            exec.schedule(this::runNyseAndReschedule, TimeUnit.MINUTES.toMillis(10), TimeUnit.MILLISECONDS);
        }
    }

    private static ZonedDateTime nextNyseRunTime(ZonedDateTime nowNy) {
        // NYSE regular hours: 09:30–16:00 ET. We run at 09:30, 11:30, 13:30, 15:30.
        List<LocalTime> slots = List.of(
                LocalTime.of(9, 30),
                LocalTime.of(11, 30),
                LocalTime.of(13, 30),
                LocalTime.of(15, 30)
        );

        ZonedDateTime candidate = null;
        LocalDate date = nowNy.toLocalDate();

        for (int i = 0; i < 14; i++) { // search up to 2 weeks ahead
            DayOfWeek dow = date.getDayOfWeek();
            boolean weekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
            if (weekday) {
                List<ZonedDateTime> candidates = new ArrayList<>();
                for (LocalTime t : slots) {
                    candidates.add(ZonedDateTime.of(date, t, NY));
                }
                candidates.sort(Comparator.naturalOrder());
                for (ZonedDateTime c : candidates) {
                    if (c.isAfter(nowNy)) {
                        candidate = c;
                        break;
                    }
                }
                if (candidate != null) break;
            }
            date = date.plusDays(1);
        }

        if (candidate == null) {
            // Should never happen; default to next day at 09:30
            LocalDate next = nowNy.toLocalDate().plusDays(1);
            return ZonedDateTime.of(next, LocalTime.of(9, 30), NY);
        }
        return candidate;
    }

    private static long computeDelayToNext(int hour) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }
}
