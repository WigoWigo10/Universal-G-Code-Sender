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
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the alignment from above. Nothing is calculated here, the alignment is only drawn.
 * <p>
 * The corrections of a PCB are a fraction of a millimeter on a board that is a hundred times
 * larger, so they can not be seen in a drawing of the whole board. They are therefore shown in
 * two more ways:
 * <ul>
 * <li>In the overview, an arrow at every reference point shows how far the point was measured from
 * its nominal position. The arrows are magnified, and the factor is written in the drawing.</li>
 * <li>Below the overview, there is a detail of every reference point, which is drawn to scale and
 * whose width is written above it. It shows how far the measured position is from where the alignment
 * puts the point.</li>
 * </ul>
 */
public class AlignmentPreviewPanel extends JPanel {
    private static final Color ORIGINAL_COLOR = new Color(150, 150, 150);
    private static final Color ALIGNED_COLOR = new Color(30, 110, 220);
    private static final Color MEASURED_COLOR = new Color(210, 50, 40);
    private static final Color VECTOR_COLOR = new Color(230, 130, 0);
    private static final Color TEXT_COLOR = new Color(70, 70, 70);
    private static final int MARGIN = 24;
    private static final int MARKER = 5;
    private static final int MAX_DETAILS = 6;
    private static final int DETAIL_GAP = 6;

    /**
     * How long the longest magnified arrow is, as a part of the size of what is drawn
     */
    private static final double VECTOR_SIZE = 0.12;

    /**
     * The smallest size in millimeters that a detail shows from its center to its edge
     */
    private static final double MIN_DETAIL_SIZE = 0.01;

    private List<Point2D> programPath = List.of();
    private List<ReferencePoint> points = List.of();
    private RigidTransform2D transform;

    public AlignmentPreviewPanel() {
        setPreferredSize(new Dimension(300, 280));
        setMinimumSize(new Dimension(200, 180));
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

    public List<Point2D> getProgramPath() {
        return programPath;
    }

    /**
     * @param transform the alignment to show, or null if there is none
     */
    public void setAlignment(List<ReferencePoint> points, RigidTransform2D transform) {
        this.points = List.copyOf(points);
        this.transform = transform;
        repaint();
    }

    /**
     * Returns the factor that the displacement arrows are magnified by, so that the longest one is clearly
     * visible. It is a round number that is easy to read, and never less than one.
     *
     * @param size                the size in millimeters of what is drawn
     * @param largestDisplacement the length in millimeters of the longest arrow
     */
    static int getMagnification(double size, double largestDisplacement) {
        if (!(largestDisplacement > 0) || !(size > 0)) {
            return 1;
        }
        return (int) Math.max(1, Math.min(100000, roundDown(VECTOR_SIZE * size / largestDisplacement)));
    }

    /**
     * Returns the size in millimeters that the details show from their center to their edge, which is a
     * round number that has room for the largest distance to show
     */
    static double getDetailSize(double largestDistance) {
        if (!(largestDistance > 0)) {
            return MIN_DETAIL_SIZE;
        }
        return Math.max(MIN_DETAIL_SIZE, roundUp(largestDistance * 1.5));
    }

    /**
     * Rounds down to 1, 2 or 5 times a power of ten
     */
    static double roundDown(double value) {
        double power = Math.pow(10, Math.floor(Math.log10(value)));
        double digit = value / power;
        return (digit >= 5 ? 5 : digit >= 2 ? 2 : 1) * power;
    }

    /**
     * Rounds up to 1, 2 or 5 times a power of ten
     */
    static double roundUp(double value) {
        double power = Math.pow(10, Math.floor(Math.log10(value)));
        double digit = value / power;
        return (digit <= 1 ? 1 : digit <= 2 ? 2 : digit <= 5 ? 5 : 10) * power;
    }

    /**
     * A reference point with all its positions, as far as they are known
     *
     * @param aligned where the alignment puts the nominal position, or null if there is no alignment
     */
    private record Marker(int number, Point2D nominal, Point2D measured, Point2D aligned) {
    }

    private List<Marker> getMarkers() {
        List<Marker> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            ReferencePoint point = points.get(i);
            Point2D nominal = isFinite(point.nominalX(), point.nominalY()) ? new Point2D(point.nominalX(), point.nominalY()) : null;
            Point2D measured = isFinite(point.measuredX(), point.measuredY()) ? new Point2D(point.measuredX(), point.measuredY()) : null;
            Point2D aligned = nominal != null && transform != null ? transform.apply(nominal) : null;
            result.add(new Marker(i + 1, nominal, measured, aligned));
        }
        return result;
    }

    private static boolean isFinite(Double x, Double y) {
        return x != null && y != null && Double.isFinite(x) && Double.isFinite(y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D graphics = (Graphics2D) g.create();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setFont(getFont().deriveFont(Font.PLAIN, 11f));

        List<Marker> markers = getMarkers();
        List<Marker> details = markers.stream().filter(marker -> marker.measured() != null && marker.aligned() != null).limit(MAX_DETAILS).toList();
        int detailSize = details.isEmpty() ? 0 : Math.max(48, Math.min(96, Math.min(getHeight() / 3, (getWidth() - DETAIL_GAP) / details.size() - DETAIL_GAP)));
        int detailTop = getHeight() - detailSize - (details.isEmpty() ? 0 : 20);

        // The line above the details is for their caption
        paintOverview(graphics, markers, new Rectangle(0, 0, getWidth(), details.isEmpty() ? detailTop : detailTop - 18));
        paintDetails(graphics, details, detailTop, detailSize);
        graphics.dispose();
    }

    private void paintOverview(Graphics2D graphics, List<Marker> markers, Rectangle area) {
        List<Point2D> alignedPath = transform == null ? List.of() : programPath.stream().map(transform::apply).toList();
        List<Point2D> all = new ArrayList<>(programPath);
        all.addAll(alignedPath);
        for (Marker marker : markers) {
            for (Point2D point : new Point2D[]{marker.nominal(), marker.measured(), marker.aligned()}) {
                if (point != null) {
                    all.add(point);
                }
            }
        }
        if (all.isEmpty()) {
            graphics.setColor(Color.GRAY);
            graphics.drawString("Nothing to preview", MARGIN, area.height / 2);
            return;
        }

        double minX = all.stream().mapToDouble(Point2D::x).min().orElse(0);
        double maxX = all.stream().mapToDouble(Point2D::x).max().orElse(0);
        double minY = all.stream().mapToDouble(Point2D::y).min().orElse(0);
        double maxY = all.stream().mapToDouble(Point2D::y).max().orElse(0);
        double size = Math.max(maxX - minX, maxY - minY);
        double scale = Math.min(
                (area.width - 2.0 * MARGIN) / Math.max(maxX - minX, 1e-6),
                (area.height - 2.0 * MARGIN - 14) / Math.max(maxY - minY, 1e-6));
        View view = new View(MARGIN, MARGIN, minX, maxY, scale);

        graphics.setStroke(new BasicStroke(1f));
        graphics.setColor(ORIGINAL_COLOR);
        drawPath(graphics, view, programPath);
        graphics.setColor(ALIGNED_COLOR);
        drawPath(graphics, view, alignedPath);

        double largestDisplacement = markers.stream()
                .filter(marker -> marker.nominal() != null && marker.measured() != null)
                .mapToDouble(marker -> marker.nominal().distance(marker.measured()))
                .max().orElse(0);
        int magnification = getMagnification(size, largestDisplacement);

        for (Marker marker : markers) {
            if (marker.nominal() != null) {
                graphics.setColor(ORIGINAL_COLOR);
                drawCircle(graphics, view.x(marker.nominal()), view.y(marker.nominal()));
                graphics.setColor(TEXT_COLOR);
                graphics.drawString(String.valueOf(marker.number()), (int) view.x(marker.nominal()) + MARKER + 3, (int) view.y(marker.nominal()) - MARKER);
            }
            if (marker.aligned() != null) {
                graphics.setColor(ALIGNED_COLOR);
                drawCircle(graphics, view.x(marker.aligned()), view.y(marker.aligned()));
            }
            if (marker.measured() != null) {
                graphics.setColor(MEASURED_COLOR);
                drawCross(graphics, view.x(marker.measured()), view.y(marker.measured()));
            }
            if (marker.nominal() != null && marker.measured() != null && largestDisplacement > 0) {
                // The arrow starts in the nominal position and is as long as the displacement times the magnification
                Point2D tip = new Point2D(
                        marker.nominal().x() + magnification * (marker.measured().x() - marker.nominal().x()),
                        marker.nominal().y() + magnification * (marker.measured().y() - marker.nominal().y()));
                graphics.setColor(VECTOR_COLOR);
                drawArrow(graphics, view.x(marker.nominal()), view.y(marker.nominal()), view.x(tip), view.y(tip));
            }
        }

        drawLegend(graphics, area, largestDisplacement > 0 ? magnification : 0);
    }

    private void paintDetails(Graphics2D graphics, List<Marker> details, int top, int size) {
        if (details.isEmpty()) {
            return;
        }

        double largestDistance = details.stream().mapToDouble(marker -> marker.measured().distance(marker.aligned())).max().orElse(0);
        double detailSize = getDetailSize(largestDistance);
        double scale = (size / 2.0) / detailSize;

        for (int i = 0; i < details.size(); i++) {
            Marker marker = details.get(i);
            int left = DETAIL_GAP + i * (size + DETAIL_GAP);
            Shape clip = graphics.getClip();
            graphics.setColor(new Color(248, 248, 248));
            graphics.fillRect(left, top, size, size);
            graphics.setColor(Color.LIGHT_GRAY);
            graphics.drawRect(left, top, size, size);
            graphics.clipRect(left + 1, top + 1, size - 1, size - 1);

            // The detail is centered on where the alignment puts the point and is drawn to scale
            double centerX = left + size / 2.0;
            double centerY = top + size / 2.0;
            double measuredX = centerX + (marker.measured().x() - marker.aligned().x()) * scale;
            double measuredY = centerY - (marker.measured().y() - marker.aligned().y()) * scale;
            graphics.setStroke(new BasicStroke(1f));
            graphics.setColor(VECTOR_COLOR);
            graphics.drawLine((int) centerX, (int) centerY, (int) measuredX, (int) measuredY);
            graphics.setColor(ALIGNED_COLOR);
            drawCircle(graphics, centerX, centerY);
            graphics.setColor(MEASURED_COLOR);
            graphics.setStroke(new BasicStroke(1.5f));
            drawCross(graphics, measuredX, measuredY);
            graphics.setClip(clip);

            graphics.setColor(TEXT_COLOR);
            graphics.drawString("#" + marker.number() + " " + AlignmentTableModel.format(marker.measured().distance(marker.aligned())) + " mm", left + 2, top + size + 13);
        }

        // The details are drawn to scale, so their width tells how large the distances in them are
        graphics.setColor(TEXT_COLOR);
        graphics.drawString("Details to scale, each " + AlignmentTableModel.format(2 * detailSize) + " mm wide", DETAIL_GAP, top - 5);
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

    private void drawCircle(Graphics2D graphics, double x, double y) {
        graphics.drawOval((int) x - MARKER, (int) y - MARKER, 2 * MARKER, 2 * MARKER);
    }

    private void drawCross(Graphics2D graphics, double x, double y) {
        graphics.drawLine((int) x - MARKER, (int) y - MARKER, (int) x + MARKER, (int) y + MARKER);
        graphics.drawLine((int) x - MARKER, (int) y + MARKER, (int) x + MARKER, (int) y - MARKER);
    }

    private void drawArrow(Graphics2D graphics, double fromX, double fromY, double toX, double toY) {
        graphics.setStroke(new BasicStroke(1.5f));
        graphics.drawLine((int) fromX, (int) fromY, (int) toX, (int) toY);
        double angle = Math.atan2(toY - fromY, toX - fromX);
        for (double side : new double[]{-0.45, 0.45}) {
            graphics.drawLine((int) toX, (int) toY, (int) (toX - 7 * Math.cos(angle + side)), (int) (toY - 7 * Math.sin(angle + side)));
        }
        graphics.setStroke(new BasicStroke(1f));
    }

    private void drawLegend(Graphics2D graphics, Rectangle area, int magnification) {
        int y = area.height - 6;
        graphics.setColor(ORIGINAL_COLOR);
        graphics.drawString("o original", 6, y);
        graphics.setColor(ALIGNED_COLOR);
        graphics.drawString("o aligned", 66, y);
        graphics.setColor(MEASURED_COLOR);
        graphics.drawString("x measured", 122, y);
        if (magnification > 0) {
            graphics.setColor(VECTOR_COLOR);
            String text = magnification == 1 ? "arrows: displacement to scale" : "arrows: displacement x" + magnification + ", not to scale";
            graphics.drawString(text, 6, 13);
        }
    }

    /**
     * Converts positions to pixels, with Y pointing up as on the machine
     */
    private record View(double left, double top, double minX, double maxY, double scale) {
        double x(Point2D point) {
            return left + (point.x() - minX) * scale;
        }

        double y(Point2D point) {
            return top + 14 + (maxY - point.y()) * scale;
        }
    }
}
