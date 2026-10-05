package train;

import java.util.*;
import static train.Model.*;

// #РасчётРасписания: физическая модель движения и независимая проверка конфликтов.
public final class Scheduling {
    private Scheduling() {}
    public static final double EPS = 1e-7;

    // #БазовыйГрафик: t = 3600*d/v; задержка хвоста = 3.6*L/v.
    public static Schedule build(Config config, Train train, List<String> route, double start) {
        Network network = new Network(config);
        List<Interval> intervals = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        double t = start, tail = 3.6 * config.length(train) / train.speed();
        intervals.add(new Interval(train.id(), route.get(0), false, t, t + tail));
        for (int i = 1; i < route.size(); i++) {
            String a = route.get(i - 1), b = route.get(i);
            double end = t + 3600 * network.edge(a, b).km() / train.speed();
            intervals.add(new Interval(train.id(), key(a, b), true, t, end + tail));
            segments.add(new Segment(a, b, t, end));
            t = end;
            intervals.add(new Interval(train.id(), b, false, t, t + tail));
        }
        return new Schedule(train, List.copyOf(route), start, t, t + tail, intervals, List.of(), segments);
    }

    // #Ограничение: время b не меньше времени a плюс w.
    public record Constraint(int a, int b, double w) {}
    public record Milestone(double x, int arrival, int departure, String node) {}
    public record Resource(Interval interval, int startVar, int endVar) {}

    // #МодельОстановок: отдельные переменные прихода и ухода головы в промежуточном узле.
    // Точки выхода хвоста добавлены отдельно: ожидание влияет на весь состав.
    public static final class Motion {
        final Schedule base;
        final List<Double> variables = new ArrayList<>();
        final List<Constraint> constraints = new ArrayList<>();
        final List<Milestone> milestones = new ArrayList<>();
        final List<Resource> resources = new ArrayList<>();
        final double[] coords;
        final int startVar, arrivalVar, completeVar;

        public Motion(Schedule base) {
            this.base = base;
            List<Interval> nodes = base.intervals().stream().filter(i -> !i.edge()).toList();
            double tail = nodes.get(0).end() - nodes.get(0).start();
            coords = nodes.stream().mapToDouble(i -> i.start() - base.start()).toArray();
            TreeSet<Double> points = new TreeSet<>();
            for (double x : coords) { points.add(x); points.add(x + tail); }
            for (double x : points) {
                int arrival = variables.size();
                variables.add(x);
                String node = null;
                for (int i = 1; i < coords.length - 1; i++) if (Math.abs(coords[i] - x) < 1e-8) node = base.route().get(i);
                int departure = arrival;
                if (node != null) {
                    departure = variables.size();
                    variables.add(x);
                    constraints.add(new Constraint(arrival, departure, 0));
                }
                milestones.add(new Milestone(x, arrival, departure, node));
            }
            // #ПостояннаяСкорость: две противоположные границы задают точное время движения.
            for (int i = 1; i < milestones.size(); i++) {
                Milestone a = milestones.get(i - 1), b = milestones.get(i);
                double duration = b.x() - a.x();
                constraints.add(new Constraint(a.departure(), b.arrival(), duration));
                constraints.add(new Constraint(b.arrival(), a.departure(), -duration));
            }
            for (Interval interval : base.intervals()) {
                Milestone a = point(interval.start() - base.start()), b = point(interval.end() - base.start());
                resources.add(new Resource(interval, interval.edge() ? a.departure() : a.arrival(),
                    tail == 0 && !interval.edge() ? b.departure() : b.arrival()));
            }
            startVar = point(0).arrival();
            arrivalVar = point(coords[coords.length - 1]).arrival();
            completeVar = point(coords[coords.length - 1] + tail).arrival();
        }

        private Milestone point(double x) {
            return milestones.stream().filter(p -> Math.abs(p.x() - x) < 1e-8).findFirst()
                .orElseThrow(() -> new IllegalStateException("Не найдена точка движения: " + x));
        }

        // #Материализация: найденные времена превращаются в интервалы, остановки и сегменты.
        public Schedule materialize(double[] times) {
            List<Interval> intervals = new ArrayList<>();
            for (Resource r : resources) {
                Interval i = r.interval();
                intervals.add(new Interval(i.trainId(), i.resource(), i.edge(), times[r.startVar()], times[r.endVar()]));
            }
            List<Dwell> dwells = new ArrayList<>();
            for (Milestone p : milestones) if (p.node() != null && times[p.departure()] > times[p.arrival()] + EPS)
                dwells.add(new Dwell(p.node(), times[p.arrival()], times[p.departure()]));
            List<Segment> segments = new ArrayList<>();
            for (int i = 1; i < coords.length; i++) segments.add(new Segment(base.route().get(i - 1), base.route().get(i),
                times[point(coords[i - 1]).departure()], times[point(coords[i]).arrival()]));
            return new Schedule(base.train(), base.route(), times[startVar], times[arrivalVar], times[completeVar], intervals, dwells, segments);
        }
    }

    // #ПовторнаяПроверка: воспроизводит сохранённые длительности ожидания с новым отправлением.
    public static Schedule withWaits(Schedule base, List<Dwell> waits) {
        Motion motion = new Motion(base);
        double[] times = new double[motion.variables.size()];
        for (int i = 0; i < times.length; i++) {
            double x = motion.variables.get(i);
            times[i] = base.start() + x;
            for (Dwell wait : waits) {
                int node = base.route().indexOf(wait.node());
                if (node > 0 && node < motion.coords.length - 1 && motion.coords[node] < x - 1e-8)
                    times[i] += wait.end() - wait.start();
            }
        }
        for (Milestone p : motion.milestones) if (p.departure() != p.arrival())
            for (Dwell wait : waits) if (wait.node().equals(p.node())) times[p.departure()] += wait.end() - wait.start();
        return motion.materialize(times);
    }

    // #НезависимаяПроверка: проверяет и встречные, и попутные составы, включая узлы и запас.
    public static List<Conflict> conflicts(List<Schedule> schedules, double gap) {
        List<Interval> intervals = schedules.stream().flatMap(s -> s.intervals().stream()).toList();
        List<Conflict> result = new ArrayList<>();
        for (int i = 0; i < intervals.size(); i++) for (int j = i + 1; j < intervals.size(); j++) {
            Interval a = intervals.get(i), b = intervals.get(j);
            if (a.trainId() == b.trainId() || !a.resource().equals(b.resource())) continue;
            double start = Math.max(a.start(), b.start()), end = Math.min(a.end() + gap, b.end() + gap);
            if (start < end - EPS) result.add(new Conflict(a.resource(), a.trainId(), b.trainId(), start, end));
        }
        return result;
    }

    public static Plan plan(List<Schedule> schedules) {
        return new Plan(List.copyOf(schedules), schedules.stream().mapToDouble(Schedule::complete).max().orElse(0));
    }
}
