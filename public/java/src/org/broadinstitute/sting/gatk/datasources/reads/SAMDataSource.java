/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.sting.gatk.datasources.reads;

import net.sf.picard.sam.MergingSamRecordIterator;
import net.sf.picard.sam.SamFileHeaderMerger;
import net.sf.samtools.*;
import net.sf.samtools.util.CloseableIterator;
import net.sf.samtools.util.RuntimeIOException;
import org.apache.log4j.Logger;
import org.broadinstitute.sting.gatk.ReadMetrics;
import org.broadinstitute.sting.gatk.ReadProperties;
import org.broadinstitute.sting.gatk.arguments.ValidationExclusion;
import org.broadinstitute.sting.gatk.downsampling.*;
import org.broadinstitute.sting.gatk.filters.CountingFilteringIterator;
import org.broadinstitute.sting.gatk.filters.ReadFilter;
import org.broadinstitute.sting.gatk.iterators.*;
import org.broadinstitute.sting.gatk.resourcemanagement.ThreadAllocation;
import org.broadinstitute.sting.utils.GenomeLocParser;
import org.broadinstitute.sting.utils.GenomeLocSortedSet;
import org.broadinstitute.sting.utils.SimpleTimer;
import org.broadinstitute.sting.utils.baq.ReadTransformingIterator;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.sam.GATKSAMReadGroupRecord;
import org.broadinstitute.sting.utils.sam.GATKSamRecordFactory;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * User: aaron
 * Date: Mar 26, 2009
 * Time: 2:36:16 PM
 * <p/>
 * Converts shards to SAM iterators over the specified region
 */
public class SAMDataSource {
    final private static GATKSamRecordFactory factory = new GATKSamRecordFactory();

    /** Backing support for reads. */
    protected final ReadProperties readProperties;

    /**
     * Runtime metrics of reads filtered, etc.
     */
    private final ReadMetrics readMetrics;

    /**
     * Tools for parsing GenomeLocs, for verifying BAM ordering against general ordering.
     */
    protected final GenomeLocParser genomeLocParser;

    /**
     * Identifiers for the readers driving this data source.
     */
    private final Collection<SAMReaderID> readerIDs;

    /**
     * How strict are the readers driving this data source.
     */
    private final SAMFileReader.ValidationStringency validationStringency;

    /**
     * Do we want to remove the program records from this data source?
     */
    private final boolean removeProgramRecords;

    /**
     * Store BAM indices for each reader present.
     */
    private final Map<SAMReaderID,GATKBAMIndex> bamIndices = new HashMap<SAMReaderID,GATKBAMIndex>();

    /**
     * The merged header.
     */
    private final SAMFileHeader mergedHeader;

    /**
     * The constituent headers of the unmerged files.
     */
    private final Map<SAMReaderID,SAMFileHeader> headers = new HashMap<SAMReaderID,SAMFileHeader>();

    /**
     * The sort order of the BAM files.  Files without a sort order tag are assumed to be
     * in coordinate order.
     */
    private SAMFileHeader.SortOrder sortOrder = null;

    /**
     * Whether the read groups in overlapping files collide.
     */
    private final boolean hasReadGroupCollisions;

    /**
     * Maps the SAM readers' merged read group ids to their original ids. Since merged read group ids
     * are always unique, we can simply use a map here, no need to stratify by reader.
     */
    private final ReadGroupMapping mergedToOriginalReadGroupMappings = new ReadGroupMapping();

    /**
     * Maps the SAM readers' original read group ids to their revised ids. This mapping must be stratified
     * by readers, since there can be readgroup id collision: different bam files (readers) can list the
     * same read group id, which will be disambiguated when these input streams are merged.
     */
    private final Map<SAMReaderID,ReadGroupMapping> originalToMergedReadGroupMappings = new HashMap<SAMReaderID,ReadGroupMapping>();

    /** our log, which we want to capture anything from this class */
    private static Logger logger = Logger.getLogger(SAMDataSource.class);

    /**
     * A collection of readers driving the merging process.
     */
    private final SAMResourcePool resourcePool;

    /**
     * Asynchronously loads BGZF blocks.
     */
    private final BGZFBlockLoadingDispatcher dispatcher;

    /**
     * How are threads allocated.
     */
    private final ThreadAllocation threadAllocation;

    /**
     * Create a new SAM data source given the supplied read metadata.
     *
     * For testing purposes
     *
     * @param samFiles list of reads files.
     */
    public SAMDataSource(Collection<SAMReaderID> samFiles, ThreadAllocation threadAllocation, Integer numFileHandles, GenomeLocParser genomeLocParser) {
        this(
                samFiles,
                threadAllocation,
                numFileHandles,
                genomeLocParser,
                false,
                SAMFileReader.ValidationStringency.STRICT,
                null,
                null,
                new ValidationExclusion(),
                new ArrayList<ReadFilter>(),
                false);
    }

    /**
     * See complete constructor.  Does not enable BAQ by default.
     *
     * For testing purposes
     */
    public SAMDataSource(
            Collection<SAMReaderID> samFiles,
            ThreadAllocation threadAllocation,
            Integer numFileHandles,
            GenomeLocParser genomeLocParser,
            boolean useOriginalBaseQualities,
            SAMFileReader.ValidationStringency strictness,
            Integer readBufferSize,
            DownsamplingMethod downsamplingMethod,
            ValidationExclusion exclusionList,
            Collection<ReadFilter> supplementalFilters,
            boolean includeReadsWithDeletionAtLoci) {
        this(   samFiles,
                threadAllocation,
                numFileHandles,
                genomeLocParser,
                useOriginalBaseQualities,
                strictness,
                readBufferSize,
                downsamplingMethod,
                exclusionList,
                supplementalFilters,
                Collections.<ReadTransformer>emptyList(),
                includeReadsWithDeletionAtLoci,
                (byte) -1,
                false,
                false);
    }

    /**
     * Create a new SAM data source given the supplied read metadata.
     * @param samFiles list of reads files.
     * @param useOriginalBaseQualities True if original base qualities should be used.
     * @param strictness Stringency of reads file parsing.
     * @param readBufferSize Number of reads to hold in memory per BAM.
     * @param downsamplingMethod Method for downsampling reads at a given locus.
     * @param exclusionList what safety checks we're willing to let slide
     * @param supplementalFilters additional filters to dynamically apply.
     * @param includeReadsWithDeletionAtLoci if 'true', the base pileups sent to the walker's map() method
     *         will explicitly list reads with deletion over the current reference base; otherwise, only observed
     *        bases will be seen in the pileups, and the deletions will be skipped silently.
     * @param defaultBaseQualities if the reads have incomplete quality scores, set them all to defaultBaseQuality.
     * @param keepReadsInLIBS should we keep a unique list of reads in LIBS?
     */
    public SAMDataSource(
            Collection<SAMReaderID> samFiles,
            ThreadAllocation threadAllocation,
            Integer numFileHandles,
            GenomeLocParser genomeLocParser,
            boolean useOriginalBaseQualities,
            SAMFileReader.ValidationStringency strictness,
            Integer readBufferSize,
            DownsamplingMethod downsamplingMethod,
            ValidationExclusion exclusionList,
            Collection<ReadFilter> supplementalFilters,
            List<ReadTransformer> readTransformers,
            boolean includeReadsWithDeletionAtLoci,
            byte defaultBaseQualities,
            boolean removeProgramRecords,
            final boolean keepReadsInLIBS) {
        this.readMetrics = new ReadMetrics();
        this.genomeLocParser = genomeLocParser;

        readerIDs = samFiles;

        this.threadAllocation = threadAllocation;
        // TODO: Consider a borrowed-thread dispatcher implementation.
        if(this.threadAllocation.getNumIOThreads() > 0) {
            logger.info("Running in asynchronous I/O mode; number of threads = " + this.threadAllocation.getNumIOThreads());
            dispatcher = new BGZFBlockLoadingDispatcher(this.threadAllocation.getNumIOThreads(), numFileHandles != null ? numFileHandles : 1);
        }
        else
            dispatcher = null;

        validationStringency = strictness;
        this.removeProgramRecords = removeProgramRecords;
        if(readBufferSize != null)
            ReadShard.setReadBufferSize(readBufferSize);   // TODO: use of non-final static variable here is just awful, especially for parallel tests
        else {
            // Choose a sensible default for the read buffer size.
            // Previously we we're picked 100000 reads per BAM per shard with a max cap of 250K reads in memory at once.
            // Now we are simply setting it to 100K reads
            ReadShard.setReadBufferSize(100000);
        }

        resourcePool = new SAMResourcePool(Integer.MAX_VALUE);
        SAMReaders readers = resourcePool.getAvailableReaders();

        // Determine the sort order.
        for(SAMReaderID readerID: readerIDs) {
            if (! readerID.samFile.canRead() )
                throw new UserException.CouldNotReadInputFile(readerID.samFile,"file is not present or user does not have appropriate permissions.  " +
                        "Please check that the file is present and readable and try again.");

            // Get the sort order, forcing it to coordinate if unsorted.
            SAMFileReader reader = readers.getReader(readerID);
            SAMFileHeader header = reader.getFileHeader();

            headers.put(readerID,header);

            if ( header.getReadGroups().isEmpty() ) {
                throw new UserException.MalformedBAM(readers.getReaderID(reader).samFile,
                        "SAM file doesn't have any read groups defined in the header.  The GATK no longer supports SAM files without read groups");
            }

            SAMFileHeader.SortOrder sortOrder = header.getSortOrder() != SAMFileHeader.SortOrder.unsorted ? header.getSortOrder() : SAMFileHeader.SortOrder.coordinate;

            // Validate that all input files are sorted in the same order.
            if(this.sortOrder != null && this.sortOrder != sortOrder)
                throw new UserException.MissortedBAM(String.format("Attempted to process mixed of files sorted as %s and %s.",this.sortOrder,sortOrder));

            // Update the sort order.
            this.sortOrder = sortOrder;
        }

        mergedHeader = readers.getMergedHeader();
        hasReadGroupCollisions = readers.hasReadGroupCollisions();

        readProperties = new ReadProperties(
                samFiles,
                mergedHeader,
                sortOrder,
                useOriginalBaseQualities,
                strictness,
                downsamplingMethod,
                exclusionList,
                supplementalFilters,
                readTransformers,
                includeReadsWithDeletionAtLoci,
                defaultBaseQualities,
                keepReadsInLIBS);

        // cache the read group id (original) -> read group id (merged)
        // and read group id (merged) -> read group id (original) mappings.
        for(SAMReaderID id: readerIDs) {
            SAMFileReader reader = readers.getReader(id);
            ReadGroupMapping mappingToMerged = new ReadGroupMapping();

            List<SAMReadGroupRecord> readGroups = reader.getFileHeader().getReadGroups();
            for(SAMReadGroupRecord readGroup: readGroups) {
                if(hasReadGroupCollisions) {
                    mappingToMerged.put(readGroup.getReadGroupId(),readers.getReadGroupId(id,readGroup.getReadGroupId()));
                    mergedToOriginalReadGroupMappings.put(readers.getReadGroupId(id,readGroup.getReadGroupId()),readGroup.getReadGroupId());
                } else {
                    mappingToMerged.put(readGroup.getReadGroupId(),readGroup.getReadGroupId());
                    mergedToOriginalReadGroupMappings.put(readGroup.getReadGroupId(),readGroup.getReadGroupId());
                }
            }

            originalToMergedReadGroupMappings.put(id,mappingToMerged);
        }

        for(SAMReaderID id: readerIDs) {
            File indexFile = findIndexFile(id.samFile);
            if(indexFile != null)
                bamIndices.put(id,new GATKBAMIndex(indexFile));
        }

        resourcePool.releaseReaders(readers);
    }

    /**
     * Returns Reads data structure containing information about the reads data sources placed in this pool as well as
     * information about how they are downsampled, sorted, and filtered
     * @return
     */
    public ReadProperties getReadsInfo() { return readProperties; }

    /**
     * Checks to see whether any reads files are supplying data.
     * @return True if no reads files are supplying data to the traversal; false otherwise.
     */
    public boolean isEmpty() {
        return readProperties.getSAMReaderIDs().size() == 0;
    }

    /**
     * Gets the SAM file associated with a given reader ID.
     * @param id The reader for which to retrieve the source file.
     * @return the file actually associated with the id.
     */
    public File getSAMFile(SAMReaderID id) {
        return id.samFile;
    }

    /**
     * Returns readers used by this data source.
     * @return A list of SAM reader IDs.
     */
    public Collection<SAMReaderID> getReaderIDs() {
        return readerIDs;
    }

    /**
     * Retrieves the id of the reader which built the given read.
     * @param read The read to test.
     * @return ID of the reader.
     */
    public SAMReaderID getReaderID(SAMRecord read) {
        return resourcePool.getReaderID(read.getFileSource().getReader());
    }

    /**
     * Gets the merged header from the SAM file.
     * @return The merged header.
     */
    public SAMFileHeader getHeader() {
        return mergedHeader;
    }

    public SAMFileHeader getHeader(SAMReaderID id) {
        return headers.get(id);
    }

    /**
     * Gets the revised read group id mapped to this 'original' read group id.
     * @param reader for which to grab a read group.
     * @param originalReadGroupId ID of the original read group.
     * @return Merged read group ID.
     */
    public String getReadGroupId(final SAMReaderID reader, final String originalReadGroupId) {
        return originalToMergedReadGroupMappings.get(reader).get(originalReadGroupId);
    }

    /**
     * Gets the original read group id (as it was specified in the original input bam file) that maps onto
     * this 'merged' read group id.
     * @param mergedReadGroupId 'merged' ID of the read group (as it is presented by the read received from merged input stream).
     * @return Merged read group ID.
     */
    public String getOriginalReadGroupId(final String mergedReadGroupId) {
        return mergedToOriginalReadGroupMappings.get(mergedReadGroupId);
    }

    /**
     * True if all readers have an index.
     * @return True if all readers have an index.
     */
    public boolean hasIndex() {
        return readerIDs.size() == bamIndices.size();
    }

    /**
     * Gets the index for a particular reader.  Always preloaded.
     * @param id Id of the reader.
     * @return The index.  Will preload the index if necessary.
     */
    public GATKBAMIndex getIndex(final SAMReaderID id) {
        return bamIndices.get(id);
    }

    /**
     * Retrieves the sort order of the readers.
     * @return Sort order.  Can be unsorted, coordinate order, or query name order.
     */
    public SAMFileHeader.SortOrder getSortOrder() {
        return sortOrder;
    }

    /**
     * Gets the cumulative read metrics for shards already processed.
     * @return Cumulative read metrics.
     */
    public ReadMetrics getCumulativeReadMetrics() {
        // don't return a clone here because the engine uses a pointer to this object
        return readMetrics;
    }

    /**
     * Incorporate the given read metrics into the cumulative read metrics.
     * @param readMetrics The 'incremental' read metrics, to be incorporated into the cumulative metrics.
     */
    public void incorporateReadMetrics(final ReadMetrics readMetrics) {
        this.readMetrics.incrementMetrics(readMetrics);
    }

    public StingSAMIterator seek(Shard shard) {
        if(shard.buffersReads()) {
            return shard.iterator();
        }
        else {
            return getIterator(shard);
        }
    }

    /**
     * Gets the reader associated with the given read.
     * @param readers Available readers.
     * @param read
     * @return
     */
    private SAMReaderID getReaderID(SAMReaders readers, SAMRecord read) {
        for(SAMReaderID id: getReaderIDs()) {
            if(readers.getReader(id) == read.getFileSource().getReader())
                return id;
        }
        throw new ReviewedStingException("Unable to find id for reader associated with read " + read.getReadName());
    }

    /**
     * Get the initial reader positions across all BAM files
     *
     * @return the start positions of the first chunk of reads for all BAM files
     */
    protected Map<SAMReaderID, GATKBAMFileSpan> getInitialReaderPositions() {
        Map<SAMReaderID, GATKBAMFileSpan> initialPositions = new HashMap<SAMReaderID, GATKBAMFileSpan>();
        SAMReaders readers = resourcePool.getAvailableReaders();

        for ( SAMReaderID id: getReaderIDs() ) {
            initialPositions.put(id, new GATKBAMFileSpan(readers.getReader(id).getFilePointerSpanningReads()));
        }

        resourcePool.releaseReaders(readers);
        return initialPositions;
    }

    /**
     * Get an iterator over the data types specified in the shard.
     *
     * @param shard The shard specifying the data limits.
     * @return An iterator over the selected data.
     */
    protected StingSAMIterator getIterator( Shard shard ) {
        return getIterator(resourcePool.getAvailableReaders(), shard, shard instanceof ReadShard);
    }

    /**
     * Get an iterator over the data types specified in the shard.
     * @param readers Readers from which to load data.
     * @param shard The shard specifying the data limits.
     * @param enableVerification True to verify.  For compatibility with old sharding strategy.
     * @return An iterator over the selected data.
     */
    private StingSAMIterator getIterator(SAMReaders readers, Shard shard, boolean enableVerification) {
        // Set up merging to dynamically merge together multiple BAMs.
        Map<SAMFileReader,CloseableIterator<SAMRecord>> iteratorMap = new HashMap<SAMFileReader,CloseableIterator<SAMRecord>>();

        for(SAMReaderID id: getReaderIDs()) {
            CloseableIterator<SAMRecord> iterator = null;

            // TODO: null used to be the signal for unmapped, but we've replaced that with a simple index query for the last bin.
            // TODO: Kill this check once we've proven that the design elements are gone.
            if(shard.getFileSpans().get(id) == null)
                throw new ReviewedStingException("SAMDataSource: received null location for reader " + id + ", but null locations are no longer supported.");

            try {
                if(threadAllocation.getNumIOThreads() > 0) {
                    BlockInputStream inputStream = readers.getInputStream(id);
                    inputStream.submitAccessPlan(new BAMAccessPlan(id, inputStream, (GATKBAMFileSpan) shard.getFileSpans().get(id)));
                    BAMRecordCodec codec = new BAMRecordCodec(getHeader(id),factory);
                    codec.setInputStream(inputStream);
                    iterator = new BAMCodecIterator(inputStream,readers.getReader(id),codec);
                }
                else {
                    iterator = readers.getReader(id).iterator(shard.getFileSpans().get(id));
                }
            } catch ( RuntimeException e ) { // we need to catch RuntimeExceptions here because the Picard code is throwing them (among SAMFormatExceptions) sometimes
                throw new UserException.MalformedBAM(id.samFile, e.getMessage());
            }

            iterator = new MalformedBAMErrorReformatingIterator(id.samFile, iterator);
            if(shard.getGenomeLocs().size() > 0)
                iterator = new IntervalOverlapFilteringIterator(iterator,shard.getGenomeLocs());

            iteratorMap.put(readers.getReader(id), iterator);
        }

        MergingSamRecordIterator mergingIterator = readers.createMergingIterator(iteratorMap);

        // The readMetrics object being passed in should be that of this dataSource and NOT the shard: the dataSource's
        // metrics is intended to keep track of the reads seen (and hence passed to the CountingFilteringIterator when
        // we apply the decorators), whereas the shard's metrics is used to keep track the "records" seen.
        return applyDecoratingIterators(readMetrics,
                enableVerification,
                readProperties.useOriginalBaseQualities(),
                new ReleasingIterator(readers,StingSAMIteratorAdapter.adapt(mergingIterator)),
                readProperties.getValidationExclusionList().contains(ValidationExclusion.TYPE.NO_READ_ORDER_VERIFICATION),
                readProperties.getSupplementalFilters(),
                readProperties.getReadTransformers(),
                readProperties.defaultBaseQualities(),
                shard instanceof LocusShard);
    }

    private class BAMCodecIterator implements CloseableIterator<SAMRecord> {
        private final BlockInputStream inputStream;
        private final SAMFileReader reader;
        private final BAMRecordCodec codec;
        private SAMRecord nextRead;

        private BAMCodecIterator(final BlockInputStream inputStream, final SAMFileReader reader, final BAMRecordCodec codec) {
            this.inputStream = inputStream;
            this.reader = reader;
            this.codec = codec;
            advance();
        }

        public boolean hasNext() {
            return nextRead != null;
        }

        public SAMRecord next() {
            if(!hasNext())
                throw new NoSuchElementException("Unable to retrieve next record from BAMCodecIterator; input stream is empty");
            SAMRecord currentRead = nextRead;
            advance();
            return currentRead;
        }

        public void close() {
            // NO-OP.
        }

        public void remove() {
            throw new UnsupportedOperationException("Unable to remove from BAMCodecIterator");
        }

        private void advance() {
            final long startCoordinate = inputStream.getFilePointer();
            nextRead = codec.decode();
            final long stopCoordinate = inputStream.getFilePointer();

            if(reader != null && nextRead != null)
                PicardNamespaceUtils.setFileSource(nextRead,new SAMFileSource(reader,new GATKBAMFileSpan(new GATKChunk(startCoordinate,stopCoordinate))));
        }
    }

    /**
     * Filter reads based on user-specified criteria.
     *
     * @param readMetrics metrics to track when using this iterator.
     * @param enableVerification Verify the order of reads.
     * @param useOriginalBaseQualities True if original base qualities should be used.
     * @param wrappedIterator the raw data source.
     * @param noValidationOfReadOrder Another trigger for the verifying iterator?  TODO: look into this.
     * @param supplementalFilters additional filters to apply to the reads.
     * @param defaultBaseQualities if the reads have incomplete quality scores, set them all to defaultBaseQuality.
     * @param isLocusBasedTraversal true if we're dealing with a read stream from a LocusShard
     * @return An iterator wrapped with filters reflecting the passed-in parameters.  Will not be null.
     */
    protected StingSAMIterator applyDecoratingIterators(ReadMetrics readMetrics,
                                                        boolean enableVerification,
                                                        boolean useOriginalBaseQualities,
                                                        StingSAMIterator wrappedIterator,
                                                        Boolean noValidationOfReadOrder,
                                                        Collection<ReadFilter> supplementalFilters,
                                                        List<ReadTransformer> readTransformers,
                                                        byte defaultBaseQualities,
                                                        boolean isLocusBasedTraversal ) {

        // Always apply the ReadFormattingIterator before both ReadFilters and ReadTransformers. At a minimum,
        // this will consolidate the cigar strings into canonical form. This has to be done before the read
        // filtering, because not all read filters will behave correctly with things like zero-length cigar
        // elements. If useOriginalBaseQualities is true or defaultBaseQualities >= 0, this iterator will also
        // modify the base qualities.
        wrappedIterator = new ReadFormattingIterator(wrappedIterator, useOriginalBaseQualities, defaultBaseQualities);

        // Read Filters: these are applied BEFORE downsampling, so that we downsample within the set of reads
        // that actually survive filtering. Otherwise we could get much less coverage than requested.
        wrappedIterator = StingSAMIteratorAdapter.adapt(new CountingFilteringIterator(readMetrics,wrappedIterator,supplementalFilters));

        // Downsampling:

        // For locus traversals where we're downsampling to coverage by sample, assume that the downsamplers
        // will be invoked downstream from us in LocusIteratorByState. This improves performance by avoiding
        // splitting/re-assembly of the read stream at this stage, and also allows for partial downsampling
        // of individual reads.
        boolean assumeDownstreamLIBSDownsampling = isLocusBasedTraversal &&
                                                   readProperties.getDownsamplingMethod().type == DownsampleType.BY_SAMPLE &&
                                                   readProperties.getDownsamplingMethod().toCoverage != null;

        // Apply downsampling iterators here only in cases where we know that LocusIteratorByState won't be
        // doing any downsampling downstream of us
        if ( ! assumeDownstreamLIBSDownsampling ) {
            wrappedIterator = applyDownsamplingIterator(wrappedIterator);
        }

        // unless they've said not to validate read ordering (!noValidationOfReadOrder) and we've enabled verification,
        // verify the read ordering by applying a sort order iterator
        if (!noValidationOfReadOrder && enableVerification)
            wrappedIterator = new VerifyingSamIterator(wrappedIterator);

        // Read transformers: these are applied last, so that we don't bother transforming reads that get discarded
        // by the read filters or downsampler.
        for ( final ReadTransformer readTransformer : readTransformers ) {
            if ( readTransformer.enabled() && readTransformer.getApplicationTime() == ReadTransformer.ApplicationTime.ON_INPUT )
                wrappedIterator = new ReadTransformingIterator(wrappedIterator, readTransformer);
        }

        return wrappedIterator;
    }

    protected StingSAMIterator applyDownsamplingIterator( StingSAMIterator wrappedIterator ) {
        if ( readProperties.getDownsamplingMethod() == null ||
             readProperties.getDownsamplingMethod().type == DownsampleType.NONE ) {
            return wrappedIterator;
        }

        if ( readProperties.getDownsamplingMethod().toFraction != null ) {

            // If we're downsampling to a fraction of reads, there's no point in paying the cost of
            // splitting/re-assembling the read stream by sample to run the FractionalDownsampler on
            // reads from each sample separately, since the result would be the same as running the
            // FractionalDownsampler on the entire stream. So, ALWAYS use the DownsamplingReadsIterator
            // rather than the PerSampleDownsamplingReadsIterator, even if BY_SAMPLE downsampling
            // was requested.

            return new DownsamplingReadsIterator(wrappedIterator,
                                                 new FractionalDownsampler<SAMRecord>(readProperties.getDownsamplingMethod().toFraction));
        }
        else if ( readProperties.getDownsamplingMethod().toCoverage != null ) {

            // If we're downsampling to coverage, we DO need to pay the cost of splitting/re-assembling
            // the read stream to run the downsampler on the reads for each individual sample separately if
            // BY_SAMPLE downsampling was requested.

            if ( readProperties.getDownsamplingMethod().type == DownsampleType.BY_SAMPLE ) {
                return new PerSampleDownsamplingReadsIterator(wrappedIterator,
                                                              new SimplePositionalDownsamplerFactory<SAMRecord>(readProperties.getDownsamplingMethod().toCoverage));
            }
            else if ( readProperties.getDownsamplingMethod().type == DownsampleType.ALL_READS ) {
                return new DownsamplingReadsIterator(wrappedIterator,
                                                     new SimplePositionalDownsampler<SAMRecord>(readProperties.getDownsamplingMethod().toCoverage));
            }
        }

        return wrappedIterator;
    }


    private class SAMResourcePool {
        /**
         * How many entries can be cached in this resource pool?
         */
        private final int maxEntries;

        /**
         * All iterators of this reference-ordered data.
         */
        private List<SAMReaders> allResources = new ArrayList<SAMReaders>();

        /**
         * All iterators that are not currently in service.
         */
        private List<SAMReaders> availableResources = new ArrayList<SAMReaders>();

        public SAMResourcePool(final int maxEntries) {
            this.maxEntries = maxEntries;
        }

        /**
         * Choose a set of readers from the pool to use for this query.  When complete,
         * @return
         */
        public synchronized SAMReaders getAvailableReaders() {
            if(availableResources.size() == 0)
                createNewResource();
            SAMReaders readers = availableResources.get(0);
            availableResources.remove(readers);
            return readers;
        }

        public synchronized void releaseReaders(SAMReaders readers) {
            if(!allResources.contains(readers))
                throw new ReviewedStingException("Tried to return readers from the pool that didn't originate in the pool.");
            availableResources.add(readers);
        }

        /**
         * Gets the reader id for the given reader.
         * @param reader Reader for which to determine the id.
         * @return id of the given reader.
         */
        protected synchronized SAMReaderID getReaderID(SAMFileReader reader) {
            for(SAMReaders readers: allResources) {
                SAMReaderID id = readers.getReaderID(reader);
                if(id != null)
                    return id;
            }
            throw new ReviewedStingException("No such reader id is available");
        }

        private synchronized void createNewResource() {
            if(allResources.size() > maxEntries)
                throw new ReviewedStingException("Cannot create a new resource pool.  All resources are in use.");
            SAMReaders readers = new SAMReaders(readerIDs, validationStringency, removeProgramRecords);
            allResources.add(readers);
            availableResources.add(readers);
        }

    }

    /**
     * A collection of readers derived from a reads metadata structure.
     */
    private class SAMReaders implements Iterable<SAMFileReader> {
        /**
         * Cached representation of the merged header used to generate a merging iterator.
         */
        private final SamFileHeaderMerger headerMerger;

        /**
         * Internal storage for a map of id -> reader.
         */
        private final Map<SAMReaderID,SAMFileReader> readers = new LinkedHashMap<SAMReaderID,SAMFileReader>();

        /**
         * The inptu streams backing
         */
        private final Map<SAMReaderID,BlockInputStream> inputStreams = new LinkedHashMap<SAMReaderID,BlockInputStream>();

        /**
         * Derive a new set of readers from the Reads metadata.
         * @param readerIDs reads to load.
         * TODO: validationStringency is not used here
         * @param validationStringency validation stringency.
         * @param removeProgramRecords indicate whether to clear program records from the readers
         */
        public SAMReaders(Collection<SAMReaderID> readerIDs, SAMFileReader.ValidationStringency validationStringency, boolean removeProgramRecords) {
            final int totalNumberOfFiles = readerIDs.size();
            int readerNumber = 1;
            final SimpleTimer timer = new SimpleTimer().start();

            if ( totalNumberOfFiles > 0 ) logger.info("Initializing SAMRecords in serial");
            final int tickSize = 50;
            int nExecutedTotal = 0;
            long lastTick = timer.currentTime();
            for(final SAMReaderID readerID: readerIDs) {
                final ReaderInitializer init = new ReaderInitializer(readerID).call();

                if (removeProgramRecords) {
                    init.reader.getFileHeader().setProgramRecords(new ArrayList<SAMProgramRecord>());
                }

                if (threadAllocation.getNumIOThreads() > 0) {
                    inputStreams.put(init.readerID, init.blockInputStream); // get from initializer
                }

                logger.debug(String.format("Processing file (%d of %d) %s...", readerNumber++, totalNumberOfFiles,  readerID.samFile));
                readers.put(init.readerID,init.reader);
                if ( ++nExecutedTotal % tickSize == 0) {
                    double tickInSec = (timer.currentTime() - lastTick) / 1000.0;
                    printReaderPerformance(nExecutedTotal, tickSize, totalNumberOfFiles, timer, tickInSec);
                    lastTick = timer.currentTime();
                }
            }

            if ( totalNumberOfFiles > 0 ) logger.info(String.format("Done initializing BAM readers: total time %.2f", timer.getElapsedTime()));

            Collection<SAMFileHeader> headers = new LinkedList<SAMFileHeader>();
            for(SAMFileReader reader: readers.values())
                headers.add(reader.getFileHeader());
            headerMerger = new SamFileHeaderMerger(SAMFileHeader.SortOrder.coordinate,headers,true);

            // update all read groups to GATKSAMRecordReadGroups
            final List<SAMReadGroupRecord> gatkReadGroups = new LinkedList<SAMReadGroupRecord>();
            for ( final SAMReadGroupRecord rg : headerMerger.getMergedHeader().getReadGroups() ) {
                gatkReadGroups.add(new GATKSAMReadGroupRecord(rg));
            }
            headerMerger.getMergedHeader().setReadGroups(gatkReadGroups);
        }

        final private void printReaderPerformance(final int nExecutedTotal,
                                                  final int nExecutedInTick,
                                                  final int totalNumberOfFiles,
                                                  final SimpleTimer timer,
                                                  final double tickDurationInSec) {
            final int pendingSize = totalNumberOfFiles - nExecutedTotal;
            final double totalTimeInSeconds = timer.getElapsedTime();
            final double nTasksPerSecond = nExecutedTotal / (1.0*totalTimeInSeconds);
            final int nRemaining = pendingSize;
            final double estTimeToComplete = pendingSize / nTasksPerSecond;
            logger.info(String.format("Init %d BAMs in last %.2f s, %d of %d in %.2f s / %.2f m (%.2f tasks/s).  %d remaining with est. completion in %.2f s / %.2f m",
                    nExecutedInTick, tickDurationInSec,
                    nExecutedTotal, totalNumberOfFiles, totalTimeInSeconds, totalTimeInSeconds / 60, nTasksPerSecond,
                    nRemaining, estTimeToComplete, estTimeToComplete / 60));
        }

        /**
         * Return the header derived from the merging of these BAM files.
         * @return the merged header.
         */
        public SAMFileHeader getMergedHeader() {
            return headerMerger.getMergedHeader();
        }

        /**
         * Do multiple read groups collide in this dataset?
         * @return True if multiple read groups collide; false otherwis.
         */
        public boolean hasReadGroupCollisions() {
            return headerMerger.hasReadGroupCollisions();
        }

        /**
         * Get the newly mapped read group ID for the given read group.
         * @param readerID Reader for which to discern the transformed ID.
         * @param originalReadGroupID Original read group.
         * @return Remapped read group.
         */
        public String getReadGroupId(final SAMReaderID readerID, final String originalReadGroupID) {
            SAMFileHeader header = readers.get(readerID).getFileHeader();
            return headerMerger.getReadGroupId(header,originalReadGroupID);
        }

        /**
         * Creates a new merging iterator from the given map, with the given header.
         * @param iteratorMap A map of readers to iterators.
         * @return An iterator which will merge those individual iterators.
         */
        public MergingSamRecordIterator createMergingIterator(final Map<SAMFileReader,CloseableIterator<SAMRecord>> iteratorMap) {
            return new MergingSamRecordIterator(headerMerger,iteratorMap,true);
        }

        /**
         * Retrieve the reader from the data structure.
         * @param id The ID of the reader to retrieve.
         * @return the reader associated with the given id.
         */
        public SAMFileReader getReader(SAMReaderID id) {
            if(!readers.containsKey(id))
                throw new NoSuchElementException("No reader is associated with id " + id);
            return readers.get(id);
        }

        /**
         * Retrieve the input stream backing a reader.
         * @param id The ID of the reader to retrieve.
         * @return the reader associated with the given id.
         */
        public BlockInputStream getInputStream(final SAMReaderID id) {
            return inputStreams.get(id);
        }

        /**
         * Searches for the reader id of this reader.
         * @param reader Reader for which to search.
         * @return The id associated the given reader, or null if the reader is not present in this collection.
         */
        protected SAMReaderID getReaderID(SAMFileReader reader) {
            for(Map.Entry<SAMReaderID,SAMFileReader> entry: readers.entrySet()) {
                if(reader == entry.getValue())
                    return entry.getKey();
            }
            // Not found? return null.
            return null;
        }

        /**
         * Returns an iterator over all readers in this structure.
         * @return An iterator over readers.
         */
        public Iterator<SAMFileReader> iterator() {
            return readers.values().iterator();
        }

        /**
         * Returns whether any readers are present in this structure.
         * @return
         */
        public boolean isEmpty() {
            return readers.isEmpty();
        }
    }

    class ReaderInitializer implements Callable<ReaderInitializer> {
        final SAMReaderID readerID;
        BlockInputStream blockInputStream = null;
        SAMFileReader reader;

        public ReaderInitializer(final SAMReaderID readerID) {
            this.readerID = readerID;
        }

        public ReaderInitializer call() {
            final File indexFile = findIndexFile(readerID.samFile);
            try {
                if (threadAllocation.getNumIOThreads() > 0)
                    blockInputStream = new BlockInputStream(dispatcher,readerID,false);
                reader = new SAMFileReader(readerID.samFile,indexFile,false);
            } catch ( RuntimeIOException e ) {
                throw new UserException.CouldNotReadInputFile(readerID.samFile, e);
            } catch ( SAMFormatException e ) {
                throw new UserException.MalformedBAM(readerID.samFile, e.getMessage());
            }
            // Picard is throwing a RuntimeException here when BAMs are malformed with bad headers (and so look like SAM files).
            // Let's keep this separate from the SAMFormatException (which ultimately derives from RuntimeException) case,
            // just in case we want to change this behavior later.
            catch ( RuntimeException e ) {
                throw new UserException.MalformedBAM(readerID.samFile, e.getMessage());
            }
            reader.setSAMRecordFactory(factory);
            reader.enableFileSource(true);
            reader.setValidationStringency(validationStringency);
            return this;
        }
    }

    private class ReleasingIterator implements StingSAMIterator {
        /**
         * The resource acting as the source of the data.
         */
        private final SAMReaders resource;

        /**
         * The iterator to wrap.
         */
        private final StingSAMIterator wrappedIterator;

        public ReleasingIterator(SAMReaders resource, StingSAMIterator wrapped) {
            this.resource = resource;
            this.wrappedIterator = wrapped;
        }

        public ReleasingIterator iterator() {
            return this;
        }

        public void remove() {
            throw new UnsupportedOperationException("Can't remove from a StingSAMIterator");
        }

        public void close() {
            wrappedIterator.close();
            resourcePool.releaseReaders(resource);
        }

        public boolean hasNext() {
            return wrappedIterator.hasNext();
        }

        public SAMRecord next() {
            return wrappedIterator.next();
        }
    }

    /**
     * Maps read groups in the original SAMFileReaders to read groups in
     */
    private class ReadGroupMapping extends HashMap<String,String> {}

    /**
     * Locates the index file alongside the given BAM, if present.
     * TODO: This is currently a hachetjob that reaches into Picard and pulls out its index file locator.  Replace with something more permanent.
     * @param bamFile The data file to use.
     * @return A File object if the index file is present; null otherwise.
     */
    private File findIndexFile(File bamFile) {
        File indexFile;

        try {
            Class bamFileReaderClass = Class.forName("net.sf.samtools.BAMFileReader");
            Method indexFileLocator = bamFileReaderClass.getDeclaredMethod("findIndexFile",File.class);
            indexFileLocator.setAccessible(true);
            indexFile = (File)indexFileLocator.invoke(null,bamFile);
        }
        catch(ClassNotFoundException ex) {
            throw new ReviewedStingException("Unable to locate BAMFileReader class, used to check for index files");
        }
        catch(NoSuchMethodException ex) {
            throw new ReviewedStingException("Unable to locate Picard index file locator.");
        }
        catch(IllegalAccessException ex) {
            throw new ReviewedStingException("Unable to access Picard index file locator.");
        }
        catch(InvocationTargetException ex) {
            throw new ReviewedStingException("Unable to invoke Picard index file locator.");
        }

        return indexFile;
    }

    /**
     * Creates a BAM schedule over all reads in the BAM file, both mapped and unmapped.  The outgoing stream
     * will be as granular as possible given our current knowledge of the best ways to split up BAM files.
     * @return An iterator that spans all reads in all BAM files.
     */
    public Iterable<Shard> createShardIteratorOverAllReads(final ShardBalancer shardBalancer) {
        shardBalancer.initialize(this,IntervalSharder.shardOverAllReads(this,genomeLocParser),genomeLocParser);
        return shardBalancer;
    }

    /**
     * Creates a BAM schedule over all mapped reads in the BAM file, when a 'mapped' read is defined as any
     * read that has been assigned
     *
     * @param   shardBalancer  shard balancer object
     * @return non-null initialized version of the shard balancer
     */
    public Iterable<Shard> createShardIteratorOverMappedReads(final ShardBalancer shardBalancer) {
        shardBalancer.initialize(this,IntervalSharder.shardOverMappedReads(this,genomeLocParser),genomeLocParser);
        return shardBalancer;
    }

    /**
     * Create a schedule for processing the initialized BAM file using the given interval list.
     * The returned schedule should be as granular as possible.
     * @param intervals The list of intervals for which to create the schedule.
     * @return A granular iterator over file pointers.
     */
    public Iterable<Shard> createShardIteratorOverIntervals(final GenomeLocSortedSet intervals,final ShardBalancer shardBalancer) {
        if(intervals == null)
            throw new ReviewedStingException("Unable to create schedule from intervals; no intervals were provided.");
        shardBalancer.initialize(this,IntervalSharder.shardOverIntervals(SAMDataSource.this,intervals),genomeLocParser);
        return shardBalancer;
    }
}



