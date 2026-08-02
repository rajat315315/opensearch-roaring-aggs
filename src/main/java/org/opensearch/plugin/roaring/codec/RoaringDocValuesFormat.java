/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.codec;

import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

import java.io.IOException;

/**
 * A custom {@link DocValuesFormat} that stores {@code SortedSetDocValues} as an
 * array of Roaring Bitmaps indexed by global ordinal.
 * <p>
 * Instead of the traditional row-oriented layout where each document maps to its
 * ordinals, this format transposes the data into a column-oriented bitmap layout:
 * for each ordinal {@code k}, a Roaring Bitmap records the set of document IDs
 * that contain ordinal {@code k}.
 * <p>
 * This enables block-level (64K-document) skipping and hardware-accelerated
 * {@code AND + POPCNT} operations during aggregation, replacing random-access
 * DocValues lookups with sequential bitwise arithmetic.
 * <p>
 * <h2>File Format</h2>
 * <ul>
 *   <li><b>.rvm</b> (metadata): field info, ordinal count, offset table</li>
 *   <li><b>.rvd</b> (data): serialized Roaring Bitmaps, one per ordinal</li>
 * </ul>
 */
public class RoaringDocValuesFormat extends DocValuesFormat {

    /** Format name used for SPI registration. */
    public static final String FORMAT_NAME = "RoaringDocValues";

    /** File extension for metadata. */
    public static final String META_EXTENSION = "rvm";

    /** File extension for data. */
    public static final String DATA_EXTENSION = "rvd";

    /** Codec header magic for the metadata file. */
    static final String META_CODEC = "RoaringDocValuesMeta";

    /** Codec header magic for the data file. */
    static final String DATA_CODEC = "RoaringDocValuesData";

    /** Current format version. */
    static final int VERSION_CURRENT = 0;

    /**
     * Creates a new instance with the default format name.
     */
    public RoaringDocValuesFormat() {
        super(FORMAT_NAME);
    }

    @Override
    public DocValuesConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        return new RoaringDocValuesConsumer(state);
    }

    @Override
    public DocValuesProducer fieldsProducer(SegmentReadState state) throws IOException {
        return new RoaringDocValuesProducer(state);
    }
}
