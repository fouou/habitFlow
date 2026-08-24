package com.fouu.habitflow.util;

import java.util.Calendar;
import java.util.Date;

/**
 * Centralised logic for habit frequencies:
 *  - DAILY   : every day counts as a target day
 *  - WEEKDAYS: Mon–Fri count, weekends are skipped (not a target, not a lapse)
 *  - WEEKLY  : a whole week (Mon–Sun) counts as one target; completing it at
 *              least once marks the week done
 */
public final class FrequencyUtil {

    public static final String DAILY = "DAILY";
    public static final String WEEKDAYS = "WEEKDAYS";
    public static final String WEEKLY = "WEEKLY";

    private FrequencyUtil() {}

    /** Whether the given day is a "target day" that the user should check in on. */
    public static boolean isTargetDay(String frequency, Date day) {
        if (DAILY.equals(frequency)) return true;
        if (WEEKLY.equals(frequency)) return true; // for WEEKLY every day is part of a week
        if (WEEKDAYS.equals(frequency)) {
            int dow = getDayOfWeek(day);
            return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
        }
        return true;
    }

    /** For WEEKLY frequency, the Monday (midnight) that starts the week of `day`. */
    public static Date startOfWeek(Date day) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(day);
        zeroTime(cal);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int delta = (dow - Calendar.MONDAY + 7) % 7;
        cal.add(Calendar.DAY_OF_YEAR, -delta);
        return cal.getTime();
    }

    public static int getDayOfWeek(Date day) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(day);
        return cal.get(Calendar.DAY_OF_WEEK);
    }

    public static void zeroTime(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    public static Date addDays(Date day, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(day);
        cal.add(Calendar.DAY_OF_YEAR, days);
        return cal.getTime();
    }
}
