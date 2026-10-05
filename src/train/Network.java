package train;

import java.util.*;
import static train.Model.*;

// #Граф: хранит включённые участки и перечисляет простые маршруты поиском в глубину.
public final class Network {
    private final Map<String, List<String>> graph = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new HashMap<>();

    public Network(Config config) {
        for (String node : NODES) graph.put(node, new ArrayList<>());
        for (Edge edge : config.edges()) if (edge.enabled()) {
            edges.put(edge.key(), edge);
            graph.get(edge.a()).add(edge.b());
            graph.get(edge.b()).add(edge.a());
        }
    }

    // #ПоискМаршрутов: повторное посещение узлов запрещено; сортировка по длине стабильная.
    public List<List<String>> paths(String from, String to) {
        List<List<String>> paths = new ArrayList<>();
        dfs(from, to, new ArrayList<>(List.of(from)), paths);
        paths.sort(Comparator.comparingDouble(this::length));
        return paths;
    }

    // #РекурсияDFS: добавляет соседа, исследует продолжение и возвращается назад.
    private void dfs(String current, String target, List<String> path, List<List<String>> result) {
        if (current.equals(target)) { result.add(List.copyOf(path)); return; }
        for (String next : graph.getOrDefault(current, List.of())) if (!path.contains(next)) {
            path.add(next);
            dfs(next, target, path, result);
            path.remove(path.size() - 1);
        }
    }

    public Edge edge(String a, String b) {
        Edge edge = edges.get(key(a, b));
        if (edge == null) throw new IllegalArgumentException("Нет включённого участка " + a + "-" + b);
        return edge;
    }

    // #ДлинаМаршрута: суммирует реальные рёбра; E-G не подменяется маршрутом через O.
    public double length(List<String> route) {
        double sum = 0;
        for (int i = 1; i < route.size(); i++) sum += edge(route.get(i - 1), route.get(i)).km();
        return sum;
    }

    // #ПроверкаМаршрута: станции, известные узлы и простота пути проверяются до расчёта.
    public void validateRoute(Train train, List<String> route) {
        if (route.size() < 2 || !route.get(0).equals(train.from()) || !route.get(route.size() - 1).equals(train.to()))
            throw new IllegalArgumentException("Поезд №" + train.id() + ": маршрут не соответствует станциям.");
        if (!NODES.containsAll(route) || new HashSet<>(route).size() != route.size())
            throw new IllegalArgumentException("Маршрут должен содержать известные узлы без повторений.");
        length(route);
    }

    // #Валидация: NaN, бесконечность, отрицательные количества и нулевые скорости запрещены.
    public static void validate(Config config) {
        for (Edge e : config.edges()) if (e.enabled() && (!Double.isFinite(e.km()) || e.km() <= 0))
            throw new IllegalArgumentException("Длина " + e.key() + " должна быть положительной.");
        for (Train t : config.trains()) {
            if (!NODES.contains(t.from()) || !NODES.contains(t.to()) || t.from().equals(t.to()))
                throw new IllegalArgumentException("Поезд №" + t.id() + ": выберите разные известные станции.");
            if (!Double.isFinite(t.speed()) || t.speed() <= 0 || t.cars() < 0 || t.tanks() < 0)
                throw new IllegalArgumentException("Поезд №" + t.id() + ": скорость > 0, количества >= 0.");
            if (!Double.isFinite(config.length(t))) throw new IllegalArgumentException("Слишком большая длина состава.");
        }
        if (!Double.isFinite(config.carLength()) || config.carLength() <= 0 || !Double.isFinite(config.tankLength()) || config.tankLength() <= 0)
            throw new IllegalArgumentException("Длины вагона и цистерны должны быть положительными.");
        if (!Double.isFinite(config.gap()) || config.gap() < 0) throw new IllegalArgumentException("Запас должен быть >= 0.");
    }
}
