/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.codec;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.CompoundFormat;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.FieldInfosFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.LiveDocsFormat;
import org.apache.lucene.codecs.NormsFormat;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.SegmentInfoFormat;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.TermVectorsFormat;

/**
 * A custom {@link Codec} that uses the default Lucene codec for everything
 * except DocValues, which are stored using the {@link RoaringDocValuesFormat}.
 * <p>
 * This codec is registered via the OpenSearch plugin mechanism and can be
 * selected at index creation time via the {@code index.codec} setting.
 * <p>
 * <b>Important:</b> This codec applies the Roaring format to <em>all</em>
 * SortedSetDocValues fields in the segment. Fields of other DocValues types
 * (Numeric, Binary, Sorted, SortedNumeric) will throw
 * {@link UnsupportedOperationException} if the codec is applied to them.
 * In practice, this means the codec should only be used on indices whose
 * keyword fields are all roaring-eligible.
 */
public class RoaringCodec extends Codec {

    /** The codec name as registered with Lucene's SPI. */
    public static final String CODEC_NAME = "RoaringCodec";

    private final Codec delegate;
    private final DocValuesFormat docValuesFormat;

    /**
     * Creates a new RoaringCodec that delegates to the given base codec
     * for all formats except DocValues.
     *
     * @param delegate the base codec to delegate non-DocValues formats to
     */
    public RoaringCodec(Codec delegate) {
        super(CODEC_NAME);
        this.delegate = delegate;
        this.docValuesFormat = new RoaringDocValuesFormat();
    }

    /**
     * No-arg constructor required by Lucene's SPI (NamedSPILoader).
     */
    public RoaringCodec() {
        this(null);
    }

    private Codec getDelegate() {
        return delegate != null ? delegate : Codec.forName("Lucene912");
    }

    @Override
    public DocValuesFormat docValuesFormat() {
        return docValuesFormat;
    }

    // ---- Delegate all other formats to the base codec ----

    @Override
    public PostingsFormat postingsFormat() {
        return getDelegate().postingsFormat();
    }

    @Override
    public StoredFieldsFormat storedFieldsFormat() {
        return getDelegate().storedFieldsFormat();
    }

    @Override
    public TermVectorsFormat termVectorsFormat() {
        return getDelegate().termVectorsFormat();
    }

    @Override
    public FieldInfosFormat fieldInfosFormat() {
        return getDelegate().fieldInfosFormat();
    }

    @Override
    public SegmentInfoFormat segmentInfoFormat() {
        return getDelegate().segmentInfoFormat();
    }

    @Override
    public NormsFormat normsFormat() {
        return getDelegate().normsFormat();
    }

    @Override
    public LiveDocsFormat liveDocsFormat() {
        return getDelegate().liveDocsFormat();
    }

    @Override
    public CompoundFormat compoundFormat() {
        return getDelegate().compoundFormat();
    }

    @Override
    public PointsFormat pointsFormat() {
        return getDelegate().pointsFormat();
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return getDelegate().knnVectorsFormat();
    }
}
