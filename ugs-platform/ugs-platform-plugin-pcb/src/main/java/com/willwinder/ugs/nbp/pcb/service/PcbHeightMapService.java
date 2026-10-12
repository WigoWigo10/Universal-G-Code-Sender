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
package com.willwinder.ugs.nbp.pcb.service;

import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.gcode.ToolPathValidator;
import com.willwinder.ugs.nbp.pcb.heightmap.Compensation;
import com.willwinder.ugs.nbp.pcb.heightmap.GridHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapBinding;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapException;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapFile;
import com.willwinder.ugs.nbp.pcb.heightmap.PcbHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.ValidationState;
import com.willwinder.universalgcodesender.gcode.GcodePreprocessorUtils;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.ArcExpander;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessorList;
import com.willwinder.universalgcodesender.gcode.processors.LineSplitter;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the height map of the board and applies it to the loaded aligned program.
 * <p>
 * The compensation itself is done by the processors of the application, in the same order as
 * its auto leveler applies them: arcs are expanded, moves are split and the mesh leveler adjusts
 * their Z. What this adds is that the height map is only applied to a program that it was
 * measured for, and that the program that comes out of it is validated against the height map
 * before it may be sent.
 * <p>
 * The height map is kept when the compensation is removed, when another program is loaded and
 * when it turns out to be incompatible. It is only replaced or discarded when that is asked for.
 */
public class PcbHeightMapService implements UGSEventListener {
    /**
     * What can be done with the height map for the loaded program
     */
    public enum State {
        /** There is no height map */
        ABSENT,
        /** There is a height map that can be applied to the loaded program */
        AVAILABLE,
        /** There is a height map that must not be applied to the loaded program */
        INCOMPATIBLE,
        /** The height map is applied to the loaded program, which has been validated with it */
        APPLIED
    }

    /**
     * @param state   what can be done with the height map
     * @param message tells the operator why
     */
    public record Status(State state, String message) {
    }

    public interface Listener {
        void onHeightMapChanged();
    }

    /**
     * The Z of the surface that programs for boards are made for
     */
    public static final double SURFACE_Z = 0;

    public static final String MESSAGE_ABSENT = "There is no height map. Load one that was measured for this board.";
    public static final String MESSAGE_AVAILABLE = "The height map was measured for the alignment of the loaded program and covers all that it cuts.";
    public static final String MESSAGE_APPLIED = "The height map is applied and the program was validated with it.";
    public static final String ERROR_NO_HEIGHT_MAP = "There is no height map.";
    public static final String ERROR_NO_PROGRAM = "There is no program loaded. Load the aligned program of the board.";
    public static final String ERROR_NOT_ALIGNED = "The loaded program is not an aligned program, or it was changed since it was aligned. A height map is only applied to an aligned program.";
    public static final String ERROR_NOT_ASSOCIATED = "The height map does not tell what it was measured for. Associate it with the alignment of the loaded program if it was measured with the board where it is now.";
    public static final String ERROR_MACHINE_WORK_COORDINATE_SYSTEM = "The machine is using %s and the height map was measured in %s.";
    public static final String ERROR_COVERAGE = "The height map does not cover all that the program cuts, for instance at ";
    public static final String ERROR_PROGRAM_NOT_VALIDATED = "The program that would be sent is not the complete aligned program, so the height map was not applied: ";
    public static final String ERROR_SENDING = "The height map can not be changed while the program is being sent.";
    public static final String ERROR_APPLIED = "The height map is applied to the loaded program. Remove the compensation first.";
    public static final String ERROR_NOT_APPLIED = "The height map is not applied.";
    public static final String ERROR_APPLY = "The height map was not applied: ";
    public static final String ERROR_REMOVE = "The compensation was removed but the program could not be validated without it, load the aligned program again: ";
    public static final String ERROR_APPLIED_NOT_VALID = "The program was processed again after the height map was applied, so it is no longer validated. Remove the compensation and apply it again.";

    private final BackendAPI backend;
    private final PcbAlignmentService alignmentService;
    private final Set<Listener> listeners = ConcurrentHashMap.newKeySet();
    private PcbHeightMap heightMap;
    private CommandProcessorList applied;

    /**
     * Read by the alignment service while it validates, which may be on another thread
     */
    private volatile Compensation compensation;

    public PcbHeightMapService(BackendAPI backend, PcbAlignmentService alignmentService) {
        this.backend = backend;
        this.alignmentService = alignmentService;
        this.alignmentService.setCompensationSource(() -> Optional.ofNullable(compensation));
        this.backend.addUGSEventListener(this);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        listeners.forEach(Listener::onHeightMapChanged);
    }

    public synchronized Optional<PcbHeightMap> getHeightMap() {
        return Optional.ofNullable(heightMap);
    }

    public synchronized boolean isApplied() {
        forgetProcessorsOfClosedProgram();
        return applied != null;
    }

    /**
     * The backend removes the processors of a program when it is closed. It tells that it is closing
     * a program before it has done so, and in the same way as when a program could not be processed,
     * so this is found out afterwards.
     */
    private void forgetProcessorsOfClosedProgram() {
        if (applied != null && backend.getGcodeFile() == null) {
            applied = null;
            compensation = null;
        }
    }

    /**
     * Tells what can be done with the height map for the program that is loaded now. Nothing of this
     * is remembered: the loaded program is read and compared with the height map every time.
     */
    public synchronized Status getStatus() {
        if (heightMap == null) {
            return new Status(State.ABSENT, MESSAGE_ABSENT);
        }

        forgetProcessorsOfClosedProgram();
        if (applied != null) {
            Compensation current = compensation;
            boolean isValidated = current != null
                    && alignmentService.getValidationState() == ValidationState.VALID
                    && alignmentService.getApproval().map(approval -> current.id().equals(approval.compensationId())).orElse(false);
            return isValidated ? new Status(State.APPLIED, MESSAGE_APPLIED) : new Status(State.INCOMPATIBLE, ERROR_APPLIED_NOT_VALID);
        }

        try {
            verifyUsable();
            return new Status(State.AVAILABLE, MESSAGE_AVAILABLE);
        } catch (HeightMapException e) {
            return new Status(State.INCOMPATIBLE, e.getMessage());
        }
    }

    /**
     * Verifies that the height map may be applied to the loaded program
     *
     * @return the alignment of the loaded program
     */
    private AlignmentMetadata verifyUsable() throws HeightMapException {
        if (heightMap == null) {
            throw new HeightMapException(ERROR_NO_HEIGHT_MAP);
        }
        AlignmentMetadata metadata = readLoadedAlignment();
        HeightMapBinding binding = heightMap.getBinding().orElseThrow(() -> new HeightMapException(ERROR_NOT_ASSOCIATED));
        Optional<String> mismatch = binding.findMismatch(metadata);
        if (mismatch.isPresent()) {
            throw new HeightMapException(mismatch.get());
        }

        GcodeState state = backend.isConnected() ? backend.getGcodeState() : null;
        if (state != null && state.offset != null && state.offset != binding.workCoordinateSystem()) {
            throw new HeightMapException(String.format(ERROR_MACHINE_WORK_COORDINATE_SYSTEM, state.offset, binding.workCoordinateSystem()));
        }

        List<Position> uncovered = findUncoveredCuts();
        if (!uncovered.isEmpty()) {
            Position position = uncovered.get(0);
            throw new HeightMapException(ERROR_COVERAGE + String.format(Locale.ROOT, "X%.3f Y%.3f", position.getX(), position.getY()));
        }
        return metadata;
    }

    private AlignmentMetadata readLoadedAlignment() throws HeightMapException {
        File loadedFile = backend.getGcodeFile();
        if (loadedFile == null) {
            throw new HeightMapException(ERROR_NO_PROGRAM);
        }
        try {
            return AlignmentMetadata.read(loadedFile).orElseThrow(() -> new HeightMapException(ERROR_NOT_ALIGNED));
        } catch (IOException e) {
            throw new HeightMapException(ERROR_NOT_ALIGNED, e);
        }
    }

    /**
     * Returns the positions in millimeters that the loaded program moves to, empty if there is none
     * or if it can not be read
     */
    public List<Position> getToolPath() {
        File loadedFile = backend.getGcodeFile();
        if (loadedFile == null) {
            return List.of();
        }
        try {
            return ToolPathValidator.trace(Files.readAllLines(loadedFile.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | GcodeParserException e) {
            return List.of();
        }
    }

    /**
     * Returns true if the tool is in the board at the position, where its height matters
     */
    public static boolean isCut(Position position) {
        return position.getZ() <= SURFACE_Z;
    }

    /**
     * Returns the positions where the loaded program cuts and the height map has no height. Outside
     * of the height map the mesh leveler takes the surface to be at zero, so the depth of a cut
     * there would be wrong and would change suddenly at the edge of the height map.
     */
    public synchronized List<Position> findUncoveredCuts() {
        if (heightMap == null) {
            return List.of();
        }
        List<Position> result = new ArrayList<>();
        for (Position position : getToolPath()) {
            if (isCut(position) && !heightMap.surface().covers(position.getX(), position.getY())) {
                result.add(position);
            }
        }
        return result;
    }

    /**
     * Returns the area that a height map has to cover for the loaded program: all that it cuts, with a margin
     */
    public Optional<ProbeArea> getAreaToMeasure(double marginMM) {
        return ProbeArea.covering(getToolPath().stream().filter(PcbHeightMapService::isCut).toList(), marginMM);
    }

    /**
     * Reads a height map from a file and keeps it in place of the current one. It is not applied.
     *
     * @param scannerUnits the units of the points if the file is of the surface scanner, which does not tell them
     */
    public synchronized PcbHeightMap load(File file, Units scannerUnits) throws HeightMapException {
        verifyNotApplied();
        PcbHeightMap result = HeightMapFile.read(file, scannerUnits);
        heightMap = result;
        notifyListeners();
        return result;
    }

    /**
     * Keeps a height map in place of the current one. It is not applied.
     */
    public synchronized void setHeightMap(PcbHeightMap heightMap) throws HeightMapException {
        verifyNotApplied();
        this.heightMap = heightMap;
        notifyListeners();
    }

    public synchronized void save(File file) throws HeightMapException {
        if (heightMap == null) {
            throw new HeightMapException(ERROR_NO_HEIGHT_MAP);
        }
        HeightMapFile.write(heightMap, file);
    }

    /**
     * Discards the height map
     */
    public synchronized void discard() throws HeightMapException {
        verifyNotApplied();
        heightMap = null;
        notifyListeners();
    }

    /**
     * Declares that the height map was measured for the alignment and in the work coordinate system
     * of the loaded aligned program. Only the operator knows this, so it is never done without being asked.
     */
    public synchronized PcbHeightMap associateWithLoadedProgram() throws HeightMapException {
        verifyNotApplied();
        if (heightMap == null) {
            throw new HeightMapException(ERROR_NO_HEIGHT_MAP);
        }
        heightMap = heightMap.withBinding(HeightMapBinding.of(readLoadedAlignment()));
        notifyListeners();
        return heightMap;
    }

    private void verifyNotApplied() throws HeightMapException {
        forgetProcessorsOfClosedProgram();
        if (applied != null) {
            throw new HeightMapException(ERROR_APPLIED);
        }
    }

    /**
     * Applies the height map to the loaded program and validates all of the program that comes out
     * of it. If anything of this fails the compensation is removed again, and if the program can not
     * be validated without it either it is left without approval, so it can not be sent.
     */
    public synchronized void apply() throws HeightMapException {
        verifyNotApplied();
        AlignmentMetadata metadata = verifyUsable();
        if (backend.isSendingFile()) {
            throw new HeightMapException(ERROR_SENDING);
        }
        // The height map is only applied to the complete aligned program. An aligned program that was
        // opened as any other file has not been validated yet, so that is done first.
        try {
            alignmentService.validateProcessedFile(0);
        } catch (AlignmentException e) {
            throw new HeightMapException(ERROR_PROGRAM_NOT_VALIDATED + e.getMessage(), e);
        }

        GridHeightMap surface = heightMap.surface();
        // The same as the auto leveler: no move is longer than a quarter of the distance between the points
        double maxSegmentLength = surface.getStep() / 4;
        CommandProcessorList processors;
        try {
            processors = new CommandProcessorList();
            processors.add(new ArcExpander(true, backend.getSettings().getAutoLevelSettings().getAutoLevelArcSliceLength(), GcodePreprocessorUtils.getDecimalFormatter()));
            processors.add(new LineSplitter(maxSegmentLength));
            processors.add(new MeshLeveler(SURFACE_Z, surface.toMesh()));
        } catch (IllegalArgumentException e) {
            throw new HeightMapException(ERROR_APPLY + e.getMessage(), e);
        }

        applied = processors;
        compensation = new Compensation(surface, SURFACE_Z, maxSegmentLength, heightMap.id() + "/" + metadata.contentSha256());
        try {
            backend.applyCommandProcessor(processors);
            alignmentService.validateProcessedFile(0);
        } catch (Exception e) {
            applied = null;
            compensation = null;
            try {
                backend.removeCommandProcessor(processors);
                alignmentService.validateProcessedFile(0);
            } catch (Exception removalFailure) {
                // The program is left without approval, and the failure to apply is the one that is reported
            }
            throw new HeightMapException(ERROR_APPLY + e.getMessage(), e);
        } finally {
            notifyListeners();
        }
    }

    /**
     * Removes the compensation from the loaded program and validates the program without it. The
     * height map is kept.
     */
    public synchronized void remove() throws HeightMapException {
        forgetProcessorsOfClosedProgram();
        if (applied == null) {
            throw new HeightMapException(ERROR_NOT_APPLIED);
        }
        if (backend.isSendingFile()) {
            throw new HeightMapException(ERROR_SENDING);
        }

        CommandProcessorList processors = applied;
        applied = null;
        compensation = null;
        try {
            backend.removeCommandProcessor(processors);
            alignmentService.validateProcessedFile(0);
        } catch (Exception e) {
            throw new HeightMapException(ERROR_REMOVE + e.getMessage(), e);
        } finally {
            notifyListeners();
        }
    }

    @Override
    public void UGSEvent(UGSEvent event) {
        if (event instanceof FileStateEvent fileStateEvent) {
            synchronized (this) {
                // The backend removes the processors of the earlier program when a program is opened. The height
                // map is kept, and has to be applied to the new program deliberately.
                if (fileStateEvent.getFileState() == FileState.OPENING_FILE) {
                    applied = null;
                    compensation = null;
                }
            }
            notifyListeners();
        } else if (event instanceof ControllerStateEvent) {
            notifyListeners();
        }
    }
}
