package com.fouu.habitflow.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * DateUtil - common date/time formatting helpers.
 */
public class DateUtil {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd, yyyy", Locale.US);
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat WEEKDAY_FORMAT = new SimpleDateFormat("EEE", Locale.US);

    public static String formatDate(Date date) {
        return DATE_FORMAT.format(date);
    }

    public static String formatTime(long millisFromMidnight) {
        int hours = (int) (millisFromMidnight / 3600000);
        int mins = (int) ((millisFromMidnight % 3600000) / 60000);
        return String.format(Locale.US, "%02d:%02d", hours, mins);
    }

    public static String getWeekdayShort(Date date) {
        return WEEKDAY_FORMAT.format(date);
    }

    public static Date getStartOfWeek(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    public static Date getEndOfWeek(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(getStartOfWeek(date));
        cal.add(Calendar.DAY_OF_YEAR, 6);
        return cal.getTime();
    }

    public static Date getDaysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -days);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    public static boolean isSameDay(Date d1, Date d2) {
        Calendar c1 = Calendar.getInstance();
        c1.setTime(d1);
        Calendar c2 = Calendar.getInstance();
        c2.setTime(d2);
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }
}
