package com.willwinder.universalgcodesender.utils;

import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tests how the settings of the auto leveler are compared and copied, which the panels of the auto
 * leveler do every time a setting is changed. A setting that is not compared is not copied when
 * it is the only one that differs, and is overwritten by an old value when another one is changed.
 */
public class AutoLevelSettingsCharacterizationTest {

    /**
     * Every setting, changed to something else than its default
     */
    private static final List<Consumer<AutoLevelSettings>> CHANGES = List.of(
            settings -> settings.setMinX(-1),
            settings -> settings.setMinY(-2),
            settings -> settings.setMinZ(-3),
            settings -> settings.setMaxX(11),
            settings -> settings.setMaxY(12),
            settings -> settings.setMaxZ(13),
            settings -> settings.setStepResolution(5),
            settings -> settings.setZRetract(0.5),
            settings -> settings.setZSurface(0.25),
            settings -> settings.setProbeScanFeedRate(500),
            settings -> settings.setProbeSpeed(20),
            settings -> settings.setAutoLevelProbeOffset(new Position(1, 2, 3, Units.MM)),
            settings -> settings.setTouchPlateThickness(1.5),
            settings -> settings.setAutoLevelArcSliceLength(0.05),
            settings -> settings.setApplyToGcode(false));

    private static void assertSameValues(AutoLevelSettings expected, AutoLevelSettings actual) {
        assertEquals(expected.getMinX(), actual.getMinX(), 0);
        assertEquals(expected.getMinY(), actual.getMinY(), 0);
        assertEquals(expected.getMinZ(), actual.getMinZ(), 0);
        assertEquals(expected.getMaxX(), actual.getMaxX(), 0);
        assertEquals(expected.getMaxY(), actual.getMaxY(), 0);
        assertEquals(expected.getMaxZ(), actual.getMaxZ(), 0);
        assertEquals(expected.getStepResolution(), actual.getStepResolution(), 0);
        assertEquals(expected.getZRetract(), actual.getZRetract(), 0);
        assertEquals(expected.getZSurface(), actual.getZSurface(), 0);
        assertEquals(expected.getProbeScanFeedRate(), actual.getProbeScanFeedRate(), 0);
        assertEquals(expected.getProbeSpeed(), actual.getProbeSpeed(), 0);
        assertEquals(expected.getAutoLevelProbeOffset(), actual.getAutoLevelProbeOffset());
        assertEquals(expected.getTouchPlateThickness(), actual.getTouchPlateThickness(), 0);
        assertEquals(expected.getAutoLevelArcSliceLength(), actual.getAutoLevelArcSliceLength(), 0);
        assertEquals(expected.getApplyToGcode(), actual.getApplyToGcode());
    }

    @Test
    public void settingsWithTheSameValuesShouldBeEqual() {
        assertTrue(new AutoLevelSettings().equals(new AutoLevelSettings()));
    }

    @Test
    public void settingsThatDifferInAnySingleSettingShouldNotBeEqual() {
        for (int i = 0; i < CHANGES.size(); i++) {
            AutoLevelSettings changed = new AutoLevelSettings();
            CHANGES.get(i).accept(changed);

            assertFalse("Change " + i, new AutoLevelSettings().equals(changed));
            assertFalse("Change " + i, changed.equals(new AutoLevelSettings()));
        }
    }

    @Test
    public void settingsThatOnlyDifferInApplyToGcodeShouldNotBeEqual() {
        AutoLevelSettings first = new AutoLevelSettings();
        AutoLevelSettings second = new AutoLevelSettings();
        second.setApplyToGcode(false);

        assertFalse(first.equals(second));
        assertTrue(first.getApplyToGcode());
    }

    @Test
    public void applyShouldCopyAnySingleSettingThatDiffers() {
        for (int i = 0; i < CHANGES.size(); i++) {
            AutoLevelSettings source = new AutoLevelSettings();
            CHANGES.get(i).accept(source);
            AutoLevelSettings target = new AutoLevelSettings();
            List<String> notifications = new ArrayList<>();
            target.setSettingChangeListener(() -> notifications.add("changed"));

            target.apply(source);

            assertSameValues(source, target);
            assertTrue("Change " + i, target.equals(source));
            assertEquals("Change " + i, 1, notifications.size());
        }
    }

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

    @Test
    public void applyShouldNotNotifyWhenNothingDiffers() {
        AutoLevelSettings target = new AutoLevelSettings();
        List<String> notifications = new ArrayList<>();
        target.setSettingChangeListener(() -> notifications.add("changed"));

        target.apply(new AutoLevelSettings());

        assertTrue(notifications.isEmpty());
    }

    @Test
    public void copyShouldHaveAllSettings() {
        AutoLevelSettings source = new AutoLevelSettings();
        CHANGES.forEach(change -> change.accept(source));

        assertSameValues(source, new AutoLevelSettings(source));
    }

    @Test
    public void copyShouldHaveApplyToGcodeWhenNothingElseDiffersFromTheDefaults() {
        AutoLevelSettings source = new AutoLevelSettings();
        source.setApplyToGcode(false);

        assertFalse(new AutoLevelSettings(source).getApplyToGcode());
    }

    @Test
    public void applyToGcodeShouldFollowWhenItIsAlternatedRepeatedly() {
        AutoLevelSettings source = new AutoLevelSettings();
        AutoLevelSettings target = new AutoLevelSettings();

        boolean expected = true;
        for (int i = 0; i < 7; i++) {
            expected = !expected;
            source.setApplyToGcode(expected);
            target.apply(source);

            assertEquals(expected, target.getApplyToGcode());
        }
    }

    @Test
    public void changingAnotherSettingThroughACopyShouldNotRevertApplyToGcode() {
        // A panel keeps a copy of the settings, is told when they change and writes its copy back when
        // one of its own settings is changed
        AutoLevelSettings settings = new AutoLevelSettings();
        AutoLevelSettings copy = new AutoLevelSettings(settings);

        settings.setApplyToGcode(false);
        copy.apply(settings);
        copy.setStepResolution(5);
        settings.apply(copy);

        assertFalse(settings.getApplyToGcode());
        assertEquals(5, settings.getStepResolution(), 0);
    }

    @Test
    public void settingAutoLevelSettingsShouldChangeApplyToGcodeWhenNothingElseDiffers() {
        Settings settings = new Settings();
        AutoLevelSettings changed = new AutoLevelSettings(settings.getAutoLevelSettings());
        changed.setApplyToGcode(false);
        List<String> notifications = new ArrayList<>();
        settings.addSettingChangeListener(() -> notifications.add("changed"));

        settings.setAutoLevelSettings(changed);

        assertFalse(settings.getAutoLevelSettings().getApplyToGcode());
        assertFalse(notifications.isEmpty());
    }
}
