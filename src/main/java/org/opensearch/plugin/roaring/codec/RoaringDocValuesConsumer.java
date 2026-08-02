/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.codec;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.BytesRef;

import org.roaringbitmap.RoaringBitmap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-path implementation for the Roaring Bitmap DocValues format.
 * <p>
 * When {@link #addSortedSetField(FieldInfo, DocValuesProducer)} is called, this
 * consumer transposes the incoming per-document ordinal data into per-ordinal
 * Roaring Bitmaps and writes them to disk:
 * <ul>
 *   <li><b>.rvm (metadata)</b>: header, field name, ordinal count, byte-offset
 *       table for each ordinal's bitmap in the data file, footer.</li>
 *   <li><b>.rvd (data)</b>: header, concatenated serialized Roaring Bitmaps
 *       (one per ordinal), footer.</li>
 * </ul>
 * <p>
 * This format supports only {@code SortedSetDocValues}. Numeric, binary, sorted,
 * and sorted-numeric fields throw {@link UnsupportedOperationException}.
 */
public class RoaringDocValuesConsumer extends DocValuesConsumer {

    private final SegmentWriteState state;
    private final IndexOutput metaOut;
    private final IndexOutput dataOut;

    /**
     * Creates a new consumer, opening the metadata and data output files.
     *
     * @param state the segment write state providing directory and segment info
     * @throws IOException if the files cannot be created
     */
    public RoaringDocValuesConsumer(SegmentWriteState state) throws IOException {
        this.state = state;

        boolean success = false;
        try {
            String metaFileName = getMetaFileName(state);
            String dataFileName = getDataFileName(state);

            metaOut = state.directory.createOutput(metaFileName, state.context);
            CodecUtil.writeIndexHeader(metaOut,
                RoaringDocValuesFormat.META_CODEC,
                RoaringDocValuesFormat.VERSION_CURRENT,
                state.segmentInfo.getId(),
                state.segmentSuffix);

            dataOut = state.directory.createOutput(dataFileName, state.context);
            CodecUtil.writeIndexHeader(dataOut,
                RoaringDocValuesFormat.DATA_CODEC,
                RoaringDocValuesFormat.VERSION_CURRENT,
                state.segmentInfo.getId(),
                state.segmentSuffix);

            success = true;
        } finally {
            if (!success) {
                close();
            }
        }
    }

    static String getMetaFileName(SegmentWriteState state) {
        return IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            RoaringDocValuesFormat.META_EXTENSION);
    }

    static String getDataFileName(SegmentWriteState state) {
        return IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            RoaringDocValuesFormat.DATA_EXTENSION);
    }

    /**
     * Transposes a SortedSetDocValues field into per-ordinal Roaring Bitmaps.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Iterate all documents, collecting each doc's ordinals</li>
     *   <li>For each ordinal encountered, add the docID to that ordinal's bitmap</li>
     *   <li>Serialize all bitmaps to the data file</li>
     *   <li>Write the offset table and term dictionary to the metadata file</li>
     * </ol>
     */
    @Override
    public void addSortedSetField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        SortedSetDocValues docValues = valuesProducer.getSortedSet(field);

        // Phase 1: Build per-ordinal RoaringBitmaps
        List<RoaringBitmap> ordinalBitmaps = new ArrayList<>();

        int docID;
        while ((docID = docValues.nextDoc()) != SortedSetDocValues.NO_MORE_DOCS) {
            long ord;
            while ((ord = docValues.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
                int ordInt = (int) ord;
                // Grow the list as needed
                while (ordinalBitmaps.size() <= ordInt) {
                    ordinalBitmaps.add(new RoaringBitmap());
                }
                ordinalBitmaps.get(ordInt).add(docID);
            }
        }

        int ordinalCount = ordinalBitmaps.size();

        // Optimize bitmaps for serialization (run-length encode where beneficial)
        for (RoaringBitmap bitmap : ordinalBitmaps) {
            bitmap.runOptimize();
        }

        // Phase 2: Serialize bitmaps to data file and record offsets
        long[] offsets = new long[ordinalCount];
        long dataStartOffset = dataOut.getFilePointer();

        for (int ord = 0; ord < ordinalCount; ord++) {
            offsets[ord] = dataOut.getFilePointer() - dataStartOffset;

            RoaringBitmap bitmap = ordinalBitmaps.get(ord);
            byte[] serialized = serializeBitmap(bitmap);
            dataOut.writeBytes(serialized, serialized.length);
        }

        long dataEndOffset = dataOut.getFilePointer();

        // Phase 3: Write term (ordinal value) dictionary.
        // Re-obtain docValues to access the term dictionary via lookupOrd().
        SortedSetDocValues termsDocValues = valuesProducer.getSortedSet(field);
        byte[][] termBytes = new byte[ordinalCount][];
        for (int ord = 0; ord < ordinalCount; ord++) {
            BytesRef termRef = termsDocValues.lookupOrd(ord);
            byte[] termBuf = new byte[termRef.length];
            System.arraycopy(termRef.bytes, termRef.offset, termBuf, 0, termRef.length);
            termBytes[ord] = termBuf;
        }

        // Phase 4: Write metadata
        // Field name
        metaOut.writeString(field.name);
        // Ordinal count
        metaOut.writeVInt(ordinalCount);
        // Data start offset
        metaOut.writeLong(dataStartOffset);
        // Data end offset
        metaOut.writeLong(dataEndOffset);
        // Offset table: one long per ordinal (relative to dataStartOffset)
        for (int ord = 0; ord < ordinalCount; ord++) {
            metaOut.writeLong(offsets[ord]);
        }
        // Term dictionary: ordinal → term bytes
        for (int ord = 0; ord < ordinalCount; ord++) {
            metaOut.writeVInt(termBytes[ord].length);
            metaOut.writeBytes(termBytes[ord], termBytes[ord].length);
        }
    }

    /**
     * Serializes a RoaringBitmap to a byte array using the standard serialization format.
     */
    private byte[] serializeBitmap(RoaringBitmap bitmap) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(bitmap.serializedSizeInBytes());
        DataOutputStream dos = new DataOutputStream(baos);
        bitmap.serialize(dos);
        dos.flush();
        return baos.toByteArray();
    }

    // ---- Unsupported field types: delegate nothing, throw on use ----

    @Override
    public void addNumericField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        throw new UnsupportedOperationException(
            "RoaringDocValues does not support NumericDocValues. Field: " + field.name);
    }

    @Override
    public void addBinaryField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        throw new UnsupportedOperationException(
            "RoaringDocValues does not support BinaryDocValues. Field: " + field.name);
    }

    @Override
    public void addSortedField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        throw new UnsupportedOperationException(
            "RoaringDocValues does not support SortedDocValues. Field: " + field.name);
    }

    @Override
    public void addSortedNumericField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        throw new UnsupportedOperationException(
            "RoaringDocValues does not support SortedNumericDocValues. Field: " + field.name);
    }

    @Override
    public void close() throws IOException {
        boolean success = false;
        try {
            if (metaOut != null) {
                CodecUtil.writeFooter(metaOut);
            }
            if (dataOut != null) {
                CodecUtil.writeFooter(dataOut);
            }
            success = true;
        } finally {
            if (success) {
                if (metaOut != null) metaOut.close();
                if (dataOut != null) dataOut.close();
            } else {
                // Best-effort close on failure
                try {
                    if (metaOut != null) metaOut.close();
                } catch (IOException ignored) {}
                try {
                    if (dataOut != null) dataOut.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
