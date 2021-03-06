/*
 * Copyright (c) 2017 NCIC, Institute of Computing Technology, Chinese Academy of Sciences
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.ncic.bioinfo.sparkseq.algorithms.walker.printreads;

import org.ncic.bioinfo.sparkseq.algorithms.utils.EventType;
import org.ncic.bioinfo.sparkseq.algorithms.utils.QualityUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.RecalUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.basic.NestedIntegerArray;
import org.ncic.bioinfo.sparkseq.algorithms.data.basic.Pair;
import org.ncic.bioinfo.sparkseq.algorithms.utils.reports.GATKReport;
import org.ncic.bioinfo.sparkseq.algorithms.utils.reports.GATKReportTable;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.QuantizationInfo;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.RecalDatum;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.RecalibrationArgumentCollection;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.RecalibrationTables;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.covariate.Covariate;
import org.ncic.bioinfo.sparkseq.exceptions.ReviewedGATKException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Author: wbc
 */
public class RecalibrationReport {
    private QuantizationInfo quantizationInfo; // histogram containing the counts for qual quantization (calculated after recalibration is done)
    private final RecalibrationTables recalibrationTables; // quick access reference to the tables
    private final Covariate[] requestedCovariates; // list of all covariates to be used in this calculation
    private final HashMap<String, Integer> optionalCovariateIndexes;

    private final GATKReportTable argumentTable; // keep the argument table untouched just for output purposes
    private final RecalibrationArgumentCollection RAC; // necessary for quantizing qualities with the same parameter

    private final int[] tempRGarray = new int[2];
    private final int[] tempQUALarray = new int[3];
    private final int[] tempCOVarray = new int[4];

    public RecalibrationReport(final GATKReport report) {
        this(report, getReadGroups(report));
    }

    public RecalibrationReport(final GATKReport report, final SortedSet<String> allReadGroups) {

        argumentTable = report.getTable(RecalUtils.ARGUMENT_REPORT_TABLE_TITLE);
        RAC = initializeArgumentCollectionTable(argumentTable);

        GATKReportTable quantizedTable = report.getTable(RecalUtils.QUANTIZED_REPORT_TABLE_TITLE);
        quantizationInfo = initializeQuantizationTable(quantizedTable);

        Pair<ArrayList<Covariate>, ArrayList<Covariate>> covariates = RecalUtils.initializeCovariates(RAC); // initialize the required and optional covariates
        ArrayList<Covariate> requiredCovariates = covariates.getFirst();
        ArrayList<Covariate> optionalCovariates = covariates.getSecond();
        requestedCovariates = new Covariate[requiredCovariates.size() + optionalCovariates.size()];
        optionalCovariateIndexes = new HashMap<String, Integer>(optionalCovariates.size());
        int covariateIndex = 0;
        for (final Covariate covariate : requiredCovariates)
            requestedCovariates[covariateIndex++] = covariate;
        for (final Covariate covariate : optionalCovariates) {
            requestedCovariates[covariateIndex] = covariate;
            final String covariateName = covariate.getClass().getSimpleName().split("Covariate")[0]; // get the name of the covariate (without the "covariate" part of it) so we can match with the GATKReport
            optionalCovariateIndexes.put(covariateName, covariateIndex-2);
            covariateIndex++;
        }

        for (Covariate cov : requestedCovariates)
            cov.initialize(RAC); // initialize any covariate member variables using the shared argument collection

        recalibrationTables = new RecalibrationTables(requestedCovariates, allReadGroups.size());

        initializeReadGroupCovariates(allReadGroups);

        parseReadGroupTable(report.getTable(RecalUtils.READGROUP_REPORT_TABLE_TITLE), recalibrationTables.getReadGroupTable());

        parseQualityScoreTable(report.getTable(RecalUtils.QUALITY_SCORE_REPORT_TABLE_TITLE), recalibrationTables.getQualityScoreTable());

        parseAllCovariatesTable(report.getTable(RecalUtils.ALL_COVARIATES_REPORT_TABLE_TITLE), recalibrationTables);

    }

    /**
     * Gets the unique read groups in the table
     *
     * @param report the GATKReport containing the table with RecalUtils.READGROUP_REPORT_TABLE_TITLE
     * @return the unique read groups
     */
    private static SortedSet<String> getReadGroups(final GATKReport report) {
        final GATKReportTable reportTable = report.getTable(RecalUtils.READGROUP_REPORT_TABLE_TITLE);
        final SortedSet<String> readGroups = new TreeSet<String>();
        for ( int i = 0; i < reportTable.getNumRows(); i++ )
            readGroups.add(reportTable.get(i, RecalUtils.READGROUP_COLUMN_NAME).toString());
        return readGroups;
    }

    /**
     * Combines two recalibration reports by adding all observations and errors
     *
     * Note: This method DOES NOT recalculate the empirical qualities and quantized qualities. You have to recalculate
     * them after combining. The reason for not calculating it is because this function is intended for combining a
     * series of recalibration reports, and it only makes sense to calculate the empirical qualities and quantized
     * qualities after all the recalibration reports have been combined. Having the user recalculate when appropriate,
     * makes this method faster
     *
     * Note2: The empirical quality reported, however, is recalculated given its simplicity.
     *
     * @param other the recalibration report to combine with this one
     */
    public void combine(final RecalibrationReport other) {
        for ( int tableIndex = 0; tableIndex < recalibrationTables.numTables(); tableIndex++ ) {
            final NestedIntegerArray<RecalDatum> myTable = recalibrationTables.getTable(tableIndex);
            final NestedIntegerArray<RecalDatum> otherTable = other.recalibrationTables.getTable(tableIndex);
            RecalUtils.combineTables(myTable, otherTable);
        }
    }

    public QuantizationInfo getQuantizationInfo() {
        return quantizationInfo;
    }

    public RecalibrationTables getRecalibrationTables() {
        return recalibrationTables;
    }

    public Covariate[] getRequestedCovariates() {
        return requestedCovariates;
    }

    /**
     * Initialize read group keys using the shared list of all the read groups.
     *
     * By using the same sorted set of read groups across all recalibration reports, even if
     * one report is missing a read group, all the reports use the same read group keys.
     *
     * @param allReadGroups The list of all possible read groups
     */
    private void initializeReadGroupCovariates(final SortedSet<String> allReadGroups) {
        for (String readGroup: allReadGroups) {
            requestedCovariates[0].keyFromValue(readGroup);
        }
    }

    /**
     * Compiles the list of keys for the Covariates table and uses the shared parsing utility to produce the actual table
     *
     * @param reportTable            the GATKReport table containing data for this table
     * @param recalibrationTables    the recalibration tables
    \     */
    private void parseAllCovariatesTable(final GATKReportTable reportTable, final RecalibrationTables recalibrationTables) {
        for ( int i = 0; i < reportTable.getNumRows(); i++ ) {
            final Object rg = reportTable.get(i, RecalUtils.READGROUP_COLUMN_NAME);
            tempCOVarray[0] = requestedCovariates[0].keyFromValue(rg);
            final Object qual = reportTable.get(i, RecalUtils.QUALITY_SCORE_COLUMN_NAME);
            tempCOVarray[1] = requestedCovariates[1].keyFromValue(qual);

            final String covName = (String)reportTable.get(i, RecalUtils.COVARIATE_NAME_COLUMN_NAME);
            final int covIndex = optionalCovariateIndexes.get(covName);
            final Object covValue = reportTable.get(i, RecalUtils.COVARIATE_VALUE_COLUMN_NAME);
            tempCOVarray[2] = requestedCovariates[RecalibrationTables.TableType.OPTIONAL_COVARIATE_TABLES_START.ordinal() + covIndex].keyFromValue(covValue);

            final EventType event = EventType.eventFrom((String)reportTable.get(i, RecalUtils.EVENT_TYPE_COLUMN_NAME));
            tempCOVarray[3] = event.ordinal();

            recalibrationTables.getTable(RecalibrationTables.TableType.OPTIONAL_COVARIATE_TABLES_START.ordinal() + covIndex).put(getRecalDatum(reportTable, i, false), tempCOVarray);
        }
    }

    /**
     *
     * Compiles the list of keys for the QualityScore table and uses the shared parsing utility to produce the actual table
     * @param reportTable            the GATKReport table containing data for this table
     * @param qualTable               the map representing this table
     */
    private void parseQualityScoreTable(final GATKReportTable reportTable, final NestedIntegerArray<RecalDatum> qualTable) {
        for ( int i = 0; i < reportTable.getNumRows(); i++ ) {
            final Object rg = reportTable.get(i, RecalUtils.READGROUP_COLUMN_NAME);
            tempQUALarray[0] = requestedCovariates[0].keyFromValue(rg);
            final Object qual = reportTable.get(i, RecalUtils.QUALITY_SCORE_COLUMN_NAME);
            tempQUALarray[1] = requestedCovariates[1].keyFromValue(qual);
            final EventType event = EventType.eventFrom((String)reportTable.get(i, RecalUtils.EVENT_TYPE_COLUMN_NAME));
            tempQUALarray[2] = event.ordinal();

            qualTable.put(getRecalDatum(reportTable, i, false), tempQUALarray);
        }
    }

    /**
     * Compiles the list of keys for the ReadGroup table and uses the shared parsing utility to produce the actual table
     *
     * @param reportTable            the GATKReport table containing data for this table
     * @param rgTable                the map representing this table
     */
    private void parseReadGroupTable(final GATKReportTable reportTable, final NestedIntegerArray<RecalDatum> rgTable) {
        for ( int i = 0; i < reportTable.getNumRows(); i++ ) {
            final Object rg = reportTable.get(i, RecalUtils.READGROUP_COLUMN_NAME);
            tempRGarray[0] = requestedCovariates[0].keyFromValue(rg);
            final EventType event = EventType.eventFrom((String)reportTable.get(i, RecalUtils.EVENT_TYPE_COLUMN_NAME));
            tempRGarray[1] = event.ordinal();

            rgTable.put(getRecalDatum(reportTable, i, true), tempRGarray);
        }
    }

    private double asDouble(final Object o) {
        if ( o instanceof Double )
            return (Double)o;
        else if ( o instanceof Integer )
            return (Integer)o;
        else if ( o instanceof Long )
            return (Long)o;
        else
            throw new ReviewedGATKException("Object " + o + " is expected to be either a double, long or integer but it's not either: " + o.getClass());
    }

    private long asLong(final Object o) {
        if ( o instanceof Long )
            return (Long)o;
        else if ( o instanceof Integer )
            return ((Integer)o).longValue();
        else if ( o instanceof Double )
            return ((Double)o).longValue();
        else
            throw new ReviewedGATKException("Object " + o + " is expected to be a long but it's not: " + o.getClass());
    }

    private RecalDatum getRecalDatum(final GATKReportTable reportTable, final int row, final boolean hasEstimatedQReportedColumn) {
        final long nObservations = asLong(reportTable.get(row, RecalUtils.NUMBER_OBSERVATIONS_COLUMN_NAME));
        final double nErrors = asDouble(reportTable.get(row, RecalUtils.NUMBER_ERRORS_COLUMN_NAME));
        //final double empiricalQuality = asDouble(reportTable.get(row, RecalUtils.EMPIRICAL_QUALITY_COLUMN_NAME));

        // the estimatedQreported column only exists in the ReadGroup table
        final double estimatedQReported = hasEstimatedQReportedColumn ?
                (Double) reportTable.get(row, RecalUtils.ESTIMATED_Q_REPORTED_COLUMN_NAME) : // we get it if we are in the read group table
                Byte.parseByte((String) reportTable.get(row, RecalUtils.QUALITY_SCORE_COLUMN_NAME)); // or we use the reported quality if we are in any other table

        final RecalDatum datum = new RecalDatum(nObservations, nErrors, (byte)1);
        datum.setEstimatedQReported(estimatedQReported);
        //datum.setEmpiricalQuality(empiricalQuality); // don't set the value here because we will want to recompute with a different conditional Q score prior value
        return datum;
    }

    /**
     * Parses the quantization table from the GATK Report and turns it into a map of original => quantized quality scores
     *
     * @param table the GATKReportTable containing the quantization mappings
     * @return an ArrayList with the quantization mappings from 0 to MAX_SAM_QUAL_SCORE
     */
    private QuantizationInfo initializeQuantizationTable(GATKReportTable table) {
        final Byte[] quals  = new Byte[QualityUtils.MAX_SAM_QUAL_SCORE + 1];
        final Long[] counts = new Long[QualityUtils.MAX_SAM_QUAL_SCORE + 1];
        for ( int i = 0; i < table.getNumRows(); i++ ) {
            final byte originalQual = (byte)i;
            final Object quantizedObject = table.get(i, RecalUtils.QUANTIZED_VALUE_COLUMN_NAME);
            final Object countObject = table.get(i, RecalUtils.QUANTIZED_COUNT_COLUMN_NAME);
            final byte quantizedQual = Byte.parseByte(quantizedObject.toString());
            final long quantizedCount = Long.parseLong(countObject.toString());
            quals[originalQual] = quantizedQual;
            counts[originalQual] = quantizedCount;
        }
        return new QuantizationInfo(Arrays.asList(quals), Arrays.asList(counts));
    }

    /**
     * Parses the arguments table from the GATK Report and creates a RAC object with the proper initialization values
     *
     * @param table the GATKReportTable containing the arguments and its corresponding values
     * @return a RAC object properly initialized with all the objects in the table
     */
    private RecalibrationArgumentCollection initializeArgumentCollectionTable(GATKReportTable table) {
        final RecalibrationArgumentCollection RAC = new RecalibrationArgumentCollection();

        for ( int i = 0; i < table.getNumRows(); i++ ) {
            final String argument = table.get(i, "Argument").toString();
            Object value = table.get(i, RecalUtils.ARGUMENT_VALUE_COLUMN_NAME);
            if (value.equals("null"))
                value = null; // generic translation of null values that were printed out as strings | todo -- add this capability to the GATKReport

            if (argument.equals("covariate") && value != null)
                RAC.COVARIATES = value.toString().split(",");

            else if (argument.equals("standard_covs"))
                RAC.DO_NOT_USE_STANDARD_COVARIATES = Boolean.parseBoolean((String) value);

            else if (argument.equals("solid_recal_mode"))
                RAC.SOLID_RECAL_MODE = RecalUtils.SOLID_RECAL_MODE.recalModeFromString((String) value);

            else if (argument.equals("solid_nocall_strategy"))
                RAC.SOLID_NOCALL_STRATEGY = RecalUtils.SOLID_NOCALL_STRATEGY.nocallStrategyFromString((String) value);

            else if (argument.equals("mismatches_context_size"))
                RAC.MISMATCHES_CONTEXT_SIZE = Integer.parseInt((String) value);

            else if (argument.equals("indels_context_size"))
                RAC.INDELS_CONTEXT_SIZE = Integer.parseInt((String) value);

            else if (argument.equals("mismatches_default_quality"))
                RAC.MISMATCHES_DEFAULT_QUALITY = Byte.parseByte((String) value);

            else if (argument.equals("insertions_default_quality"))
                RAC.INSERTIONS_DEFAULT_QUALITY = Byte.parseByte((String) value);

            else if (argument.equals("deletions_default_quality"))
                RAC.DELETIONS_DEFAULT_QUALITY = Byte.parseByte((String) value);

            else if (argument.equals("maximum_cycle_value"))
                RAC.MAXIMUM_CYCLE_VALUE = Integer.parseInt((String) value);

            else if (argument.equals("low_quality_tail"))
                RAC.LOW_QUAL_TAIL = Byte.parseByte((String) value);

            else if (argument.equals("default_platform"))
                RAC.DEFAULT_PLATFORM = (String) value;

            else if (argument.equals("force_platform"))
                RAC.FORCE_PLATFORM = (String) value;

            else if (argument.equals("quantizing_levels"))
                RAC.QUANTIZING_LEVELS = Integer.parseInt((String) value);

            else if (argument.equals("binary_tag_name"))
                RAC.BINARY_TAG_NAME = (value == null) ? null : (String) value;

            else if (argument.equals("sort_by_all_columns"))
                RAC.SORT_BY_ALL_COLUMNS = Boolean.parseBoolean((String) value);
        }

        return RAC;
    }

    /**
     * this functionality avoids recalculating the empirical qualities, estimated reported quality
     * and quantization of the quality scores during every call of combine(). Very useful for the BQSRGatherer.
     */
    public void calculateQuantizedQualities() {
        quantizationInfo = new QuantizationInfo(recalibrationTables, RAC.QUANTIZING_LEVELS);
    }

    public GATKReport createGATKReport() {
        return RecalUtils.getRecalibrationReport(this.RAC, this.quantizationInfo, this.recalibrationTables, this.requestedCovariates, this.RAC.SORT_BY_ALL_COLUMNS.booleanValue());
    }

    public RecalibrationArgumentCollection getRAC() {
        return RAC;
    }

    /**
     *
     * @deprecated use {@link #getRequestedCovariates()} instead.
     */
    @Deprecated
    public Covariate[] getCovariates() {
        return requestedCovariates;
    }

    /**
     * @return true if the report has no data
     */
    public boolean isEmpty() {
        return recalibrationTables.isEmpty();
    }
}
