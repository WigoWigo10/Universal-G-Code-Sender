/*
    Copyright 2026 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.probing.Obstacle;
import com.willwinder.ugs.nbp.pcb.probing.ProbePlan;
import com.willwinder.universalgcodesender.model.Position;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * Shows a probing plan from above: the area that was asked for, the area that the points cover,
 * the points in the order they are measured with the path between them, the obstacles, and the
 * tool path of the loaded program behind it all.
 */
public class ProbePlanPreviewPanel extends JPanel {
    static final Color PATH_COLOR = new Color(0, 110, 200);
    static final Color POINT_COLOR = new Color(20, 20, 20);
    static final Color FIRST_POINT_COLOR = new Color(0, 150, 60);
    static final Color OBSTACLE_COLOR = new Color(210, 40, 40);
    static final Color TOOL_PATH_COLOR = new Color(190, 190, 190);
    static final Color AREA_COLOR = new Color(120, 120, 120);
    private static final Color TEXT_COLOR = new Color(70, 70, 70);
    private static final int MARGIN = 16;

    private transient ProbePlan plan;
    private transient List<Position> toolPath = List.of();

    public ProbePlanPreviewPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(320, 240));
        setMinimumSize(new Dimension(160, 120));
    }

    /**
     * @param plan     the plan to show, null if there is none
     * @param toolPath the positions in millimeters that the loaded program moves to
     */
    public void setData(ProbePlan plan, List<Position> toolPath) {
        this.plan = plan;
        this.toolPath = List.copyOf(toolPath);
        repaint();
    }

    Rectangle2D getWorldBounds() {
        Rectangle2D bounds = null;
        for (Position position : toolPath) {
            bounds = union(bounds, new Rectangle2D.Double(position.getX(), position.getY(), 0, 0));
        }
        if (plan != null) {
            bounds = union(bounds, rectangle(plan.settings().area()));
            bounds = union(bounds, rectangle(plan.gridArea()));
            for (Obstacle obstacle : plan.settings().obstacles()) {
                bounds = union(bounds, rectangle(obstacle.area()));
            }
        }
        return bounds;
    }

    private static Rectangle2D union(Rectangle2D first, Rectangle2D second) {
        return first == null ? second : first.createUnion(second);
    }

    private static Rectangle2D rectangle(ProbeArea area) {
        return new Rectangle2D.Double(area.minX(), area.minY(), area.width(), area.height());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D graphics = (Graphics2D) g.create();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(getFont().deriveFont(11f));

        Rectangle2D world = getWorldBounds();
        if (world == null) {
            graphics.setColor(TEXT_COLOR);
            graphics.drawString("No program and no plan", MARGIN, MARGIN + 12);
            graphics.dispose();
            return;
        }

        double width = Math.max(world.getWidth(), 1e-6);
        double height = Math.max(world.getHeight(), 1e-6);
        int availableWidth = Math.max(10, getWidth() - 2 * MARGIN);
        int availableHeight = Math.max(10, getHeight() - 2 * MARGIN - 14);
        double scale = Math.min(availableWidth / width, availableHeight / height);
        double left = MARGIN + (availableWidth - width * scale) / 2 - world.getMinX() * scale;
        // Y points up on the machine and down on the screen
        double bottom = MARGIN + (availableHeight + height * scale) / 2 + world.getMinY() * scale;

        graphics.setColor(TOOL_PATH_COLOR);
        graphics.setStroke(new BasicStroke(0.8f));
        for (int i = 1; i < toolPath.size(); i++) {
            Position start = toolPath.get(i - 1);
            Position end = toolPath.get(i);
            graphics.draw(new Line2D.Double(left + start.getX() * scale, bottom - start.getY() * scale, left + end.getX() * scale, bottom - end.getY() * scale));
        }

        if (plan != null) {
            paintPlan(graphics, scale, left, bottom);
        }
        graphics.dispose();
    }

    private void paintPlan(Graphics2D graphics, double scale, double left, double bottom) {
        graphics.setColor(AREA_COLOR);
        graphics.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
        graphics.draw(toScreen(plan.settings().area(), scale, left, bottom));
        graphics.setStroke(new BasicStroke(1f));
        graphics.draw(toScreen(plan.gridArea(), scale, left, bottom));

        graphics.setColor(new Color(OBSTACLE_COLOR.getRed(), OBSTACLE_COLOR.getGreen(), OBSTACLE_COLOR.getBlue(), 70));
        for (Obstacle obstacle : plan.settings().obstacles()) {
            graphics.fill(toScreen(obstacle.area(), scale, left, bottom));
        }
        graphics.setColor(OBSTACLE_COLOR);
        for (Obstacle obstacle : plan.settings().obstacles()) {
            graphics.draw(toScreen(obstacle.area(), scale, left, bottom));
        }

        List<Point2D> points = plan.points();
        graphics.setColor(PATH_COLOR);
        graphics.setStroke(new BasicStroke(1.2f));
        for (int i = 1; i < points.size(); i++) {
            graphics.draw(new Line2D.Double(left + points.get(i - 1).x() * scale, bottom - points.get(i - 1).y() * scale,
                    left + points.get(i).x() * scale, bottom - points.get(i).y() * scale));
        }
        for (int i = 0; i < points.size(); i++) {
            int x = (int) Math.round(left + points.get(i).x() * scale);
            int y = (int) Math.round(bottom - points.get(i).y() * scale);
            graphics.setColor(i == 0 ? FIRST_POINT_COLOR : POINT_COLOR);
            int size = i == 0 ? 8 : 4;
            graphics.fillOval(x - size / 2, y - size / 2, size, size);
        }

        graphics.setColor(TEXT_COLOR);
        graphics.drawString("Green: first point. Blue: path at the travel height. Dashed: area asked for.", MARGIN, getHeight() - 6);
    }

    private static Rectangle2D toScreen(ProbeArea area, double scale, double left, double bottom) {
        return new Rectangle2D.Double(left + area.minX() * scale, bottom - area.maxY() * scale, area.width() * scale, area.height() * scale);
    }
}
