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
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.ReferencePoint;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the program and the reference points from above, as they are in the program and as they
 * will be when the alignment is applied. Nothing is calculated here, the alignment is only drawn.
 */
public class AlignmentPreviewPanel extends JPanel {
    private static final Color ORIGINAL_COLOR = new Color(150, 150, 150);
    private static final Color ALIGNED_COLOR = new Color(30, 110, 220);
    private static final Color MEASURED_COLOR = new Color(210, 50, 40);
    private static final int MARGIN = 24;
    private static final int MARKER = 5;

    private List<Point2D> programPath = List.of();
    private List<ReferencePoint> points = List.of();
    private RigidTransform2D transform;

    public AlignmentPreviewPanel() {
        setPreferredSize(new Dimension(260, 220));
        setMinimumSize(new Dimension(160, 140));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
    }

    /**
     * @param programPath the positions of the original program
     */
    public void setProgramPath(List<Point2D> programPath) {
        this.programPath = programPath;
        repaint();
    }

    /**
     * @param transform the alignment to show, or null if there is none
     */
    public void setAlignment(List<ReferencePoint> points, RigidTransform2D transform) {
        this.points = List.copyOf(points);
        this.transform = transform;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D graphics = (Graphics2D) g.create();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        List<Point2D> nominal = new ArrayList<>();
        List<Point2D> measured = new ArrayList<>();
        for (ReferencePoint point : points) {
            if (isFinite(point.nominalX(), point.nominalY())) {
                nominal.add(new Point2D(point.nominalX(), point.nominalY()));
            }
            if (isFinite(point.measuredX(), point.measuredY())) {
                measured.add(new Point2D(point.measuredX(), point.measuredY()));
            }
        }
        List<Point2D> alignedPath = transform == null ? List.of() : programPath.stream().map(transform::apply).toList();
        List<Point2D> alignedNominal = transform == null ? List.of() : nominal.stream().map(transform::apply).toList();

        List<Point2D> all = new ArrayList<>(programPath);
        all.addAll(alignedPath);
        all.addAll(nominal);
        all.addAll(alignedNominal);
        all.addAll(measured);
        if (all.isEmpty()) {
            graphics.setColor(Color.GRAY);
            graphics.drawString("Nothing to preview", MARGIN, getHeight() / 2);
            graphics.dispose();
            return;
        }

        double minX = all.stream().mapToDouble(Point2D::x).min().orElse(0);
        double maxX = all.stream().mapToDouble(Point2D::x).max().orElse(0);
        double minY = all.stream().mapToDouble(Point2D::y).min().orElse(0);
        double maxY = all.stream().mapToDouble(Point2D::y).max().orElse(0);
        double scale = Math.min(
                (getWidth() - 2.0 * MARGIN) / Math.max(maxX - minX, 1e-6),
                (getHeight() - 2.0 * MARGIN) / Math.max(maxY - minY, 1e-6));
        View view = new View(minX, maxY, scale);

        graphics.setStroke(new BasicStroke(1f));
        graphics.setColor(ORIGINAL_COLOR);
        drawPath(graphics, view, programPath);
        nominal.forEach(point -> drawCircle(graphics, view, point));

        graphics.setColor(ALIGNED_COLOR);
        drawPath(graphics, view, alignedPath);
        alignedNominal.forEach(point -> drawCircle(graphics, view, point));

        graphics.setColor(MEASURED_COLOR);
        graphics.setStroke(new BasicStroke(1.5f));
        measured.forEach(point -> drawCross(graphics, view, point));

        drawLegend(graphics);
        graphics.dispose();
    }

    private static boolean isFinite(Double x, Double y) {
        return x != null && y != null && Double.isFinite(x) && Double.isFinite(y);
    }

    private void drawPath(Graphics2D graphics, View view, List<Point2D> path) {
        if (path.size() < 2) {
            return;
        }
        Path2D.Double shape = new Path2D.Double();
        shape.moveTo(view.x(path.get(0)), view.y(path.get(0)));
        for (int i = 1; i < path.size(); i++) {
            shape.lineTo(view.x(path.get(i)), view.y(path.get(i)));
        }
        graphics.draw(shape);
    }

    private void drawCircle(Graphics2D graphics, View view, Point2D point) {
        graphics.drawOval((int) view.x(point) - MARKER, (int) view.y(point) - MARKER, 2 * MARKER, 2 * MARKER);
    }

    private void drawCross(Graphics2D graphics, View view, Point2D point) {
        int x = (int) view.x(point);
        int y = (int) view.y(point);
        graphics.drawLine(x - MARKER, y - MARKER, x + MARKER, y + MARKER);
        graphics.drawLine(x - MARKER, y + MARKER, x + MARKER, y - MARKER);
    }

    private void drawLegend(Graphics2D graphics) {
        int y = getHeight() - 6;
        graphics.setColor(ORIGINAL_COLOR);
        graphics.drawString("original", 6, y);
        graphics.setColor(ALIGNED_COLOR);
        graphics.drawString("aligned", 62, y);
        graphics.setColor(MEASURED_COLOR);
        graphics.drawString("x measured", 114, y);
    }

    /**
     * Converts positions to pixels, with Y pointing up as on the machine
     */
    private record View(double minX, double maxY, double scale) {
        double x(Point2D point) {
            return MARGIN + (point.x() - minX) * scale;
        }

        double y(Point2D point) {
            return MARGIN + (maxY - point.y()) * scale;
        }
    }
}
