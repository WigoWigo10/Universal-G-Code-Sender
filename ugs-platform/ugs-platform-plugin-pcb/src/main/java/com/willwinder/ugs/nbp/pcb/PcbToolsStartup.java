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
package com.willwinder.ugs.nbp.pcb;

import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService;
import com.willwinder.ugs.nbp.pcb.service.PcbJobValidator;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.services.RunFromService;
import org.openide.modules.OnStart;

import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * Registers the job validator for aligned programs when the application starts, so that aligned
 * programs are protected whether or not any window of the PCB tools has been opened.
 * <p>
 * If this fails the validator is missing, and the backend then refuses to start aligned programs
 * as they declare that they require it.
 */
@OnStart
public class PcbToolsStartup implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(PcbToolsStartup.class.getName());

    @Override
    public void run() {
        BackendAPI backend = LookupService.lookup(BackendAPI.class);
        RunFromService runFromService = LookupService.lookup(RunFromService.class);
        register(backend, runFromService::getLineNumber);
    }

    /**
     * @param runFromLine gives the line that the program is set to run from, or zero if it runs from the beginning
     * @return the alignment service that the validator uses, which is also registered for lookup
     */
    static PcbAlignmentService register(BackendAPI backend, IntSupplier runFromLine) {
        PcbAlignmentService alignmentService = new PcbAlignmentService(backend);
        LookupService.register(alignmentService);
        backend.getJobValidationService().register(new PcbJobValidator(alignmentService, runFromLine));
        LOGGER.info("Registered the job validator for aligned PCB programs");
        return alignmentService;
    }
}
