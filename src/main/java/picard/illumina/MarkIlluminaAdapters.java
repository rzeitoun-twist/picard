/*
 * The MIT License
 *
 * Copyright (c) 2009-2016 The Broad Institute
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

package picard.illumina;

import htsjdk.samtools.ReservedTagConstants;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.CollectionUtil;
import htsjdk.samtools.util.Histogram;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import picard.PicardException;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.BaseCallingProgramGroup;
import picard.util.AdapterMarker;
import picard.util.AdapterPair;
import picard.util.ClippingUtility;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static picard.util.IlluminaUtil.IlluminaAdapterPair;

/**
 * Command line program to mark the location of adapter sequences.
 * This also outputs a Histogram of metrics describing the clipped bases
 *
 * @author Tim Fennell (adapted by mborkan@broadinstitute.org)
 */
@CommandLineProgramProperties(

        summary = MarkIlluminaAdapters.USAGE_SUMMARY + MarkIlluminaAdapters.USAGE_DETAILS,
        oneLineSummary = MarkIlluminaAdapters.USAGE_SUMMARY,
        programGroup = BaseCallingProgramGroup.class
)
@DocumentedFeature
public class MarkIlluminaAdapters extends CommandLineProgram {

    static final String USAGE_SUMMARY = "Reads a SAM/BAM/CRAM file and rewrites it with new adapter-trimming tags.  ";
    static final String USAGE_DETAILS = "<p>This tool clears any existing adapter-trimming tags (XT:i:) in the optional tag region of " +
            "the input file.  The SAM/BAM/CRAM file must be sorted by query name.</p> "+
            "<p>Outputs a metrics file histogram showing counts of bases_clipped per read." +
            "" +
    "<h4>Usage example:</h4>" +
            "<pre>" +
            "java -jar picard.jar MarkIlluminaAdapters \\<br /> " +
            "INPUT=input.sam \\<br />" +
            "METRICS=metrics.txt " +
            "</pre>" +
            "<hr />"
            ;
    // The following attributes define the command-line arguments

    @Argument(shortName = StandardOptionDefinitions.INPUT_SHORT_NAME)
    public File INPUT;

    @Argument(doc = "If output is not specified, just the metrics are generated",
            shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME, optional = true)
    public File OUTPUT;

    @Argument(doc = "Histogram showing counts of bases_clipped in how many reads", shortName = "M")
    public File METRICS;

    @Argument(doc = "The minimum number of bases to match over when clipping single-end reads.")
    public int MIN_MATCH_BASES_SE = ClippingUtility.MIN_MATCH_BASES;

    @Argument(doc = "The minimum number of bases to match over (per-read) when clipping paired-end reads.")
    public int MIN_MATCH_BASES_PE = ClippingUtility.MIN_MATCH_PE_BASES;

    @Argument(doc = "The maximum mismatch error rate to tolerate when clipping single-end reads.")
    public double MAX_ERROR_RATE_SE = ClippingUtility.MAX_ERROR_RATE;

    @Argument(doc = "The maximum mismatch error rate to tolerate when clipping paired-end reads.")
    public double MAX_ERROR_RATE_PE = ClippingUtility.MAX_PE_ERROR_RATE;

    @Argument(doc = "DEPRECATED. Whether this is a paired-end run. No longer used.", shortName = "PE", optional = true)
    public Boolean PAIRED_RUN;

    @Argument(doc = "Which adapters sequences to attempt to identify and clip.")
    public List<IlluminaAdapterPair> ADAPTERS =
            CollectionUtil.makeList(IlluminaAdapterPair.INDEXED,
                    IlluminaAdapterPair.DUAL_INDEXED,
                    IlluminaAdapterPair.PAIRED_END
            );

    @Argument(doc = "For specifying adapters other than standard Illumina", optional = true)
    public String FIVE_PRIME_ADAPTER;
    @Argument(doc = "For specifying adapters other than standard Illumina", optional = true)
    public String THREE_PRIME_ADAPTER;

    @Argument(doc = "Adapters are truncated to this length to speed adapter matching.  Set to a large number to effectively disable truncation.")
    public int ADAPTER_TRUNCATION_LENGTH = AdapterMarker.DEFAULT_ADAPTER_LENGTH;

    @Argument(doc = "If looking for multiple adapter sequences, then after having seen this many adapters, shorten the list of sequences. " +
            "Keep the adapters that were found most frequently in the input so far. " +
            "Set to -1 if the input has a heterogeneous mix of adapters so shortening is undesirable.",
            shortName = "APT")
    public int PRUNE_ADAPTER_LIST_AFTER_THIS_MANY_ADAPTERS_SEEN = AdapterMarker.DEFAULT_PRUNE_ADAPTER_LIST_AFTER_THIS_MANY_ADAPTERS_SEEN;

    @Argument(doc = "If pruning the adapter list, keep only this many adapter sequences when pruning the list (plus any adapters that " +
            "were tied with the adapters being kept).")
    public int NUM_ADAPTERS_TO_KEEP = AdapterMarker.DEFAULT_NUM_ADAPTERS_TO_KEEP;

    private static final Log log = Log.getInstance(MarkIlluminaAdapters.class);

    @Override
    protected String[] customCommandLineValidation() {
        if ((FIVE_PRIME_ADAPTER != null && THREE_PRIME_ADAPTER == null) || (THREE_PRIME_ADAPTER != null && FIVE_PRIME_ADAPTER == null)) {
            return new String[]{"THREE_PRIME_ADAPTER and FIVE_PRIME_ADAPTER must either both be null or both be set."};
        } else {
            return null;
        }
    }

    @Override
    protected int doWork() {
        IOUtil.assertFileIsReadable(INPUT);
        IOUtil.assertFileIsWritable(METRICS);

        final SamReader in = SamReaderFactory.makeDefault().referenceSequence(REFERENCE_SEQUENCE).open(INPUT);
        final SAMFileHeader.SortOrder order = in.getFileHeader().getSortOrder();
        SAMFileWriter out = null;
        if (OUTPUT != null) {
            IOUtil.assertFileIsWritable(OUTPUT);
            out = new SAMFileWriterFactory().makeWriter(in.getFileHeader(), true, OUTPUT, REFERENCE_SEQUENCE);
        }

        final Histogram<Integer> histo = new Histogram<Integer>("clipped_bases", "read_count");

        // Combine any adapters and custom adapter pairs from the command line into an array for use in clipping
        final AdapterPair[] adapters;
        {
            final List<AdapterPair> tmp = new ArrayList<AdapterPair>();
            tmp.addAll(ADAPTERS);
            if (FIVE_PRIME_ADAPTER != null && THREE_PRIME_ADAPTER != null) {
                tmp.add(new CustomAdapterPair(FIVE_PRIME_ADAPTER, THREE_PRIME_ADAPTER));
            }
            adapters = tmp.toArray(new AdapterPair[tmp.size()]);
        }

        ////////////////////////////////////////////////////////////////////////
        // Main loop that consumes reads, clips them and writes them to the output
        ////////////////////////////////////////////////////////////////////////
        final ProgressLogger progress = new ProgressLogger(log, 1000000, "Read");
        final SAMRecordIterator iterator = in.iterator();

        final AdapterMarker adapterMarker = new AdapterMarker(ADAPTER_TRUNCATION_LENGTH, adapters).
                setMaxPairErrorRate(MAX_ERROR_RATE_PE).setMinPairMatchBases(MIN_MATCH_BASES_PE).
                setMaxSingleEndErrorRate(MAX_ERROR_RATE_SE).setMinSingleEndMatchBases(MIN_MATCH_BASES_SE).
                setNumAdaptersToKeep(NUM_ADAPTERS_TO_KEEP).
                setThresholdForSelectingAdaptersToKeep(PRUNE_ADAPTER_LIST_AFTER_THIS_MANY_ADAPTERS_SEEN);

        while (iterator.hasNext()) {
            final SAMRecord rec = iterator.next();
            final SAMRecord rec2 = rec.getReadPairedFlag() && iterator.hasNext() ? iterator.next() : null;
            rec.setAttribute(ReservedTagConstants.XT, null);

            // Do the clipping one way for PE and another for SE reads
            if (rec.getReadPairedFlag()) {
                // Assert that the input file is in query name order only if we see some PE reads
                if (order != SAMFileHeader.SortOrder.queryname) {
                    throw new PicardException("Input file must be sorted by queryname");
                }

                if (rec2 == null) throw new PicardException("Missing mate pair for paired read: " + rec.getReadName());
                rec2.setAttribute(ReservedTagConstants.XT, null);

                // Assert that we did in fact just get two mate pairs
                if (!rec.getReadName().equals(rec2.getReadName())) {
                    throw new PicardException("Adjacent reads expected to be mate-pairs have different names: " +
                            rec.getReadName() + ", " + rec2.getReadName());
                }

                // establish which of pair is first and which second
                final SAMRecord first, second;

                if (rec.getFirstOfPairFlag() && rec2.getSecondOfPairFlag()) {
                    first = rec;
                    second = rec2;
                } else if (rec.getSecondOfPairFlag() && rec2.getFirstOfPairFlag()) {
                    first = rec2;
                    second = rec;
                } else {
                    throw new PicardException("Two reads with same name but not correctly marked as 1st/2nd of pair: " + rec.getReadName());
                }

                adapterMarker.adapterTrimIlluminaPairedReads(first, second);
            } else {
                adapterMarker.adapterTrimIlluminaSingleRead(rec);
            }

            // Then output the records, update progress and metrics
            for (final SAMRecord r : new SAMRecord[]{rec, rec2}) {
                if (r != null) {
                    progress.record(r);
                    if (out != null) out.addAlignment(r);

                    final Integer clip = r.getIntegerAttribute(ReservedTagConstants.XT);
                    if (clip != null) histo.increment(r.getReadLength() - clip + 1);
                }
            }
        }

        if (out != null) out.close();

        // Lastly output the metrics to file
        final MetricsFile<?, Integer> metricsFile = getMetricsFile();
        metricsFile.setHistogram(histo);
        metricsFile.write(METRICS);

        CloserUtil.close(in);
        return 0;
    }
}
