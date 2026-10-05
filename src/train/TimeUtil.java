package train;

import java.util.Locale;

// #Время: перевод пользовательского времени в секунды и обратно.
public final class TimeUtil {
    private TimeUtil() {}

    // #РазборВремени: допускает секунды или ЧЧ:ММ:СС с дробной частью секунд.
    public static double parse(String text) {
        String[] parts = text.trim().replace(',', '.').split(":", -1);
        double result;
        if (parts.length == 1 && !parts[0].isBlank()) result = Double.parseDouble(parts[0]);
        else if (parts.length == 3) {
            int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]);
            double s = Double.parseDouble(parts[2]);
            if (h < 0 || m < 0 || m >= 60 || s < 0 || s >= 60) throw new IllegalArgumentException("Время: ЧЧ:ММ:СС, минуты и секунды меньше 60.");
            result = h * 3600.0 + m * 60.0 + s;
        } else throw new IllegalArgumentException("Введите секунды или ЧЧ:ММ:СС.");
        if (!Double.isFinite(result) || result < 0) throw new IllegalArgumentException("Время должно быть конечным и неотрицательным.");
        return result;
    }

    // #ВыводВремени: округление только для экрана; алгоритм хранит double без округления.
    public static String format(double seconds) {
        long value = Math.max(0, Math.round(seconds));
        return String.format(Locale.ROOT, "%02d:%02d:%02d", value / 3600, value / 60 % 60, value % 60);
    }

    // #ТочноеВремя: дробные секунды нужны при ручной повторной проверке найденного плана.
    public static String exact(double seconds) {
        return String.format(Locale.ROOT, "%.9f", seconds);
    }
}
