/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.roaring.util;

/**
 * Hardware-accelerated bitwise AND + population count utilities.
 * <p>
 * The primary method {@link #bitwiseAndPopCount(long[], long[], int)} computes
 * {@code POPCNT(a[i] AND b[i])} across a pair of {@code long[]} blocks and
 * returns the total bit count. HotSpot's C2 compiler intrinsifies
 * {@link Long#bitCount(long)} into the x86 {@code POPCNT} instruction on
 * supported hardware, giving near-native throughput without JNI or the
 * Panama Vector API.
 */
public final class BitsetUtil {

    private BitsetUtil() {
        // utility class
    }

    /**
     * Computes the population count of the bitwise AND of two long arrays.
     * <p>
     * For each position {@code i} in {@code [0, length)}, this computes
     * {@code Long.bitCount(a[i] & b[i])} and returns the sum.
     *
     * @param a      first operand block (query bitset chunk)
     * @param b      second operand block (category/ordinal bitset chunk)
     * @param length number of longs to process (must be &le; min(a.length, b.length))
     * @return total number of set bits in the intersection
     */
    public static long bitwiseAndPopCount(long[] a, long[] b, int length) {
        long count = 0;
        // Process in groups of 4 for ILP (instruction-level parallelism).
        // HotSpot will unroll and pipeline the POPCNT instructions.
        int i = 0;
        int limit = length - 3;
        for (; i < limit; i += 4) {
            count += Long.bitCount(a[i] & b[i])
                   + Long.bitCount(a[i + 1] & b[i + 1])
                   + Long.bitCount(a[i + 2] & b[i + 2])
                   + Long.bitCount(a[i + 3] & b[i + 3]);
        }
        for (; i < length; i++) {
            count += Long.bitCount(a[i] & b[i]);
        }
        return count;
    }

    /**
     * Computes the population count of a single long array.
     *
     * @param a      the long array
     * @param length number of longs to process
     * @return total number of set bits
     */
    public static long popCount(long[] a, int length) {
        long count = 0;
        for (int i = 0; i < length; i++) {
            count += Long.bitCount(a[i]);
        }
        return count;
    }

    /**
     * Checks whether a 64K-document block (represented as 1024 longs) is entirely empty.
     *
     * @param block the block of 1024 longs representing 65536 document bits
     * @return {@code true} if all bits are zero
     */
    public static boolean isBlockEmpty(long[] block) {
        // OR all words together – if the result is zero the block is empty.
        // This is branch-free and auto-vectorizable by C2.
        long combined = 0;
        for (long word : block) {
            combined |= word;
        }
        return combined == 0L;
    }

    /**
     * Number of longs in a 64K-document block (65536 bits / 64 bits per long).
     */
    public static final int BLOCK_LONGS = 1024;

    /**
     * Number of documents per block (2^16 = 65536).
     */
    public static final int BLOCK_SIZE = 65536;
}
