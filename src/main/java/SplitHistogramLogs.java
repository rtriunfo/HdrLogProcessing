import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;
import org.HdrHistogram.HistogramLogWriter;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class SplitHistogramLogs {
    @Option(name = "-start", aliases = "-s", usage = "relative log start time in seconds, (default: 0.0)", required = false)
    public double start = 0.0;

    @Option(name = "-end", aliases = "-e", usage = "relative log end time in seconds, (default: MAX_DOUBLE)", required = false)
    public double end = Double.MAX_VALUE;

    @Option(name = "-verbose", aliases = "-v", usage = "verbose logging, (default: false)", required = false)
    public boolean verbose = false;

    @Option(name = "-inputPath", aliases = "-ip", usage = "set path to use for input files, defaults to current folder", required = false)
    public void setInputPath(String inputFolderName) {
        inputPath = new File(inputFolderName);
        if (!inputPath.exists())
            throw new IllegalArgumentException("inputPath:" + inputFolderName + " must exist!");
        if (!inputPath.isDirectory())
            throw new IllegalArgumentException("inputPath:" + inputFolderName + " must be a directory!");
    }

    @Option(name = "-inputFile", aliases = "-if", usage = "set the input hdr log from input path, also takes regexp", required = true)
    public void setInputFile(String inputFileName) {
        inputFile = new File(inputPath, inputFileName);
        if (!inputFile.exists())
            throw new IllegalArgumentException("inputFile:" + inputFile.getAbsolutePath() + " must exist!");
    }

    @Option(name = "-filterTag", aliases = "-ft", usage = "add a tag to filter from input, 'default' is a special tag for the null tag.", required = false)
    public void addFilterTag(String tag) {
        filterTags.add(tag);
    }

    private File inputPath = new File(".");
    private File inputFile;
    private Set<String> filterTags = new HashSet<>();

    public static void main(String[] args) throws Exception {
        SplitHistogramLogs app = new SplitHistogramLogs();
        CmdLineParser parser = new CmdLineParser(app);
        try {
            parser.parseArgument(args);
            app.execute();
        } catch (CmdLineException e) {
            System.out.println(e.getMessage());
            parser.printUsage(System.out);
        }
    }

    private void execute() throws FileNotFoundException {
        if (verbose) {
            String absolutePath = inputPath.getAbsolutePath();
            String name = inputFile.getName();
            if (end != Double.MAX_VALUE)
                System.out.printf("start:%.2f end:%.2f path:%s file:%s \n", start, end, absolutePath, name);
            else
                System.out.printf("start:%.2f end: MAX path:%s file:%s \n", start, absolutePath, name);
        }
        split();
    }

    private void split() throws FileNotFoundException {
        HistogramLogReader reader = new HistogramLogReader(inputFile);
        Map<String, HistogramLogWriter> writerByTag = new HashMap<>();
        Histogram interval;
        int i = 0;
        while ((interval = (Histogram) reader.nextIntervalHistogram(start, end)) != null) {
            String ntag = interval.getTag();
            if ((ntag == null && filterTags.contains("default")) || filterTags.contains(ntag)) {
                if (verbose) {
                    String tag = (ntag == null) ? "(skipped:default) " : "(skipped:"+ntag+") ";
                    logHistogramForVerbose(interval, i, tag);
                    i++;
                }
                continue;
            }
            interval.setTag(null);
            HistogramLogWriter writer = writerByTag.computeIfAbsent(ntag, k -> createWriterForTag(reader, k));
            writer.outputIntervalHistogram(interval);
            if (verbose) {
                String tag = (ntag == null) ? "(default) " : "("+ntag+") ";
                logHistogramForVerbose(interval, i, tag);
                i++;
            }

        }
    }

    private void logHistogramForVerbose(Histogram interval, int i, String ntag) {
        System.out.printf("%s%d: [count=%d,min=%d,max=%d,avg=%.2f,50=%d,99=%d,999=%d]\n",
                ntag, i,
                interval.getTotalCount(),
                (long) (interval.getMinValue()),
                (long) (interval.getMaxValue()),
                interval.getMean(),
                (long) (interval.getValueAtPercentile(50)),
                (long) (interval.getValueAtPercentile(99)),
                (long) (interval.getValueAtPercentile(99.9)));
    }

    private HistogramLogWriter createWriterForTag(HistogramLogReader reader, String tag) {
        tag = (tag == null) ? "default" : tag;
        HistogramLogWriter writer = null;
        try {
            writer = new HistogramLogWriter(new File(tag+"."+inputFile.getName()));
        } catch (FileNotFoundException e) {
            new RuntimeException("Unable to open output file:", e);
        }
        writer.outputLogFormatVersion();
        writer.outputComment("Splitting of:" + inputFile.getName() + " start:" + start + " end:" + end);
        long startTimeStamp = (long) (reader.getStartTimeSec() * 1000);
        writer.setBaseTime(startTimeStamp);
        writer.outputBaseTime(startTimeStamp);
        writer.outputStartTime(startTimeStamp);
        writer.outputLegend();
        return writer;
    }
}
