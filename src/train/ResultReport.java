package train;

import java.util.Locale;
import static train.Model.*;

// #ВоспроизводимыйОтчёт: реальные варианты вычисляются тем же алгоритмом, что и в окне.
public final class ResultReport {
    private ResultReport() {}
    public static void main(String[] args) {
        System.out.println("# Лучшие варианты на исходных данных\n\nОтчёт создан классом `train.ResultReport`. "
            + "Везде используется режим маршрутов «Авто», запас 1 с, длины единиц по 20 м. "
            + "Приоритет: освобождение сети, суммарное ожидание, общий километраж. "
            + "Ожидание включает задержку отправления и остановки. Равные строки равноценны.\n");
        Config defaults = Model.defaults();
        for (boolean stops : new boolean[]{true, false}) for (boolean cars : new boolean[]{true, false}) {
            Config config = new Config(defaults.edges(), defaults.trains(), 20, 20, 1, cars, stops);
            SearchResult result = Optimizer.search(config, p -> {});
            System.out.println("## " + (stops ? "С ожиданием у стрелок" : "Без остановок") + " / " + (cars ? "с вагонами" : "без вагонов"));
            System.out.printf(Locale.ROOT, "\nСочетаний: %d; отсечено: %d; нижняя граница: %.2f с.\n\n", result.combinations(), result.bounded(), result.lowerBound());
            System.out.println("| № | Освобождение, с | Ожидание, с | Путь, км | Конфликты | Маршруты №1; №2; №3; №4 |\n|---|---:|---:|---:|---:|---|");
            for (int i = 0; i < result.variants().size(); i++) {
                Plan p = result.variants().get(i);
                PlanQuality.Score q = PlanQuality.score(config, p);
                System.out.printf(Locale.ROOT, "| %d | %.2f | %.2f | %.2f | %d | %s |%n", i + 1, q.total(), q.waiting(), q.distance(),
                    Scheduling.conflicts(p.schedules(), config.gap()).size(), String.join("; ", p.schedules().stream().map(s -> String.join("-", s.route())).toList()));
            }
            System.out.println("\nПервый вариант подробно:\n\n| Поезд | Отправление, с | Голова на конечной, с | Хвост вышел, с | Ожидания в узлах |\n|---|---:|---:|---:|---|");
            for (Schedule s : result.variants().get(0).schedules()) System.out.printf(Locale.ROOT, "| %d | %.2f | %.2f | %.2f | %s |%n",
                s.train().id(), s.start(), s.arrival(), s.complete(), s.dwells().isEmpty() ? "Нет" : s.dwells().toString());
            System.out.println();
        }
    }
}
