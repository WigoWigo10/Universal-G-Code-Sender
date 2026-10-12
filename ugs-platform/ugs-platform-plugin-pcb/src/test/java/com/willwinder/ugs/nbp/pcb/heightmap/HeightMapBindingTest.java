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
package com.willwinder.ugs.nbp.pcb.heightmap;

import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.util.Code;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HeightMapBindingTest {
    private static final RigidTransform2D TRANSFORM = new RigidTransform2D(0.02, 10, -5);

    private static AlignmentMetadata metadata(String sourceSha256, RigidTransform2D transform, Code workCoordinateSystem) {
        return new AlignmentMetadata("board.nc", sourceSha256, transform, workCoordinateSystem, "content");
    }

    @Test
    public void bindingShouldMatchTheAlignmentItWasMadeFrom() {
        AlignmentMetadata metadata = metadata("a", TRANSFORM, Code.G54);

        assertTrue(HeightMapBinding.of(metadata).findMismatch(metadata).isEmpty());
    }

    @Test
    public void bindingShouldMatchAnotherProgramOfTheSameBoardWithTheSameAlignment() {
        HeightMapBinding binding = HeightMapBinding.of(metadata("isolation", TRANSFORM, Code.G54));

        assertTrue(binding.findMismatch(metadata("drilling", TRANSFORM, Code.G54)).isEmpty());
    }

    @Test
    public void bindingShouldNotMatchAnotherWorkCoordinateSystem() {
        HeightMapBinding binding = HeightMapBinding.of(metadata("a", TRANSFORM, Code.G54));

        assertEquals("The height map was measured in G54 and the program is aligned in G55.",
                binding.findMismatch(metadata("a", TRANSFORM, Code.G55)).orElseThrow());
    }

    @Test
    public void bindingShouldNotMatchAnotherAlignment() {
        HeightMapBinding binding = HeightMapBinding.of(metadata("a", TRANSFORM, Code.G54));

        assertTrue(binding.findMismatch(metadata("a", new RigidTransform2D(0.02, 10, -5.001), Code.G54)).isPresent());
        assertTrue(binding.findMismatch(metadata("a", new RigidTransform2D(0.02, 10.001, -5), Code.G54)).isPresent());
        assertTrue(binding.findMismatch(metadata("a", new RigidTransform2D(0.0200001, 10, -5), Code.G54)).isPresent());
    }
}
