/*
    Copyright 2016-2022 Will Winder

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
package com.willwinder.ugs.nbm.visualizer.renderables;

import static com.jogamp.opengl.GL.GL_LINES;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.awt.TextRenderer;
import com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions;

import static com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions.VISUALIZER_OPTION_GRID;
import static com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions.VISUALIZER_OPTION_X;
import static com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions.VISUALIZER_OPTION_XY_GRID;
import static com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions.VISUALIZER_OPTION_PLANE_COLOR;
import static com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions.VISUALIZER_OPTION_SIZE;
import static com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions.VISUALIZER_OPTION_Y;
import static com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions.VISUALIZER_OPTION_Z;
import com.willwinder.ugs.nbm.visualizer.shared.Renderable;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.visualizer.VisualizerUtils;

import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.text.DecimalFormat;

/**
 *
 * @author wwinder
 */
public class Grid extends Renderable {
    private float[] gridLineColor;
    private float[] gridPlaneColor;
    private float[] xAxisColor;
    private float[] yAxisColor;
    private float[] zAxisColor;
    private float[] labelColor;
    private TextRenderer textRenderer;
    private boolean textRendererDirty = true;

    public Grid(String title) {
        super(5, title, VISUALIZER_OPTION_GRID);
        reloadPreferences(new VisualizerOptions());
    }

    @Override
    final public void reloadPreferences(VisualizerOptions vo) {
        gridLineColor = VisualizerOptions.colorToFloatArray(vo.getOptionForKey(VISUALIZER_OPTION_XY_GRID).value);
        gridPlaneColor = VisualizerOptions.colorToFloatArray(vo.getOptionForKey(VISUALIZER_OPTION_PLANE_COLOR).value);
        xAxisColor = VisualizerOptions.colorToFloatArray(vo.getOptionForKey(VISUALIZER_OPTION_X).value);
        yAxisColor = VisualizerOptions.colorToFloatArray(vo.getOptionForKey(VISUALIZER_OPTION_Y).value);
        zAxisColor = VisualizerOptions.colorToFloatArray(vo.getOptionForKey(VISUALIZER_OPTION_Z).value);
        labelColor = VisualizerOptions.colorToFloatArray(vo.getOptionForKey(VISUALIZER_OPTION_SIZE).value);
        textRendererDirty = true;
    }

    @Override
    public boolean enableLighting() {
        return false;
    }

    @Override
    public boolean rotate() {
        return true;
    }

    @Override
    public boolean center() {
        return true;
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        textRenderer = new TextRenderer(new Font("SansSerif", Font.PLAIN, 72));
        textRenderer.setColor(labelColor[0], labelColor[1], labelColor[2], labelColor[3]);
        labelFontHeight = textRenderer.getBounds("0").getHeight();
        textRendererDirty = false;
    }

    /**
     * The distances that there can be between the lines of the grid, in the preferred units
     */
    private static final double[] STEP_SIZES = {0.01, 0.05, 0.1, 0.5, 1, 5, 10, 50, 100, 500, 1000, 5000};
    private static final int DEFAULT_STEP_INDEX = 6;
    private static final int MAX_CELLS = 40;
    private static final int MIN_CELLS = 4;
    private static final int LABEL_INTERVAL = 2;
    private static final double MM_PER_INCH = 25.4;
    private static final DecimalFormat LABEL_FORMAT = new DecimalFormat("#.##");
    private double labelFontHeight = 1;

    /**
     * Returns the distance between the lines of the grid. It is 10 units for programs of a usual size, and
     * another round distance when that would give too many or too few lines.
     *
     * @param maxSide the longest side of what is shown, in the same units as the result
     */
    static double getStepSize(double maxSide) {
        int index = DEFAULT_STEP_INDEX;
        while (index < STEP_SIZES.length - 1 && maxSide / STEP_SIZES[index] > MAX_CELLS) {
            index++;
        }
        while (index > 0 && maxSide / STEP_SIZES[index] < MIN_CELLS) {
            index--;
        }
        return STEP_SIZES[index];
    }

    /**
     * Returns the number of the first line of the grid, where line 0 goes through the origin
     */
    static long getFirstLine(double min, double stepSize) {
        return (long) Math.floor(min / stepSize + 1e-9);
    }

    /**
     * Returns the number of the last line of the grid, which is always after the first one
     */
    static long getLastLine(double min, double max, double stepSize) {
        return Math.max((long) Math.ceil(max / stepSize - 1e-9), getFirstLine(min, stepSize) + 1);
    }

    static String getLabel(long line, double stepSize) {
        return LABEL_FORMAT.format(line * stepSize);
    }

    private static double getMillimetersPerUnit() {
        return LookupService.lookupOptional(BackendAPI.class)
                .map(backend -> backend.getSettings().getPreferredUnits())
                .filter(units -> units == Units.INCH)
                .map(units -> MM_PER_INCH)
                .orElse(1d);
    }

    @Override
    public void draw(GLAutoDrawable drawable, boolean idle, Position machineCoord, Position workCoord, Position focusMin, Position focusMax, double scaleFactor, Position mouseCoordinates, Position rotation) {
        double maxSide = VisualizerUtils.findMaxSide(focusMin, focusMax);
        if (maxSide == 0) {
            maxSide = 1;
        }

        // The positions are in millimeters, and the lines are at round positions in the preferred units
        double millimetersPerUnit = getMillimetersPerUnit();
        double unitStepSize = getStepSize(maxSide / millimetersPerUnit);
        double stepSize = unitStepSize * millimetersPerUnit;

        long firstX = getFirstLine(focusMin.x, stepSize);
        long lastX = getLastLine(focusMin.x, focusMax.x, stepSize);
        long firstY = getFirstLine(focusMin.y, stepSize);
        long lastY = getLastLine(focusMin.y, focusMax.y, stepSize);
        double minX = firstX * stepSize;
        double maxX = lastX * stepSize;
        double minY = firstY * stepSize;
        double maxY = lastY * stepSize;

        GL2 gl = drawable.getGL().getGL2();
        gl.glPushMatrix();
            double offset = 0.001;

            gl.glLineWidth(1f);
            // grid
            gl.glBegin(GL_LINES);
            gl.glColor4fv(gridLineColor, 0);
            for (long line = firstX; line <= lastX; line++) {
                double x = line * stepSize;
                gl.glVertex3d(x, minY, offset);
                gl.glVertex3d(x, maxY, offset);
                gl.glVertex3d(x, minY, -offset);
                gl.glVertex3d(x, maxY, -offset);
            }
            for (long line = firstY; line <= lastY; line++) {
                double y = line * stepSize;
                gl.glVertex3d(minX, y, offset);
                gl.glVertex3d(maxX, y, offset);
                gl.glVertex3d(minX, y, -offset);
                gl.glVertex3d(maxX, y, -offset);
            }
            gl.glEnd();

            // The axes start at the origin and point to the positive side
            double axisLength = 2 * stepSize;
            gl.glLineWidth(2f);
            gl.glBegin(GL_LINES);
                // X Axis Line
                gl.glColor4fv(xAxisColor, 0);
                gl.glVertex3d(0, 0, offset);
                gl.glVertex3d(Math.max(maxX, axisLength), 0, offset);
                gl.glVertex3d(0, 0, -offset);
                gl.glVertex3d(Math.max(maxX, axisLength), 0, -offset);

                // Y Axis Line
                gl.glColor4fv(yAxisColor, 0);
                gl.glVertex3d(0, 0, offset);
                gl.glVertex3d(0, Math.max(maxY, axisLength), offset);
                gl.glVertex3d(0, 0, -offset);
                gl.glVertex3d(0, Math.max(maxY, axisLength), -offset);

                // Z Axis Line
                gl.glColor4fv(zAxisColor, 0);
                gl.glVertex3d(0, 0, 0);
                gl.glVertex3d(0, 0, Math.max(Math.max(focusMax.z, -focusMin.z), axisLength));
            gl.glEnd();

            // floor - cover entire model and a little extra, unless it is fully transparent
            if (gridPlaneColor[3] > 0) {
                gl.glColor4fv(gridPlaneColor, 0);
                gl.glBegin(GL2.GL_QUADS);
                    gl.glVertex3d(minX, minY, 0);
                    gl.glVertex3d(minX, maxY, 0);
                    gl.glVertex3d(maxX, maxY, 0);
                    gl.glVertex3d(maxX, minY, 0);
                gl.glEnd();
            }

            drawLabels(drawable, stepSize, unitStepSize, firstX, lastX, firstY, lastY);
        gl.glPopMatrix();
    }

    /**
     * Writes the position of every other line along the axes, or along the edge of the grid that is the closest
     * to an axis that is outside of the grid.
     */
    private void drawLabels(GLAutoDrawable drawable, double stepSize, double unitStepSize, long firstX, long lastX, long firstY, long lastY) {
        if (textRendererDirty || textRenderer == null) {
            init(drawable);
        }

        // The labels are as large as the cells, so that they never run into each other
        double height = stepSize * 0.25;
        double gap = stepSize * 0.15;
        float scale = (float) (height / labelFontHeight);
        double axisX = Math.min(Math.max(0, firstX), lastX) * stepSize;
        double axisY = Math.min(Math.max(0, firstY), lastY) * stepSize;

        textRenderer.begin3DRendering();
        for (long line = firstX; line <= lastX; line++) {
            if (line % LABEL_INTERVAL != 0) {
                continue;
            }
            String label = getLabel(line, unitStepSize);
            Rectangle2D bounds = textRenderer.getBounds(label);
            textRenderer.draw3D(label, (float) (line * stepSize - bounds.getWidth() * scale / 2), (float) (axisY - gap - height), 0f, scale);
        }
        for (long line = firstY; line <= lastY; line++) {
            if (line % LABEL_INTERVAL != 0) {
                continue;
            }
            String label = getLabel(line, unitStepSize);
            Rectangle2D bounds = textRenderer.getBounds(label);
            textRenderer.draw3D(label, (float) (axisX - gap - bounds.getWidth() * scale), (float) (line * stepSize - height / 2), 0f, scale);
        }
        textRenderer.end3DRendering();
    }


    @Override
    public void setEnabled(boolean enabled) {
        VisualizerOptions.setBooleanOption(VISUALIZER_OPTION_GRID, enabled);
    }

    @Override
    public boolean isEnabled() {
        return VisualizerOptions.getBooleanOption(VISUALIZER_OPTION_GRID, true);
    }
}
