package train;

import java.util.*;
import java.util.function.IntConsumer;
import java.util.concurrent.CancellationException;
import static train.Model.*;
import static train.Scheduling.*;
import train.PlanQuality.Score;

// #Оптимизатор: полный перебор простых маршрутов и метод ветвей и границ для времени.
public final class Optimizer {
    private Optimizer() {}
    private record TimedResource(Interval interval, int startVar, int endVar, double startOffset, double endOffset) {}
    private record Pair(TimedResource a, TimedResource b) {}

    // #ПоискВариантов: сохраняет до шести лучших сочетаний и отсекает их по нижней границе.
    public static SearchResult search(Config config, IntConsumer progress) {
        return search(config, Map.of(), progress);
    }

    // #ЗаданныеМаршруты: выбранный пользователем путь фиксируется, остальные ищутся автоматически.
    public static SearchResult search(Config config, Map<Integer, List<String>> chosenRoutes, IntConsumer progress) {
        Network.validate(config);
        Network network = new Network(config);
        List<List<List<String>>> sets = new ArrayList<>();
        int count = 1;
        for (Train train : config.trains()) {
            List<String> chosen = chosenRoutes.get(train.id());
            if (chosen != null) network.validateRoute(train, chosen);
            List<List<String>> paths = chosen == null ? network.paths(train.from(), train.to()) : List.of(List.copyOf(chosen));
            if (paths.isEmpty()) throw new IllegalArgumentException("Для поезда №" + train.id() + " нет пути.");
            sets.add(paths);
            count = Math.multiplyExact(count, paths.size());
        }
        List<Plan> variants = new ArrayList<>();
        Comparator<Plan> ranking = Comparator.comparing(p -> PlanQuality.score(config, p));
        double lowerBound = 0;
        for (int i = 0; i < sets.size(); i++) lowerBound = Math.max(lowerBound,
            build(config, config.trains().get(i), sets.get(i).get(0), 0).complete());
        int bounded = 0;
        for (int index = 0; index < count; index++) {
            checkInterrupted();
            int remainder = index;
            List<List<String>> routes = new ArrayList<>(Collections.nCopies(sets.size(), null));
            // #ДекартовоПроизведение: последняя группа маршрутов меняется быстрее остальных.
            for (int i = sets.size() - 1; i >= 0; i--) {
                routes.set(i, sets.get(i).get(remainder % sets.get(i).size()));
                remainder /= sets.get(i).size();
            }
            List<Schedule> bases = new ArrayList<>();
            for (int i = 0; i < routes.size(); i++) bases.add(build(config, config.trains().get(i), routes.get(i), 0));
            double distance = routes.stream().mapToDouble(network::length).sum();
            Score ceiling = variants.size() >= 6 ? PlanQuality.score(config, variants.get(5))
                : new Score(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            Plan result = null;
            // #БезопасноеОтсечение: равное время ещё может дать меньше ожидания или километров.
            if (new Score(plan(bases).total(), 0, distance).compareTo(ceiling) < 0)
                result = solveRanked(bases, config.gap(), config.stops(), ceiling, distance);
            if (result == null) bounded++;
            else {
                if (!conflicts(result.schedules(), config.gap()).isEmpty()) throw new IllegalStateException("Проверка обнаружила конфликт в найденном графике.");
                variants.add(result);
                variants.sort(ranking);
                if (variants.size() > 6) variants.remove(6);
            }
            if (index % 32 == 0 || index == count - 1) progress.accept((index + 1) * 100 / count);
        }
        variants.sort(ranking);
        return new SearchResult(List.copyOf(variants), count, bounded, lowerBound);
    }

    // #РешениеВремён: одна переменная отправления на поезд либо подробная модель остановок.
    public static Plan solve(List<Schedule> bases, double gap, boolean stops, double ceiling) {
        return solveRanked(bases, gap, stops, new Score(ceiling, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY), 0);
    }

    private static Plan solveRanked(List<Schedule> bases, double gap, boolean stops, Score ceiling, double distance) {
        List<Motion> models = stops ? bases.stream().map(Motion::new).toList() : List.of();
        List<Constraint> fixed = new ArrayList<>();
        List<TimedResource> resources = new ArrayList<>();
        int[] offsets = new int[bases.size()], completion = new int[bases.size()];
        double[] completionOffset = new double[bases.size()];
        List<Double> initial = new ArrayList<>();
        double cursor = 0;
        for (int i = 0; i < bases.size(); i++) {
            Schedule base = bases.get(i);
            offsets[i] = initial.size();
            if (stops) {
                Motion m = models.get(i);
                for (double x : m.variables) initial.add(cursor + x);
                for (Constraint c : m.constraints) fixed.add(new Constraint(c.a() + offsets[i], c.b() + offsets[i], c.w()));
                for (Resource r : m.resources) resources.add(new TimedResource(r.interval(), r.startVar() + offsets[i], r.endVar() + offsets[i], 0, 0));
                completion[i] = offsets[i] + m.completeVar;
            } else {
                initial.add(cursor);
                completion[i] = offsets[i];
                completionOffset[i] = base.complete();
                for (Interval r : base.intervals()) resources.add(new TimedResource(r, offsets[i], offsets[i], r.start(), r.end()));
            }
            cursor += base.complete() + gap;
        }
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < resources.size(); i++) for (int j = i + 1; j < resources.size(); j++) {
            TimedResource a = resources.get(i), b = resources.get(j);
            if (a.interval().trainId() != b.interval().trainId() && a.interval().resource().equals(b.interval().resource())) pairs.add(new Pair(a, b));
        }
        double[] bestTimes = initial.stream().mapToDouble(Double::doubleValue).toArray();
        double baselineSum = bases.stream().mapToDouble(Schedule::complete).sum();
        Score best = quality(bestTimes, completion, completionOffset, baselineSum, distance);
        if (best.compareTo(ceiling) >= 0) { best = ceiling; bestTimes = null; }
        Deque<List<Constraint>> stack = new ArrayDeque<>();
        stack.push(List.of());
        // #Ветвление: для каждого конфликта рассматриваем оба порядка доступа к ресурсу.
        while (!stack.isEmpty()) {
            checkInterrupted();
            List<Constraint> branch = stack.pop();
            List<Constraint> constraints = new ArrayList<>(fixed);
            constraints.addAll(branch);
            double[] times = new double[initial.size()];
            boolean feasible = true;
            // #НижниеГраницы: распространение ограничений; положительный цикл недопустим.
            for (int pass = 0; pass < times.length; pass++) {
                boolean changed = false;
                for (Constraint c : constraints) if (times[c.b()] + 1e-8 < times[c.a()] + c.w()) {
                    times[c.b()] = times[c.a()] + c.w();
                    changed = true;
                }
                if (!changed) break;
                if (pass == times.length - 1 || quality(times, completion, completionOffset, baselineSum, distance).compareTo(best) >= 0) { feasible = false; break; }
            }
            Score candidate = quality(times, completion, completionOffset, baselineSum, distance);
            if (!feasible || candidate.compareTo(best) >= 0) continue;
            Pair conflict = null;
            for (Pair p : pairs) {
                TimedResource a = p.a(), b = p.b();
                if (Math.max(times[a.startVar()] + a.startOffset(), times[b.startVar()] + b.startOffset()) <
                    Math.min(times[a.endVar()] + a.endOffset() + gap, times[b.endVar()] + b.endOffset() + gap) - EPS) { conflict = p; break; }
            }
            if (conflict == null) { bestTimes = times; best = candidate; continue; }
            TimedResource a = conflict.a(), b = conflict.b();
            List<Constraint> reverse = new ArrayList<>(branch), forward = new ArrayList<>(branch);
            reverse.add(new Constraint(b.endVar(), a.startVar(), b.endOffset() + gap - a.startOffset()));
            forward.add(new Constraint(a.endVar(), b.startVar(), a.endOffset() + gap - b.startOffset()));
            stack.push(reverse);
            stack.push(forward);
        }
        if (bestTimes == null) return null;
        List<Schedule> schedules = new ArrayList<>();
        for (int i = 0; i < bases.size(); i++) {
            if (stops) schedules.add(models.get(i).materialize(Arrays.copyOfRange(bestTimes, offsets[i], offsets[i] + models.get(i).variables.size())));
            else schedules.add(shift(bases.get(i), bestTimes[i]));
        }
        return plan(schedules);
    }

    private static double finish(double[] times, int[] completion, double[] offsets) {
        double max = 0;
        for (int i = 0; i < completion.length; i++) max = Math.max(max, times[completion[i]] + offsets[i]);
        return max;
    }

    // #ГраницаОжиданий: выход хвоста минус чистое время движения равен отправлению плюс остановки.
    // При распространении времён это нижняя граница: каждое время может только увеличиваться.
    private static Score quality(double[] times, int[] completion, double[] offsets, double baselineSum, double distance) {
        double sum = 0;
        for (int i = 0; i < completion.length; i++) sum += times[completion[i]] + offsets[i];
        return new Score(finish(times, completion, offsets), Math.max(0, sum - baselineSum), distance);
    }

    // #Сдвиг: в режиме без остановок все события сдвигаются на одно время отправления.
    private static Schedule shift(Schedule base, double start) {
        List<Interval> intervals = base.intervals().stream().map(i -> new Interval(i.trainId(), i.resource(), i.edge(), i.start() + start, i.end() + start)).toList();
        List<Segment> segments = base.segments().stream().map(s -> new Segment(s.from(), s.to(), s.start() + start, s.end() + start)).toList();
        return new Schedule(base.train(), base.route(), start, base.arrival() + start, base.complete() + start, intervals, List.of(), segments);
    }

    // #Отмена: закрытие окна или кнопка отмены останавливают поиск между ветвями.
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Расчёт отменён.");
    }
}
