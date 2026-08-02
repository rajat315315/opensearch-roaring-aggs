/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link BitsetUtil}.
 */
public class BitsetUtilTests {

    @Test
    public void testBitwiseAndPopCountAllOnes() {
        long[] a = new long[4];
        long[] b = new long[4];
        java.util.Arrays.fill(a, -1L); // all bits set
        java.util.Arrays.fill(b, -1L);

        long result = BitsetUtil.bitwiseAndPopCount(a, b, 4);
        // 4 longs × 64 bits = 256
        assertEquals(256, result);
    }

    @Test
    public void testBitwiseAndPopCountAllZeros() {
        long[] a = new long[4];
        long[] b = new long[4];

        long result = BitsetUtil.bitwiseAndPopCount(a, b, 4);
        assertEquals(0, result);
    }

    @Test
    public void testBitwiseAndPopCountDisjoint() {
        long[] a = {0xAAAAAAAAAAAAAAAAL, 0xAAAAAAAAAAAAAAAAL};
        long[] b = {0x5555555555555555L, 0x5555555555555555L};

        // a and b have no bits in common
        long result = BitsetUtil.bitwiseAndPopCount(a, b, 2);
        assertEquals(0, result);
    }

    @Test
    public void testBitwiseAndPopCountPartialOverlap() {
        long[] a = {0xFFL}; // lower 8 bits set
        long[] b = {0x0FL}; // lower 4 bits set

        long result = BitsetUtil.bitwiseAndPopCount(a, b, 1);
        assertEquals(4, result); // 0xFF & 0x0F = 0x0F → 4 bits
    }

    @Test
    public void testBitwiseAndPopCountLargeArray() {
        int size = 1024; // one full 64K block
        long[] a = new long[size];
        long[] b = new long[size];
        java.util.Arrays.fill(a, -1L);
        java.util.Arrays.fill(b, -1L);

        long result = BitsetUtil.bitwiseAndPopCount(a, b, size);
        assertEquals(65536, result); // 1024 × 64
    }

    @Test
    public void testBitwiseAndPopCountOddLength() {
        // Test with length not divisible by 4 to exercise the remainder loop
        long[] a = new long[7];
        long[] b = new long[7];
        java.util.Arrays.fill(a, -1L);
        java.util.Arrays.fill(b, -1L);

        long result = BitsetUtil.bitwiseAndPopCount(a, b, 7);
        assertEquals(7 * 64, result);
    }

    @Test
    public void testPopCount() {
        long[] a = {0xFFL, 0xFFL, 0x0L};
        long result = BitsetUtil.popCount(a, 3);
        assertEquals(16, result); // 8 + 8 + 0
    }

    @Test
    public void testIsBlockEmptyTrue() {
        long[] block = new long[BitsetUtil.BLOCK_LONGS];
        assertTrue(BitsetUtil.isBlockEmpty(block));
    }

    @Test
    public void testIsBlockEmptyFalse() {
        long[] block = new long[BitsetUtil.BLOCK_LONGS];
        block[500] = 1L;
        assertFalse(BitsetUtil.isBlockEmpty(block));
    }

    @Test
    public void testIsBlockEmptyLastWord() {
        long[] block = new long[BitsetUtil.BLOCK_LONGS];
        block[BitsetUtil.BLOCK_LONGS - 1] = 1L;
        assertFalse(BitsetUtil.isBlockEmpty(block));
    }

    @Test
    public void testConstants() {
        assertEquals(1024, BitsetUtil.BLOCK_LONGS);
        assertEquals(65536, BitsetUtil.BLOCK_SIZE);
        assertEquals(BitsetUtil.BLOCK_LONGS * 64, BitsetUtil.BLOCK_SIZE);
    }
}
