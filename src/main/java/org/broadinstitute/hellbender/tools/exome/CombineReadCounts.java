package org.broadinstitute.hellbender.tools.exome;

import org.broadinstitute.hellbender.cmdline.*;
import org.broadinstitute.hellbender.cmdline.programgroups.ExomeAnalysisProgramGroup;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.tsv.DataLine;
import org.broadinstitute.hellbender.utils.tsv.TableColumnCollection;
import org.broadinstitute.hellbender.utils.tsv.TableReader;
import org.broadinstitute.hellbender.utils.tsv.TableWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Combines a set of read counts input files in a single multi read count column output file.
 * <p>
 * The user can indicate the input files individually using multiple
 *   {@value org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions#INPUT_SHORT_NAME} arguments,
 *   collectively in a input file name
 *   list file using a single {@value #READ_COUNT_FILE_LIST_SHORT_NAME} argument or
 *   a combination of both approaches.
 * </p>
 *
 * <p>
 *   The output read count columns will appear sorted in alpha-numerical order and target will appear in the same order as the input. The output
 *   file name must be specified with the {@value org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions#OUTPUT_SHORT_NAME} argument.
 * </p>
 *
 * <p>
 *   By default, all targets in the inputs will be present in the output. However the user can indicate
 *   the subset of targets to output using the {@value TargetArgumentCollection#TARGET_FILE_SHORT_NAME} argument.
 * </p>
 *
 * <p>
 *   In order to be able to handle a large number of input files, the tool proceeds to merge files
 *   in a balance tree fashion. The maximum number of files to merge in one go can be specified using
 *   the {@value #MAX_GROUP_SIZE_SHORT_NAME} argument that is set to {@value #DEFAULT_MAX_GROUP_SIZE} by default.
 * </p>
 *
 * <p>
 *    The following restrictions apply:
 *
 *    <ul>
 *        <li>All input files must contain the subject targets in the same order.</li>
 *        <li>If no target file is specified all input files must contain exactly the same targets.</li>
 *        <li>If a target file is specified all input files must contain at least the targets listed in that file and in the same order;
 *            they can contain other additional targets that may appear any where in the input files. These
 *            will be ignored.</li>
 *    </ul>
 * </p>
 *
 * <p>
 *     Example 1:
 *     <pre>
 *         java -jar hellbender.jar CombineReadCounts -I sample1.tab -I sample2.tab ... -I sampleN.tab -O all-samples.tab
 *     </pre>
 * </p>
 * <p>
 *     Example 2:
 *     <pre>
 *         java -jar hellbender.jar CombineReadCounts -inputList my-samples.list -O all-samples.tab
 *     </pre>
 *     where {@code my-samples.list} contains:
 *     <pre>
 *
 *         sample1.tab
 *         sample2.tab
 *         sample3.tab
 *         ...
 *         sampleN.tab
 *     </pre>
 * </p>
 * <h2>Input and Output File Format</h2>
 * <p>
 *    Input read count file format follows the one produced by {@link ExomeReadCounts}.
 *    Namely a tab separated value table file where there is a least one column, <b>NAME</b>;
 *    indicating the target name, an arbitrary number of read count columns (e.g. one per sample)
 *    and the target coordinates (<b>CONTIG</b>, <b>START</b> and <b>STOP</b>) which are optional if
 *    a targets file is provided.
 * </p>
 * <p>
 *     Example:
 *     <pre>
 *         CONTIG   START   END     NAME        SAMPLE_1    SAMPLE_2    SAMPLE_3 ...    SAMPLE_N
 *         1        100     200     target_0    10.1        13.3        .42             4.4
 *         1        300     400     target_1    5.1         1.5         5.1             19.0
 *         2        100     500     target_2    79.0        5.3         9.1             20.11
 *     </pre>
 * </p>
 *
 * <p>
 *     When provided, the targets file follows the same format but without read count columns, the coordinate
 *     columns are mandatory.
 *
 *     Example:
 *     <pre>
 *         CONTIG   START   END     NAME
 *         1        100     200     target_0
 *         1        300     400     target_1
 *         2        100     500     target_2
 *     </pre>
 * </p>
 *
 * <p>
 *     The output file format is the same as the input file format with a one read count column for each present amongst
 *     the input files. The coordinates columns are always present.
 * </p>
 *
 * @author Valentin Ruano-Rubio &lt;valentin@broadinstitute.org&gt;
 */
@CommandLineProgramProperties(
        oneLineSummary = "Combines Read Counts",
        summary = "Combines Read Counts",
        programGroup = ExomeAnalysisProgramGroup.class
)
public final class CombineReadCounts extends CommandLineProgram {

    public static final String READ_COUNT_FILES_SHORT_NAME = StandardArgumentDefinitions.INPUT_SHORT_NAME;
    public static final String READ_COUNT_FILES_FULL_NAME  = StandardArgumentDefinitions.INPUT_LONG_NAME;
    public static final String READ_COUNT_FILE_LIST_SHORT_NAME = "inputList";
    public static final String READ_COUNT_FILE_LIST_FULL_NAME = READ_COUNT_FILE_LIST_SHORT_NAME;
    public static final String MAX_GROUP_SIZE_SHORT_NAME = "MOF";
    public static final String MAX_GROUP_SIZE_FULL_NAME = "maxOpenFiles";
    public static final int DEFAULT_MAX_GROUP_SIZE = 100;

    private static final String READ_COUNTS_FILE_DOCUMENTATION =
            "Coverage files to combine, they must contain all the targets in the input file (" +
                    TargetArgumentCollection.TARGET_FILE_FULL_NAME + ") and in the same order";

    @Argument(
            doc = "File containing a list of coverage files to merge",
            shortName = READ_COUNT_FILE_LIST_SHORT_NAME,
            fullName  = READ_COUNT_FILE_LIST_FULL_NAME,
            optional = true
    )
    protected File coverageFileList;

    @Argument(
            doc = READ_COUNTS_FILE_DOCUMENTATION,
            shortName = READ_COUNT_FILES_SHORT_NAME,
            fullName = READ_COUNT_FILES_FULL_NAME,
            optional = true
    )
    protected List<File> coverageFiles = new ArrayList<>();

    @Argument(
            doc = "Maximum number of files to combine simultaneously.",
            shortName = MAX_GROUP_SIZE_SHORT_NAME,
            fullName = MAX_GROUP_SIZE_FULL_NAME,
            optional = false
    )
    protected int maxMergeSize = DEFAULT_MAX_GROUP_SIZE;


    @ArgumentCollection
    protected TargetArgumentCollection targetArguments = new TargetArgumentCollection(() ->
            composeAndCheckInputReadCountFiles(this.coverageFiles, this.coverageFileList).stream().findFirst().orElseGet(null));

    @Argument(
            doc = "Output file",
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName  = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            optional  = false
    )
    protected File outputFile;

    @Override
    public Object doWork() {
        final Set<File> temporaryFiles = new HashSet<>();
        final List<File> coverageFiles = composeAndCheckInputReadCountFiles(this.coverageFiles, this.coverageFileList);

        final TargetCollection<Target> targets = targetArguments.readTargetCollection(false);
        final int optimalMergingFileCount = calculateOptimalMergingFileCount(coverageFiles.size());
        logger.info(String.format("Merging %d read count files, maximum %d file at a time", coverageFiles.size(), optimalMergingFileCount));

        final Queue<File> remainingFilesToMerge = new ArrayDeque<>(coverageFiles);
        while (true) {
            logger.debug(String.format("Merging %d of %d",
                    Math.max(optimalMergingFileCount, remainingFilesToMerge.size()), remainingFilesToMerge.size()));
            final List<File> filesToMerge = removeFilesToMergeNext(optimalMergingFileCount, remainingFilesToMerge);
            final File mergeOutputFile = determineMergeOutputFile(temporaryFiles, remainingFilesToMerge);
            doMerge(targets, filesToMerge, mergeOutputFile);
            deleteMergedTemporaryFiles(temporaryFiles, filesToMerge);
            if (remainingFilesToMerge.isEmpty()) {
                break;
            }
            remainingFilesToMerge.add(mergeOutputFile);
        }
        return "SUCCESS";
    }

    /**
     * Delete temporary files within an merge file set.
     * <p>
     *     A file is considered to be temporary if is present in the temporary-file set provided.
     * </p>
     * <p>
     *     This set is modified by removed files as they are deleted.
     * </p>
     *
     * @param temporaryFiles set of files considered to be temporary.
     * @param alreadyMergedFiles the merged file set.
     */
    private void deleteMergedTemporaryFiles(final Set<File> temporaryFiles, final List<File> alreadyMergedFiles) {
        alreadyMergedFiles.stream()
                .filter(temporaryFiles::remove)
                .forEach(File::delete);
    }

    /**
     * Produces the name of the file to merge into given the remaining files-to-merge.
     * <p>
     * The files to be merged should have been already removed from the input files-to-merge queue, so that
     * an empty queue indicates the last merge.
     * </p>
     * <p>
     * Temporary merge output files are added to the input set.
     * </p>
     *
     * @param temporaryFiles set of temporary-files. Temporary files that need to be deleted when not needed anymore
     *                       must be added to this list.
     * @param remainingFilesToMerge remaining files to be merged after the next merge.
     * @return never {@code null}.
     */
    private File determineMergeOutputFile(final Set<File> temporaryFiles, final Queue<File> remainingFilesToMerge) {
        final File mergeOutputFile;
        if (remainingFilesToMerge.isEmpty()) {
            mergeOutputFile = outputFile;
        } else {
            temporaryFiles.add(mergeOutputFile = createMergeTemporalFile());
        }
        return mergeOutputFile;
    }

    /**
     * Extracts from the remaining files-to-merge queue the ones to be merged next.
     * <p>
     * This method modifies the input queue {@code filesToMerge} by removing the files to be merged next.
     * </p>
     * @param maximumMergingFileCount the maximum merge file group size.
     * @param remainingFilesToMerge the file-to-merge queue.
     * @return never {@code null}. The return list won't ever have more than {@code maximumMergingFileCount} members.
     */
    private List<File> removeFilesToMergeNext(final int maximumMergingFileCount, final Queue<File> remainingFilesToMerge) {
        final List<File> result = new ArrayList<>(maximumMergingFileCount);
        for (int i = 0; i < maximumMergingFileCount && !remainingFilesToMerge.isEmpty(); i++) {
            result.add(remainingFilesToMerge.remove());
        }
        return result;
    }

    /**
     * Composes the list of input read-count files from user arguments.
     * <p>
     * Checks whether the file names provided corresponds to readable regular files.
     * </p>
     * @param coverageFiles coverage files directly provided in the command line.
     * @param coverageFileList coverage file list file name.
     * @return never {@code null}.
     */
    private static List<File> composeAndCheckInputReadCountFiles(final List<File> coverageFiles, final File coverageFileList) {
        final List<File> result = new ArrayList<>(Math.max(100, coverageFiles.size()));
        result.addAll(coverageFiles);
        if (coverageFileList != null) {
            if (!coverageFileList.canRead() || !coverageFileList.isFile()) {
                throw new UserException.CouldNotReadInputFile(coverageFileList, "is not readable or not a regular file");
            }
            try (final BufferedReader reader = new BufferedReader(new FileReader(coverageFileList))) {
                reader.lines()
                // skip comment lines (start with #) and those with just space characters:
                      .filter(l -> !l.startsWith("#") && l.matches(".*\\S.*"))
                      .map(File::new)
                      .collect(Collectors.toList())
                      .forEach(result::add);
            } catch (final IOException ex) {
                throw new UserException.CouldNotReadInputFile(coverageFileList, ex);
            }
        }
        if (result.isEmpty()) {
            throw new UserException.BadArgumentValue(READ_COUNT_FILES_SHORT_NAME,
                    String.format("there must be at least one input file or a non-empty input file list (arg. -%s)", READ_COUNT_FILE_LIST_SHORT_NAME));
        } else {
            final Optional<File> badInputFile = coverageFiles.stream().filter(f -> !f.canRead() || !f.isFile()).findAny();
            if (badInputFile.isPresent()) {
                throw new UserException.CouldNotReadInputFile(badInputFile.get(), "is not readable or not a regular file");
            }
            return result;
        }
    }

    /**
     * The actual merge operation.
     * @param targets the target to merge in the input.
     * @param filesToMerge input files to be merged.
     * @param outputFile output file name.
     */
    private void doMerge(final TargetCollection<Target> targets, final List<File> filesToMerge, final File outputFile) {
        try (final ReadCountReaderCollection readers = new ReadCountReaderCollection(filesToMerge, targets);
             final ReadCountWriter writer = new ReadCountWriter(outputFile, readers.countColumnNames)) {
            final ReadCount readCounts = new ReadCount();
            for (final Target target : targets.targets()) {
                final double[] counts = readers.countsFor(target);
                readCounts.set(target, counts);
                writer.writeRecord(readCounts);
            }
        } catch (final IOException ex) {
            throw new UserException.CouldNotCreateOutputFile(outputFile, "Could not create output file");
        }
    }

    private File createMergeTemporalFile() {
        final File result;
        try {
            result = File.createTempFile("read-count-merge", ".tab");
        } catch (final IOException e) {
            throw new GATKException("Could not create temporal merge file", e);
        }
        result.deleteOnExit();
        return result;
    }

    /**
     * Returns the optimal number of files to merge simultaneously.
     *
     * @param inputSize number of coverage files to merge.
     * @return 1 or greater but no more than {@link #maxMergeSize}.
     */
    private int calculateOptimalMergingFileCount(final int inputSize) {
        final double minRoundCounts = Math.max(Math.round(Math.ceil(Math.log(inputSize) / Math.log(maxMergeSize))), 1.0);
        return (int) Math.round(Math.ceil(Math.pow(inputSize, 1.0 / minRoundCounts )));
    }

    private static final class ReadCount {
        private Target target;
        private double[] counts;

        /**
         * Creates a read-count with null target and counts.
         */
        private ReadCount() {
            // nothing to be done here.
        }

        /**
         * Creates a read-count giving initial values for the target and counts.
         * @param target the initial value for the target field.
         * @param counts the initial value for the counts field.
         */
        private ReadCount(final Target target, final double[] counts) {
            this.target = target;
            this.counts = counts;
        }

        /**
         * Change the values for the target and counts fields.
         * @param target the new value for the target field.
         * @param counts the new value for the counts field.
         */
        private void set(final Target target, final double[] counts) {
            this.target = target;
            this.counts = counts;
        }
    }

    private static TableColumnCollection composeOutputColumns(final List<String> countColumns) {
        final Set<String> columnNames = new LinkedHashSet<>(countColumns.size() + 4);
        columnNames.add(TargetColumns.CONTIG.toString());
        columnNames.add(TargetColumns.START.toString());
        columnNames.add(TargetColumns.END.toString());
        columnNames.add(TargetColumns.NAME.toString());
        for (final String countColumn : countColumns) {
            if (!columnNames.add(countColumn)) {
                throw new UserException.BadInput(
                        String.format("the count column named %s appears more than once amongst the input files", countColumn));
            }
        }
        return new TableColumnCollection(columnNames);
    }

    private static class ReadCountWriter extends TableWriter<ReadCount> implements AutoCloseable {

        public ReadCountWriter(final File file, final List<String> countColumns) throws IOException {
            super(file, composeOutputColumns(countColumns));
        }

        @Override
        protected void composeLine(final ReadCount record, final DataLine dataLine) {
            dataLine.append(record.target.getContig())
                    .append(record.target.getStart())
                    .append(record.target.getEnd())
                    .append(record.target.getName())
                    .append(record.counts);
        }
    }

    /**
     * Returns the list of count column names (no target info related columns)
     * in the order they appear in the table column collection.
     * @param columns the input table column collection.
     * @return never {@code null} but perhaps empty.
     */
    private List<String> readCountColumnNames(final TableColumnCollection columns) {
        return columns.names().stream()
                .filter(n -> !TargetColumns.isTargetColumnName(n))
                .collect(Collectors.toList());
    }

    /**
     * Creates a read-count file reader given the input files and the expected target collection.
     * @param file the input file.
     * @param targets the expected targets in the input file.
     * @return never {@code null}.
     */
    private TableReader<ReadCount> readCountFileReader(final File file, final TargetCollection<Target> targets) {

        try {
            return new TableReader<ReadCount>(file) {

                private boolean hasName;
                private boolean hasCoordinates;
                private int countColumnCount;
                private int[] countColumnIndexes;

                @Override
                public void processColumns(final TableColumnCollection columns) {
                    hasCoordinates = columns.containsAll(TargetColumns.CONTIG.toString(), TargetColumns.START.toString(),
                            TargetColumns.END.toString());
                    hasName = columns.contains(TargetColumns.NAME.toString());
                    if (!hasCoordinates && !hasName) {
                        throw this.formatException("header contain neither coordinates nor target name columns");
                    }
                    final List<String> countColumnNames = readCountColumnNames(columns);
                    countColumnCount = countColumnNames.size();
                    countColumnIndexes = new int[countColumnCount];
                    for (int i = 0; i < countColumnCount; i++) {
                        countColumnIndexes[i] = columns.indexOf(countColumnNames.get(i));
                    }
                }

                @Override
                protected ReadCount createRecord(final DataLine dataLine) {
                    final double[] counts = new double[countColumnCount];
                    final Target target = createTarget(dataLine);
                    for (int i = 0; i < counts.length; i++) {
                        counts[i] = dataLine.getDouble(countColumnIndexes[i]);
                    }
                    return new ReadCount(target, counts);
                }

                /**
                 * Extracts the target object out of a data input line.
                 * @param dataLine the input data line.
                 * @return never {@code null}.
                 */
                private Target createTarget(final DataLine dataLine) {
                    if (hasName) {
                        final String name = dataLine.get(TargetColumns.NAME);
                        final Target target = targets.target(name);
                        final SimpleInterval interval = createInterval(dataLine);
                        if (target == null) {
                            return new Target(name, createInterval(dataLine));
                        } else if (interval != null && !interval.equals(target.getInterval())) {
                            throw new UserException.BadInput(String.format("invalid target '%s' coordinates: expected %s but found %s",
                                    name, target.getInterval(), createInterval(dataLine)));
                        } else {
                            return target;
                        }
                    } else { // hasCoordinates must be true.
                        final SimpleInterval interval = createInterval(dataLine);
                        final Optional<Target> target = targets.targets(interval).stream().findAny();
                        if (!target.isPresent() || !target.get().getInterval().equals(interval)) {
                            throw formatException("target not found with coordinates " + interval);
                        }
                        return target.get();
                    }
                }

                /**
                 * Extract the interval out of a data line.
                 * @param dataLine the input data line.
                 * @return {@code null} if the interval cannot be determined from the input file alone.
                 */
                private SimpleInterval createInterval(final DataLine dataLine) {
                    if (hasCoordinates) {
                        return new SimpleInterval(dataLine.get(TargetColumns.CONTIG),
                                dataLine.getInt(TargetColumns.START),
                                dataLine.getInt(TargetColumns.END));
                    } else {
                        return null;
                    }
                }

            };
        } catch (final IOException ex) {
            throw new UserException.CouldNotReadInputFile(file, ex);
        }
    }

    /**
     * Collection of read-count readers that simultaneously parse several input read-count files.
     * <p>
     * All the readers progress through their corresponding input files at the same pace; thus sharing the
     * current target.
     * </p>
     */
    private final class ReadCountReaderCollection implements AutoCloseable {
        private final List<TableReader<ReadCount>> readers;
        private final List<ReadCount> lastReadCounts;
        private List<String> countColumnNames;
        private int[] countColumnSourceIndexMap;

        /**
         * Counts buffer used to accumulate counts coming from different readers.
         */
        private final double[] countsBuffer;
        private Target lastTarget;
        public ReadCountReaderCollection(final List<File> mergeGroup,
                                         final TargetCollection<Target> targets) {
            readers = mergeGroup.stream().map(f -> readCountFileReader(f, targets))
                .collect(Collectors.toList());
            lastReadCounts = new ArrayList<>();
            seekTarget(targets.target(0));
            composeCountColumnNamesAndSourceIndexMapping();
            // pre-allocate count array used to accumulate the counts from all readers.
            countsBuffer = new double[countColumnNames.size()];
        }

        /**
         * Initializes count column name data-structures.
         * <p>
         * Initializes {@link #countColumnNames} and {@link #countColumnSourceIndexMap}
         * based on the input readers headers.
         * </p>
         * <p>
         * This operation must be performed after we have found the first target
         * in all sources; headers may not be yet defined before then.
         * </p>
         */
        private void composeCountColumnNamesAndSourceIndexMapping() {
            final List<String> unsortedCountColumnNames = new ArrayList<>();
            for (final TableReader<ReadCount> reader : readers) {
                unsortedCountColumnNames.addAll(readCountColumnNames(reader.columns()));
            }
            countColumnSourceIndexMap = IntStream.range(0, unsortedCountColumnNames.size()).boxed()
                    .sorted(Comparator.comparing(unsortedCountColumnNames::get))
                    .mapToInt(Integer::intValue).toArray();
            countColumnNames = IntStream.of(countColumnSourceIndexMap)
                    .mapToObj(unsortedCountColumnNames::get)
                    .collect(Collectors.toList());
        }

        /**
         * Find a given target in the remaining input from each reader.
         * <p>
         * This code assume that the requested target its downstream in the input readers.
         * </p>
         * @throws UserException.BadInput if the target cannot be found in any of the inputs.
         * @param target the target to find.
         */
        private void seekTarget(final Target target) {
            lastReadCounts.clear();
            for (final TableReader<ReadCount> reader : readers) {
                boolean targetFound = false;
                for (final ReadCount counts : reader) {
                    if (counts.target.equals(target)) {
                        lastReadCounts.add(counts);
                        targetFound = true;
                        break;
                    }
                }
                if (!targetFound) {
                    throw new UserException.BadInput(
                            String.format("could not find target %s (or is out of order, after %s) in file %s",
                                    target, lastTarget, reader.getSource()));
                }
            }
            lastTarget = target;
        }

        @Override
        public void close() {
            for (final TableReader<ReadCount> reader : readers) {
                try {
                    reader.close();
                } catch (final IOException ex) {
                    throw new GATKException(String.format("problems closing a read-count reader for %s", reader.getSource()), ex);
                }
            }
        }

        /**
         * Returns the counts for a given target.
         * <p>
         * It returns an array with as many elements as count columns in {@link #countColumnNames}.
         * The ith corresponds to the ith column in that list.
         * </p>
         * @param target the query target.
         * @return never {@code null}.
         * @throws UserException.BadInput if the requested target could not be found in the remaining
         * of the input readers.
         */
        public double[] countsFor(final Target target) {
            if (!target.equals(lastTarget)) {
                seekTarget(target);
            }
            return composeLastTargetCounts();
        }

        /**
         * Compose the count array from the last read target.
         *
         * @return never {@code null}.
         */
        private double[] composeLastTargetCounts() {
            final double[] result = new double[countColumnNames.size()];
            int nextIndex = 0;
            for (final ReadCount readCount : lastReadCounts) {
                final int readCountLength = readCount.counts.length;
                System.arraycopy(readCount.counts, 0, countsBuffer, nextIndex, readCountLength);
                nextIndex += readCountLength;
            }
            for (int i = 0; i < result.length; i++) {
                result[i] = countsBuffer[countColumnSourceIndexMap[i]];
            }
            return result;
        }
    }
}
