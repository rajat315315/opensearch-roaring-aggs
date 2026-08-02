# Contributing to OpenSearch Roaring Bitmap Aggregation Plugin

Thank you for your interest in contributing! This document provides guidelines
for contributing to the plugin.

## Development Setup

### Prerequisites
- **JDK 17** or later
- **Gradle 8.x** (uses the Gradle wrapper)
- An understanding of Apache Lucene's DocValues and codec architecture

### Building
```bash
./gradlew build
```

### Running Tests
```bash
./gradlew test
```

### Creating the Plugin ZIP
```bash
./gradlew pluginZip
```
The plugin ZIP will be generated in `build/distributions/`.

## Code Style

- Follow the [OpenSearch Java code style](https://github.com/opensearch-project/OpenSearch/blob/main/DEVELOPER_GUIDE.md)
- Use SPDX license headers on all Java files
- Write Javadoc for all public classes and methods

## Pull Request Process

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes with tests
4. Ensure all tests pass: `./gradlew test`
5. Submit a pull request with a clear description

## Areas for Contribution

- **Performance benchmarks**: JMH benchmarks comparing roaring_terms vs standard terms aggregation
- **Panama Vector API integration**: SIMD acceleration using `jdk.incubator.vector` for JDK 21+
- **Off-heap bitmap loading**: Memory-mapped I/O for zero-copy bitmap access
- **Merge optimizations**: Efficient bitmap merge during segment merges
- **Additional aggregation types**: Cardinality, range, and histogram aggregations using bitmaps

## License

By contributing, you agree that your contributions will be licensed under the
Apache License 2.0.
