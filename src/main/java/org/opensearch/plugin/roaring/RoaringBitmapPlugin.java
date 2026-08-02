/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring;

import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.codec.CodecServiceFactory;
import org.opensearch.index.engine.EngineConfig;
import org.opensearch.plugins.EnginePlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.search.aggregations.InternalAggregation;

import org.opensearch.plugin.roaring.aggregation.InternalRoaringTerms;
import org.opensearch.plugin.roaring.aggregation.RoaringTermsAggregationBuilder;
import org.opensearch.plugin.roaring.codec.RoaringCodec;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * OpenSearch plugin that provides opt-in Roaring Bitmap indexing for
 * high-cardinality multi-valued aggregations.
 * <p>
 * This plugin implements two extension points:
 * <ul>
 *   <li><b>{@link EnginePlugin}</b>: Registers the custom {@link RoaringCodec}
 *       via {@link #getCustomCodecServiceFactory(IndexSettings)}, which
 *       enables the Roaring Bitmap DocValues format for indices configured
 *       with {@code index.codec: RoaringCodec}.</li>
 *   <li><b>{@link SearchPlugin}</b>: Registers the {@code roaring_terms}
 *       aggregation via {@link #getAggregations()}, which performs
 *       bitmap-accelerated terms aggregation.</li>
 * </ul>
 * <p>
 * <h3>Quick Start</h3>
 * <pre>
 * // 1. Create an index with the Roaring codec
 * PUT /my-index
 * {
 *   "settings": {
 *     "index.codec": "RoaringCodec"
 *   },
 *   "mappings": {
 *     "properties": {
 *       "tags": { "type": "keyword" }
 *     }
 *   }
 * }
 *
 * // 2. Run a roaring_terms aggregation
 * POST /my-index/_search
 * {
 *   "size": 0,
 *   "aggs": {
 *     "top_tags": {
 *       "roaring_terms": {
 *         "field": "tags",
 *         "size": 10
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
public class RoaringBitmapPlugin extends Plugin implements EnginePlugin, SearchPlugin {

    /**
     * Registers the custom {@link RoaringCodec} as a codec option.
     * <p>
     * When an index is created with {@code "index.codec": "RoaringCodec"},
     * OpenSearch will use this factory to create a codec service that provides
     * the Roaring Bitmap DocValues format.
     */
    @Override
    public Optional<CodecServiceFactory> getCustomCodecServiceFactory(IndexSettings indexSettings) {
        String codecName = indexSettings.getValue(EngineConfig.INDEX_CODEC_SETTING);
        if (RoaringCodec.CODEC_NAME.equals(codecName)) {
            return Optional.of(config -> new org.opensearch.index.codec.CodecService(
                config.getMapperService(),
                config.getIndexSettings(),
                config.getLogger()
            ) {
                @Override
                public org.apache.lucene.codecs.Codec codec(String name) {
                    if (RoaringCodec.CODEC_NAME.equals(name)) {
                        return new RoaringCodec();
                    }
                    return super.codec(name);
                }
            });
        }
        return Optional.empty();
    }

    /**
     * Registers the {@code roaring_terms} aggregation.
     */
    @Override
    public List<AggregationSpec> getAggregations() {
        return Collections.singletonList(
            new AggregationSpec(
                RoaringTermsAggregationBuilder.NAME,
                RoaringTermsAggregationBuilder::new,
                RoaringTermsAggregationBuilder::parse
            ).addResultReader(InternalRoaringTerms::new)
        );
    }
}
