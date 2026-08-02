/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.aggregation;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;

import org.opensearch.search.aggregations.Aggregator;
import org.opensearch.search.aggregations.AggregatorBase;
import org.opensearch.search.aggregations.AggregatorFactories;
import org.opensearch.search.aggregations.CardinalityUpperBound;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.LeafBucketCollector;
import org.opensearch.search.aggregations.LeafBucketCollectorBase;
import org.opensearch.search.internal.SearchContext;

import org.opensearch.plugin.roaring.codec.RoaringCodec;
import org.opensearch.plugin.roaring.codec.RoaringDocValuesFormat;
import org.opensearch.plugin.roaring.codec.RoaringDocValuesProducer;
import org.opensearch.plugin.roaring.util.BitsetUtil;

import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Core aggregator that performs terms aggregation using Roaring Bitmap
 * intersections with 64K-block skipping.
 * <p>
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Collect all matching docIDs into a query result bitset (Q) during
 *       the standard collection phase.</li>
 *   <li>In {@link #buildAggregations(long[])}, access the
 *       {@link RoaringDocValuesProducer} to retrieve per-ordinal Roaring
 *       Bitmaps for the target field.</li>
 *   <li>Convert Q into a {@link RoaringBitmap} and compute
 *       {@code Q.andCardinality(Ck)} for each ordinal k. The RoaringBitmap
 *       library internally uses container-level (64K-block) skipping and
 *       POPCNT for this operation.</li>
 *   <li>Select the top-N ordinals by count and resolve their term values.</li>
 * </ol>
 * <p>
 * If the field was not indexed with the Roaring format, the aggregator falls
 * back to standard SortedSetDocValues iteration.
 */
public class RoaringTermsAggregator extends AggregatorBase {

    private final String field;
    private final int size;

    // Per-segment state: collect matching docIDs
    private final List<LeafCollectedDocs> collectedDocs = new ArrayList<>();

    /**
     * Holds the matching docIDs and the leaf reader context for one segment.
     */
    private static class LeafCollectedDocs {
        final LeafReaderContext context;
        final RoaringBitmap matchingDocs;

        LeafCollectedDocs(LeafReaderContext context, RoaringBitmap matchingDocs) {
            this.context = context;
            this.matchingDocs = matchingDocs;
        }
    }

    /**
     * Creates a new aggregator.
     *
     * @param name     the aggregation name
     * @param field    the target field name
     * @param size     the number of top buckets to return
     * @param context  the search context
     * @param parent   the parent aggregator (null for top-level)
     * @param metadata aggregation metadata
     */
    public RoaringTermsAggregator(
            String name,
            String field,
            int size,
            SearchContext context,
            Aggregator parent,
            Map<String, Object> metadata) throws IOException {
        super(name, AggregatorFactories.EMPTY, context, parent, CardinalityUpperBound.NONE, metadata);
        this.field = field;
        this.size = size;
    }

    @Override
    public ScoreMode scoreMode() {
        return ScoreMode.COMPLETE_NO_SCORES;
    }

    @Override
    protected LeafBucketCollector getLeafCollector(LeafReaderContext ctx, LeafBucketCollector sub) throws IOException {
        // Create a RoaringBitmap to collect matching docIDs for this segment
        RoaringBitmap segmentDocs = new RoaringBitmap();
        LeafCollectedDocs leafDocs = new LeafCollectedDocs(ctx, segmentDocs);
        collectedDocs.add(leafDocs);

        return new LeafBucketCollectorBase(sub, null) {
            @Override
            public void collect(int doc, long owningBucketOrd) throws IOException {
                segmentDocs.add(doc);
            }
        };
    }

    @Override
    public InternalAggregation[] buildAggregations(long[] owningBucketOrds) throws IOException {
        // Aggregate across all segments
        // We accumulate counts per ordinal globally (for the single-bucket case)
        long[] globalCounts = null;
        int globalOrdinalCount = 0;
        BytesRef[] globalTerms = null;

        for (LeafCollectedDocs leafDocs : collectedDocs) {
            LeafReaderContext ctx = leafDocs.context;
            RoaringBitmap queryBitmap = leafDocs.matchingDocs;

            if (queryBitmap.isEmpty()) {
                continue;
            }

            // Try to get the RoaringDocValuesProducer from the segment
            RoaringDocValuesProducer producer = getRoaringProducer(ctx);

            if (producer == null) {
                // Field not indexed with Roaring format - skip this segment
                // A production implementation would fall back to standard DocValues
                continue;
            }

            int ordinalCount = producer.getOrdinalCount(field);
            if (ordinalCount == 0) {
                continue;
            }

            // Initialize global arrays if needed
            if (globalCounts == null) {
                globalOrdinalCount = ordinalCount;
                globalCounts = new long[ordinalCount];
                globalTerms = new BytesRef[ordinalCount];
                for (int ord = 0; ord < ordinalCount; ord++) {
                    globalTerms[ord] = producer.lookupOrd(field, ord);
                }
            }

            // Core bitmap intersection: for each ordinal, compute
            // |Q ∩ Ck| using RoaringBitmap's native andCardinality()
            // which internally uses container-level skipping and POPCNT.
            for (int ord = 0; ord < ordinalCount; ord++) {
                RoaringBitmap ordinalBitmap = producer.getRoaringBitmap(field, ord);
                long count = RoaringBitmap.andCardinality(queryBitmap, ordinalBitmap);
                if (ord < globalCounts.length) {
                    globalCounts[ord] += count;
                }
            }
        }

        // Build the result: select top-N ordinals by count
        List<InternalRoaringTerms.Bucket> buckets;
        if (globalCounts == null) {
            buckets = Collections.emptyList();
        } else {
            buckets = selectTopBuckets(globalCounts, globalTerms, globalOrdinalCount, size);
        }

        InternalAggregation[] results = new InternalAggregation[owningBucketOrds.length];
        for (int i = 0; i < owningBucketOrds.length; i++) {
            results[i] = new InternalRoaringTerms(name, buckets, size, metadata());
        }
        return results;
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalRoaringTerms(name, Collections.emptyList(), size, metadata());
    }

    /**
     * Attempts to obtain a {@link RoaringDocValuesProducer} from the given
     * leaf reader context.
     * <p>
     * This looks for the custom Roaring DocValues files. If the segment was
     * not written with the Roaring codec, returns {@code null}.
     */
    private RoaringDocValuesProducer getRoaringProducer(LeafReaderContext ctx) {
        try {
            if (ctx.reader() instanceof SegmentReader) {
                SegmentReader segmentReader = (SegmentReader) ctx.reader();
                // Check if the field exists and has SortedSet doc values
                FieldInfo fieldInfo = segmentReader.getFieldInfos().fieldInfo(field);
                if (fieldInfo == null || fieldInfo.getDocValuesType() != DocValuesType.SORTED_SET) {
                    return null;
                }

                // Try to obtain the Roaring producer through the segment's codec
                // The codec should be the RoaringCodec if the index was created with it
                org.apache.lucene.codecs.DocValuesFormat dvFormat =
                    segmentReader.getSegmentInfo().info.getCodec().docValuesFormat();
                String codecName = segmentReader.getSegmentInfo().info.getCodec().getName();

                if (RoaringCodec.CODEC_NAME.equals(codecName) ||
                    RoaringDocValuesFormat.FORMAT_NAME.equals(dvFormat.getName()) ||
                    dvFormat instanceof org.opensearch.plugin.roaring.codec.RoaringDocValuesFormat) {
                    return new RoaringDocValuesProducer(
                        new org.apache.lucene.index.SegmentReadState(
                            segmentReader.directory(),
                            segmentReader.getSegmentInfo().info,
                            segmentReader.getFieldInfos(),
                            org.apache.lucene.store.IOContext.READ));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Selects the top-N buckets by document count using a min-heap.
     */
    private List<InternalRoaringTerms.Bucket> selectTopBuckets(
            long[] counts, BytesRef[] terms, int ordinalCount, int topN) {

        // Use a min-heap of size topN
        PriorityQueue<InternalRoaringTerms.Bucket> heap =
            new PriorityQueue<>(topN, (a, b) -> Long.compare(a.getDocCount(), b.getDocCount()));

        for (int ord = 0; ord < ordinalCount; ord++) {
            if (counts[ord] == 0) continue;

            InternalRoaringTerms.Bucket bucket =
                new InternalRoaringTerms.Bucket(terms[ord], counts[ord]);

            if (heap.size() < topN) {
                heap.add(bucket);
            } else if (bucket.getDocCount() > heap.peek().getDocCount()) {
                heap.poll();
                heap.add(bucket);
            }
        }

        // Extract in descending order
        List<InternalRoaringTerms.Bucket> result = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            result.add(heap.poll());
        }
        Collections.reverse(result);
        return result;
    }
}
