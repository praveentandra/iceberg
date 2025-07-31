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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.util.SerializableSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

public class TestS3FileIOPathOverride {
  private S3Client mockS3Client;
  private S3FileIO s3FileIO;

  @BeforeEach
  public void before() {
    mockS3Client = mock(S3Client.class);
    SerializableSupplier<S3Client> clientSupplier = () -> mockS3Client;
    s3FileIO = new S3FileIO(clientSupplier);
  }

  @Test
  public void testInputFilePathOverride() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/");

    s3FileIO.initialize(props);

    // Create input file with source path
    InputFile inputFile = s3FileIO.newInputFile("s3://source-bucket/data/file.parquet");

    // Verify the location is remapped
    assertThat(inputFile.location()).isEqualTo("s3://target-bucket/data/file.parquet");
  }

  @Test
  public void testInputFileWithLengthPathOverride() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/");

    s3FileIO.initialize(props);

    // Create input file with source path and length
    InputFile inputFile = s3FileIO.newInputFile("s3://source-bucket/data/file.parquet", 1024L);

    // Verify the location is remapped
    assertThat(inputFile.location()).isEqualTo("s3://target-bucket/data/file.parquet");
  }

  @Test
  public void testOutputFilePathOverride() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/");

    s3FileIO.initialize(props);

    // Create output file with source path
    OutputFile outputFile = s3FileIO.newOutputFile("s3://source-bucket/data/output.parquet");

    // Verify the location is remapped
    assertThat(outputFile.location()).isEqualTo("s3://target-bucket/data/output.parquet");
  }

  @Test
  public void testDeleteFilePathOverride() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/",
            S3FileIOProperties.DELETE_ENABLED,
            "true");

    s3FileIO.initialize(props);

    // Delete file with source path
    s3FileIO.deleteFile("s3://source-bucket/data/file.parquet");

    // Verify delete was called with remapped path
    ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(mockS3Client).deleteObject(captor.capture());

    DeleteObjectRequest request = captor.getValue();
    assertThat(request.bucket()).isEqualTo("target-bucket");
    assertThat(request.key()).isEqualTo("data/file.parquet");
  }

  @Test
  public void testNoOverrideWhenDisabled() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "false",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/");

    s3FileIO.initialize(props);

    // Create input file - should not be remapped
    InputFile inputFile = s3FileIO.newInputFile("s3://source-bucket/data/file.parquet");
    assertThat(inputFile.location()).isEqualTo("s3://source-bucket/data/file.parquet");
  }

  @Test
  public void testNoOverrideWithoutMapping() {
    Map<String, String> props =
        ImmutableMap.of(S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED, "true");

    s3FileIO.initialize(props);

    // Create input file - should not be remapped (no mappings defined)
    InputFile inputFile = s3FileIO.newInputFile("s3://source-bucket/data/file.parquet");
    assertThat(inputFile.location()).isEqualTo("s3://source-bucket/data/file.parquet");
  }

  @Test
  public void testCrossRegionDisasterRecoveryScenario() {
    // Simulate a DR scenario where we need to failover from us-east-1 to us-west-2
    Map<String, String> props =
        ImmutableMap.<String, String>builder()
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED, "true")
            .put(
                S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
                "s3://prod-warehouse-us-east-1/")
            .put(
                S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
                "s3://dr-warehouse-us-west-2/")
            .build();

    s3FileIO.initialize(props);

    // Test various file operations with the DR mapping
    String originalPath = "s3://prod-warehouse-us-east-1/db/table/data/00001.parquet";
    String expectedPath = "s3://dr-warehouse-us-west-2/db/table/data/00001.parquet";

    // Input file
    InputFile inputFile = s3FileIO.newInputFile(originalPath);
    assertThat(inputFile.location()).isEqualTo(expectedPath);

    // Output file
    OutputFile outputFile = s3FileIO.newOutputFile(originalPath);
    assertThat(outputFile.location()).isEqualTo(expectedPath);
  }

  @Test
  public void testMultipleBucketMappings() {
    Map<String, String> props =
        ImmutableMap.<String, String>builder()
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED, "true")
            // First mapping for production data
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source", "s3://prod-data/")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target", "s3://archive-data/")
            // Second mapping for temp data
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "2.source", "s3://temp-data/")
            .put(S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "2.target", "s3://backup-temp/")
            .build();

    s3FileIO.initialize(props);

    // Test first mapping
    InputFile prodFile = s3FileIO.newInputFile("s3://prod-data/table/file.parquet");
    assertThat(prodFile.location()).isEqualTo("s3://archive-data/table/file.parquet");

    // Test second mapping
    InputFile tempFile = s3FileIO.newInputFile("s3://temp-data/staging/file.parquet");
    assertThat(tempFile.location()).isEqualTo("s3://backup-temp/staging/file.parquet");

    // Test unmapped path
    InputFile otherFile = s3FileIO.newInputFile("s3://other-bucket/file.parquet");
    assertThat(otherFile.location()).isEqualTo("s3://other-bucket/file.parquet");
  }

  @Test
  public void testPathOverrideWithSpecialCharacters() {
    Map<String, String> props =
        ImmutableMap.of(
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            "true",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.source",
            "s3://source-bucket/data/",
            S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX + "1.target",
            "s3://target-bucket/archived/");

    s3FileIO.initialize(props);

    // Test with special characters in file name
    String pathWithSpaces = "s3://source-bucket/data/file with spaces.parquet";
    InputFile fileWithSpaces = s3FileIO.newInputFile(pathWithSpaces);
    assertThat(fileWithSpaces.location())
        .isEqualTo("s3://target-bucket/archived/file with spaces.parquet");

    // Test with encoded characters
    String pathWithEncoded = "s3://source-bucket/data/file%20name%2Bspecial.parquet";
    InputFile fileWithEncoded = s3FileIO.newInputFile(pathWithEncoded);
    assertThat(fileWithEncoded.location())
        .isEqualTo("s3://target-bucket/archived/file%20name%2Bspecial.parquet");
  }
}
