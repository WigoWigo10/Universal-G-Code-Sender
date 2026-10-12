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
package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.Evaluation;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.ReferencePoint;

import javax.swing.table.AbstractTableModel;
import java.util.List;
import java.util.Locale;

/**
 * Shows the reference points of an {@link AlignmentModel} in a table where the positions can be edited
 */
public class AlignmentTableModel extends AbstractTableModel {
    public static final int COLUMN_NUMBER = 0;
    public static final int COLUMN_NOMINAL_X = 1;
    public static final int COLUMN_NOMINAL_Y = 2;
    public static final int COLUMN_MEASURED_X = 3;
    public static final int COLUMN_MEASURED_Y = 4;
    public static final int COLUMN_RESIDUAL = 5;
    public static final int COLUMN_CAPTURE = 6;

    public static final String CAPTURE = "Capture";
    public static final String INVALID = "?";

    private static final String[] COLUMNS = {"#", "Nominal X", "Nominal Y", "Measured X", "Measured Y", "Residual", "Machine"};

    private final AlignmentModel model;
    private final Runnable modelListener = this::fireTableDataChanged;

    public AlignmentTableModel(AlignmentModel model) {
        this.model = model;
        model.addListener(modelListener);
    }

    /**
     * Stops showing the model, which may outlive the table
     */
    public void dispose() {
        model.removeListener(modelListener);
    }

    @Override
    public int getRowCount() {
        return model.getPoints().size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column != COLUMN_NUMBER && column != COLUMN_RESIDUAL;
    }

    @Override
    public Object getValueAt(int row, int column) {
        ReferencePoint point = model.getPoints().get(row);
        return switch (column) {
            case COLUMN_NUMBER -> row + 1;
            case COLUMN_NOMINAL_X -> format(point.nominalX());
            case COLUMN_NOMINAL_Y -> format(point.nominalY());
            case COLUMN_MEASURED_X -> format(point.measuredX());
            case COLUMN_MEASURED_Y -> format(point.measuredY());
            case COLUMN_RESIDUAL -> getResidual(row);
            default -> CAPTURE;
        };
    }

    private String getResidual(int row) {
        Evaluation evaluation = model.evaluate();
        if (evaluation.result().isEmpty()) {
            return "";
        }
        List<Double> residuals = evaluation.result().get().residuals();
        return row < residuals.size() ? format(residuals.get(row)) : "";
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        ReferencePoint point = model.getPoints().get(row);
        Double number = parse(value);
        switch (column) {
            case COLUMN_NOMINAL_X -> model.setNominal(row, number, point.nominalY());
            case COLUMN_NOMINAL_Y -> model.setNominal(row, point.nominalX(), number);
            case COLUMN_MEASURED_X -> model.setMeasured(row, number, point.measuredY());
            case COLUMN_MEASURED_Y -> model.setMeasured(row, point.measuredX(), number);
            default -> {
                // The other columns have no values to set
            }
        }
    }

    /**
     * Parses a coordinate that may be written with a decimal comma. An empty value is no coordinate,
     * and anything that is not a number becomes a coordinate that is not valid.
     */
    static Double parse(Object value) {
        String text = value == null ? "" : value.toString().trim().replace(',', '.');
        if (text.isEmpty()) {
            return null;
        }

        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String format(Double value) {
        if (value == null) {
            return "";
        }
        if (!Double.isFinite(value)) {
            return INVALID;
        }

        String text = String.format(Locale.ROOT, "%.4f", value);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text.equals("-0") ? "0" : text;
    }
}
