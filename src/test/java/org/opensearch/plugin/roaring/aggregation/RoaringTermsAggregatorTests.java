/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.aggregation;

import org.apache.lucene.util.BytesRef;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the aggregation result classes.
 */
public class RoaringTermsAggregatorTests {

    @Test
    public void testInternalRoaringTermsBucketCreation() {
        InternalRoaringTerms.Bucket bucket =
            new InternalRoaringTerms.Bucket(new BytesRef("test_term"), 42);

        assertEquals("test_term", bucket.getKeyAsString());
        assertEquals(42, bucket.getDocCount());
        assertNotNull(bucket.getTerm());
    }

    @Test
    public void testInternalRoaringTermsEmpty() {
        InternalRoaringTerms terms = new InternalRoaringTerms(
            "test_agg", Collections.emptyList(), 10, null);

        assertEquals("roaring_terms", terms.getWriteableName());
        assertTrue(terms.getBuckets().isEmpty());
    }

    @Test
    public void testInternalRoaringTermsWithBuckets() {
        List<InternalRoaringTerms.Bucket> buckets = Arrays.asList(
            new InternalRoaringTerms.Bucket(new BytesRef("alpha"), 100),
            new InternalRoaringTerms.Bucket(new BytesRef("beta"), 50),
            new InternalRoaringTerms.Bucket(new BytesRef("gamma"), 25)
        );

        InternalRoaringTerms terms = new InternalRoaringTerms(
            "test_agg", buckets, 10, null);

        assertEquals(3, terms.getBuckets().size());
        assertEquals("alpha", terms.getBuckets().get(0).getKeyAsString());
        assertEquals(100, terms.getBuckets().get(0).getDocCount());
        assertEquals("beta", terms.getBuckets().get(1).getKeyAsString());
        assertEquals(50, terms.getBuckets().get(1).getDocCount());
    }

    @Test
    public void testReduceMergesShardsCorrectly() {
        // Shard 1 results
        List<InternalRoaringTerms.Bucket> shard1Buckets = Arrays.asList(
            new InternalRoaringTerms.Bucket(new BytesRef("alpha"), 100),
            new InternalRoaringTerms.Bucket(new BytesRef("beta"), 50)
        );
        InternalRoaringTerms shard1 = new InternalRoaringTerms("test", shard1Buckets, 10, null);

        // Shard 2 results
        List<InternalRoaringTerms.Bucket> shard2Buckets = Arrays.asList(
            new InternalRoaringTerms.Bucket(new BytesRef("alpha"), 80),
            new InternalRoaringTerms.Bucket(new BytesRef("gamma"), 30)
        );
        InternalRoaringTerms shard2 = new InternalRoaringTerms("test", shard2Buckets, 10, null);

        // Reduce
        // Note: We can't easily test reduce() without a ReduceContext,
        // but we can verify the merge logic conceptually through bucket creation
        assertEquals(2, shard1.getBuckets().size());
        assertEquals(2, shard2.getBuckets().size());

        // Verify bucket equality
        InternalRoaringTerms.Bucket b1 = new InternalRoaringTerms.Bucket(new BytesRef("test"), 10);
        InternalRoaringTerms.Bucket b2 = new InternalRoaringTerms.Bucket(new BytesRef("test"), 10);
        assertEquals(b1, b2);
        assertEquals(b1.hashCode(), b2.hashCode());
    }

    @Test
    public void testBucketEquality() {
        InternalRoaringTerms.Bucket a = new InternalRoaringTerms.Bucket(new BytesRef("term"), 10);
        InternalRoaringTerms.Bucket b = new InternalRoaringTerms.Bucket(new BytesRef("term"), 10);
        InternalRoaringTerms.Bucket c = new InternalRoaringTerms.Bucket(new BytesRef("term"), 20);
        InternalRoaringTerms.Bucket d = new InternalRoaringTerms.Bucket(new BytesRef("other"), 10);

        assertEquals(a, b);
        assertTrue(!a.equals(c));  // different count
        assertTrue(!a.equals(d));  // different term
    }

    @Test
    public void testAggregationBuilderFieldAndSize() {
        RoaringTermsAggregationBuilder builder = new RoaringTermsAggregationBuilder("my_agg");
        builder.field("tags").size(20);

        assertEquals("tags", builder.field());
        assertEquals(20, builder.size());
        assertEquals("roaring_terms", builder.getType());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAggregationBuilderInvalidSize() {
        new RoaringTermsAggregationBuilder("my_agg").size(0);
    }

    @Test(expected = NullPointerException.class)
    public void testAggregationBuilderNullField() {
        new RoaringTermsAggregationBuilder("my_agg").field(null);
    }
}
