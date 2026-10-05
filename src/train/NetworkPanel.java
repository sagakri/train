package train;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.Map;
import static train.Model.*;

// #СхемаСети: рисует X-сеть, занятые ресурсы и движение головы и условного хвоста.
public final class NetworkPanel extends JPanel {
    private static final Map<String, Point> POINTS = Map.of(
        "A", new Point(120, 70), "D", new Point(640, 70), "B", new Point(120, 490),
        "C", new Point(640, 490), "O", new Point(380, 280), "E", new Point(265, 187),
        "S", new Point(495, 373), "F", new Point(495, 187), "G", new Point(265, 373));
    public static final Color[] COLORS = {new Color(43, 112, 221), new Color(214, 96, 47), new Color(27, 149, 119), new Color(148, 84, 192)};
    private Config config = Model.defaults();
    private Plan plan;
    private double time;

    public NetworkPanel() {
        setPreferredSize(new Dimension(650, 430));
        setBackground(new Color(246, 249, 253));
    }

    public void display(Config config, Plan plan, double time) {
        this.config = config; this.plan = plan; this.time = time; repaint();
    }

    // #Перерисовка: координаты масштабируются под размер панели, а расстояния берутся из модели.
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double scale = Math.min(getWidth() / 760.0, getHeight() / 560.0);
        g.translate((getWidth() - 760 * scale) / 2, (getHeight() - 560 * scale) / 2);
        g.scale(scale, scale);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        for (Edge edge : config.edges()) {
            Point a = POINTS.get(edge.a()), b = POINTS.get(edge.b());
            g.setColor(!edge.enabled() ? new Color(214, 220, 230) : occupied(edge.key()) ? new Color(230, 150, 42) : new Color(91, 111, 137));
            g.setStroke(new BasicStroke(edge.enabled() ? 5 : 2));
            g.drawLine(a.x, a.y, b.x, b.y);
            g.setColor(new Color(57, 75, 96));
            String label = String.format(java.util.Locale.ROOT, "%s · %.1f км", edge.key(), edge.km());
            g.drawString(label, (a.x + b.x) / 2 + 8, (a.y + b.y) / 2 - 8);
        }
        for (String node : NODES) {
            Point p = POINTS.get(node);
            g.setColor(occupied(node) ? new Color(255, 201, 86) : Color.WHITE);
            g.fillOval(p.x - 15, p.y - 15, 30, 30);
            g.setColor(new Color(41, 60, 83));
            g.setStroke(new BasicStroke(2));
            g.drawOval(p.x - 15, p.y - 15, 30, 30);
            g.drawString(node, p.x - 6, p.y + 6);
        }
        if (plan != null) for (Schedule schedule : plan.schedules()) drawTrain(g, schedule);
        g.dispose();
    }

    private boolean occupied(String resource) {
        if (plan == null) return false;
        return plan.schedules().stream().flatMap(s -> s.intervals().stream())
            .anyMatch(i -> i.resource().equals(resource) && time >= i.start() && time <= i.end());
    }

    // #ПоложениеПоезда: интерполяция на участке; во время ожидания голова остаётся в узле.
    private void drawTrain(Graphics2D g, Schedule schedule) {
        if (time < schedule.start() || time > schedule.complete()) return;
        Point2D.Double position = null;
        Point previous = POINTS.get(schedule.route().get(0));
        for (Dwell dwell : schedule.dwells()) if (time >= dwell.start() && time <= dwell.end()) {
            Point p = POINTS.get(dwell.node()); position = new Point2D.Double(p.x, p.y);
            int index = schedule.route().indexOf(dwell.node());
            previous = POINTS.get(schedule.route().get(index - 1));
        }
        if (position == null) for (Segment segment : schedule.segments()) if (time >= segment.start() && time <= segment.end()) {
            Point a = POINTS.get(segment.from()), b = POINTS.get(segment.to());
            double ratio = (time - segment.start()) / (segment.end() - segment.start());
            position = new Point2D.Double(a.x + (b.x - a.x) * ratio, a.y + (b.y - a.y) * ratio);
            previous = a; break;
        }
        if (position == null) {
            Point p = POINTS.get(schedule.route().get(schedule.route().size() - 1));
            position = new Point2D.Double(p.x, p.y);
            previous = POINTS.get(schedule.route().get(schedule.route().size() - 2));
        }
        g.setColor(COLORS[(schedule.train().id() - 1) % COLORS.length]);
        double dx = position.x - previous.x, dy = position.y - previous.y, distance = Math.hypot(dx, dy);
        if (config.length(schedule.train()) > 0 && distance > 0) {
            double length = Math.min(44, Math.max(16, config.length(schedule.train()) / 18));
            g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(position.x, position.y, position.x - dx / distance * length, position.y - dy / distance * length));
        }
        g.fill(new Ellipse2D.Double(position.x - 10, position.y - 10, 20, 20));
        g.drawString("№" + schedule.train().id(), (float) position.x + 15, (float) position.y - 10);
    }
}
