package train;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import static train.Model.*;
import static train.Scheduling.*;

// #ГлавноеОкно: управляет вводом, поиском, ручной проверкой, таблицами и анимацией.
public final class SimulatorFrame extends JFrame {
    private final DefaultTableModel edges = new DefaultTableModel(new Object[]{"Участок", "км", "Включён"}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return col > 0; }
        @Override public Class<?> getColumnClass(int col) { return col == 2 ? Boolean.class : Object.class; }
    };
    private final DefaultTableModel trains = new DefaultTableModel(new Object[]{"№", "От", "До", "км/ч", "Вагоны", "Цистерны", "Отправление", "Маршрут", "Выбор маршрута"}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return col > 0; }
    };
    private final DefaultTableModel events = readOnly("Поезд", "Ресурс", "Событие", "Голова / начало", "Хвост / конец");
    private final DefaultTableModel summary = readOnly("Поезд", "Маршрут", "Отправление", "Прибытие", "Освобождение", "Ожидание у стрелок");
    private final JTable edgeTable = new JTable(edges), trainTable = new JTable(trains);
    private final JTextField carLength = new JTextField("20", 4), tankLength = new JTextField("20", 4), gap = new JTextField("1", 4);
    private final JTextField legend = new JTextField("ц = цистерны, в = вагоны", 22);
    private final JCheckBox withCars = new JCheckBox("С вагонами", true);
    private final JComboBox<String> routing = new JComboBox<>(new String[]{"С ожиданием у стрелок", "Без остановок в пути"});
    private final JComboBox<String> variants = new JComboBox<>();
    private final JButton auto = new JButton("Найти лучший график"), manual = new JButton("Проверить отправления"), cancel = new JButton("Отменить");
    private final JButton random = new JButton("Случайные данные"), defaults = new JButton("Исходные данные");
    private final JButton reverse = new JButton("Поменять направление выбранного поезда");
    private final JLabel status = new JLabel("Готов к расчёту"), metrics = new JLabel("Освобождение: —"), clock = new JLabel("00:00:00");
    private final JLabel comparison = new JLabel("С вагонами: —   |   Без вагонов: —");
    private final JTextArea messages = new JTextArea(3, 60);
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final NetworkPanel network = new NetworkPanel();
    private final TimelinePanel timeline = new TimelinePanel();
    private final JSlider slider = new JSlider(0, 1, 0);
    private final JComboBox<Integer> rate = new JComboBox<>(new Integer[]{1, 5, 15, 30, 60});
    private final javax.swing.Timer timer;
    private final Map<String, double[]> comparisons = new HashMap<>();
    private List<Plan> plans = List.of();
    private List<List<Dwell>> savedWaits = List.of();
    private Config current = Model.defaults();
    private Plan selected;
    private SwingWorker<SearchResult, Void> worker;
    private boolean updating;
    private double animationTime;

    public SimulatorFrame() {
        super("Симулятор движения поездов — Java");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 720));
        setSize(1380, 900);
        setLocationRelativeTo(null);
        timer = new javax.swing.Timer(40, e -> advanceAnimation());
        setLayout(new BorderLayout(8, 8));
        JPanel commands = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component c : new Component[]{auto, manual, random, defaults, cancel, withCars, routing}) commands.add(c);
        JPanel header = new JPanel(new GridLayout(3, 1));
        header.add(commands); header.add(metrics); header.add(comparison); add(header, BorderLayout.NORTH);

        JPanel settings = new JPanel(new BorderLayout(5, 5));
        settings.setBorder(BorderFactory.createTitledBorder("Параметры сети"));
        settings.add(new JScrollPane(edgeTable), BorderLayout.CENTER);
        JPanel lengths = new JPanel(new GridLayout(4, 2, 5, 5));
        lengths.add(new JLabel("Вагон, м")); lengths.add(carLength);
        lengths.add(new JLabel("Цистерна, м")); lengths.add(tankLength);
        lengths.add(new JLabel("Запас, с")); lengths.add(gap);
        lengths.add(new JLabel("Обозначения")); lengths.add(legend);
        settings.add(lengths, BorderLayout.SOUTH);
        JPanel drawing = new JPanel(new BorderLayout());
        drawing.setBorder(BorderFactory.createTitledBorder("Сеть: геометрия условная; центральный узел O"));
        drawing.add(network, BorderLayout.CENTER);
        JPanel transport = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton play = new JButton("▶"), pause = new JButton("Пауза"), reset = new JButton("В начало");
        buttons.add(play); buttons.add(pause); buttons.add(reset); buttons.add(new JLabel("Скорость, мин/с")); buttons.add(rate); buttons.add(clock);
        transport.add(buttons, BorderLayout.NORTH); transport.add(slider, BorderLayout.SOUTH);
        drawing.add(transport, BorderLayout.SOUTH);
        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, settings, drawing);
        top.setResizeWeight(0.25); top.setDividerLocation(310);

        trainTable.setRowHeight(28);
        trainTable.getColumnModel().getColumn(7).setPreferredWidth(270);
        trainTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // #Редактирование: выбор станций и пути доступен одним щелчком.
        for (int col : new int[]{1, 2}) {
            DefaultCellEditor editor = new DefaultCellEditor(new JComboBox<>(NODES.toArray(String[]::new)));
            editor.setClickCountToStart(1);
            trainTable.getColumnModel().getColumn(col).setCellEditor(editor);
        }
        trainTable.getColumnModel().getColumn(7).setCellEditor(new RouteEditor());
        trainTable.getColumnModel().getColumn(8).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"Авто", "Заданный"})));
        JPanel trainPanel = new JPanel(new BorderLayout());
        trainPanel.setBorder(BorderFactory.createTitledBorder("Поезда: щёлкните «От», «До» или «Маршрут» для выбора"));
        JPanel directionControls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        directionControls.add(reverse);
        directionControls.add(new JLabel("Авто — поиск всех путей; Заданный — расчёт выбранного маршрута."));
        trainPanel.add(directionControls, BorderLayout.NORTH);
        trainPanel.add(new JScrollPane(trainTable));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Поезда", trainPanel);
        tabs.addTab("Расписание", new JScrollPane(new JTable(summary)));
        tabs.addTab("График времени", new JScrollPane(timeline));
        tabs.addTab("События", new JScrollPane(new JTable(events)));
        messages.setEditable(false); messages.setLineWrap(true); messages.setWrapStyleWord(true);
        tabs.addTab("Проверка / конфликты", new JScrollPane(messages));
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel choice = new JPanel(new FlowLayout(FlowLayout.LEFT));
        choice.add(new JLabel("Расчётный вариант:")); choice.add(variants);
        bottom.add(choice, BorderLayout.NORTH); bottom.add(tabs, BorderLayout.CENTER);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        main.setResizeWeight(0.65); main.setDividerLocation(500); add(main, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0)); footer.add(status); footer.add(progress, BorderLayout.EAST); add(footer, BorderLayout.SOUTH);
        progress.setStringPainted(true); cancel.setEnabled(false);

        // #Обработчики: каждое действие запускает соответствующую операцию приложения.
        auto.addActionListener(e -> startSearch()); manual.addActionListener(e -> checkManual());
        defaults.addActionListener(e -> loadDefaults()); random.addActionListener(e -> randomize());
        reverse.addActionListener(e -> reverseDirection());
        cancel.addActionListener(e -> { if (worker != null) worker.cancel(true); });
        variants.addActionListener(e -> {
            if (!updating && variants.getSelectedIndex() >= 0 && variants.getSelectedIndex() < plans.size()) apply(plans.get(variants.getSelectedIndex()));
        });
        play.addActionListener(e -> { if (selected != null) { if (animationTime >= selected.total()) showTime(0); timer.start(); } });
        pause.addActionListener(e -> timer.stop()); reset.addActionListener(e -> { timer.stop(); showTime(0); });
        slider.addChangeListener(e -> { if (!updating) { timer.stop(); showTime(slider.getValue()); } });
        edges.addTableModelListener(e -> invalidateInputs()); trains.addTableModelListener(e -> trainChanged(e));
        withCars.addActionListener(e -> invalidateInputs()); routing.addActionListener(e -> invalidateInputs());
        DocumentListener changes = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { invalidateInputs(); }
            public void removeUpdate(DocumentEvent e) { invalidateInputs(); }
            public void changedUpdate(DocumentEvent e) { invalidateInputs(); }
        };
        for (JTextField field : List.of(carLength, tankLength, gap)) field.getDocument().addDocumentListener(changes);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { timer.stop(); if (worker != null) worker.cancel(true); }
        });
        loadDefaults();
    }

    private static DefaultTableModel readOnly(String... columns) {
        return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
    }

    // #СменаСтанций: старый путь не переносится на новое направление.
    private void trainChanged(TableModelEvent event) {
        if (updating) return;
        int row = event.getFirstRow(), column = event.getColumn();
        if (row >= 0 && row < trains.getRowCount()) {
            updating = true;
            if (column == 1 || column == 2) {
                trains.setValueAt("", row, 7); trains.setValueAt("Авто", row, 8);
            } else if (column == 7) {
                trains.setValueAt(text(row, 7).isEmpty() ? "Авто" : "Заданный", row, 8);
            }
            updating = false;
        }
        invalidateInputs();
    }

    // #СписокМаршрутов: варианты строятся для текущих станций и включённых участков.
    private final class RouteEditor extends DefaultCellEditor {
        private final JComboBox<String> choices;
        @SuppressWarnings("unchecked")
        RouteEditor() {
            super(new JComboBox<String>());
            choices = (JComboBox<String>) getComponent();
            choices.setEditable(true); setClickCountToStart(1);
        }
        @Override public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
            choices.removeAllItems(); choices.addItem("");
            try {
                Network graph = new Network(readConfig(false));
                for (List<String> route : graph.paths(text(row, 1), text(row, 2))) choices.addItem(String.join("-", route));
            } catch (IllegalArgumentException ignored) {
                // #ЧерновыеПараметры: ручной ввод доступен, проверка выполняется при расчёте.
            }
            return super.getTableCellEditorComponent(table, value, selected, row, column);
        }
    }

    // #Разворот: станции меняются местами; заданный маршрут также разворачивается.
    private void reverseDirection() {
        try {
            readConfig(true);
            int row = trainTable.getSelectedRow();
            if (row < 0) throw new IllegalArgumentException("Выберите строку поезда, направление которого нужно изменить.");
            String from = text(row, 1), to = text(row, 2), route = text(row, 7);
            boolean fixed = "ЗАДАННЫЙ".equals(text(row, 8));
            updating = true;
            trains.setValueAt(to, row, 1); trains.setValueAt(from, row, 2);
            if (fixed && !route.isEmpty()) {
                List<String> points = new ArrayList<>(Arrays.asList(route.split("-", -1)));
                Collections.reverse(points); trains.setValueAt(String.join("-", points), row, 7);
            } else { trains.setValueAt("", row, 7); trains.setValueAt("Авто", row, 8); }
            updating = false; invalidateInputs();
        } catch (IllegalArgumentException e) { updating = false; showError(e); }
    }

    // #ОграниченияПоиска: только явно заданные маршруты ограничивают автоматический расчёт.
    private Map<Integer, List<String>> chosenRoutes(Config config) {
        Network graph = new Network(config);
        Map<Integer, List<String>> choices = new HashMap<>();
        for (int row = 0; row < config.trains().size(); row++) if ("ЗАДАННЫЙ".equals(text(row, 8))) {
            List<String> route = Arrays.stream(text(row, 7).split("-", -1)).map(String::trim).toList();
            graph.validateRoute(config.trains().get(row), route);
            choices.put(config.trains().get(row).id(), route);
        }
        return Map.copyOf(choices);
    }

    // #СбросРезультатов: изменение входных данных исключает показ устаревшего плана.
    private void invalidateInputs() {
        if (updating) return;
        timer.stop(); selected = null; plans = List.of(); savedWaits = List.of();
        updating = true; variants.removeAllItems(); updating = false;
        summary.setRowCount(0); events.setRowCount(0); messages.setText("");
        timeline.display(null);
        metrics.setText("Параметры изменены — требуется расчёт."); status.setText("Параметры изменены");
        showTime(0);
        // #ЧерновойВвод: незавершённая ячейка не вызывает диалог на каждый символ.
        try { current = readConfig(false); updateComparison(); }
        catch (IllegalArgumentException ignored) { comparison.setText("Сравнение: завершите ввод параметров."); }
        network.display(current, null, 0);
    }

    // #ЧтениеВвода: подтверждает редактируемую ячейку и создаёт независимый снимок настроек.
    private Config readConfig(boolean commit) {
        if (commit) for (JTable table : List.of(edgeTable, trainTable))
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) throw new IllegalArgumentException("Завершите редактирование ячейки.");
        List<Edge> edgeList = new ArrayList<>();
        for (int i = 0; i < edges.getRowCount(); i++) {
            String[] pair = edges.getValueAt(i, 0).toString().split("-");
            edgeList.add(new Edge(pair[0], pair[1], number(edges.getValueAt(i, 1)), Boolean.TRUE.equals(edges.getValueAt(i, 2))));
        }
        List<Train> trainList = new ArrayList<>();
        for (int i = 0; i < trains.getRowCount(); i++) trainList.add(new Train(i + 1, text(i, 1), text(i, 2), number(trains.getValueAt(i, 3)),
            integer(trains.getValueAt(i, 4)), integer(trains.getValueAt(i, 5))));
        Config config = new Config(edgeList, trainList, number(carLength.getText()), number(tankLength.getText()), number(gap.getText()), withCars.isSelected(), routing.getSelectedIndex() == 0);
        Network.validate(config); return config;
    }

    private static double number(Object value) {
        try { return Double.parseDouble(value.toString().trim().replace(',', '.')); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Некорректное число: " + value); }
    }
    private static int integer(Object value) {
        double n = number(value);
        if (!Double.isFinite(n) || n < 0 || n > Integer.MAX_VALUE || n != Math.floor(n)) throw new IllegalArgumentException("Количество должно быть неотрицательным целым числом.");
        return (int) n;
    }
    private String text(int row, int col) { return Objects.toString(trains.getValueAt(row, col), "").trim().toUpperCase(Locale.ROOT); }

    // #ФоновыйПоиск: SwingWorker выполняет тяжёлые вычисления вне потока интерфейса.
    private void startSearch() {
        try {
            current = readConfig(true);
            Map<Integer, List<String>> routeChoices = chosenRoutes(current);
            timer.stop();
            selected = null; plans = List.of(); savedWaits = List.of();
            updating = true; variants.removeAllItems(); updating = false;
            summary.setRowCount(0); events.setRowCount(0); showTime(0);
            timeline.display(null);
            Config snapshot = current; setBusy(true); progress.setValue(0); status.setText("Поиск безопасных расписаний…");
            worker = new SwingWorker<>() {
                @Override protected SearchResult doInBackground() { return Optimizer.search(snapshot, routeChoices, this::setProgress); }
                // #ЗавершениеПоиска: get извлекает результат или ошибку фоновой задачи.
                @Override protected void done() {
                    try {
                        SearchResult result = get(); plans = result.variants();
                        updating = true; variants.removeAllItems();
                        for (int i = 0; i < plans.size(); i++) variants.addItem("Вариант " + (i + 1) + " · " + TimeUtil.format(plans.get(i).total()));
                        updating = false;
                        if (!plans.isEmpty()) {
                            double[] pair = comparisons.computeIfAbsent(comparisonKey(), k -> new double[]{Double.NaN, Double.NaN});
                            pair[current.withCars() ? 0 : 1] = plans.get(0).total();
                            apply(plans.get(0)); updateComparison();
                        }
                        status.setText("Оценено сочетаний: " + result.combinations() + "; отсечено: " + result.bounded());
                    } catch (CancellationException e) {
                        status.setText("Расчёт отменён");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); status.setText("Расчёт прерван");
                    } catch (ExecutionException e) {
                        showError(e.getCause());
                    } finally {
                        updating = false; setBusy(false); worker = null;
                    }
                }
            };
            worker.addPropertyChangeListener(e -> { if ("progress".equals(e.getPropertyName())) progress.setValue((Integer) e.getNewValue()); });
            worker.execute();
        } catch (IllegalArgumentException e) { showError(e); }
    }

    // #РучнаяПроверка: использует введённые маршруты и времена, сохраняя ожидания выбранного плана.
    private void checkManual() {
        try {
            current = readConfig(true);
            Network graph = new Network(current);
            List<Schedule> schedules = new ArrayList<>();
            for (int i = 0; i < current.trains().size(); i++) {
                Train train = current.trains().get(i);
                String routeText = text(i, 7);
                List<String> route;
                if (routeText.isEmpty()) {
                    List<List<String>> paths = graph.paths(train.from(), train.to());
                    if (paths.isEmpty()) throw new IllegalArgumentException("Для поезда №" + train.id() + " нет пути.");
                    route = paths.get(0);
                } else route = Arrays.stream(routeText.split("-", -1)).map(String::trim).toList();
                graph.validateRoute(train, route);
                Schedule base = build(current, train, route, TimeUtil.parse(text(i, 6)));
                schedules.add(current.stops() && savedWaits.size() > i ? withWaits(base, savedWaits.get(i)) : base);
            }
            displayPlan(plan(schedules)); status.setText("Ручная проверка завершена");
        } catch (IllegalArgumentException e) { showError(e); }
    }

    // #ПрименениеВарианта: точные отправления и маршруты записываются в таблицу поездов.
    private void apply(Plan plan) {
        updating = true;
        for (int i = 0; i < plan.schedules().size(); i++) {
            Schedule schedule = plan.schedules().get(i);
            trains.setValueAt(TimeUtil.exact(schedule.start()), i, 6);
            trains.setValueAt(String.join("-", schedule.route()), i, 7);
        }
        savedWaits = plan.schedules().stream().map(Schedule::dwells).toList();
        updating = false; displayPlan(plan);
    }

    // #ВыводРезультата: независимая проверка, итоговые метрики и журнал событий.
    private void displayPlan(Plan plan) {
        selected = plan; timer.stop();
        timeline.display(plan);
        List<Conflict> conflicts = conflicts(plan.schedules(), current.gap());
        double departureWait = plan.schedules().stream().mapToDouble(Schedule::start).sum();
        double stopWait = plan.schedules().stream().flatMap(s -> s.dwells().stream()).mapToDouble(d -> d.end() - d.start()).sum();
        metrics.setText("Освобождение: " + TimeUtil.format(plan.total()) + "   |   Ожидание отправления: " + TimeUtil.format(departureWait)
            + "   |   У стрелок: " + TimeUtil.format(stopWait) + "   |   Конфликты: " + conflicts.size());
        summary.setRowCount(0); events.setRowCount(0);
        for (Schedule s : plan.schedules()) {
            String waits = s.dwells().isEmpty() ? "Нет" : String.join("; ", s.dwells().stream().map(d -> d.node() + ": " + TimeUtil.format(d.start()) + "–" + TimeUtil.format(d.end())).toList());
            summary.addRow(new Object[]{s.train().id(), String.join("-", s.route()), TimeUtil.format(s.start()), TimeUtil.format(s.arrival()), TimeUtil.format(s.complete()), waits});
        }
        List<Interval> intervals = plan.schedules().stream().flatMap(s -> s.intervals().stream()).sorted(Comparator.comparingDouble(Interval::start)).toList();
        for (Interval i : intervals) events.addRow(new Object[]{i.trainId(), i.resource(), i.edge() ? "Занятие / освобождение участка" : "Проход / освобождение узла", TimeUtil.format(i.start()), TimeUtil.format(i.end())});
        for (Schedule s : plan.schedules()) for (Dwell d : s.dwells()) events.addRow(new Object[]{s.train().id(), d.node(), "Ожидание у стрелки", TimeUtil.format(d.start()), TimeUtil.format(d.end())});
        StringBuilder report = new StringBuilder(conflicts.isEmpty() ? "Конфликтов нет.\n" : "Обнаружены конфликты:\n");
        for (Conflict c : conflicts) report.append(c.resource()).append(": №").append(c.first()).append(" и №").append(c.second()).append(" ").append(TimeUtil.format(c.start())).append("–").append(TimeUtil.format(c.end())).append('\n');
        for (Schedule s : plan.schedules()) report.append("№").append(s.train().id()).append(": длина ").append(current.length(s.train())).append(" м; освобождение сети ").append(TimeUtil.exact(s.complete())).append(" с.\n");
        messages.setText(report.toString()); updating = true; slider.setMaximum((int) Math.ceil(plan.total())); updating = false; showTime(0);
    }

    // #СравнениеРежимов: ключ исключает ручные отправления, но учитывает физические параметры.
    private String comparisonKey() {
        StringBuilder routes = new StringBuilder();
        for (int row = 0; row < trains.getRowCount(); row++) if ("ЗАДАННЫЙ".equals(text(row, 8))) routes.append(row).append(':').append(text(row, 7)).append(';');
        return current.edges().toString() + current.trains() + current.carLength() + ":" + current.tankLength() + ":" + current.gap() + ":" + current.stops() + ":" + routes;
    }
    private void updateComparison() {
        double[] values = comparisons.getOrDefault(comparisonKey(), new double[]{Double.NaN, Double.NaN});
        comparison.setText("С вагонами: " + (Double.isNaN(values[0]) ? "не рассчитано" : TimeUtil.format(values[0])) + "   |   Без вагонов: " + (Double.isNaN(values[1]) ? "не рассчитано" : TimeUtil.format(values[1])));
    }

    private void showTime(double time) {
        animationTime = time; updating = true; slider.setValue((int) Math.round(time)); updating = false;
        clock.setText(TimeUtil.format(time)); network.display(current, selected, time);
    }
    private void advanceAnimation() {
        if (selected == null) { timer.stop(); return; }
        double time = Math.min(selected.total(), animationTime + 0.04 * (Integer) rate.getSelectedItem() * 60);
        showTime(time); if (time >= selected.total()) timer.stop();
    }

    // #БлокировкаВвода: параметры не меняются во время вычисления снимка данных.
    private void setBusy(boolean busy) {
        for (Component c : new Component[]{auto, manual, random, defaults, reverse, withCars, routing, variants, edgeTable, trainTable, carLength, tankLength, gap}) c.setEnabled(!busy);
        cancel.setEnabled(busy);
    }

    // #ОбработкаОшибки: пользователь получает причину, приложение продолжает работать.
    private void showError(Throwable error) {
        status.setText("Ошибка ввода или расчёта"); messages.setText(Objects.toString(error.getMessage(), error.toString()));
        JOptionPane.showMessageDialog(this, messages.getText(), "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    // #ИсходныеДанные: восстанавливает параметры сети и поездов.
    private void loadDefaults() {
        updating = true; Config config = Model.defaults(); edges.setRowCount(0); trains.setRowCount(0);
        for (Edge edge : config.edges()) edges.addRow(new Object[]{edge.a() + "-" + edge.b(), edge.km(), edge.enabled()});
        for (Train t : config.trains()) trains.addRow(new Object[]{t.id(), t.from(), t.to(), t.speed(), t.cars(), t.tanks(), "00:00:00", "", "Авто"});
        trainTable.setRowSelectionInterval(0, 0);
        carLength.setText("20"); tankLength.setText("20"); gap.setText("1"); legend.setText("ц = цистерны, в = вагоны");
        withCars.setSelected(true); routing.setSelectedIndex(0); updating = false; invalidateInputs();
    }

    // #СлучайныеДанные: меняет характеристики составов, оставляя текущую сеть.
    private void randomize() {
        updating = true; Random random = new Random();
        for (int i = 0; i < trains.getRowCount(); i++) {
            trains.setValueAt(25 + random.nextInt(46), i, 3); trains.setValueAt(random.nextInt(24), i, 4);
            trains.setValueAt(random.nextInt(18), i, 5); trains.setValueAt(TimeUtil.format(random.nextInt(900)), i, 6);
        }
        updating = false; invalidateInputs();
    }
}
