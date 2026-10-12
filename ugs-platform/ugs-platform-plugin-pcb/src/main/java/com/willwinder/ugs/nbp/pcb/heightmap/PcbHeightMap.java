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

import java.time.Instant;
import java.util.Optional;

/**
 * A measured surface together with what it was measured for
 *
 * @param surface the measured surface
 * @param binding what the surface was measured for, or null if that is not known. A height map
 *                without it is never applied.
 * @param created when the surface was measured, as far as that is known
 * @param name    tells the operator where the height map comes from
 */
public record PcbHeightMap(GridHeightMap surface, HeightMapBinding binding, Instant created, String name) {

    public Optional<HeightMapBinding> getBinding() {
        return Optional.ofNullable(binding);
    }

    public PcbHeightMap withBinding(HeightMapBinding newBinding) {
        return new PcbHeightMap(surface, newBinding, created, name);
    }

    /**
     * Identifies the measurements and what they were measured for
     */
    public String id() {
        return surface.sha256() + "/" + (binding == null ? "unbound" : binding.workCoordinateSystem() + "/"
                + binding.transform().rotation() + "/" + binding.transform().translationX() + "/" + binding.transform().translationY());
    }
}
