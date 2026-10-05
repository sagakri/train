package train;

import javax.swing.*;
import java.awt.GraphicsEnvironment;

// #ТочкаВхода: запускает самостоятельное Swing-приложение или консольную демонстрацию.
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--demo")) {
            // #Демонстрация: тот же Java-алгоритм можно проверить без графического экрана.
            Model.SearchResult result = Optimizer.search(Model.defaults(), percent -> {});
            Model.Plan best = result.variants().get(0);
            System.out.println("Освобождение сети: " + TimeUtil.format(best.total()) + " (" + best.total() + " с)");
            for (Model.Schedule s : best.schedules()) System.out.println("№" + s.train().id() + " " + String.join("-", s.route())
                + " отправление=" + TimeUtil.exact(s.start()) + " прибытие=" + TimeUtil.exact(s.arrival()) + " ожидания=" + s.dwells());
            System.out.println("Сочетаний: " + result.combinations() + "; конфликтов: " + Scheduling.conflicts(best.schedules(), 1).size());
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Графический экран недоступен. Для консольной проверки используйте --demo.");
            System.exit(1);
        }
        // #ПотокSwing: создание и обновление компонентов выполняется в Event Dispatch Thread.
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
                // #РезервныйСтиль: недоступность системной темы не мешает запуску.
                System.err.println("Используется стандартная тема Swing: " + e.getMessage());
            }
            new SimulatorFrame().setVisible(true);
        });
    }
}
