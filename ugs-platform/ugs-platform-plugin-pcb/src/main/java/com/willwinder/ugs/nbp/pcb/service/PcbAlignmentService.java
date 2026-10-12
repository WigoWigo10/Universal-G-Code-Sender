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

import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeExporter;
import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeFile;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.gcode.ToolPathValidator;
import com.willwinder.ugs.nbp.pcb.heightmap.Compensation;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.services.FileLoader;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.services.jobvalidation.JobRequest;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.GUIHelpers;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.IGcodeStreamReader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Aligns the program that is loaded in the backend. The aligned program is written to a file of
 * its own next to the original program and is then loaded in its place, so the backend and all
 * its processors only ever see a program that is already aligned.
 */
public class PcbAlignmentService implements UGSEventListener {
    /**
     * If the program that will be sent is known to be the complete aligned program
     */
    public enum ValidationState {
        /** No aligned program is loaded, or the program has been processed again since it was validated */
        NOT_VALIDATED,
        /** An aligned program is being loaded and will be validated when the backend has processed it */
        PENDING,
        VALID,
        INVALID
    }

    /**
     * The record of a validated program. A job is only the approved one if all of it is the same when
     * the job is started: the aligned program, the processed program and the work coordinate system.
     *
     * @param alignedFile     the aligned program that was loaded
     * @param metadata        identifies the aligned program, its original and the work coordinate system
     * @param processedSha256 the hash of the processed program that was validated, which is what may be sent
     * @param maxZChangeMM    the change of Z that was accepted
     * @param compensationId  identifies the height map that the program was validated with, null if it has none applied
     */
    public record Approval(File alignedFile, AlignmentMetadata metadata, String processedSha256, double maxZChangeMM, String compensationId) {
    }

    /**
     * What applying an alignment will do, decided before the operator is asked to confirm it. Applying
     * the plan does exactly this or nothing: it fails if another program has been loaded or if the
     * original program has been changed since the plan was made.
     *
     * @param loadedFile           the program that was loaded when the plan was made
     * @param source               the original program that will be aligned
     * @param sourceSha256         the hash of the original program
     * @param target               the file that the aligned program will be written to
     * @param transform            the alignment with the translation in millimeters
     * @param workCoordinateSystem the work coordinate system that the alignment was measured in
     */
    public record AlignmentPlan(File loadedFile, File source, String sourceSha256, File target,
                                RigidTransform2D transform, Code workCoordinateSystem) {
    }

    public static final String ALIGNED_FILE_SUFFIX = "_aligned";

    public static final String ERROR_NO_FILE = "There is no program loaded to align.";
    public static final String ERROR_SENDING = "The program can not be aligned while it is being sent.";
    public static final String ERROR_SOURCE_MISSING = "The loaded program is already aligned and its original program was not found unchanged next to it: ";
    public static final String ERROR_WORK_COORDINATE_SYSTEM = "The machine is using another work coordinate system than the alignment was measured in: ";
    public static final String ERROR_NOT_ALIGNED = "The loaded program is not an aligned program, or it has been changed since it was aligned: ";
    public static final String ERROR_NOT_PROCESSED = "The aligned program has not been processed for sending.";
    public static final String ERROR_LOAD = "The aligned program could not be loaded: ";
    public static final String ERROR_PLAN_OUTDATED = "Another program was loaded after the alignment was confirmed, so it was not applied.";
    public static final String ERROR_PROCESSED_FROM_OTHER_PROGRAM = "The program that would be sent was not made from the loaded aligned program. Load the aligned program again.";
    public static final String ERROR_CHANGED_DURING_VALIDATION = "The program was processed again while it was being validated, validate it again.";
    public static final String ERROR_NOT_APPROVED = "The aligned program has not been validated. Validate it with the PCB tools before it is started.";
    public static final String ERROR_PROCESSED_CHANGED = "The aligned program has been processed again since it was validated, for instance by the auto leveler. Validate it again with the PCB tools before it is started.";
    public static final String ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN = "The work coordinate system of the machine is not known, so the aligned program can not be started. It must be ";

    /**
     * The largest accepted distance in millimeters between the program that will be sent and the
     * aligned program, which allows for the rounding of the coordinates.
     */
    public static final double VALIDATION_TOLERANCE_MM = 0.005;

    private final BackendAPI backend;
    private final FileLoader fileLoader;
    private final Consumer<AlignmentException> errorHandler;
    private final AlignedGcodeExporter exporter = new AlignedGcodeExporter();

    private ValidationState validationState = ValidationState.NOT_VALIDATED;
    private Approval approval;
    private File pendingFile;
    private File alignedFile;
    private boolean isLoading;
    private boolean loadedWhileOpening;

    /**
     * Tells which height map is applied to the loaded program, if any. Read without holding on to
     * anything of its owner, as it is asked while this service is busy validating.
     */
    private volatile Supplier<Optional<Compensation>> compensationSource = Optional::empty;

    /**
     * Creates a service that opens the aligned program in the same way as the application opens files,
     * which in the platform is in the editor, and shows an error dialog if it turns out to be invalid.
     */
    public PcbAlignmentService(BackendAPI backend) {
        this(backend,
                file -> LookupService.lookupOptional(FileLoader.class).orElseGet(() -> new BackendFileLoader(backend)).openFile(file),
                e -> GUIHelpers.displayErrorDialog(e.getMessage()));
    }

    /**
     * @param fileLoader   opens the aligned program. If it has loaded the program in the backend when it
     *                     returns the program is validated at once, otherwise when the backend has loaded it.
     * @param errorHandler is given the error if a program that was loaded later turned out to be invalid
     */
    public PcbAlignmentService(BackendAPI backend, FileLoader fileLoader, Consumer<AlignmentException> errorHandler) {
        this.backend = backend;
        this.fileLoader = fileLoader;
        this.errorHandler = errorHandler;
        this.backend.addUGSEventListener(this);
    }

    /**
     * @param compensationSource tells which height map is applied to the loaded program, if any. The
     *                           processed program is then only valid if it follows the aligned tool
     *                           path on that surface.
     */
    public void setCompensationSource(Supplier<Optional<Compensation>> compensationSource) {
        this.compensationSource = compensationSource;
    }

    public synchronized ValidationState getValidationState() {
        return validationState;
    }

    /**
     * Returns the approval of the latest validated program, which may no longer be the loaded one
     */
    public synchronized Optional<Approval> getApproval() {
        return Optional.ofNullable(approval);
    }

    /**
     * Verifies that a job that is about to be started is the program that was validated. Nothing of
     * this relies on having been told about changes: the aligned program is read and verified again,
     * the processed program is identified by its content, and the machine is asked for its work
     * coordinate system.
     *
     * @throws AlignmentException if the job is not the validated program
     */
    public synchronized void verifyApproved(JobRequest request) throws AlignmentException {
        AlignmentMetadata metadata = readVerifiedMetadata(request.getGcodeFile());
        if (approval == null || !isSameFile(approval.alignedFile(), request.getGcodeFile()) || !approval.metadata().equals(metadata)) {
            throw new AlignmentException(ERROR_NOT_APPROVED);
        }

        String processedSha256;
        try {
            processedSha256 = request.getProcessedSha256();
        } catch (JobValidationException e) {
            throw new AlignmentException(e.getMessage(), e);
        }
        if (!approval.processedSha256().equals(processedSha256)) {
            throw new AlignmentException(ERROR_PROCESSED_CHANGED);
        }

        GcodeState state = backend.isConnected() ? backend.getGcodeState() : null;
        if (state == null || state.offset == null) {
            throw new AlignmentException(ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN + metadata.workCoordinateSystem());
        }
        if (state.offset != metadata.workCoordinateSystem()) {
            throw new AlignmentException(ERROR_WORK_COORDINATE_SYSTEM + state.offset + " instead of " + metadata.workCoordinateSystem());
        }
    }

    /**
     * Aligns the original of the loaded program and loads the result. If the loaded program is
     * already aligned its original program is aligned again, so alignments are never added to
     * each other.
     *
     * @param transform            the alignment with the translation in millimeters
     * @param workCoordinateSystem the work coordinate system that the alignment was measured in
     * @throws AlignmentException if the program could not be aligned, in which case the loaded program is not changed
     */
    public synchronized AlignedGcodeFile alignLoadedFile(RigidTransform2D transform, Code workCoordinateSystem) throws AlignmentException {
        return apply(plan(transform, workCoordinateSystem));
    }

    /**
     * Decides what applying an alignment to the loaded program will do, without doing anything yet
     *
     * @param transform            the alignment with the translation in millimeters
     * @param workCoordinateSystem the work coordinate system that the alignment was measured in
     * @throws AlignmentException if the loaded program can not be aligned
     */
    public synchronized AlignmentPlan plan(RigidTransform2D transform, Code workCoordinateSystem) throws AlignmentException {
        File loadedFile = backend.getGcodeFile();
        if (loadedFile == null) {
            throw new AlignmentException(ERROR_NO_FILE);
        }
        if (backend.isSendingFile()) {
            throw new AlignmentException(ERROR_SENDING);
        }
        verifyWorkCoordinateSystem(workCoordinateSystem);

        File source = findOriginalFile(loadedFile);
        try {
            String sourceSha256 = AlignmentMetadata.sha256(Files.readAllBytes(source.toPath()));
            return new AlignmentPlan(loadedFile.getAbsoluteFile(), source, sourceSha256, getAlignedFile(source), transform, workCoordinateSystem);
        } catch (IOException e) {
            throw new AlignmentException(ERROR_SOURCE_MISSING + loadedFile, e);
        }
    }

    /**
     * Applies a plan: aligns the original program and loads the result
     *
     * @throws AlignmentException if the plan could not be applied as it was made, in which case the loaded program is not changed
     */
    public synchronized AlignedGcodeFile apply(AlignmentPlan plan) throws AlignmentException {
        if (!isSameFile(plan.loadedFile(), backend.getGcodeFile())) {
            throw new AlignmentException(ERROR_PLAN_OUTDATED);
        }
        if (backend.isSendingFile()) {
            throw new AlignmentException(ERROR_SENDING);
        }
        verifyWorkCoordinateSystem(plan.workCoordinateSystem());

        AlignedGcodeFile result = exporter.export(plan.source(), plan.transform(), plan.workCoordinateSystem(), plan.target(), plan.sourceSha256());
        load(result);
        return result;
    }

    /**
     * Returns the file that the aligned program of an original program is written to, which is next to it
     */
    public File getAlignedFile(File source) {
        int extensionStart = source.getName().lastIndexOf('.');
        String baseName = extensionStart < 0 ? source.getName() : source.getName().substring(0, extensionStart);
        String extension = extensionStart < 0 ? "" : source.getName().substring(extensionStart + 1);
        String name = baseName + ALIGNED_FILE_SUFFIX + (extension.isEmpty() ? "" : "." + extension);
        return new File(source.getAbsoluteFile().getParentFile(), name);
    }

    /**
     * Returns the original program of the given program, which is the program itself unless it is aligned
     */
    public File findOriginalFile(File file) throws AlignmentException {
        try {
            if (!AlignmentMetadata.isAligned(file)) {
                return file;
            }

            Optional<AlignmentMetadata> metadata = AlignmentMetadata.read(file);
            Optional<File> source = metadata.isPresent() ? metadata.get().findSource(file) : Optional.empty();
            return source.orElseThrow(() -> new AlignmentException(ERROR_SOURCE_MISSING + file));
        } catch (IOException e) {
            throw new AlignmentException(ERROR_SOURCE_MISSING + file, e);
        }
    }

    /**
     * Loads an aligned program in the backend and validates what the backend made of it.
     * <p>
     * Loading a program removes the processors that were applied to the previous one, and the auto
     * leveler applies its height map again to any program that is loaded. A height map may have
     * been made for another position of the board, so applying it is turned off and has to be
     * turned on again deliberately.
     */
    public synchronized void load(AlignedGcodeFile alignedFile) throws AlignmentException {
        verifyWorkCoordinateSystem(alignedFile.metadata().workCoordinateSystem());
        readVerifiedMetadata(alignedFile.file());

        backend.getSettings().getAutoLevelSettings().setApplyToGcode(false);
        approval = null;
        pendingFile = alignedFile.file();
        this.alignedFile = alignedFile.file();
        validationState = ValidationState.PENDING;
        loadedWhileOpening = false;
        isLoading = true;
        try {
            fileLoader.openFile(alignedFile.file());
        } catch (Exception e) {
            rejectLoadedFile();
            throw new AlignmentException(ERROR_LOAD + alignedFile.file(), e);
        } finally {
            isLoading = false;
        }

        // The program is only validated when the backend has loaded it after this was called. That the
        // backend has a program with the same path says nothing: when an aligned program is aligned again
        // it is the earlier one, until the new one has been loaded. Some loaders open the file later, the
        // program is then validated when the backend tells that it has been loaded.
        if (loadedWhileOpening) {
            validatePendingFile();
        }
    }

    @Override
    public synchronized void UGSEvent(UGSEvent event) {
        if (!(event instanceof FileStateEvent fileStateEvent)) {
            return;
        }

        boolean isLoaded = fileStateEvent.getFileState() == FileState.FILE_LOADED;
        if (isLoading) {
            loadedWhileOpening |= isLoaded && isSameFile(pendingFile, backend.getGcodeFile());
            return;
        }

        // The program was unloaded or could not be processed, so there is nothing left that is validated.
        // A program that is being loaded stays pending, as loading it starts with unloading the earlier one.
        if (fileStateEvent.getFileState() == FileState.FILE_UNLOADED) {
            validationState = pendingFile != null ? ValidationState.PENDING : ValidationState.NOT_VALIDATED;
            approval = null;
            return;
        }
        if (!isLoaded) {
            return;
        }

        File loadedFile = backend.getGcodeFile();
        if (pendingFile != null && isSameFile(pendingFile, loadedFile)) {
            try {
                validatePendingFile();
            } catch (AlignmentException e) {
                errorHandler.accept(e);
            }
        } else if (pendingFile == null && isSameFile(alignedFile, loadedFile)) {
            // The aligned program was loaded or processed again. It is validated as when it was first loaded,
            // which only succeeds if nothing has changed it, and otherwise it has to be validated deliberately.
            try {
                validateProcessedFile(0);
            } catch (AlignmentException e) {
                validationState = ValidationState.NOT_VALIDATED;
            }
        } else {
            // Another program was loaded
            validationState = pendingFile != null ? ValidationState.PENDING : ValidationState.NOT_VALIDATED;
            approval = null;
        }
    }

    private void validatePendingFile() throws AlignmentException {
        pendingFile = null;
        try {
            validateProcessedFile(0);
        } catch (AlignmentException e) {
            rejectLoadedFile();
            throw e;
        }
    }

    private void rejectLoadedFile() {
        pendingFile = null;
        validationState = ValidationState.INVALID;
        approval = null;
        try {
            backend.unsetGcodeFile();
        } catch (Exception e) {
            // The failure that caused the unload is the one that is reported
        }
        validationState = ValidationState.INVALID;
    }

    private static boolean isSameFile(File first, File second) {
        return first != null && second != null && first.getAbsoluteFile().equals(second.getAbsoluteFile());
    }

    /**
     * Validates the program that will be sent against the loaded aligned program, and approves it
     * for sending if it is valid. This must be done again after anything has been applied to the
     * program, such as the auto leveler: if a processor fails the backend is left with the part of
     * the program that it had processed.
     * <p>
     * The approval is for the content of the processed program that was validated. The job validator
     * only lets a job start if it is streamed from that same content.
     *
     * @param maxZChangeMM the largest accepted change of Z, zero if nothing may have changed Z
     * @throws AlignmentException if the program that will be sent is not the complete aligned program
     */
    public synchronized void validateProcessedFile(double maxZChangeMM) throws AlignmentException {
        validationState = ValidationState.INVALID;
        approval = null;
        File loadedFile = backend.getGcodeFile();
        if (loadedFile == null) {
            throw new AlignmentException(ERROR_NO_FILE);
        }
        AlignmentMetadata metadata = readVerifiedMetadata(loadedFile);
        verifyWorkCoordinateSystem(metadata.workCoordinateSystem());

        File processedFile = backend.getProcessedGcodeFile();
        if (processedFile == null || !processedFile.isFile()) {
            throw new AlignmentException(ERROR_NOT_PROCESSED);
        }

        try {
            String processedSha256 = JobRequest.sha256(processedFile);
            List<String> loadedLines = Files.readAllLines(loadedFile.toPath(), StandardCharsets.UTF_8);
            List<GcodeCommand> processedCommands = readProcessedCommands(processedFile);
            verifyProcessedFromLoadedFile(loadedLines, processedCommands);

            List<Position> expected = ToolPathValidator.trace(loadedLines);
            List<Position> processed = ToolPathValidator.trace(processedCommands.stream().map(GcodeCommand::getCommandString).toList());
            Optional<Compensation> compensation = compensationSource.get();
            if (compensation.isPresent()) {
                ToolPathValidator.validate(expected, processed, VALIDATION_TOLERANCE_MM, maxZChangeMM, compensation.get()::offsetAt);
                ToolPathValidator.validateMoveLength(processed, compensation.get().maxSegmentLengthMM() + VALIDATION_TOLERANCE_MM, compensation.get().surface()::covers);
            } else {
                ToolPathValidator.validate(expected, processed, VALIDATION_TOLERANCE_MM, maxZChangeMM);
            }

            // What was validated must be what is approved
            if (!processedSha256.equals(JobRequest.sha256(processedFile)) || !metadata.equals(readVerifiedMetadata(loadedFile))) {
                throw new AlignmentException(ERROR_CHANGED_DURING_VALIDATION);
            }
            approval = new Approval(loadedFile.getAbsoluteFile(), metadata, processedSha256, maxZChangeMM, compensation.map(Compensation::id).orElse(null));
            validationState = ValidationState.VALID;
        } catch (IOException | GcodeParserException | GcodeStreamReader.NotGcodeStreamFile | JobValidationException e) {
            throw new AlignmentException(ERROR_NOT_PROCESSED, e);
        }
    }

    private AlignmentMetadata readVerifiedMetadata(File file) throws AlignmentException {
        try {
            return AlignmentMetadata.read(file).orElseThrow(() -> new AlignmentException(ERROR_NOT_ALIGNED + file));
        } catch (IOException e) {
            throw new AlignmentException(ERROR_NOT_ALIGNED + file, e);
        }
    }

    /**
     * The alignment is only valid in the work coordinate system that the reference points were
     * measured in. This can only be verified while connected to the machine.
     */
    private void verifyWorkCoordinateSystem(Code workCoordinateSystem) throws AlignmentException {
        if (!backend.isConnected()) {
            return;
        }

        GcodeState state = backend.getGcodeState();
        if (state != null && state.offset != workCoordinateSystem) {
            throw new AlignmentException(ERROR_WORK_COORDINATE_SYSTEM + state.offset + " instead of " + workCoordinateSystem);
        }
    }

    /**
     * Verifies that the processed program was made from the loaded program as it is now. The processed
     * program records the line that every command comes from, which must be the same line of the loaded
     * program. Comparing tool paths is not enough for this: an earlier alignment that only differs a
     * little is within the tolerance of that comparison.
     */
    private void verifyProcessedFromLoadedFile(List<String> loadedLines, List<GcodeCommand> processedCommands) throws AlignmentException {
        for (GcodeCommand command : processedCommands) {
            int lineNumber = command.getCommandNumber();
            String original = command.getOriginalCommandString() == null ? "" : command.getOriginalCommandString().trim();
            if (lineNumber < 1 || lineNumber > loadedLines.size() || !loadedLines.get(lineNumber - 1).trim().equals(original)) {
                throw new AlignmentException(ERROR_PROCESSED_FROM_OTHER_PROGRAM);
            }
        }
    }

    private List<GcodeCommand> readProcessedCommands(File processedFile) throws IOException, GcodeStreamReader.NotGcodeStreamFile {
        List<GcodeCommand> result = new ArrayList<>();
        try (IGcodeStreamReader reader = new GcodeStreamReader(processedFile, backend.getCommandCreator())) {
            while (reader.getNumRowsRemaining() > 0) {
                result.add(reader.getNextCommand());
            }
        }
        return result;
    }
}
