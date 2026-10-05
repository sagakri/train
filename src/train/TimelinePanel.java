package train;

import javax.swing.*;
import java.awt.*;
import static train.Model.*;

// #ВременнаяДиаграмма: сопоставляет отправление, движение, остановки и выход хвоста поездов.
public final class TimelinePanel extends JPanel {
    private Plan plan;

    public TimelinePanel() {
        setPreferredSize(new Dimension(950, 230));
        setBackground(Color.WHITE);
    }

    public void display(Plan plan) { this.plan = plan; repaint(); }

    // #Диаграмма: общая шкала времени и отдельная строка для каждого поезда.
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (plan == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int left = 150, width = Math.max(1, getWidth() - left - 35);
        double total = Math.max(1, plan.total());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int tick = 0; tick <= 6; tick++) {
            int x = left + tick * width / 6;
            g.setColor(new Color(225, 230, 238)); g.drawLine(x, 28, x, 180);
            g.setColor(Color.DARK_GRAY); g.drawString(TimeUtil.format(total * tick / 6), x - 25, 20);
        }
        int row = 0;
        for (Schedule s : plan.schedules()) {
            int y = 40 + row++ * 36;
            g.setColor(Color.DARK_GRAY); g.drawString("№" + s.train().id() + " " + String.join("-", s.route()), 8, y + 16);
            g.setColor(new Color(220, 225, 233));
            fill(g, left, width, total, 0, s.start(), y);
            g.setColor(NetworkPanel.COLORS[(s.train().id() - 1) % NetworkPanel.COLORS.length]);
            fill(g, left, width, total, s.start(), s.arrival(), y);
            g.setColor(new Color(250, 190, 65));
            for (Dwell d : s.dwells()) fill(g, left, width, total, d.start(), d.end(), y);
            g.setColor(new Color(165, 190, 215)); fill(g, left, width, total, s.arrival(), s.complete(), y);
        }
        g.setColor(Color.DARK_GRAY);
        g.drawString("Серый — ожидание отправления; цвет поезда — движение; жёлтый — остановка; голубой — выход хвоста.", 8, 210);
        g.dispose();
    }

    private static void fill(Graphics2D g, int left, int width, double total, double start, double end, int y) {
        if (end <= start) return;
        int x = left + (int) Math.round(start / total * width);
        int w = Math.max(1, (int) Math.round((end - start) / total * width));
        g.fillRect(x, y, w, 23);
    }
}
