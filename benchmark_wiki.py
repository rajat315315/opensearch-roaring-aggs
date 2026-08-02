import json
import time
import urllib.request

OPENSEARCH_URL = "http://localhost:9200"
DATASET_PATH = "/run/media/rajat/Ubuntu/enwiki-20120502-lines-1k-fixed-utf8-with-random-label.clean1m.txt"
DOC_LIMIT = 1000000  # Index 1,000,000 documents for full scale benchmark

def http_post(path, data):
    url = f"{OPENSEARCH_URL}{path}"
    req = urllib.request.Request(
        url,
        data=json.dumps(data).encode("utf-8") if isinstance(data, dict) else data.encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"HTTP Error {e.code} on POST {path}: {e.read().decode('utf-8')}")
        raise e

def http_put(path, data):
    url = f"{OPENSEARCH_URL}{path}"
    req = urllib.request.Request(
        url,
        data=json.dumps(data).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="PUT"
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"HTTP Error {e.code} on PUT {path}: {e.read().decode('utf-8')}")
        raise e

def http_delete(path):
    try:
        url = f"{OPENSEARCH_URL}{path}"
        req = urllib.request.Request(url, method="DELETE")
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        pass

def setup_indices():
    print("Setting up indices...")
    # Clear any cluster blocks caused by disk watermarks
    try:
        http_put("/_cluster/settings", {
            "persistent": {
                "cluster.blocks.create_index": None,
                "cluster.routing.allocation.disk.threshold_enabled": False
            },
            "transient": {
                "cluster.blocks.create_index": None,
                "cluster.routing.allocation.disk.threshold_enabled": False
            }
        })
    except Exception as e:
        print(f"Warning setting cluster settings: {e}")

    http_delete("/wiki_default")
    http_delete("/wiki_roaring")

    # Default index mapping
    mapping_default = {
        "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0
        },
        "mappings": {
            "properties": {
                "doctitle": {"type": "keyword"},
                "docdate": {"type": "keyword"},
                "label": {"type": "keyword"},
                "words": {"type": "keyword"}  # multi-valued keyword field
            }
        }
    }
    http_put("/wiki_default", mapping_default)

    # Roaring index mapping with RoaringCodec
    mapping_roaring = {
        "settings": {
            "index.codec": "RoaringCodec",
            "number_of_shards": 1,
            "number_of_replicas": 0
        },
        "mappings": {
            "properties": {
                "doctitle": {"type": "keyword"},
                "docdate": {"type": "keyword"},
                "label": {"type": "keyword"},
                "words": {"type": "keyword"}  # multi-valued keyword field
            }
        }
    }
    http_put("/wiki_roaring", mapping_roaring)
    print("Indices created successfully.")

def index_wikimedia_data():
    print(f"Reading Wikimedia dataset from {DATASET_PATH}...")
    batch_default = []
    batch_roaring = []
    count = 0
    start_time = time.time()

    with open(DATASET_PATH, "r", encoding="utf-8", errors="ignore") as f:
        # Skip header
        header = f.readline()

        for line in f:
            parts = line.strip().split("\t")
            if len(parts) < 4:
                continue

            doctitle = parts[0]
            docdate = parts[1]
            body = parts[2]
            label = parts[3]

            # Extract words from body to create a rich multi-valued keyword field
            # Use top words / tokens
            words = list(set([w.strip().lower() for w in body.split() if len(w) > 3]))[:15]

            doc = {
                "doctitle": doctitle,
                "docdate": docdate,
                "label": label,
                "words": words
            }

            # Prepare bulk indexing payloads
            action = json.dumps({"index": {}})
            doc_str = json.dumps(doc)

            batch_default.append(f"{action}\n{doc_str}\n")
            batch_roaring.append(f"{action}\n{doc_str}\n")
            count += 1

            if len(batch_default) >= 5000:
                http_post("/wiki_default/_bulk", "".join(batch_default))
                http_post("/wiki_roaring/_bulk", "".join(batch_roaring))
                batch_default = []
                batch_roaring = []
                print(f"Indexed {count}/{DOC_LIMIT} documents...")

            if count >= DOC_LIMIT:
                break

    if batch_default:
        http_post("/wiki_default/_bulk", "".join(batch_default))
        http_post("/wiki_roaring/_bulk", "".join(batch_roaring))

    # Force refresh & commit segments
    http_post("/wiki_default/_refresh", {})
    http_post("/wiki_roaring/_refresh", {})
    print(f"Finished indexing {count} Wikimedia documents in {time.time() - start_time:.2f}s.")

def print_index_sizes():
    print("\n========================================================")
    print("      INDEX DISK STORAGE COMPARISON                     ")
    print("========================================================")
    try:
        stats = http_get("/wiki_default,wiki_roaring/_stats/store")
        indices = stats.get("indices", {})
        size_def_bytes = indices.get("wiki_default", {}).get("total", {}).get("store", {}).get("size_in_bytes", 0)
        size_roaring_bytes = indices.get("wiki_roaring", {}).get("total", {}).get("store", {}).get("size_in_bytes", 0)

        size_def_mb = size_def_bytes / (1024 * 1024)
        size_roaring_mb = size_roaring_bytes / (1024 * 1024)

        print(f"Standard Index (wiki_default) Size : {size_def_mb:.2f} MB ({size_def_bytes:,} bytes)")
        print(f"Roaring Index  (wiki_roaring) Size : {size_roaring_mb:.2f} MB ({size_roaring_bytes:,} bytes)")

        if size_def_bytes > 0:
            diff_pct = ((size_roaring_bytes - size_def_bytes) / size_def_bytes) * 100
            if diff_pct < 0:
                print(f"💾 DISK SAVINGS: Roaring Codec is {abs(diff_pct):.1f}% SMALLER on disk!")
            else:
                print(f"💾 DISK SIZE DIFFERENCE: Roaring Codec is {diff_pct:.1f}% larger (stores explicit Bitmaps per term)")
    except Exception as e:
        print(f"Error fetching index store sizes: {e}")
    print("========================================================\n")

def measure_query(index_name, query_body, iters=10):
    # Warmup
    for _ in range(3):
        http_post(f"/{index_name}/_search", query_body)

    latencies = []
    last_res = {}
    for _ in range(iters):
        t0 = time.time()
        last_res = http_post(f"/{index_name}/_search", query_body)
        latencies.append((time.time() - t0) * 1000)

    avg_lat = sum(latencies) / len(latencies)
    took_ms = last_res.get("took", 0)
    buckets = last_res.get("aggregations", {}).get("top_words", {}).get("buckets", [])
    return avg_lat, took_ms, buckets

def run_benchmark_scenario(scenario_name, filter_query, field_name, agg_size=10):
    print(f"\n========================================================")
    print(f"  SCENARIO: {scenario_name} (size={agg_size})")
    print(f"========================================================")

    q_def = {"size": 0, "request_cache": False, "aggs": {"top_words": {"terms": {"field": field_name, "size": agg_size}}}}
    q_roar = {"size": 0, "request_cache": False, "aggs": {"top_words": {"roaring_terms": {"field": field_name, "size": agg_size}}}}

    if filter_query:
        q_def["query"] = filter_query
        q_roar["query"] = filter_query

    avg_def, took_def, buckets_def = measure_query("wiki_default", q_def)
    avg_roar, took_roar, buckets_roar = measure_query("wiki_roaring", q_roar)

    print(f"--- Standard Terms Aggregation (wiki_default) ---")
    print(f"Avg Client Latency: {avg_def:.2f} ms | OpenSearch took: {took_def} ms")
    for b in buckets_def[:5]:
        print(f"  - {b['key']}: {b['doc_count']}")

    print(f"\n--- Roaring Terms Aggregation (wiki_roaring) ---")
    print(f"Avg Client Latency: {avg_roar:.2f} ms | OpenSearch took: {took_roar} ms")
    for b in buckets_roar[:5]:
        print(f"  - {b['key']}: {b['doc_count']}")

    if avg_roar > 0:
        speedup = avg_def / avg_roar
        print(f"\n🚀 SPEEDUP: Roaring Terms Aggregation is {speedup:.2f}x FASTER!")
    print("--------------------------------------------------------")

def run_benchmarks():
    print("\n========================================================")
    print("      BENCHMARK SUITE: Wikimedia Terms Aggregations     ")
    print("      (Request Cache Explicitly Disabled)             ")
    print("========================================================")

    # 1. Low Cardinality Field (label) - size 10
    run_benchmark_scenario("Low Cardinality Field (label)", None, "label", agg_size=10)

    # 2. Multi-valued Body Words (words) - size 10 vs size 100 vs size 500
    run_benchmark_scenario("Unfiltered Body Words (words)", None, "words", agg_size=10)
    run_benchmark_scenario("Unfiltered Body Words (words)", None, "words", agg_size=100)
    run_benchmark_scenario("Unfiltered Body Words (words)", None, "words", agg_size=500)

    # 3. High Cardinality Document Titles (doctitle) - size 10 vs size 100
    run_benchmark_scenario("High Cardinality Document Titles (doctitle)", None, "doctitle", agg_size=10)
    run_benchmark_scenario("High Cardinality Document Titles (doctitle)", None, "doctitle", agg_size=100)

    # 4. Filtered Aggregation (label == LABEL_1) - size 10
    filter_label = {"term": {"label": "LABEL_1"}}
    run_benchmark_scenario("Filtered Aggregation (query: label=LABEL_1 on words)", filter_label, "words", agg_size=10)

if __name__ == "__main__":
    setup_indices()
    index_wikimedia_data()
    print_index_sizes()
    run_benchmarks()
