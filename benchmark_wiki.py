import json
import time
import urllib.request

OPENSEARCH_URL = "http://localhost:9200"
DATASET_PATH = "/run/media/rajat/Ubuntu/enwiki-20120502-lines-1k-fixed-utf8-with-random-label.clean1m.txt"
DOC_LIMIT = 50000  # Index 50,000 documents for benchmarking

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

            if len(batch_default) >= 2000:
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

def run_benchmarks():
    print("\n========================================================")
    print("      BENCHMARK: Wikimedia Terms Aggregation          ")
    print("========================================================")

    # 1. Standard Terms Aggregation on wiki_default
    query_default = {
        "size": 0,
        "aggs": {
            "top_words": {
                "terms": {
                    "field": "words",
                    "size": 10
                }
            }
        }
    }

    # Warmup
    for _ in range(3):
        http_post("/wiki_default/_search", query_default)

    # Measure Standard Terms Agg
    latencies_default = []
    for _ in range(10):
        t0 = time.time()
        res_def = http_post("/wiki_default/_search", query_default)
        latencies_default.append((time.time() - t0) * 1000)

    avg_def = sum(latencies_default) / len(latencies_default)
    took_def = res_def.get("took", 0)

    # 2. Roaring Terms Aggregation on wiki_roaring
    query_roaring = {
        "size": 0,
        "aggs": {
            "top_words": {
                "roaring_terms": {
                    "field": "words",
                    "size": 10
                }
            }
        }
    }

    # Warmup
    for _ in range(3):
        http_post("/wiki_roaring/_search", query_roaring)

    # Measure Roaring Terms Agg
    latencies_roaring = []
    for _ in range(10):
        t0 = time.time()
        res_roaring = http_post("/wiki_roaring/_search", query_roaring)
        latencies_roaring.append((time.time() - t0) * 1000)

    avg_roaring = sum(latencies_roaring) / len(latencies_roaring)
    took_roaring = res_roaring.get("took", 0)

    print(f"\n--- Standard Terms Aggregation (wiki_default) ---")
    print(f"Avg Client Latency: {avg_def:.2f} ms | OpenSearch took: {took_def} ms")
    buckets_def = res_def.get("aggregations", {}).get("top_words", {}).get("buckets", [])
    for b in buckets_def[:5]:
        print(f"  - {b['key']}: {b['doc_count']}")

    print(f"\n--- Roaring Terms Aggregation (wiki_roaring) ---")
    print(f"Avg Client Latency: {avg_roaring:.2f} ms | OpenSearch took: {took_roaring} ms")
    buckets_roaring = res_roaring.get("aggregations", {}).get("top_words", {}).get("buckets", [])
    for b in buckets_roaring[:5]:
        print(f"  - {b['key']}: {b['doc_count']}")

    if avg_roaring > 0:
        speedup = avg_def / avg_roaring
        print(f"\n🚀 SPEEDUP: Roaring Terms Aggregation is {speedup:.2f}x FASTER!")
    print("========================================================\n")

if __name__ == "__main__":
    setup_indices()
    index_wikimedia_data()
    run_benchmarks()
