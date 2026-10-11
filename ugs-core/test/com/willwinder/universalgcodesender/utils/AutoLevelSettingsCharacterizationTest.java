package com.willwinder.universalgcodesender.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Tests how the setting for applying the height map to the program is copied between settings,
 * which the auto leveler panel does every time a setting is changed.
 */
public class AutoLevelSettingsCharacterizationTest {
    @Ignore("Fails: equals does not compare applyToGcode, so apply does not copy it unless another setting differs")
    @Test
    public void applyShouldCopyApplyToGcodeWhenNothingElseDiffers() {
        AutoLevelSettings source = new AutoLevelSettings();
        source.setApplyToGcode(false);
        AutoLevelSettings target = new AutoLevelSettings();

        target.apply(source);

        assertFalse(target.getApplyToGcode());
    }

    @Test
    public void applyShouldCopyApplyToGcodeWhenSomethingElseDiffers() {
        AutoLevelSettings source = new AutoLevelSettings();
        source.setApplyToGcode(false);
        source.setStepResolution(5);
        AutoLevelSettings target = new AutoLevelSettings();

        target.apply(source);

        assertFalse(target.getApplyToGcode());
    }

    @Ignore("Fails: equals does not compare applyToGcode, so apply does not copy it unless another setting differs")
    @Test
    public void settingsThatOnlyDifferInApplyToGcodeShouldNotBeEqual() {
        AutoLevelSettings first = new AutoLevelSettings();
        AutoLevelSettings second = new AutoLevelSettings();
        second.setApplyToGcode(false);

        assertFalse(first.equals(second));
        assertTrue(first.getApplyToGcode());
    }
}
