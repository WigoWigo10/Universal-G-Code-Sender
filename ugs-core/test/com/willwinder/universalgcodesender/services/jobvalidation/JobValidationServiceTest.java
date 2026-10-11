package com.willwinder.universalgcodesender.services.jobvalidation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

public class JobValidationServiceTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final JobValidationService service = new JobValidationService();
    private final List<JobRequest> requests = new ArrayList<>();
    private File gcodeFile;
    private File processedFile;

    @Before
    public void setUp() throws Exception {
        gcodeFile = write("program.nc", List.of("G21 G90", "G0 X1 Y1"));
        processedFile = write("processed", List.of("G21G90", "G0X1Y1"));
    }

    private File write(String name, List<String> lines) throws Exception {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        return file;
    }

    private JobValidator accepting(String id) {
        return new JobValidator() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public void validate(JobRequest request) {
                requests.add(request);
            }
        };
    }

    private JobValidator rejecting(String id, String message) {
        return new JobValidator() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public void validate(JobRequest request) throws JobValidationException {
                requests.add(request);
                throw new JobValidationException(message);
            }
        };
    }

    @Test
    public void jobShouldBeAcceptedWithoutValidators() throws Exception {
        service.validate(JobType.PROGRAM, gcodeFile, processedFile);
    }

    @Test
    public void jobWithoutLoadedProgramShouldBeAcceptedWithoutValidators() throws Exception {
        service.validate(JobType.PROGRAM, null, processedFile);
    }

    @Test
    public void validatorShouldBeAskedAboutTheJob() throws Exception {
        service.register(accepting("test"));

        service.validate(JobType.OUTLINE, gcodeFile, processedFile);

        assertEquals(1, requests.size());
        assertEquals(JobType.OUTLINE, requests.get(0).getType());
        assertSame(gcodeFile, requests.get(0).getGcodeFile());
        assertSame(processedFile, requests.get(0).getProcessedGcodeFile());
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(processedFile.toPath())));
        assertEquals(expectedHash, requests.get(0).getProcessedSha256());
    }

    @Test
    public void validatorShouldOnlyBeRegisteredOnce() throws Exception {
        JobValidator validator = accepting("test");
        service.register(validator);
        service.register(validator);

        service.validate(JobType.PROGRAM, gcodeFile, processedFile);

        assertEquals(1, requests.size());
    }

    @Test
    public void unregisteredValidatorShouldNotBeAsked() throws Exception {
        JobValidator validator = rejecting("test", "Not allowed");
        service.register(validator);
        service.unregister(validator);

        service.validate(JobType.PROGRAM, gcodeFile, processedFile);

        assertTrue(requests.isEmpty());
    }

    @Test
    public void jobShouldBeRejectedWithTheMessageOfTheValidator() {
        service.register(accepting("first"));
        service.register(rejecting("second", "The program is not safe to start"));
        service.register(accepting("third"));

        JobValidationException exception = assertThrows(JobValidationException.class,
                () -> service.validate(JobType.PROGRAM, gcodeFile, processedFile));

        assertEquals("The program is not safe to start", exception.getMessage());
        assertEquals(2, requests.size());
    }

    @Test
    public void jobShouldBeRejectedWhenValidatorFailsUnexpectedly() {
        service.register(new JobValidator() {
            @Override
            public String getId() {
                return "broken";
            }

            @Override
            public void validate(JobRequest request) {
                throw new IllegalStateException("Something is wrong");
            }
        });

        JobValidationException exception = assertThrows(JobValidationException.class,
                () -> service.validate(JobType.PROGRAM, gcodeFile, processedFile));

        assertEquals(JobValidationService.ERROR_VALIDATOR_FAILED + "broken", exception.getMessage());
    }

    @Test
    public void programThatRequiresMissingValidatorShouldBeRejected() throws Exception {
        gcodeFile = write("protected.nc", List.of("(A header)", JobValidationService.createRequirement("special"), "G0 X1 Y1"));
        service.register(accepting("other"));

        JobValidationException exception = assertThrows(JobValidationException.class,
                () -> service.validate(JobType.PROGRAM, gcodeFile, processedFile));

        assertEquals(JobValidationService.ERROR_VALIDATOR_MISSING + "special", exception.getMessage());
        assertTrue(requests.isEmpty());
    }

    @Test
    public void outlineOfProgramThatRequiresMissingValidatorShouldBeRejected() throws Exception {
        gcodeFile = write("protected.nc", List.of(JobValidationService.createRequirement("special"), "G0 X1 Y1"));

        assertThrows(JobValidationException.class, () -> service.validate(JobType.OUTLINE, gcodeFile, processedFile));
    }

    @Test
    public void programThatRequiresRegisteredValidatorShouldBeLeftToTheValidator() throws Exception {
        gcodeFile = write("protected.nc", List.of(JobValidationService.createRequirement("special"), "G0 X1 Y1"));
        service.register(accepting("special"));

        service.validate(JobType.PROGRAM, gcodeFile, processedFile);

        assertEquals(1, requests.size());
    }

    @Test
    public void programThatRequiresSeveralValidatorsShouldNeedAllOfThem() throws Exception {
        gcodeFile = write("protected.nc", List.of(
                JobValidationService.createRequirement("first"),
                JobValidationService.createRequirement("second"),
                "G0 X1 Y1"));
        service.register(accepting("first"));

        JobValidationException exception = assertThrows(JobValidationException.class,
                () -> service.validate(JobType.PROGRAM, gcodeFile, processedFile));

        assertEquals(JobValidationService.ERROR_VALIDATOR_MISSING + "second", exception.getMessage());
    }

    @Test
    public void requirementAfterTheFirstLinesShouldNotBeFound() throws Exception {
        List<String> lines = new ArrayList<>(Collections.nCopies(JobValidationService.REQUIREMENT_HEADER_LINES, "G0 X1 Y1"));
        lines.add(JobValidationService.createRequirement("special"));
        gcodeFile = write("late.nc", lines);

        service.validate(JobType.PROGRAM, gcodeFile, processedFile);
    }

    @Test
    public void loadedProgramThatCanNotBeReadShouldBeRejected() {
        File missing = new File(folder.getRoot(), "missing.nc");

        JobValidationException exception = assertThrows(JobValidationException.class,
                () -> service.validate(JobType.PROGRAM, missing, processedFile));

        assertEquals(JobValidationService.ERROR_PROGRAM_NOT_READABLE + missing, exception.getMessage());
    }

    @Test
    public void hashOfMissingProcessedProgramShouldFail() {
        JobRequest request = new JobRequest(JobType.PROGRAM, gcodeFile, new File(folder.getRoot(), "missing"));

        assertThrows(JobValidationException.class, request::getProcessedSha256);
        assertThrows(JobValidationException.class, () -> new JobRequest(JobType.PROGRAM, gcodeFile, null).getProcessedSha256());
    }
}
