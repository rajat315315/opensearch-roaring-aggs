# opensearch-roaring-aggs

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.txt)
[![OpenSearch](https://img.shields.io/badge/OpenSearch-2.19.x-orange.svg)](https://opensearch.org/)

An OpenSearch plugin that provides **opt-in Roaring Bitmap indexing** for dramatically faster aggregations on high-cardinality multi-valued keyword fields.

Based on the proposal in [apache/lucene#16477](https://github.com/apache/lucene/issues/16477).

---

## Table of Contents

- [Problem](#problem)
- [Solution](#solution)
- [Performance](#performance)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration Reference](#configuration-reference)
- [How It Works](#how-it-works)
- [API Reference](#api-reference)
- [Compatibility](#compatibility)
- [Building from Source](#building-from-source)
- [Running Tests](#running-tests)
- [Uninstallation](#uninstallation)
- [Contributing](#contributing)
- [License](#license)

---

## Problem

OpenSearch relies on `SortedSetDocValues` for aggregating multi-valued keyword fields. For each query match, the aggregation engine:

1. Performs a **random-access lookup** into the DocValues column
2. Retrieves the segment ordinals for the document
3. Increments the corresponding bucket counts

For high-cardinality datasets (100K+ unique values) with high match densities (millions of docs), this creates **O(MatchingDocs) non-contiguous memory lookups**, causing:

- ❌ Severe L3 cache misses and memory latency
- ❌ Poor SIMD vectorization due to variable ordinals per document
- ❌ GC pressure from object allocation during collection

## Solution

This plugin transposes the field representation into **per-ordinal Roaring Bitmaps** at index time:

```
Traditional:  doc₀ → [ord₁, ord₃]    doc₁ → [ord₂]    doc₂ → [ord₁, ord₂]
Roaring:      ord₁ → {doc₀, doc₂}    ord₂ → {doc₁, doc₂}    ord₃ → {doc₀}
```

At query time, aggregation is performed using **bitwise AND + POPCNT** operations:

```
Count(ordinal_k) = POPCNT(QueryBitset AND OrdinalBitmap_k)
```

This replaces millions of random-access lookups with sequential, cache-friendly, hardware-accelerated bitmap intersections.

## Performance (1 Million Real Wikimedia Documents)

Below are empirical, uncached benchmark results measured on **1,000,000 real Wikipedia articles** comparing standard OpenSearch DocValues (`wiki_default`) against **Roaring DocValues & Aggregator** (`wiki_roaring`).

### 🖥️ Hardware & Environment Setup

| Component | Specification |
| :--- | :--- |
| **CPU** | Intel® Core™ i5-6200U CPU @ 2.30GHz (2 Cores / 4 Threads) |
| **RAM** | 16 GB DDR4 |
| **OS / Kernel** | Linux 7.0.0-28-generic (Ubuntu x86_64) |
| **OpenSearch Version** | OpenSearch 2.19.0 (Lucene 9.12.1) |
| **JVM** | Bundled OpenJDK 21.0.6 (1 GB Heap) |
| **Dataset** | 1,000,000 English Wikipedia Articles (`enwiki-20120502`) |
| **Query Cache** | Bypassed explicitly on all queries (`?request_cache=false`) |

---

### 💾 Index Disk Storage Comparison

| Index | Codec | Store Size (MB) | Store Size (Bytes) | Storage Difference |
| :--- | :--- | :-: | :-: | :-: |
| `wiki_default` | Standard `Lucene90` DocValues | **379.29 MB** | 397,714,096 bytes | Baseline |
| `wiki_roaring` | Opt-in `RoaringCodec` | **557.00 MB** | 584,056,095 bytes | `+46.9%` (stores explicit Bitmaps) |

> 💡 **Storage Trade-off**: Roaring Bitmaps pre-index compressed bitset containers (`.rvd`/`.rvm` files) for every term ordinal. Trading ~47% additional disk space unlocks up to **439x faster aggregation speed**.

---

### ⚡ Uncached Aggregation Latency & Speedup Benchmarks

| # | Benchmark Scenario | Field Type & Cardinality | Agg Size | Standard (`terms`) | Roaring (`roaring_terms`) | 🚀 Speedup |
| :-: | :--- | :--- | :-: | :-: | :-: | :-: |
| **1** | **Low Cardinality Field** | Single-Valued (`label`) | `size=10` | 490.77 ms (577ms OS) | **40.56 ms (17ms OS)** | **12.10x Faster** |
| **2** | **Multi-Valued Body Words** | Multi-Valued (`words`) | `size=10` | 6,818.71 ms (7,316ms OS) | **15.51 ms (13ms OS)** | **439.52x Faster** |
| **3** | **Multi-Valued Body Words** | Multi-Valued (`words`) | `size=100` | 7,647.21 ms (8,573ms OS) | **20.08 ms (23ms OS)** | **380.76x Faster** |
| **4** | **Multi-Valued Body Words** | Multi-Valued (`words`) | `size=500` | 7,587.67 ms (7,366ms OS) | **42.11 ms (22ms OS)** | **180.17x Faster** |
| **5** | **Document Title Terms** | High Cardinality (`doctitle`) | `size=10` | 2,661.20 ms (2,752ms OS) | **37.70 ms (15ms OS)** | **70.60x Faster** |
| **6** | **Document Title Terms** | High Cardinality (`doctitle`) | `size=100` | 2,826.59 ms (2,664ms OS) | **17.85 ms (12ms OS)** | **158.38x Faster** |
| **7** | **Filtered Query** | `label=LABEL_1` on `words` | `size=10` | 12.97 ms (15ms OS) | **4.68 ms (2ms OS)** | **2.77x Faster** |

---

### 🔑 Key Takeaways
1. **Unfiltered Multi-Valued Aggregations (439x Speedup)**: Standard Lucene DocValues takes **7.3 seconds** to iterate doc-by-doc over 1M documents, while `roaring_terms` finishes in **13 milliseconds** using hardware `POPCNT` vectorization.
2. **Agg Size Scaling (`size=10` vs `100` vs `500`)**: `roaring_terms` stays sub-45ms even when collecting top 500 buckets.
3. **Query Filtering (`QueryBitset AND OrdinalBitmap`)**: Filtered aggregations run in **4.68 ms** (vs 12.97 ms standard).

---

## Installation

### From Pre-built Release

```bash
# Download the plugin ZIP from the releases page
bin/opensearch-plugin install file:///path/to/opensearch-roaring-aggs-2.19.0.0.zip
```

### From Build Output

```bash
# Build the plugin first (see "Building from Source" below)
bin/opensearch-plugin install file:///path/to/opensearch-roaring-aggs/build/distributions/opensearch-roaring-aggs-2.19.0.0.zip
```

### Security Permissions

During installation, you will be prompted to grant security permissions. The plugin requires:

- **File I/O permissions**: For reading/writing the Roaring Bitmap index files (`.rvd`, `.rvm`)
- **Reflection permissions**: For RoaringBitmap library off-heap buffer operations

Type `y` to accept.

### Verify Installation

```bash
bin/opensearch-plugin list
```

You should see `opensearch-roaring-aggs` in the output.

### Restart OpenSearch

```bash
# Restart the node to load the plugin
sudo systemctl restart opensearch
# or
bin/opensearch
```

## Quick Start

### 1. Create an Index with the Roaring Codec

```json
PUT /products
{
  "settings": {
    "index.codec": "RoaringCodec",
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "name": { "type": "text" },
      "tags": { "type": "keyword" },
      "categories": { "type": "keyword" }
    }
  }
}
```

### 2. Index Some Documents

```json
POST /products/_bulk
{"index": {}}
{"name": "Laptop", "tags": ["electronics", "computers", "portable"], "categories": ["tech", "gadgets"]}
{"index": {}}
{"name": "Phone", "tags": ["electronics", "mobile", "portable"], "categories": ["tech", "communication"]}
{"index": {}}
{"name": "Desk", "tags": ["furniture", "office"], "categories": ["home", "work"]}
{"index": {}}
{"name": "Chair", "tags": ["furniture", "office", "ergonomic"], "categories": ["home", "work"]}
{"index": {}}
{"name": "Headphones", "tags": ["electronics", "audio", "portable"], "categories": ["tech", "entertainment"]}
```

### 3. Run a Roaring Terms Aggregation

```json
POST /products/_search
{
  "size": 0,
  "aggs": {
    "top_tags": {
      "roaring_terms": {
        "field": "tags",
        "size": 5
      }
    }
  }
}
```

### Expected Response

```json
{
  "aggregations": {
    "top_tags": {
      "buckets": [
        { "key": "electronics", "doc_count": 3 },
        { "key": "portable", "doc_count": 3 },
        { "key": "furniture", "doc_count": 2 },
        { "key": "office", "doc_count": 2 },
        { "key": "audio", "doc_count": 1 }
      ]
    }
  }
}
```

### 4. Use with Filtered Queries

The real power shows with filtered queries — block-level skipping bypasses empty 64K doc chunks entirely:

```json
POST /products/_search
{
  "size": 0,
  "query": {
    "term": { "categories": "tech" }
  },
  "aggs": {
    "tech_tags": {
      "roaring_terms": {
        "field": "tags",
        "size": 10
      }
    }
  }
}
```

## Configuration Reference

### Index Settings

| Setting | Default | Description |
|---|---|---|
| `index.codec` | `default` | Set to `RoaringCodec` to enable Roaring Bitmap DocValues for all keyword fields in the index |

### Aggregation Parameters

| Parameter | Required | Default | Description |
|---|---|---|---|
| `field` | ✅ | — | The keyword field to aggregate on. Must be in an index using `RoaringCodec`. |
| `size` | ❌ | `10` | Number of top buckets to return, ordered by `doc_count` descending. |

## How It Works

### Index Time

1. Documents are indexed normally via the standard OpenSearch APIs
2. The `RoaringDocValuesConsumer` intercepts `SortedSetDocValues` writes
3. Instead of storing `docID → ordinals[]`, it transposes into `ordinal → RoaringBitmap(docIDs)`
4. Each ordinal's bitmap is run-length optimized and serialized to disk:
   - `.rvm` (metadata): field info, ordinal count, offset table, term dictionary
   - `.rvd` (data): concatenated serialized Roaring Bitmaps

### Query Time

1. OpenSearch executes the boolean query and collects matching `docIDs`
2. The `RoaringTermsAggregator` converts matched docIDs into a `RoaringBitmap` (Q)
3. For each ordinal k, it computes `|Q ∩ Bitmap_k|` using `RoaringBitmap.andCardinality()`
4. The RoaringBitmap library internally uses:
   - **Container-level (64K-block) skipping**: Empty blocks are skipped in O(1)
   - **POPCNT acceleration**: HotSpot intrinsifies `Long.bitCount()` to the x86 `POPCNT` instruction
5. Top-N ordinals are selected via a min-heap and resolved to term values

### Architecture Diagram

```
┌──────────────────────────────────────────────────────┐
│                   OpenSearch Node                     │
├──────────────────────────────────────────────────────┤
│                                                       │
│  ┌─────────────┐    ┌──────────────────────────────┐ │
│  │  REST API   │───▶│  RoaringTermsAggregation     │ │
│  │  Request    │    │  Builder (parse field, size)  │ │
│  └─────────────┘    └──────────┬───────────────────┘ │
│                                │                      │
│                    ┌───────────▼───────────────────┐  │
│                    │  RoaringTermsAggregator        │  │
│                    │  ┌───────────────────────┐     │  │
│                    │  │ 1. Collect docIDs      │     │  │
│                    │  │ 2. Build query bitmap  │     │  │
│                    │  │ 3. AND + POPCNT        │     │  │
│                    │  │ 4. Top-N selection     │     │  │
│                    │  └───────────────────────┘     │  │
│                    └───────────┬───────────────────┘  │
│                                │                      │
│                    ┌───────────▼───────────────────┐  │
│                    │  RoaringDocValuesProducer      │  │
│                    │  (reads .rvd + .rvm files)     │  │
│                    └──────────────────────────────┘  │
│                                                       │
│  Index Time:                                          │
│  ┌────────────────────────────────────────────────┐  │
│  │  RoaringDocValuesConsumer                       │  │
│  │  doc→ordinals[] ──transpose──▶ ord→Bitmap(docs) │  │
│  │  Write .rvd (data) + .rvm (metadata)            │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

## API Reference

### `roaring_terms` Aggregation

A terms aggregation optimized for Roaring Bitmap-indexed keyword fields.

**Request:**
```json
{
  "aggs": {
    "<aggregation_name>": {
      "roaring_terms": {
        "field": "<field_name>",
        "size": <number>
      }
    }
  }
}
```

**Response:**
```json
{
  "aggregations": {
    "<aggregation_name>": {
      "buckets": [
        {
          "key": "<term_value>",
          "doc_count": <count>
        }
      ]
    }
  }
}
```

## Compatibility

| Plugin Version | OpenSearch Version | Java Version | Status |
|---|---|---|---|
| 2.19.0.0 | 2.19.x | JDK 17+ | ✅ Supported |

## Building from Source

### Prerequisites

- **JDK 17** or later
- **Git**

### Build Steps

```bash
# Clone the repository
git clone https://github.com/rajat315315/opensearch-roaring-aggs.git
cd opensearch-roaring-aggs

# Build the plugin (includes running tests)
./gradlew build

# Build just the plugin ZIP (skip tests)
./gradlew pluginZip -x test
```

The plugin ZIP will be located at:
```
build/distributions/opensearch-roaring-aggs-2.19.0.0.zip
```

## Running Tests

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "org.opensearch.plugin.roaring.codec.RoaringDocValuesFormatTests"

# Run with verbose output
./gradlew test --info
```

### Test Coverage

| Component | Test Class | Coverage |
|---|---|---|
| BitsetUtil | `BitsetUtilTests` | AND+POPCNT, empty blocks, edge cases |
| DocValues Format | `RoaringDocValuesFormatTests` | Round-trip, multi-valued, high cardinality |
| Aggregation | `RoaringTermsAggregatorTests` | Builder, buckets, merge logic |

## Uninstallation

```bash
# Remove the plugin
bin/opensearch-plugin remove opensearch-roaring-aggs

# Restart OpenSearch
sudo systemctl restart opensearch
```

> ⚠️ **Warning:** After uninstalling the plugin, indices created with `index.codec: RoaringCodec` will not be readable. Either reindex the data with the default codec before uninstalling, or keep the plugin installed.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the [Apache License 2.0](LICENSE.txt).

---

## Acknowledgments

- [Apache Lucene](https://lucene.apache.org/) — The search library powering OpenSearch
- [RoaringBitmap](https://roaringbitmap.org/) — The compressed bitmap library used for bitmap operations
- [OpenSearch](https://opensearch.org/) — The open-source search and analytics suite
- [Lucene Issue #16477](https://github.com/apache/lucene/issues/16477) — The original proposal for this approach
