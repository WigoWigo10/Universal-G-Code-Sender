/*
    Copyright 2017-2024 Will Winder

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
package com.willwinder.ugs.platform.surfacescanner.renderable;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.Iterables;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.gl2.GLUT;
import com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions;
import static com.willwinder.ugs.nbm.visualizer.options.VisualizerOptions.VISUALIZER_OPTION_AUTOLEVEL_PREVIEW;
import com.willwinder.ugs.nbm.visualizer.shared.Renderable;
import com.willwinder.universalgcodesender.model.CNCPoint;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.Arrays;
import java.util.stream.DoubleStream;

/**
 * @author wwinder
 */
public class AutoLevelPreview extends Renderable {

    private static final String TOUCH_PLATE_VISIBLE_OPTION = "platform.visualizer.autolevel.touch-plate";

    private final GLUT glut;

    private ImmutableCollection<Position> positions;
    private Position[][] grid = null;
    private double touchPlateThickness = 0;
    private double probeAreaMinZ = 0;
    private boolean touchPlateVisible = VisualizerOptions.getBooleanOption(TOUCH_PLATE_VISIBLE_OPTION, true);

    // The maximum distance of a probe used for coloring.
    private double maxZ;
    private double minZ;

    private float[] high = {0.7f, 0.02f, 0.15f, 0.8f};
    private float[] low = {0.23f, 0.3f, 0.75f, 0.8f};
    private static final float[] touchPlateColor = {0.4f, 0.5f, 0.8f, 0.35f};
    private static final float[] probeAreaColor = {0.3f, 0, 0, 0.2f};
    private static final float[] MIDDLE_COLOR = {0.91f, 0.91f, 0.91f};
    private static final float[] MESH_COLOR = {0.1f, 0.1f, 0.1f, 0.3f};
    private static final float[] POINT_COLOR = {0.2f, 0.2f, 0.2f, 1.0f};
    private static final float POINT_SIZE = 5f;

    public AutoLevelPreview(String title) {
        super(10, title, VISUALIZER_OPTION_AUTOLEVEL_PREVIEW);

        glut = new GLUT();

        reloadPreferences(new VisualizerOptions());
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
    }

    @Override
    public final void reloadPreferences(VisualizerOptions vo) {
        super.reloadPreferences(vo);
        high = VisualizerOptions.colorToFloatArray(vo.getOptionForKey(VisualizerOptions.VISUALIZER_OPTION_HIGH).value);
        low = VisualizerOptions.colorToFloatArray(vo.getOptionForKey(VisualizerOptions.VISUALIZER_OPTION_LOW).value);
    }

    public boolean isTouchPlateVisible() {
        return touchPlateVisible;
    }

    public void setTouchPlateVisible(boolean touchPlateVisible) {
        this.touchPlateVisible = touchPlateVisible;
        VisualizerOptions.setBooleanOption(TOUCH_PLATE_VISIBLE_OPTION, touchPlateVisible);
    }

    public void updateSettings(
            ImmutableCollection<Position> positions,
            final Position[][] grid,
            double probeAreaMinZ,
            double touchPlateThickness) {
        this.probeAreaMinZ = probeAreaMinZ;
        this.touchPlateThickness = touchPlateThickness;
        if (positions != null && !positions.isEmpty()) {
            this.positions = positions;
            this.grid = grid;
            updateMinMaxZ();
        }
    }

    @Override
    public void draw(GLAutoDrawable drawable, boolean idle, Position machineCoord, Position workCoord, Position objectMin, Position objectMax, double scaleFactor, Position mouseWorldCoordinates, Position rotation) {

        // Don't draw something invalid.
        if (positions == null || positions.isEmpty()) {
            return;
        }

        Position first = Iterables.getFirst(positions, null);
        Units unit = first.getUnits();

        double minx, miny;
        double maxx, maxy;
        double maxz;

        minx = maxx = first.x;
        miny = maxy = first.y;
        maxz = first.z;

        GL2 gl = drawable.getGL().getGL2();
        gl.glPushMatrix();
        // Scale inch to mm if needed
        double scale = UnitUtils.scaleUnits(unit, Units.MM);
        if (unit != Units.MM) {
            gl.glScaled(scale, scale, scale);
        }

        // With lighting enabled the alpha channel is taken from the diffuse material instead of the
        // current color, so translucent geometry would be drawn opaque. The surface has no normals
        // either, so without lighting its colors are exactly the ones of its heights.
        gl.glDisable(GL2.GL_LIGHTING);

        // Dots indicating the probe start locations.
        gl.glEnable(GL2.GL_POINT_SMOOTH);
        gl.glPointSize(POINT_SIZE);
        gl.glColor4fv(POINT_COLOR, 0);
        gl.glBegin(GL2.GL_POINTS);
        for (Position p : positions) {
            gl.glVertex3d(p.x, p.y, p.z);

            // update min/max
            minx = Math.min(minx, p.x);
            maxx = Math.max(maxx, p.x);
            miny = Math.min(miny, p.y);
            maxz = Math.max(maxz, p.z);
            maxy = Math.max(maxy, p.y);
        }
        gl.glEnd();

        drawProbedSurface(gl);

        drawProbeAreaOutline(gl, minx, maxx, miny, maxy, maxz);
        drawProbeAreaOutline(gl, minx, maxx, miny, maxy, probeAreaMinZ);
        drawTouchPlate(gl, minx, maxx, miny, maxy);
        gl.glEnable(GL2.GL_LIGHTING);

        gl.glPopMatrix();
    }

    private void drawProbeAreaOutline(GL2 gl, double minx, double maxx, double miny, double maxy, double z) {
        gl.glPushMatrix();
        gl.glTranslated(
                (minx + maxx) / 2,
                (miny + maxy) / 2,
                z);
        gl.glScaled(maxx - minx, maxy - miny, 0);
        gl.glColor4fv(probeAreaColor, 0);
        glut.glutWireCube((float) 1.);
        gl.glPopMatrix();
    }

    private void drawTouchPlate(GL2 gl, double minx, double maxx, double miny, double maxy) {
        if (!touchPlateVisible || touchPlateThickness <= 0) {
            return;
        }

        // Translucent geometry must not hide anything drawn after it
        gl.glDepthMask(false);
        gl.glPushMatrix();
        gl.glTranslated(
                (minx + maxx) / 2,
                (miny + maxy) / 2,
                touchPlateThickness / 2);
        gl.glScaled(maxx - minx, maxy - miny, touchPlateThickness);
        gl.glColor4fv(touchPlateColor, 0);
        glut.glutSolidCube((float) 1.);
        gl.glPopMatrix();
        gl.glDepthMask(true);
    }

    /**
     * Returns the color for a height: from the low color through a neutral one to the high color, so
     * that what is above and what is below the middle is told apart at a glance.
     */
    static float[] getColorForZ(double zPos, double minZ, double maxZ, float[] low, float[] high) {
        float ratio = maxZ > minZ ? (float) Math.max(0, Math.min(1, (zPos - minZ) / (maxZ - minZ))) : 0.5f;
        float[] from = ratio < 0.5f ? low : MIDDLE_COLOR;
        float[] to = ratio < 0.5f ? MIDDLE_COLOR : high;
        float t = ratio < 0.5f ? ratio * 2 : ratio * 2 - 1;
        return new float[]{
                from[0] + t * (to[0] - from[0]),
                from[1] + t * (to[1] - from[1]),
                from[2] + t * (to[2] - from[2]),
                low[3] + ratio * (high[3] - low[3])};
    }

    private void addVertex(GL2 gl, Position position) {
        gl.glColor4fv(getColorForZ(position.z, minZ, maxZ, low, high), 0);
        gl.glVertex3d(position.x, position.y, position.z);
    }

    private static boolean isProbed(Position position) {
        return position != null && !Double.isNaN(position.z);
    }

    private void drawProbedSurface(GL2 gl) {
        if (this.grid == null) {
            return;
        }

        // The surface is pushed back a little, so that the mesh and the tool path on it are not hidden by it
        gl.glEnable(GL2.GL_POLYGON_OFFSET_FILL);
        gl.glPolygonOffset(1f, 1f);
        gl.glBegin(GL2.GL_TRIANGLES);
        int xLen = this.grid.length;
        for (int x = 0; x < xLen - 1; x++) {
            if (this.grid[x] == null || this.grid[x + 1] == null) {
                continue;
            }

            for (int y = 0; y < this.grid[x].length - 1; y++) {
                Position pos1 = this.grid[x][y];
                Position pos2 = this.grid[x + 1][y];
                Position pos3 = this.grid[x][y + 1];
                Position pos4 = this.grid[x + 1][y + 1];

                // Bottom left of quad
                if (isProbed(pos1) && isProbed(pos2) && isProbed(pos3)) {
                    addVertex(gl, pos1);
                    addVertex(gl, pos3);
                    addVertex(gl, pos2);
                }

                // Top right of quad
                if (isProbed(pos2) && isProbed(pos3) && isProbed(pos4)) {
                    addVertex(gl, pos4);
                    addVertex(gl, pos3);
                    addVertex(gl, pos2);
                }
            }
        }
        gl.glEnd();
        gl.glDisable(GL2.GL_POLYGON_OFFSET_FILL);

        // The mesh between the probed points
        gl.glLineWidth(1f);
        gl.glColor4fv(MESH_COLOR, 0);
        gl.glBegin(GL2.GL_LINES);
        for (int x = 0; x < xLen; x++) {
            if (this.grid[x] == null) {
                continue;
            }
            for (int y = 0; y < this.grid[x].length; y++) {
                Position position = this.grid[x][y];
                if (!isProbed(position)) {
                    continue;
                }
                if (y + 1 < this.grid[x].length && isProbed(this.grid[x][y + 1])) {
                    gl.glVertex3d(position.x, position.y, position.z);
                    gl.glVertex3d(this.grid[x][y + 1].x, this.grid[x][y + 1].y, this.grid[x][y + 1].z);
                }
                if (x + 1 < xLen && this.grid[x + 1] != null && y < this.grid[x + 1].length && isProbed(this.grid[x + 1][y])) {
                    gl.glVertex3d(position.x, position.y, position.z);
                    gl.glVertex3d(this.grid[x + 1][y].x, this.grid[x + 1][y].y, this.grid[x + 1][y].z);
                }
            }
        }
        gl.glEnd();
    }

    private void updateMinMaxZ() {
        // Figure out the min/max scanned points
        minZ = getGridZStream()
                .min()
                .orElse(0);

        maxZ = getGridZStream()
                .max()
                .orElse(0);
    }

    private DoubleStream getGridZStream() {
        return Arrays.stream(grid).flatMap(Arrays::stream)
                .mapToDouble(CNCPoint::getZ)
                .filter(z -> !Double.isNaN(z));
    }
}
