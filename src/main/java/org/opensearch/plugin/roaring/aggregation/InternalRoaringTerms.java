/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.aggregation;

import org.apache.lucene.util.BytesRef;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.search.aggregations.InternalAggregation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal aggregation result holder for the {@code roaring_terms} aggregation.
 * <p>
 * Each instance contains a list of {@link Bucket} objects, each representing a
 * unique term value and its document count. This class handles:
 * <ul>
 *   <li>Serialization over the transport layer for cross-shard communication</li>
 *   <li>Cross-shard reduction (merging buckets from multiple shards)</li>
 *   <li>XContent rendering for the REST API response</li>
 * </ul>
 */
public class InternalRoaringTerms extends InternalAggregation {

    /**
     * A single bucket in the roaring_terms aggregation result.
     */
    public static class Bucket {
        private final BytesRef term;
        private final long docCount;

        /**
         * Creates a new bucket.
         *
         * @param term     the term value
         * @param docCount the document count for this term
         */
        public Bucket(BytesRef term, long docCount) {
            this.term = term;
            this.docCount = docCount;
        }

        /** Returns the term value as a string. */
        public String getKeyAsString() {
            return term.utf8ToString();
        }

        /** Returns the raw term value. */
        public BytesRef getTerm() {
            return term;
        }

        /** Returns the document count. */
        public long getDocCount() {
            return docCount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Bucket bucket = (Bucket) o;
            return docCount == bucket.docCount && Objects.equals(term, bucket.term);
        }

        @Override
        public int hashCode() {
            return Objects.hash(term, docCount);
        }

        @Override
        public String toString() {
            return "Bucket{term=" + getKeyAsString() + ", docCount=" + docCount + "}";
        }
    }

    private final List<Bucket> buckets;
    private final int size;

    /**
     * Creates a new internal aggregation result.
     *
     * @param name     the aggregation name
     * @param buckets  the term buckets (sorted by doc_count descending)
     * @param size     the requested number of top buckets
     * @param metadata aggregation metadata
     */
    public InternalRoaringTerms(String name, List<Bucket> buckets, int size, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.size = size;
    }

    /**
     * Deserialization constructor.
     */
    public InternalRoaringTerms(StreamInput in) throws IOException {
        super(in);
        this.size = in.readVInt();
        int bucketCount = in.readVInt();
        this.buckets = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            BytesRef term = in.readBytesRef();
            long docCount = in.readVLong();
            buckets.add(new Bucket(term, docCount));
        }
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeVInt(size);
        out.writeVInt(buckets.size());
        for (Bucket bucket : buckets) {
            out.writeBytesRef(bucket.term);
            out.writeVLong(bucket.docCount);
        }
    }

    @Override
    public String getWriteableName() {
        return RoaringTermsAggregationBuilder.NAME;
    }

    @Override
    protected boolean mustReduceOnSingleInternalAgg() {
        return false;
    }

    /** Returns the list of buckets. */
    public List<Bucket> getBuckets() {
        return Collections.unmodifiableList(buckets);
    }

    /**
     * Merges results from multiple shards.
     * <p>
     * Sums document counts for identical terms across shards, then selects
     * the top-N buckets by count.
     */
    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext reduceContext) {
        Map<BytesRef, Long> mergedCounts = new HashMap<>();

        for (InternalAggregation agg : aggregations) {
            InternalRoaringTerms terms = (InternalRoaringTerms) agg;
            for (Bucket bucket : terms.buckets) {
                mergedCounts.merge(bucket.term, bucket.docCount, Long::sum);
            }
        }

        // Sort by count descending, then by term ascending for deterministic ordering
        List<Bucket> mergedBuckets = new ArrayList<>();
        for (Map.Entry<BytesRef, Long> entry : mergedCounts.entrySet()) {
            mergedBuckets.add(new Bucket(entry.getKey(), entry.getValue()));
        }
        mergedBuckets.sort((a, b) -> {
            int cmp = Long.compare(b.docCount, a.docCount);
            if (cmp != 0) return cmp;
            return a.term.compareTo(b.term);
        });

        // Trim to requested size
        if (mergedBuckets.size() > size) {
            mergedBuckets = new ArrayList<>(mergedBuckets.subList(0, size));
        }

        return new InternalRoaringTerms(getName(), mergedBuckets, size, getMetadata());
    }

    @Override
    public Object getProperty(List<String> path) {
        if (path.isEmpty()) {
            return this;
        }
        if ("buckets".equals(path.get(0))) {
            return buckets;
        }
        throw new IllegalArgumentException("Unknown property [" + path.get(0) + "] for [roaring_terms]");
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        builder.startArray("buckets");
        for (Bucket bucket : buckets) {
            builder.startObject();
            builder.field("key", bucket.getKeyAsString());
            builder.field("doc_count", bucket.docCount);
            builder.endObject();
        }
        builder.endArray();
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        InternalRoaringTerms that = (InternalRoaringTerms) o;
        return size == that.size && Objects.equals(buckets, that.buckets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), buckets, size);
    }
}
