package train;

import java.util.*;

// #Модель: классы данных отделены от интерфейса и алгоритма поиска.
public final class Model {
    private Model() {}

    // #Участок: двунаправленный однопутный ресурс с длиной в километрах.
    public record Edge(String a, String b, double km, boolean enabled) {
        public String key() { return Model.key(a, b); }
    }

    // #Поезд: исходные характеристики; длина состава вычисляется из количества единиц.
    public record Train(int id, String from, String to, double speed, int cars, int tanks) {}
    // #Занятость: ресурс занят от входа головы до освобождения хвостом.
    public record Interval(int trainId, String resource, boolean edge, double start, double end) {}
    // #Ожидание: остановка головы в промежуточном узле.
    public record Dwell(String node, double start, double end) {}
    // #Движение: время прохождения одного участка для анимации.
    public record Segment(String from, String to, double start, double end) {}
    // #Конфликт: пересечение интервалов разных поездов на одном ресурсе.
    public record Conflict(String resource, int first, int second, double start, double end) {}
    // #Расписание: полный результат для одного поезда, включая хвост и остановки.
    public record Schedule(Train train, List<String> route, double start, double arrival,
                           double complete, List<Interval> intervals, List<Dwell> dwells,
                           List<Segment> segments) {}
    // #План: сочетание расписаний и общий срок освобождения сети.
    public record Plan(List<Schedule> schedules, double total) {}
    public record SearchResult(List<Plan> variants, int combinations, int bounded) {}

    // #Настройки: снимок параметров расчёта; фоновой задаче передаются отдельные данные.
    public record Config(List<Edge> edges, List<Train> trains, double carLength,
                         double tankLength, double gap, boolean withCars, boolean stops) {
        public Config {
            edges = List.copyOf(edges);
            trains = List.copyOf(trains);
        }
        public double length(Train train) {
            return withCars ? train.cars() * carLength + train.tanks() * tankLength : 0;
        }
    }

    public static final List<String> NODES = List.of("A", "D", "B", "C", "O", "E", "S", "F", "G");

    // #ИсходныеДанные: та же сеть и те же четыре состава, что в JavaScript-проекте.
    public static Config defaults() {
        String[] links = {"A-E", "E-O", "O-S", "S-C", "D-F", "F-O", "O-G", "G-B", "E-F", "E-G", "F-S", "G-S"};
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < links.length; i++) {
            String[] pair = links[i].split("-");
            edges.add(new Edge(pair[0], pair[1], Set.of(0, 3, 4, 7).contains(i) ? 23 : 2, true));
        }
        return new Config(edges, List.of(new Train(1, "D", "B", 50, 10, 2),
            new Train(2, "C", "D", 45, 15, 5), new Train(3, "A", "C", 30, 0, 30),
            new Train(4, "B", "A", 40, 20, 10)), 20, 20, 1, true, true);
    }

    // #ОбщийКлюч: E-F и F-E обозначают один ресурс независимо от направления.
    public static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + "-" + b : b + "-" + a;
    }
}
