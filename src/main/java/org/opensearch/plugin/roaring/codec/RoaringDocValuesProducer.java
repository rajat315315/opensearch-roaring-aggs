/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.codec;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.BytesRef;

import org.roaringbitmap.RoaringBitmap;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-path implementation for the Roaring Bitmap DocValues format.
 * <p>
 * At construction time this producer reads the metadata file (.rvm) to learn
 * the ordinal count, the offset table, and the term dictionary for each field.
 * Individual Roaring Bitmaps are deserialized on demand from the data file (.rvd)
 * when {@link #getRoaringBitmap(String, int)} is called.
 * <p>
 * This producer also implements the standard {@link DocValuesProducer} API
 * ({@link #getSortedSet(FieldInfo)}) so that Lucene's merge logic and standard
 * facet collectors can still operate transparently over roaring-indexed fields.
 */
public class RoaringDocValuesProducer extends DocValuesProducer {

    /** Per-field metadata loaded from the .rvm file. */
    static class FieldMeta {
        final String fieldName;
        final int ordinalCount;
        final long dataStartOffset;
        final long dataEndOffset;
        final long[] bitmapOffsets;   // relative to dataStartOffset
        final byte[][] termBytes;     // ordinal → term value

        FieldMeta(String fieldName, int ordinalCount, long dataStartOffset,
                  long dataEndOffset, long[] bitmapOffsets, byte[][] termBytes) {
            this.fieldName = fieldName;
            this.ordinalCount = ordinalCount;
            this.dataStartOffset = dataStartOffset;
            this.dataEndOffset = dataEndOffset;
            this.bitmapOffsets = bitmapOffsets;
            this.termBytes = termBytes;
        }
    }

    private final IndexInput dataIn;
    private final Map<String, FieldMeta> fields;
    private final DocValuesProducer delegateProducer;

    public RoaringDocValuesProducer(SegmentReadState state) throws IOException {
        this(state, null);
    }

    /**
     * Opens the metadata and data files, reads all field metadata.
     *
     * @param state            the segment read state providing directory and segment info
     * @param delegateProducer fallback producer for non-SortedSet field types
     * @throws IOException if the files cannot be opened or are corrupt
     */
    public RoaringDocValuesProducer(SegmentReadState state, DocValuesProducer delegateProducer) throws IOException {
        this.delegateProducer = delegateProducer;
        Map<String, FieldMeta> fieldMap = new HashMap<>();

        // Read metadata
        String metaFileName = IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            RoaringDocValuesFormat.META_EXTENSION);

        try (ChecksumIndexInput metaIn = state.directory.openChecksumInput(metaFileName, state.context)) {
            CodecUtil.checkIndexHeader(metaIn,
                RoaringDocValuesFormat.META_CODEC,
                RoaringDocValuesFormat.VERSION_CURRENT,
                RoaringDocValuesFormat.VERSION_CURRENT,
                state.segmentInfo.getId(),
                state.segmentSuffix);

            // Read fields until we hit the footer
            // We detect the footer by checking remaining bytes
            while (metaIn.getFilePointer() < metaIn.length() - CodecUtil.footerLength()) {
                String fieldName = metaIn.readString();
                int ordinalCount = metaIn.readVInt();
                long dataStartOffset = metaIn.readLong();
                long dataEndOffset = metaIn.readLong();

                long[] bitmapOffsets = new long[ordinalCount];
                for (int i = 0; i < ordinalCount; i++) {
                    bitmapOffsets[i] = metaIn.readLong();
                }

                byte[][] termBytes = new byte[ordinalCount][];
                for (int i = 0; i < ordinalCount; i++) {
                    int len = metaIn.readVInt();
                    termBytes[i] = new byte[len];
                    metaIn.readBytes(termBytes[i], 0, len);
                }

                fieldMap.put(fieldName, new FieldMeta(
                    fieldName, ordinalCount, dataStartOffset, dataEndOffset,
                    bitmapOffsets, termBytes));
            }

            CodecUtil.checkFooter(metaIn);
        }

        this.fields = Collections.unmodifiableMap(fieldMap);

        // Open data file for random-access reads
        String dataFileName = IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            RoaringDocValuesFormat.DATA_EXTENSION);

        IndexInput dataInput = state.directory.openInput(dataFileName, state.context);
        CodecUtil.checkIndexHeader(dataInput,
            RoaringDocValuesFormat.DATA_CODEC,
            RoaringDocValuesFormat.VERSION_CURRENT,
            RoaringDocValuesFormat.VERSION_CURRENT,
            state.segmentInfo.getId(),
            state.segmentSuffix);

        this.dataIn = dataInput;
    }

    /**
     * Returns the metadata for a field, or {@code null} if the field is not
     * indexed with the Roaring format.
     */
    public FieldMeta getFieldMeta(String fieldName) {
        return fields.get(fieldName);
    }

    /**
     * Deserializes and returns the Roaring Bitmap for a given field and ordinal.
     * <p>
     * This is the primary access method used by the aggregation layer.
     *
     * @param fieldName the field name
     * @param ordinal   the ordinal index
     * @return the deserialized RoaringBitmap
     * @throws IOException if reading fails
     * @throws IllegalArgumentException if the field or ordinal is invalid
     */
    public RoaringBitmap getRoaringBitmap(String fieldName, int ordinal) throws IOException {
        FieldMeta meta = fields.get(fieldName);
        if (meta == null) {
            throw new IllegalArgumentException("Field [" + fieldName + "] not found in Roaring index");
        }
        if (ordinal < 0 || ordinal >= meta.ordinalCount) {
            throw new IllegalArgumentException(
                "Ordinal [" + ordinal + "] out of range [0, " + meta.ordinalCount + ") for field [" + fieldName + "]");
        }

        long absoluteOffset = meta.dataStartOffset + meta.bitmapOffsets[ordinal];
        long nextOffset;
        if (ordinal + 1 < meta.ordinalCount) {
            nextOffset = meta.dataStartOffset + meta.bitmapOffsets[ordinal + 1];
        } else {
            nextOffset = meta.dataEndOffset;
        }
        int length = (int) (nextOffset - absoluteOffset);

        // Read the serialized bitmap bytes
        byte[] buf = new byte[length];
        synchronized (dataIn) {
            IndexInput clone = dataIn.clone();
            clone.seek(absoluteOffset);
            clone.readBytes(buf, 0, length);
        }

        // Deserialize
        RoaringBitmap bitmap = new RoaringBitmap();
        bitmap.deserialize(new DataInputStream(new ByteArrayInputStream(buf)));
        return bitmap;
    }

    /**
     * Returns the number of unique ordinals for a field.
     */
    public int getOrdinalCount(String fieldName) {
        FieldMeta meta = fields.get(fieldName);
        return meta != null ? meta.ordinalCount : 0;
    }

    /**
     * Resolves an ordinal to its term value.
     */
    public BytesRef lookupOrd(String fieldName, int ordinal) {
        FieldMeta meta = fields.get(fieldName);
        if (meta == null || ordinal < 0 || ordinal >= meta.ordinalCount) {
            return null;
        }
        return new BytesRef(meta.termBytes[ordinal]);
    }

    // ---- Standard DocValuesProducer API ----
    // These are required by the interface but the Roaring format is accessed
    // through the specialized getRoaringBitmap() method during aggregation.
    // For merge compatibility, getSortedSet() provides a minimal implementation.

    @Override
    public SortedSetDocValues getSortedSet(FieldInfo field) throws IOException {
        FieldMeta meta = fields.get(field.name);
        if (meta == null) {
            throw new IllegalArgumentException("No Roaring data for field: " + field.name);
        }

        // Load all bitmaps for this field to build a SortedSetDocValues view
        final RoaringBitmap[] bitmaps = new RoaringBitmap[meta.ordinalCount];
        for (int ord = 0; ord < meta.ordinalCount; ord++) {
            bitmaps[ord] = getRoaringBitmap(field.name, ord);
        }

        return new RoaringSortedSetDocValues(meta, bitmaps);
    }

    @Override
    public NumericDocValues getNumeric(FieldInfo field) throws IOException {
        return delegateProducer != null ? delegateProducer.getNumeric(field) : null;
    }

    @Override
    public BinaryDocValues getBinary(FieldInfo field) throws IOException {
        return delegateProducer != null ? delegateProducer.getBinary(field) : null;
    }

    @Override
    public SortedDocValues getSorted(FieldInfo field) throws IOException {
        return delegateProducer != null ? delegateProducer.getSorted(field) : null;
    }

    @Override
    public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
        return delegateProducer != null ? delegateProducer.getSortedNumeric(field) : null;
    }

    @Override
    public void checkIntegrity() throws IOException {
        if (delegateProducer != null) {
            delegateProducer.checkIntegrity();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (dataIn != null) {
                dataIn.close();
            }
        } finally {
            if (delegateProducer != null) {
                delegateProducer.close();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Inner class: a SortedSetDocValues view over Roaring Bitmaps
    // This is needed for merge compatibility and standard facet collectors.
    // -----------------------------------------------------------------------

    /**
     * A {@link SortedSetDocValues} implementation backed by Roaring Bitmaps.
     * <p>
     * This reconstructs the row-oriented view from the column-oriented bitmap
     * layout, enabling transparent compatibility with Lucene's merge logic and
     * any collector that expects standard SortedSetDocValues iteration.
     */
    private static class RoaringSortedSetDocValues extends SortedSetDocValues {
        private final FieldMeta meta;
        private final RoaringBitmap[] bitmaps;

        // Iteration state
        private int currentDoc = -1;
        private int[] currentOrds;
        private int ordIndex;
        private final RoaringBitmap allDocs;

        // Iterator over all docs that have at least one ordinal
        private final int[] allDocIds;
        private int allDocIndex;

        RoaringSortedSetDocValues(FieldMeta meta, RoaringBitmap[] bitmaps) {
            this.meta = meta;
            this.bitmaps = bitmaps;

            // Compute union of all bitmaps to find all docs with values
            allDocs = new RoaringBitmap();
            for (RoaringBitmap bm : bitmaps) {
                allDocs.or(bm);
            }
            allDocIds = allDocs.toArray();
            allDocIndex = 0;
        }

        @Override
        public int docValueCount() {
            return currentOrds != null ? currentOrds.length : 0;
        }

        @Override
        public long nextOrd() {
            if (currentOrds == null || ordIndex >= currentOrds.length) {
                return NO_MORE_ORDS;
            }
            return currentOrds[ordIndex++];
        }

        @Override
        public int docID() {
            return currentDoc;
        }

        @Override
        public int nextDoc() {
            if (allDocIndex >= allDocIds.length) {
                currentDoc = NO_MORE_DOCS;
                return NO_MORE_DOCS;
            }
            currentDoc = allDocIds[allDocIndex++];
            loadOrdsForCurrentDoc();
            return currentDoc;
        }

        @Override
        public int advance(int target) {
            while (allDocIndex < allDocIds.length && allDocIds[allDocIndex] < target) {
                allDocIndex++;
            }
            return nextDoc();
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
            if (!allDocs.contains(target)) {
                currentDoc = target;
                currentOrds = null;
                return false;
            }
            currentDoc = target;
            // Advance the index pointer
            while (allDocIndex < allDocIds.length && allDocIds[allDocIndex] < target) {
                allDocIndex++;
            }
            if (allDocIndex < allDocIds.length && allDocIds[allDocIndex] == target) {
                allDocIndex++;
            }
            loadOrdsForCurrentDoc();
            return true;
        }

        @Override
        public long cost() {
            return allDocIds.length;
        }

        @Override
        public BytesRef lookupOrd(long ord) throws IOException {
            int o = (int) ord;
            if (o < 0 || o >= meta.ordinalCount) {
                throw new IndexOutOfBoundsException("Ordinal " + o + " out of range");
            }
            return new BytesRef(meta.termBytes[o]);
        }

        @Override
        public long getValueCount() {
            return meta.ordinalCount;
        }

        private void loadOrdsForCurrentDoc() {
            // Find all ordinals for the current document
            java.util.List<Integer> ords = new java.util.ArrayList<>();
            for (int ord = 0; ord < bitmaps.length; ord++) {
                if (bitmaps[ord].contains(currentDoc)) {
                    ords.add(ord);
                }
            }
            currentOrds = new int[ords.size()];
            for (int i = 0; i < ords.size(); i++) {
                currentOrds[i] = ords.get(i);
            }
            ordIndex = 0;
        }
    }
}
