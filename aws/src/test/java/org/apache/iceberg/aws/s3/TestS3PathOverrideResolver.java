/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.aws.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

public class TestS3PathOverrideResolver {

  @Test
  public void testDisabledByDefault() {
    Map<String, String> props = ImmutableMap.of();
    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    assertThat(resolver.isEnabled()).isFalse();
    assertThat(resolver.mappingCount()).isEqualTo(0);
    assertThat(resolver.resolvePath("s3://bucket/path/file.parquet"))
        .isEqualTo("s3://bucket/path/file.parquet");
  }

  @Test
  public void testSimplePathRemapping() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/data/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/data/");

    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    assertThat(resolver.isEnabled()).isTrue();
    assertThat(resolver.mappingCount()).isEqualTo(1);
    assertThat(resolver.resolvePath("s3://source-bucket/data/file.parquet"))
        .isEqualTo("s3://target-bucket/data/file.parquet");
    assertThat(resolver.resolvePath("s3://source-bucket/data/nested/file.parquet"))
        .isEqualTo("s3://target-bucket/data/nested/file.parquet");
  }

  @Test
  public void testMultipleMappings() {
    Map<String, String> props =
        ImmutableMap.<String, String>builder()
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED, "true")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source", "s3://bucket-a/")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target", "s3://bucket-b/")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "2.source", "s3://bucket-c/data/")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "2.target", "s3://bucket-d/archive/")
            .build();

    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    assertThat(resolver.isEnabled()).isTrue();
    assertThat(resolver.mappingCount()).isEqualTo(2);

    // Test first mapping
    assertThat(resolver.resolvePath("s3://bucket-a/file1.parquet"))
        .isEqualTo("s3://bucket-b/file1.parquet");

    // Test second mapping
    assertThat(resolver.resolvePath("s3://bucket-c/data/file2.parquet"))
        .isEqualTo("s3://bucket-d/archive/file2.parquet");

    // Test non-matching path
    assertThat(resolver.resolvePath("s3://bucket-e/file3.parquet"))
        .isEqualTo("s3://bucket-e/file3.parquet");
  }

  @Test
  public void testPrefixNormalization() {
    // Test that prefixes are normalized to end with '/'
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/data",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/data");

    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    // Should work with normalized paths
    assertThat(resolver.resolvePath("s3://source-bucket/data/file.parquet"))
        .isEqualTo("s3://target-bucket/data/file.parquet");
  }

  @Test
  public void testNoMatchingMapping() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/");

    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    // Path that doesn't match any mapping
    assertThat(resolver.resolvePath("s3://other-bucket/file.parquet"))
        .isEqualTo("s3://other-bucket/file.parquet");
  }

  @Test
  public void testNullPath() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/");

    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    assertThat(resolver.resolvePath(null)).isNull();
  }

  @Test
  public void testIncompleteMappingIgnored() {
    // Missing target
    Map<String, String> props1 =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/");

    S3PathOverrideResolver resolver1 = new S3PathOverrideResolver(props1);
    assertThat(resolver1.mappingCount()).isEqualTo(0);

    // Missing source
    Map<String, String> props2 =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/");

    S3PathOverrideResolver resolver2 = new S3PathOverrideResolver(props2);
    assertThat(resolver2.mappingCount()).isEqualTo(0);
  }

  @Test
  public void testNullPrefixesInMapping() {
    // Test that null source prefix throws exception
    assertThatThrownBy(() -> new S3PathOverrideResolver.PathMapping(null, "s3://target/"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("Source prefix cannot be null");

    // Test that null target prefix throws exception
    assertThatThrownBy(() -> new S3PathOverrideResolver.PathMapping("s3://source/", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("Target prefix cannot be null");
  }

  @Test
  public void testCrossRegionFailover() {
    // Real-world disaster recovery scenario
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://us-east-1-bucket/warehouse/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://us-west-2-bucket/warehouse/");

    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    assertThat(resolver.resolvePath("s3://us-east-1-bucket/warehouse/db/table/data/file.parquet"))
        .isEqualTo("s3://us-west-2-bucket/warehouse/db/table/data/file.parquet");
  }

  @Test
  public void testBucketMigration() {
    // Bucket migration use case
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://old-bucket/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://new-bucket/");

    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    assertThat(resolver.resolvePath("s3://old-bucket/data/2024/01/file.parquet"))
        .isEqualTo("s3://new-bucket/data/2024/01/file.parquet");
  }

  @Test
  public void testLongestPrefixMatching() {
    // When multiple mappings could match, the longest prefix should win
    Map<String, String> props =
        ImmutableMap.<String, String>builder()
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED, "true")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source", "s3://bucket/")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target", "s3://bucket-general/")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "2.source", "s3://bucket/special/")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "2.target", "s3://bucket-special/")
            .build();

    S3PathOverrideResolver resolver = new S3PathOverrideResolver(props);

    // Should match the more specific mapping
    assertThat(resolver.resolvePath("s3://bucket/special/file.parquet"))
        .isEqualTo("s3://bucket-special/file.parquet");

    // Should match the general mapping
    assertThat(resolver.resolvePath("s3://bucket/general/file.parquet"))
        .isEqualTo("s3://bucket-general/general/file.parquet");
  }
}
