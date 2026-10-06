package train;

import static train.Model.*;

// #КачествоПлана: единые критерии поиска и отображения результатов.
public final class PlanQuality {
    private PlanQuality() {}

    // #ПорядокКритериев: время сети, затем всё ожидание, затем общий километраж.
    // Округление до микросекунды устраняет различия от арифметики double, сохраняя порядок сравнения.
    public record Score(double total, double waiting, double distance) implements Comparable<Score> {
        @Override public int compareTo(Score other) {
            int result = Double.compare(Math.rint(total * 1e6), Math.rint(other.total * 1e6));
            if (result == 0) result = Double.compare(Math.rint(waiting * 1e6), Math.rint(other.waiting * 1e6));
            if (result == 0) result = Double.compare(distance, other.distance);
            return result;
        }
    }

    public static double waiting(Plan plan) {
        return plan.schedules().stream().mapToDouble(s -> s.start()
            + s.dwells().stream().mapToDouble(d -> d.end() - d.start()).sum()).sum();
    }

    public static Score score(Config config, Plan plan) {
        Network graph = new Network(config);
        return new Score(plan.total(), waiting(plan), plan.schedules().stream().mapToDouble(s -> graph.length(s.route())).sum());
    }
}
