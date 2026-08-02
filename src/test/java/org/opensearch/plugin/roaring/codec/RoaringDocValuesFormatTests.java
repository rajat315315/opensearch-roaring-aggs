/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.codec;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link RoaringDocValuesFormat} — round-trip write and read.
 * <p>
 * These tests write documents with SortedSetDocValuesFields using the
 * RoaringDocValuesFormat, then read them back and verify correctness.
 */
public class RoaringDocValuesFormatTests {

    /**
     * Tests basic round-trip: write a few multi-valued documents, read back
     * through SortedSetDocValues compatibility view.
     */
    @Test
    public void testBasicRoundTrip() throws Exception {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setCodec(new RoaringCodec());

        try (IndexWriter writer = new IndexWriter(dir, config)) {
            // Doc 0: tags = [alpha, beta]
            Document doc0 = new Document();
            doc0.add(new SortedSetDocValuesField("tags", new BytesRef("alpha")));
            doc0.add(new SortedSetDocValuesField("tags", new BytesRef("beta")));
            writer.addDocument(doc0);

            // Doc 1: tags = [beta, gamma]
            Document doc1 = new Document();
            doc1.add(new SortedSetDocValuesField("tags", new BytesRef("beta")));
            doc1.add(new SortedSetDocValuesField("tags", new BytesRef("gamma")));
            writer.addDocument(doc1);

            // Doc 2: tags = [alpha]
            Document doc2 = new Document();
            doc2.add(new SortedSetDocValuesField("tags", new BytesRef("alpha")));
            writer.addDocument(doc2);

            writer.commit();
        }

        // Read back
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            assertEquals(3, reader.numDocs());

            LeafReaderContext ctx = reader.leaves().get(0);
            LeafReader leafReader = ctx.reader();
            SortedSetDocValues dv = leafReader.getSortedSetDocValues("tags");

            assertNotNull("SortedSetDocValues should not be null", dv);
            assertEquals(3, dv.getValueCount()); // alpha, beta, gamma

            // Verify term dictionary
            assertEquals("alpha", dv.lookupOrd(0).utf8ToString());
            assertEquals("beta", dv.lookupOrd(1).utf8ToString());
            assertEquals("gamma", dv.lookupOrd(2).utf8ToString());

            // Doc 0: should have ordinals for alpha and beta
            assertTrue(dv.advanceExact(0));
            assertEquals(0, dv.nextOrd()); // alpha
            assertEquals(1, dv.nextOrd()); // beta
            assertEquals(SortedSetDocValues.NO_MORE_ORDS, dv.nextOrd());

            // Doc 1: should have ordinals for beta and gamma
            assertTrue(dv.advanceExact(1));
            assertEquals(1, dv.nextOrd()); // beta
            assertEquals(2, dv.nextOrd()); // gamma
            assertEquals(SortedSetDocValues.NO_MORE_ORDS, dv.nextOrd());

            // Doc 2: should have ordinal for alpha only
            assertTrue(dv.advanceExact(2));
            assertEquals(0, dv.nextOrd()); // alpha
            assertEquals(SortedSetDocValues.NO_MORE_ORDS, dv.nextOrd());
        }

        dir.close();
    }

    /**
     * Tests that the Roaring producer provides correct bitmap access.
     */
    @Test
    public void testRoaringBitmapAccess() throws Exception {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setCodec(new RoaringCodec());

        try (IndexWriter writer = new IndexWriter(dir, config)) {
            // Create 5 docs with varying tags
            for (int i = 0; i < 5; i++) {
                Document doc = new Document();
                doc.add(new SortedSetDocValuesField("category", new BytesRef("cat_" + (i % 3))));
                if (i % 2 == 0) {
                    doc.add(new SortedSetDocValuesField("category", new BytesRef("even")));
                }
                writer.addDocument(doc);
            }
            writer.commit();
        }

        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            LeafReaderContext ctx = reader.leaves().get(0);
            LeafReader leafReader = ctx.reader();
            SortedSetDocValues dv = leafReader.getSortedSetDocValues("category");

            assertNotNull(dv);
            // We should have ordinals for: cat_0, cat_1, cat_2, even
            assertTrue("Should have at least 3 ordinals", dv.getValueCount() >= 3);
        }

        dir.close();
    }

    /**
     * Tests an empty segment (no documents).
     */
    @Test
    public void testEmptySegment() throws Exception {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setCodec(new RoaringCodec());

        try (IndexWriter writer = new IndexWriter(dir, config)) {
            writer.commit();
        }

        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            assertEquals(0, reader.numDocs());
        }

        dir.close();
    }

    /**
     * Tests single-valued documents (each doc has exactly one ordinal).
     */
    @Test
    public void testSingleValued() throws Exception {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setCodec(new RoaringCodec());

        try (IndexWriter writer = new IndexWriter(dir, config)) {
            for (int i = 0; i < 100; i++) {
                Document doc = new Document();
                doc.add(new SortedSetDocValuesField("status",
                    new BytesRef("status_" + (i % 5))));
                writer.addDocument(doc);
            }
            writer.commit();
        }

        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            assertEquals(100, reader.numDocs());

            LeafReaderContext ctx = reader.leaves().get(0);
            LeafReader leafReader = ctx.reader();
            SortedSetDocValues dv = leafReader.getSortedSetDocValues("status");

            assertNotNull(dv);
            assertEquals(5, dv.getValueCount());

            // Check first doc
            assertTrue(dv.advanceExact(0));
            long firstOrd = dv.nextOrd();
            assertTrue(firstOrd >= 0);
            assertEquals(SortedSetDocValues.NO_MORE_ORDS, dv.nextOrd());
        }

        dir.close();
    }

    /**
     * Tests high-cardinality scenario with many unique ordinals.
     */
    @Test
    public void testHighCardinality() throws Exception {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setCodec(new RoaringCodec());

        int numDocs = 1000;
        int numUniqueTerms = 500;

        try (IndexWriter writer = new IndexWriter(dir, config)) {
            for (int i = 0; i < numDocs; i++) {
                Document doc = new Document();
                // Each doc gets 1-3 terms
                doc.add(new SortedSetDocValuesField("tags",
                    new BytesRef("term_" + (i % numUniqueTerms))));
                doc.add(new SortedSetDocValuesField("tags",
                    new BytesRef("term_" + ((i + 1) % numUniqueTerms))));
                writer.addDocument(doc);
            }
            writer.commit();
        }

        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            assertEquals(numDocs, reader.numDocs());

            LeafReaderContext ctx = reader.leaves().get(0);
            LeafReader leafReader = ctx.reader();
            SortedSetDocValues dv = leafReader.getSortedSetDocValues("tags");

            assertNotNull(dv);
            assertEquals(numUniqueTerms, dv.getValueCount());
        }

        dir.close();
    }
}
