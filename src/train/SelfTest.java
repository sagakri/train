package train;

import java.util.*;
import static train.Model.*;
import static train.Scheduling.*;

// #РегрессионныеПроверки: проверяют физику, безопасность и оптимум независимо от интерфейса.
public final class SelfTest {
    private static int checks;
    private SelfTest() {}

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-6, message + ": " + actual + " != " + expected);
    }
    private static Config config(Config base, boolean cars, boolean stops) {
        return new Config(base.edges(), base.trains(), base.carLength(), base.tankLength(), base.gap(), cars, stops);
    }

    public static void main(String[] args) {
        Config original = Model.defaults();
        Network network = new Network(original);
        int[] expected = {9, 8, 9, 8};
        for (int i = 0; i < 4; i++) {
            Train t = original.trains().get(i);
            check(network.paths(t.from(), t.to()).size() == expected[i], "Количество маршрутов №" + t.id());
        }
        near(network.length(List.of("A", "E", "O", "S", "C")), 50, "Кратчайший путь №3");

        // #ВсеРежимы: проверка полного поиска с остановками/без и с длиной/без длины.
        for (boolean stops : new boolean[]{false, true}) for (boolean cars : new boolean[]{true, false}) {
            Config cfg = config(original, cars, stops);
            SearchResult result = Optimizer.search(cfg, p -> {});
            check(result.combinations() == 5184, "Все сочетания рассмотрены");
            check(result.variants().size() == 6, "Шесть лучших вариантов");
            near(result.variants().get(0).total(), cars ? 6072 : 6000, "Контрольный оптимум");
            near(result.lowerBound(), cars ? 6072 : 6000, "Нижняя граница исходной задачи");
            for (int i = 1; i < result.variants().size(); i++) check(
                PlanQuality.score(cfg, result.variants().get(i - 1)).compareTo(PlanQuality.score(cfg, result.variants().get(i))) <= 0,
                "Варианты отсортированы по всем критериям");
            if (cars && stops) near(PlanQuality.waiting(result.variants().get(0)), 43, "Минимальное ожидание при исходном оптимуме");
            for (Plan plan : result.variants()) {
                check(conflicts(plan.schedules(), cfg.gap()).isEmpty(), "Найденный план безопасен");
                for (Schedule schedule : plan.schedules()) {
                    Schedule rebuilt = withWaits(build(cfg, schedule.train(), schedule.route(), schedule.start()), schedule.dwells());
                    near(rebuilt.complete(), schedule.complete(), "Повторный расчёт хвоста");
                    for (int i = 0; i < rebuilt.intervals().size(); i++) {
                        near(rebuilt.intervals().get(i).start(), schedule.intervals().get(i).start(), "Начало интервала");
                        near(rebuilt.intervals().get(i).end(), schedule.intervals().get(i).end(), "Конец интервала");
                    }
                }
            }
            System.out.println("Режим: stops=" + stops + ", cars=" + cars + "; оптимум=" + result.variants().get(0).total());
        }

        // #Однопутность: совпадающие, встречные движения и точный защитный интервал.
        for (boolean cars : new boolean[]{true, false}) {
            Config cfg = config(original, cars, false);
            Train a = cfg.trains().get(0), b = new Train(99, "F", "E", a.speed(), a.cars(), a.tanks());
            Schedule first = build(cfg, a, List.of("E", "F"), 0);
            check(conflicts(List.of(first, build(cfg, b, List.of("E", "F"), 1)), 1).stream().anyMatch(c -> c.resource().equals("E-F")), "Попутный конфликт");
            check(conflicts(List.of(first, build(cfg, b, List.of("F", "E"), 1)), 1).stream().anyMatch(c -> c.resource().equals("E-F")), "Встречный конфликт");
            check(!conflicts(List.of(first, build(cfg, b, List.of("F", "E"), first.complete() + 0.5)), 1).isEmpty(), "Защитный интервал");
            check(conflicts(List.of(first, build(cfg, b, List.of("F", "E"), first.complete() + 1)), 1).isEmpty(), "Вход после освобождения");
            if (!cars) near(first.arrival(), first.complete(), "Нулевая задержка хвоста");
        }
        Train longTrain = new Train(1, "E", "S", 36, 150, 0);
        Schedule longTail = withWaits(build(original, longTrain, List.of("E", "O", "S"), 0), List.of(new Dwell("O", 200, 300)));
        near(longTail.intervals().get(0).end(), 400, "Остановка продлевает занятие исходного узла");
        near(longTail.intervals().get(1).end(), 600, "Остановка продлевает занятие участка");
        near(longTail.complete(), 800, "Выход длинного хвоста");

        // #МалыйОракул: полный перебор целочисленных отправлений для независимой сверки.
        List<Schedule> small = List.of(synthetic(1, 8, new String[]{"X", "Y"}, new double[]{0, 5}, new double[]{3, 8}),
            synthetic(2, 7, new String[]{"Y", "X"}, new double[]{0, 4}, new double[]{2, 7}),
            synthetic(3, 5, new String[]{"X"}, new double[]{1}, new double[]{5}));
        for (double gap : new double[]{0, 1, 2}) {
            double oracle = Double.POSITIVE_INFINITY;
            double oracleWait = Double.POSITIVE_INFINITY;
            for (int a = 0; a <= 25; a++) for (int b = 0; b <= 25; b++) for (int c = 0; c <= 25; c++) {
                List<Schedule> schedules = List.of(shift(small.get(0), a), shift(small.get(1), b), shift(small.get(2), c));
                if (conflicts(schedules, gap).isEmpty()) {
                    double total = plan(schedules).total(), waiting = a + b + c;
                    if (total < oracle || total == oracle && waiting < oracleWait) { oracle = total; oracleWait = waiting; }
                }
            }
            near(Optimizer.solve(small, gap, false, Double.POSITIVE_INFINITY).total(), oracle, "Оракул отправлений");
            near(PlanQuality.waiting(Optimizer.solve(small, gap, false, Double.POSITIVE_INFINITY)), oracleWait, "Оракул минимального ожидания");
        }
        // #ОракулОстановок: перебор двух отправлений и двух ожиданий в малой встречной задаче.
        Train fast = new Train(1, "E", "O", 7200, 100, 0), opposite = new Train(99, "O", "E", 7200, 100, 0);
        List<Schedule> bases = List.of(build(original, fast, List.of("E", "F", "O"), 0), build(original, opposite, List.of("O", "F", "E"), 0));
        double oracle = Double.POSITIVE_INFINITY;
        double oracleWait = Double.POSITIVE_INFINITY;
        for (int a = 0; a <= 8; a++) for (int b = 0; b <= 8; b++) for (int wa = 0; wa <= 8; wa++) for (int wb = 0; wb <= 8; wb++) {
            List<Schedule> trial = List.of(withWaits(build(original, fast, bases.get(0).route(), a), List.of(new Dwell("F", 0, wa))),
                withWaits(build(original, opposite, bases.get(1).route(), b), List.of(new Dwell("F", 0, wb))));
            if (conflicts(trial, 1).isEmpty()) {
                double total = plan(trial).total(), waiting = a + b + wa + wb;
                if (total < oracle || total == oracle && waiting < oracleWait) { oracle = total; oracleWait = waiting; }
            }
        }
        near(Optimizer.solve(bases, 1, true, Double.POSITIVE_INFINITY).total(), oracle, "Оракул остановок");
        near(PlanQuality.waiting(Optimizer.solve(bases, 1, true, Double.POSITIVE_INFINITY)), oracleWait, "Оракул ожиданий с остановками");

        // #ОракулСочетаний: проверяем весь рейтинг маршрутов, включая третий критерий — километраж.
        Config tiny = new Config(List.of(new Edge("E", "F", 2, true), new Edge("F", "O", 2, true), new Edge("E", "O", 2, true)),
            List.of(fast, opposite), 20, 20, 1, true, false);
        Network tinyGraph = new Network(tiny);
        List<Plan> expectedPlans = new ArrayList<>();
        for (List<String> aRoute : tinyGraph.paths("E", "O")) for (List<String> bRoute : tinyGraph.paths("O", "E")) {
            Plan best = null;
            for (int a = 0; a <= 12; a++) for (int b = 0; b <= 12; b++) {
                Plan trial = plan(List.of(build(tiny, fast, aRoute, a), build(tiny, opposite, bRoute, b)));
                if (conflicts(trial.schedules(), 1).isEmpty() && (best == null || PlanQuality.score(tiny, trial).compareTo(PlanQuality.score(tiny, best)) < 0)) best = trial;
            }
            expectedPlans.add(best);
        }
        expectedPlans.sort(Comparator.comparing(p -> PlanQuality.score(tiny, p)));
        SearchResult tinyResult = Optimizer.search(tiny, p -> {});
        check(tinyResult.variants().size() == 4, "Все четыре различные комбинации в малой сети");
        for (int i = 0; i < 4; i++) check(PlanQuality.score(tiny, tinyResult.variants().get(i)).compareTo(PlanQuality.score(tiny, expectedPlans.get(i))) == 0,
            "Рейтинг маршрутов совпадает с полным перебором");

        List<Edge> edited = new ArrayList<>(original.edges());
        for (int i = 0; i < edited.size(); i++) if (edited.get(i).key().equals("E-G")) edited.set(i, new Edge("E", "G", 3, true));
        Config changed = new Config(edited, original.trains(), 20, 20, 1, true, true);
        near(new Network(changed).length(List.of("B", "G", "E", "A")), 49, "Изменение E-G");
        for (int i = 0; i < edited.size(); i++) if (edited.get(i).key().equals("E-G")) edited.set(i, new Edge("E", "G", 3, false));
        check(new Network(new Config(edited, original.trains(), 20, 20, 1, true, true)).paths("B", "A").stream().noneMatch(p -> p.equals(List.of("B", "G", "E", "A"))), "Отключённый участок исключён");
        boolean rejected = false;
        try { Network.validate(new Config(original.edges(), List.of(new Train(1, "A", "C", Double.NaN, 0, 0)), 20, 20, 1, true, true)); }
        catch (IllegalArgumentException expectedError) { rejected = true; }
        check(rejected, "NaN запрещён");
        near(TimeUtil.parse("01:41:12"), 6072, "Разбор времени");
        near(TimeUtil.parse("78,28"), 78.28, "Дробные секунды");
        // #ФиксированныйПуть: поиск не заменяет указанный путь более коротким.
        List<String> fixedRoute = List.of("D", "F", "E", "O", "G", "B");
        SearchResult fixedSearch = Optimizer.search(config(original, true, false), Map.of(1, fixedRoute), p -> {});
        check(fixedSearch.combinations() == 8 * 9 * 8, "Заданный путь ограничивает сочетания");
        for (Plan p : fixedSearch.variants()) check(p.schedules().get(0).route().equals(fixedRoute), "Сохранение заданного маршрута");
        boolean invalidRouteRejected = false;
        try { Optimizer.search(original, Map.of(1, List.of("A", "E", "G", "B")), p -> {}); }
        catch (IllegalArgumentException expectedError) { invalidRouteRejected = true; }
        check(invalidRouteRejected, "Неверное направление заданного пути запрещено");
        check(original.trains().get(2).tanks() == 30, "Режим без вагонов не изменяет составы");
        System.out.println("OK: " + checks + " проверок пройдено.");
    }

    private static Schedule synthetic(int id, double complete, String[] names, double[] starts, double[] ends) {
        List<Interval> intervals = new ArrayList<>();
        for (int i = 0; i < names.length; i++) intervals.add(new Interval(id, names[i], true, starts[i], ends[i]));
        return new Schedule(new Train(id, "E", "F", 1, 0, 0), List.of("E", "F"), 0, complete, complete, intervals, List.of(), List.of());
    }
    private static Schedule shift(Schedule base, double offset) {
        return new Schedule(base.train(), base.route(), offset, base.arrival() + offset, base.complete() + offset,
            base.intervals().stream().map(i -> new Interval(i.trainId(), i.resource(), i.edge(), i.start() + offset, i.end() + offset)).toList(), List.of(), List.of());
    }
}
