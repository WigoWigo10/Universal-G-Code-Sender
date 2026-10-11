package com.willwinder.ugs.platform.surfacescanner.ui;

import com.willwinder.ugs.platform.surfacescanner.MeshLevelManager;
import com.willwinder.ugs.platform.surfacescanner.SurfaceScanner;
import com.willwinder.ugs.platform.surfacescanner.actions.ToggleApplyToGcodeAction;
import com.willwinder.ugs.platform.surfacescanner.renderable.AutoLevelPreview;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Unit;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.events.SettingChangedEvent;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.uielements.components.UnitSpinner;
import com.willwinder.universalgcodesender.utils.AutoLevelSettings;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JCheckBox;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;

/**
 * Tests the check box for applying the height map to the program together with the rest of the
 * panel, connected to the backend in the same way as the window of the auto leveler does it.
 */
public class AutoLevelerPanelApplyToGcodeTest {
    private BackendAPI backend;
    private AutoLevelSettings backendSettings;
    private SurfaceScanner scanner;
    private AutoLevelerPanel panel;
    private JCheckBox applyToGcode;
    private UGSEventListener settingsListener;
    private int numberOfNewScans;

    @Before
    public void setUp() throws Exception {
        GUIBackend newBackend = new GUIBackend();
        newBackend.applySettings(new Settings());
        LookupService.register(newBackend);

        // The actions of the panel use the backend that is registered
        backend = LookupService.lookup(BackendAPI.class);
        backendSettings = backend.getSettings().getAutoLevelSettings();
        backendSettings.apply(new AutoLevelSettings());

        scanner = new SurfaceScanner(backend);
        MeshLevelManager meshLevelManager = new MeshLevelManager(scanner, backend);
        panel = new AutoLevelerPanel(scanner, meshLevelManager, new AutoLevelPreview("Test"), backendSettings, UnitUtils.Units.MM);

        // What the window of the auto leveler does when the settings are changed in the panel
        panel.addAutoLevelerPanelListener(() -> {
            numberOfNewScans++;
            backendSettings.apply(panel.getSettings());
            scanner.update(panel.getMinPosition(), panel.getMaxPosition());
            scanner.reset();
        });

        // What the window of the auto leveler does when the settings are changed in the backend
        settingsListener = event -> {
            if (event instanceof SettingChangedEvent) {
                panel.setSettings(backendSettings);
            }
        };
        backend.addUGSEventListener(settingsListener);

        applyToGcode = findApplyToGcodeCheckBox(panel);
    }

    @After
    public void tearDown() {
        backend.removeUGSEventListener(settingsListener);
        backendSettings.apply(new AutoLevelSettings());
    }

    private static JCheckBox findApplyToGcodeCheckBox(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JCheckBox checkBox && checkBox.getAction() instanceof ToggleApplyToGcodeAction) {
                return checkBox;
            }
            if (component instanceof Container child) {
                JCheckBox result = findApplyToGcodeCheckBox(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private UnitSpinner getSpinner(String name) throws Exception {
        Field field = AutoLevelerPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return (UnitSpinner) field.get(panel);
    }

    private void assertApplyToGcode(boolean expected) {
        assertEquals("The setting of the backend", expected, backendSettings.getApplyToGcode());
        assertEquals("The check box", expected, applyToGcode.isSelected());
        assertEquals("The settings of the panel", expected, panel.getSettings().getApplyToGcode());
    }

    @Test
    public void checkBoxShouldShowTheSettingWhenThePanelIsCreated() {
        assertApplyToGcode(true);
    }

    @Test
    public void checkBoxShouldShowTheSettingWhenThePanelIsCreatedWithItTurnedOff() {
        backendSettings.setApplyToGcode(false);

        AutoLevelerPanel otherPanel = new AutoLevelerPanel(scanner, new MeshLevelManager(scanner, backend), new AutoLevelPreview("Test"), backendSettings, UnitUtils.Units.MM);

        assertFalse(findApplyToGcodeCheckBox(otherPanel).isSelected());
        assertFalse(otherPanel.getSettings().getApplyToGcode());
    }

    @Test
    public void clickingCheckBoxShouldChangeTheSettingEverywhere() {
        applyToGcode.doClick();

        assertApplyToGcode(false);
    }

    @Test
    public void clickingCheckBoxRepeatedlyShouldAlternateTheSetting() {
        boolean expected = true;
        for (int i = 0; i < 7; i++) {
            applyToGcode.doClick();
            expected = !expected;

            assertApplyToGcode(expected);
        }
    }

    @Test
    public void clickingCheckBoxShouldNotDiscardTheScannedSurface() {
        scanner.scanRandomData();
        assertTrue(scanner.isValid());
        numberOfNewScans = 0;

        for (int i = 0; i < 4; i++) {
            applyToGcode.doClick();

            assertTrue(scanner.isValid());
        }
        assertEquals(0, numberOfNewScans);
        assertApplyToGcode(true);
    }

    @Test
    public void turningSettingOffFromElsewhereShouldUncheckTheCheckBoxAndKeepTheScannedSurface() {
        scanner.scanRandomData();
        numberOfNewScans = 0;

        // This is how the alignment of PCBs turns off the height map
        backendSettings.setApplyToGcode(false);

        assertApplyToGcode(false);
        assertTrue(scanner.isValid());
        assertEquals(0, numberOfNewScans);
    }

    @Test
    public void changingAnotherSettingInThePanelShouldNotTurnTheSettingBackOn() throws Exception {
        applyToGcode.doClick();
        assertApplyToGcode(false);

        getSpinner("stepResolution").setValue(0.5, Unit.MM);
        getSpinner("zSurface").setValue(0.25, Unit.MM);

        assertEquals(0.5, backendSettings.getStepResolution(), 0.0001);
        assertEquals(0.25, backendSettings.getZSurface(), 0.0001);
        assertApplyToGcode(false);
    }

    @Test
    public void changingAnotherSettingInThePanelShouldNotTurnTheSettingBackOff() throws Exception {
        applyToGcode.doClick();
        applyToGcode.doClick();
        assertApplyToGcode(true);

        getSpinner("stepResolution").setValue(0.5, Unit.MM);

        assertEquals(0.5, backendSettings.getStepResolution(), 0.0001);
        assertApplyToGcode(true);
    }

    @Test
    public void changingAnotherSettingInTheBackendShouldNotChangeTheSetting() {
        applyToGcode.doClick();

        backendSettings.setStepResolution(0.5);
        backendSettings.setMaxX(40);

        assertEquals(0.5, panel.getSettings().getStepResolution(), 0.0001);
        assertEquals(40, panel.getSettings().getMaxX(), 0.0001);
        assertApplyToGcode(false);
    }

    @Test
    public void changingScanAreaInTheBackendShouldStillPrepareANewScan() {
        scanner.scanRandomData();
        numberOfNewScans = 0;

        backendSettings.setMaxX(40);

        assertTrue(numberOfNewScans > 0);
        assertFalse(scanner.isValid());
    }

    @Test
    public void setSettingsShouldNotTellListenersWhenOnlyApplyToGcodeDiffers() {
        AutoLevelSettings settings = new AutoLevelSettings(panel.getSettings());
        settings.setApplyToGcode(false);
        numberOfNewScans = 0;

        panel.setSettings(settings);

        assertFalse(panel.getSettings().getApplyToGcode());
        assertEquals(0, numberOfNewScans);
    }

    @Test
    public void setSettingsShouldTellListenersWhenTheScanAreaDiffers() {
        AutoLevelSettings settings = new AutoLevelSettings(panel.getSettings());
        settings.setApplyToGcode(false);
        settings.setMaxY(30);
        numberOfNewScans = 0;

        panel.setSettings(settings);

        assertFalse(panel.getSettings().getApplyToGcode());
        assertEquals(30, panel.getSettings().getMaxY(), 0.0001);
        assertTrue(numberOfNewScans > 0);
    }
}
