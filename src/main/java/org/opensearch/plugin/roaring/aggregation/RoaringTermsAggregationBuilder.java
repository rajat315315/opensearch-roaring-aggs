/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.aggregation;

import org.opensearch.core.ParseField;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ObjectParser;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.search.aggregations.AbstractAggregationBuilder;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.AggregatorFactories;
import org.opensearch.search.aggregations.AggregatorFactory;
import org.opensearch.search.aggregations.support.ValuesSourceRegistry;
import org.opensearch.index.query.QueryShardContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregation builder for the {@code roaring_terms} aggregation.
 * <p>
 * This aggregation performs a terms aggregation over keyword fields that have
 * been indexed with the {@link org.opensearch.plugin.roaring.codec.RoaringDocValuesFormat}.
 * Instead of iterating through matched documents and performing random-access
 * DocValues lookups, it uses block-level bitmap intersections with hardware
 * POPCNT for dramatically faster aggregation on high-cardinality fields.
 * <p>
 * <h3>REST API Example</h3>
 * <pre>
 * POST /my-index/_search
 * {
 *   "size": 0,
 *   "aggs": {
 *     "categories": {
 *       "roaring_terms": {
 *         "field": "tags",
 *         "size": 10
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
public class RoaringTermsAggregationBuilder extends AbstractAggregationBuilder<RoaringTermsAggregationBuilder> {

    /** The aggregation type name. */
    public static final String NAME = "roaring_terms";

    /** Parse field for the target field name. */
    private static final ParseField FIELD_FIELD = new ParseField("field");

    /** Parse field for the number of top buckets to return. */
    private static final ParseField SIZE_FIELD = new ParseField("size");

    private static final int DEFAULT_SIZE = 10;

    private String field;
    private int size = DEFAULT_SIZE;

    /**
     * Creates a new builder with the given aggregation name.
     *
     * @param name the aggregation name
     */
    public RoaringTermsAggregationBuilder(String name) {
        super(name);
    }

    /**
     * Deserialization constructor.
     */
    public RoaringTermsAggregationBuilder(StreamInput in) throws IOException {
        super(in);
        this.field = in.readString();
        this.size = in.readVInt();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(field);
        out.writeVInt(size);
    }

    /**
     * Sets the field to aggregate on.
     */
    public RoaringTermsAggregationBuilder field(String field) {
        this.field = Objects.requireNonNull(field, "field must not be null");
        return this;
    }

    /**
     * Returns the field to aggregate on.
     */
    public String field() {
        return field;
    }

    /**
     * Sets the number of top buckets to return.
     */
    public RoaringTermsAggregationBuilder size(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("[size] must be positive, got " + size);
        }
        this.size = size;
        return this;
    }

    /**
     * Returns the number of top buckets to return.
     */
    public int size() {
        return size;
    }

    @Override
    public BucketCardinality bucketCardinality() {
        return BucketCardinality.MANY;
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    protected AggregatorFactory doBuild(
            QueryShardContext queryShardContext,
            AggregatorFactory parent,
            AggregatorFactories.Builder subFactoriesBuilder) throws IOException {
        return new RoaringTermsAggregatorFactory(
            name, field, size, queryShardContext, parent, subFactoriesBuilder, metadata);
    }

    @Override
    protected XContentBuilder internalXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(FIELD_FIELD.getPreferredName(), field);
        builder.field(SIZE_FIELD.getPreferredName(), size);
        builder.endObject();
        return builder;
    }

    @Override
    protected AggregationBuilder shallowCopy(AggregatorFactories.Builder factoriesBuilder, Map<String, Object> metadata) {
        RoaringTermsAggregationBuilder copy = new RoaringTermsAggregationBuilder(name);
        copy.field = this.field;
        copy.size = this.size;
        copy.metadata = metadata;
        return copy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), field, size);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        if (!super.equals(obj)) return false;
        RoaringTermsAggregationBuilder other = (RoaringTermsAggregationBuilder) obj;
        return Objects.equals(field, other.field) && size == other.size;
    }

    /**
     * Parses the aggregation from XContent.
     */
    public static RoaringTermsAggregationBuilder parse(String aggregationName, XContentParser parser) throws IOException {
        RoaringTermsAggregationBuilder builder = new RoaringTermsAggregationBuilder(aggregationName);
        XContentParser.Token token;
        String currentFieldName = null;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                if (FIELD_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    builder.field(parser.text());
                } else if (SIZE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    builder.size(parser.intValue());
                } else {
                    throw new IllegalArgumentException("Unknown parameter [" + currentFieldName + "] for [" + NAME + "]");
                }
            }
        }

        if (builder.field() == null) {
            throw new IllegalArgumentException("[field] is required for [" + NAME + "] aggregation");
        }

        return builder;
    }
}
