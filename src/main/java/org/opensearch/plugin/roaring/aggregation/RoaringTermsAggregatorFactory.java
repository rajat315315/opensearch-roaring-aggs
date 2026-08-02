/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.aggregation;

import org.opensearch.index.query.QueryShardContext;
import org.opensearch.search.aggregations.Aggregator;
import org.opensearch.search.aggregations.AggregatorFactories;
import org.opensearch.search.aggregations.AggregatorFactory;
import org.opensearch.search.aggregations.CardinalityUpperBound;
import org.opensearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Map;

/**
 * Factory for creating {@link RoaringTermsAggregator} instances.
 * <p>
 * This factory is instantiated by the {@link RoaringTermsAggregationBuilder}
 * and is responsible for creating one aggregator per shard during search
 * execution.
 */
public class RoaringTermsAggregatorFactory extends AggregatorFactory {

    private final String field;
    private final int size;

    /**
     * Creates a new factory.
     *
     * @param name          the aggregation name
     * @param field         the target field
     * @param size          number of top buckets to return
     * @param context       the query shard context
     * @param parent        the parent factory (may be null for top-level aggs)
     * @param subFactories  sub-aggregation factories
     * @param metadata      aggregation metadata
     */
    public RoaringTermsAggregatorFactory(
            String name,
            String field,
            int size,
            QueryShardContext context,
            AggregatorFactory parent,
            AggregatorFactories.Builder subFactories,
            Map<String, Object> metadata) throws IOException {
        super(name, context, parent, subFactories, metadata);
        this.field = field;
        this.size = size;
    }

    @Override
    protected Aggregator createInternal(
            SearchContext searchContext,
            Aggregator parent,
            CardinalityUpperBound cardinality,
            Map<String, Object> metadata) throws IOException {
        return new RoaringTermsAggregator(name, field, size, searchContext, parent, metadata);
    }
}
