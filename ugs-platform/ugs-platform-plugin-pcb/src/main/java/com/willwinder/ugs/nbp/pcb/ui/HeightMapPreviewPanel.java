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

import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.heightmap.SurfaceModel;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService;
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
import java.util.Locale;

/**
 * Shows from above what the height map covers and where the loaded program moves: the heights as
 * colors with the measured points on them, the cuts of the program as dark lines and its moves
 * above the board as light lines. Cuts where no height was measured are marked in red.
 */
public class HeightMapPreviewPanel extends JPanel {
    static final Color LOW_COLOR = new Color(59, 76, 192);
    static final Color MIDDLE_COLOR = new Color(232, 232, 232);
    static final Color HIGH_COLOR = new Color(180, 4, 38);
    static final Color CUT_COLOR = new Color(20, 20, 20);
    static final Color TRAVEL_COLOR = new Color(150, 150, 150);
    static final Color UNCOVERED_COLOR = new Color(220, 0, 0);
    static final Color POINT_COLOR = new Color(60, 60, 60);
    private static final Color TEXT_COLOR = new Color(70, 70, 70);
    private static final int MARGIN = 14;
    private static final int LEGEND_WIDTH = 86;
    private static final int MIN_WIDTH_WITH_LEGEND = 300;
    private static final int CELL_SIZE = 5;

    private transient List<Position> toolPath = List.of();
    private transient List<Position> uncovered = List.of();
    private transient SurfaceModel surface;

    public HeightMapPreviewPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(320, 260));
        setMinimumSize(new Dimension(160, 120));
    }

    /**
     * @param toolPath  the positions in millimeters that the program moves to
     * @param surface   the measured surface, or null if there is none
     * @param uncovered the positions where the program cuts and no height was measured
     */
    public void setData(List<Position> toolPath, SurfaceModel surface, List<Position> uncovered) {
        this.toolPath = List.copyOf(toolPath);
        this.surface = surface;
        this.uncovered = List.copyOf(uncovered);
        repaint();
    }

    /**
     * Returns the color for a height between the lowest and the highest height that was measured
     */
    static Color getColor(double height, double min, double max) {
        double t = max > min ? Math.max(0, Math.min(1, (height - min) / (max - min))) : 0.5;
        return t < 0.5 ? blend(LOW_COLOR, MIDDLE_COLOR, t * 2) : blend(MIDDLE_COLOR, HIGH_COLOR, t * 2 - 1);
    }

    private static Color blend(Color from, Color to, double t) {
        return new Color(
                (int) Math.round(from.getRed() + t * (to.getRed() - from.getRed())),
                (int) Math.round(from.getGreen() + t * (to.getGreen() - from.getGreen())),
                (int) Math.round(from.getBlue() + t * (to.getBlue() - from.getBlue())));
    }

    /**
     * Returns the area in millimeters that is shown, which has the program and the height map
     */
    Rectangle2D getWorldBounds() {
        Rectangle2D bounds = null;
        for (Position position : toolPath) {
            Rectangle2D point = new Rectangle2D.Double(position.getX(), position.getY(), 0, 0);
            bounds = bounds == null ? point : bounds.createUnion(point);
        }
        if (surface != null) {
            ProbeArea area = surface.getArea();
            Rectangle2D rectangle = new Rectangle2D.Double(area.minX(), area.minY(), area.width(), area.height());
            bounds = bounds == null ? rectangle : bounds.createUnion(rectangle);
        }
        return bounds;
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
            graphics.drawString("No program and no height map", MARGIN, MARGIN + 12);
            graphics.dispose();
            return;
        }

        double width = Math.max(world.getWidth(), 1e-6);
        double height = Math.max(world.getHeight(), 1e-6);
        // The legend is left out when it would take the place of what it explains
        boolean hasLegend = surface != null && getWidth() >= MIN_WIDTH_WITH_LEGEND;
        int availableWidth = Math.max(10, getWidth() - 2 * MARGIN - (hasLegend ? LEGEND_WIDTH : 0));
        int availableHeight = Math.max(10, getHeight() - 2 * MARGIN);
        double scale = Math.min(availableWidth / width, availableHeight / height);
        double left = MARGIN + (availableWidth - width * scale) / 2 - world.getMinX() * scale;
        // Y points up on the machine and down on the screen
        double bottom = MARGIN + (availableHeight + height * scale) / 2 + world.getMinY() * scale;

        if (surface != null) {
            paintSurface(graphics, scale, left, bottom);
        }
        paintToolPath(graphics, scale, left, bottom);
        if (hasLegend) {
            paintLegend(graphics);
        }
        graphics.dispose();
    }

    private void paintSurface(Graphics2D graphics, double scale, double left, double bottom) {
        ProbeArea area = surface.getArea();
        double min = surface.getMinHeight();
        double max = surface.getMaxHeight();
        int x0 = (int) Math.floor(left + area.minX() * scale);
        int x1 = (int) Math.ceil(left + area.maxX() * scale);
        int y0 = (int) Math.floor(bottom - area.maxY() * scale);
        int y1 = (int) Math.ceil(bottom - area.minY() * scale);

        for (int x = x0; x < x1; x += CELL_SIZE) {
            for (int y = y0; y < y1; y += CELL_SIZE) {
                double worldX = Math.max(area.minX(), Math.min(area.maxX(), (x + CELL_SIZE / 2.0 - left) / scale));
                double worldY = Math.max(area.minY(), Math.min(area.maxY(), (bottom - y - CELL_SIZE / 2.0) / scale));
                graphics.setColor(getColor(surface.heightAt(worldX, worldY), min, max));
                graphics.fillRect(x, y, Math.min(CELL_SIZE, x1 - x), Math.min(CELL_SIZE, y1 - y));
            }
        }

        graphics.setColor(POINT_COLOR);
        graphics.setStroke(new BasicStroke(1f));
        graphics.drawRect(x0, y0, x1 - x0, y1 - y0);
        for (Position point : surface.getPoints()) {
            int x = (int) Math.round(left + point.getX() * scale);
            int y = (int) Math.round(bottom - point.getY() * scale);
            graphics.fillOval(x - 2, y - 2, 4, 4);
        }
    }

    private void paintToolPath(Graphics2D graphics, double scale, double left, double bottom) {
        BasicStroke travelStroke = new BasicStroke(0.8f);
        BasicStroke cutStroke = new BasicStroke(1.2f);
        for (int i = 1; i < toolPath.size(); i++) {
            Position start = toolPath.get(i - 1);
            Position end = toolPath.get(i);
            boolean isCut = PcbHeightMapService.isCut(start) && PcbHeightMapService.isCut(end);
            graphics.setColor(isCut ? CUT_COLOR : TRAVEL_COLOR);
            graphics.setStroke(isCut ? cutStroke : travelStroke);
            graphics.draw(new Line2D.Double(left + start.getX() * scale, bottom - start.getY() * scale, left + end.getX() * scale, bottom - end.getY() * scale));
        }

        graphics.setColor(UNCOVERED_COLOR);
        for (Position position : uncovered) {
            int x = (int) Math.round(left + position.getX() * scale);
            int y = (int) Math.round(bottom - position.getY() * scale);
            graphics.fillOval(x - 2, y - 2, 4, 4);
        }
    }

    private void paintLegend(Graphics2D graphics) {
        int barLeft = getWidth() - LEGEND_WIDTH + 8;
        int barTop = MARGIN + 16;
        int barHeight = Math.max(20, Math.min(140, getHeight() - 2 * MARGIN - 60));
        double min = surface.getMinHeight();
        double max = surface.getMaxHeight();
        for (int y = 0; y < barHeight; y++) {
            graphics.setColor(getColor(max - (max - min) * y / (barHeight - 1.0), min, max));
            graphics.fillRect(barLeft, barTop + y, 12, 1);
        }
        graphics.setColor(TEXT_COLOR);
        graphics.drawRect(barLeft - 1, barTop - 1, 13, barHeight + 1);
        graphics.drawString("Height mm", barLeft, MARGIN + 8);
        graphics.drawString(format(max), barLeft + 17, barTop + 9);
        graphics.drawString(format(min), barLeft + 17, barTop + barHeight);

        int legendTop = barTop + barHeight + 18;
        graphics.setColor(CUT_COLOR);
        graphics.fillRect(barLeft, legendTop - 5, 12, 2);
        graphics.setColor(TEXT_COLOR);
        graphics.drawString("Cut", barLeft + 17, legendTop);
        graphics.setColor(TRAVEL_COLOR);
        graphics.fillRect(barLeft, legendTop + 9, 12, 1);
        graphics.setColor(TEXT_COLOR);
        graphics.drawString("Travel", barLeft + 17, legendTop + 14);
        if (!uncovered.isEmpty()) {
            graphics.setColor(UNCOVERED_COLOR);
            graphics.fillOval(barLeft + 3, legendTop + 21, 6, 6);
            graphics.setColor(TEXT_COLOR);
            graphics.drawString("Not covered", barLeft + 17, legendTop + 28);
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%+.3f", value);
    }
}
