package train;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import javax.imageio.ImageIO;

// #ПроверкаИнтерфейса: выполняет настоящие обработчики Swing и сохраняет рендер окна.
public final class UiSelfTest {
    private UiSelfTest() {}
    private static SimulatorFrame frame;
    private static int checks;

    private static Object field(String name) throws ReflectiveOperationException {
        Field field = SimulatorFrame.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(frame);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    // #ПотокИнтерфейса: тест тоже обновляет компоненты только в EDT.
    private static void edt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private static void click(String name) throws Exception {
        JButton button = (JButton) field(name);
        edt(button::doClick);
    }
    private static void awaitSearch() throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        boolean[] done = {false};
        while (!done[0]) {
            edt(() -> {
                try { done[0] = field("worker") == null; }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            if (System.nanoTime() > deadline) throw new AssertionError("Поиск не завершился за 60 секунд");
            Thread.sleep(50);
        }
    }
    private static void assertPlan(double total) throws Exception {
        edt(() -> {
            try {
                Model.Plan plan = (Model.Plan) field("selected");
                Model.Config config = (Model.Config) field("current");
                check(plan != null && Math.abs(plan.total() - total) < 1e-6, "Верный результат в окне");
                check(Scheduling.conflicts(plan.schedules(), config.gap()).isEmpty(), "Безопасность плана в окне");
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
    }

    public static void main(String[] args) throws Exception {
        try {
            edt(() -> frame = new SimulatorFrame());
            click("auto"); awaitSearch(); assertPlan(6072);
            JComboBox<?> variants = (JComboBox<?>) field("variants");
            edt(() -> { check(variants.getItemCount() == 6, "Выбор шести вариантов"); variants.setSelectedIndex(1); });
            assertPlan(6072); click("manual"); assertPlan(6072);
            JCheckBox cars = (JCheckBox) field("withCars");
            edt(cars::doClick); click("auto"); awaitSearch(); assertPlan(6000);
            JLabel comparison = (JLabel) field("comparison");
            edt(() -> check(comparison.getText().contains("01:41:12") && comparison.getText().contains("01:40:00"), "Сравнение двух длин"));
            JComboBox<?> routing = (JComboBox<?>) field("routing");
            edt(() -> routing.setSelectedIndex(1)); click("auto"); awaitSearch(); assertPlan(6000);
            click("defaults"); click("auto"); awaitSearch(); assertPlan(6072);
            // #Рендер: визуальная проверка выполняется без управления чужими окнами.
            edt(() -> {
                frame.pack(); frame.setSize(1380, 900); frame.validate();
                BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); frame.getContentPane().printAll(g); g.dispose();
                try { ImageIO.write(image, "png", new File("java-desktop.png")); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
            DefaultTableModel trains = (DefaultTableModel) field("trains");
            edt(() -> trains.setValueAt(55, 0, 3));
            edt(() -> {
                try { check(field("selected") == null && ((List<?>) field("savedWaits")).isEmpty(), "Изменение параметра сбрасывает результат и ожидания"); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            click("random");
            edt(() -> {
                for (int i = 0; i < 4; i++) {
                    check(((Number) trains.getValueAt(i, 3)).doubleValue() >= 25, "Случайная скорость допустима");
                    check(((Number) trains.getValueAt(i, 4)).intValue() >= 0, "Случайный состав допустим");
                }
            });
            click("defaults");
            System.out.println("OK: " + checks + " проверок интерфейса; рендер: java-desktop.png");
        } finally {
            if (frame != null) edt(frame::dispose);
        }
    }
}
