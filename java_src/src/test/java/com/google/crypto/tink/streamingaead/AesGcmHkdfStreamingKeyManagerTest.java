// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.streamingaead;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.crypto.tink.KeyTemplate;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.internal.KeyTypeManager;
import com.google.crypto.tink.proto.AesGcmHkdfStreamingKey;
import com.google.crypto.tink.proto.AesGcmHkdfStreamingKeyFormat;
import com.google.crypto.tink.proto.AesGcmHkdfStreamingParams;
import com.google.crypto.tink.proto.HashType;
import com.google.crypto.tink.proto.KeyData.KeyMaterialType;
import com.google.crypto.tink.subtle.Hex;
import com.google.crypto.tink.subtle.Random;
import com.google.crypto.tink.testing.StreamingTestUtil;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.FromDataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

/** Test for AesGcmHkdfStreamingKeyManager. */
@RunWith(Theories.class)
public class AesGcmHkdfStreamingKeyManagerTest {
  private final AesGcmHkdfStreamingKeyManager manager = new AesGcmHkdfStreamingKeyManager();
  private final KeyTypeManager.KeyFactory<AesGcmHkdfStreamingKeyFormat, AesGcmHkdfStreamingKey>
      factory = manager.keyFactory();

  @Before
  public void register() throws Exception {
    StreamingAeadConfig.register();
  }

  private static AesGcmHkdfStreamingKeyFormat createKeyFormat(
      int keySize, int derivedKeySize, HashType hashType, int segmentSize) {
    return AesGcmHkdfStreamingKeyFormat.newBuilder()
        .setKeySize(keySize)
        .setParams(
            AesGcmHkdfStreamingParams.newBuilder()
                .setDerivedKeySize(derivedKeySize)
                .setHkdfHashType(hashType)
                .setCiphertextSegmentSize(segmentSize))
        .build();
  }

  @Test
  public void basics() throws Exception {
    assertThat(manager.getKeyType())
        .isEqualTo("type.googleapis.com/google.crypto.tink.AesGcmHkdfStreamingKey");
    assertThat(manager.getVersion()).isEqualTo(0);
    assertThat(manager.keyMaterialType()).isEqualTo(KeyMaterialType.SYMMETRIC);
  }

  @Test
  public void validateKeyFormat_empty_throws() throws Exception {
    assertThrows(
        GeneralSecurityException.class,
        () -> factory.validateKeyFormat(AesGcmHkdfStreamingKeyFormat.getDefaultInstance()));
  }

  @Test
  public void validateKeyFormat_valid() throws Exception {
    factory.validateKeyFormat(createKeyFormat(32, 32, HashType.SHA256, 1024));
  }

  @Test
  public void validateKeyFormat_unkownHash_throws() throws Exception {
    assertThrows(
        GeneralSecurityException.class,
        () -> factory.validateKeyFormat(createKeyFormat(32, 32, HashType.UNKNOWN_HASH, 1024)));
  }

  @Test
  public void validateKeyFormat_sha384_throws() throws Exception {
    assertThrows(
        GeneralSecurityException.class,
        () -> factory.validateKeyFormat(createKeyFormat(32, 32, HashType.SHA384, 1024)));
  }

  @Test
  public void validateKeyFormat_sha224_throws() throws Exception {
    assertThrows(
        GeneralSecurityException.class,
        () -> factory.validateKeyFormat(createKeyFormat(32, 32, HashType.SHA224, 1024)));
  }

  @Test
  public void validateKeyFormat_smallKey_throws() throws Exception {
    assertThrows(
        GeneralSecurityException.class,
        () -> factory.validateKeyFormat(createKeyFormat(15, 32, HashType.SHA256, 1024)));
  }

  @Test
  public void validateKeyFormat_smallSegment_throws() throws Exception {
    assertThrows(
        GeneralSecurityException.class,
        () -> factory.validateKeyFormat(createKeyFormat(16, 32, HashType.SHA256, 45)));
  }

  @Test
  public void createKey_checkValues() throws Exception {
    AesGcmHkdfStreamingKeyFormat format = createKeyFormat(32, 32, HashType.SHA256, 1024);

    AesGcmHkdfStreamingKey key = factory.createKey(format);

    assertThat(key.getParams()).isEqualTo(format.getParams());
    assertThat(key.getVersion()).isEqualTo(0);
    assertThat(key.getKeyValue()).hasSize(format.getKeySize());
  }

  @Test
  public void testDeriveKey_size32() throws Exception {
    final int keySize = 32;
    final int derivedKeySize = 16;
    AesGcmHkdfStreamingKeyFormat format =
        createKeyFormat(keySize, derivedKeySize, HashType.SHA256, 1024);

    byte[] keyMaterial = Random.randBytes(100);
    AesGcmHkdfStreamingKey key = factory.deriveKey(format, new ByteArrayInputStream(keyMaterial));
    assertThat(key.getKeyValue()).hasSize(32);
    for (int i = 0; i < keySize; ++i) {
      assertThat(key.getKeyValue().byteAt(i)).isEqualTo(keyMaterial[i]);
    }
    assertThat(key.getParams()).isEqualTo(format.getParams());
  }

  @Test
  public void testDeriveKey_handlesDataFragmentationCorrectly() throws Exception {
    int keySize = 32;
    int derivedKeySize = 16;
    byte randomness = 4;
    InputStream fragmentedInputStream =
        new InputStream() {
          @Override
          public int read() {
            return 0;
          }

          @Override
          public int read(byte[] b, int off, int len) {
            b[off] = randomness;
            return 1;
          }
        };
    AesGcmHkdfStreamingKeyFormat format =
        createKeyFormat(keySize, derivedKeySize, HashType.SHA256, 1024);

    AesGcmHkdfStreamingKey key = factory.deriveKey(format, fragmentedInputStream);

    assertThat(key.getKeyValue()).hasSize(keySize);
    for (int i = 0; i < keySize; ++i) {
      assertThat(key.getKeyValue().byteAt(i)).isEqualTo(randomness);
    }
  }

  @Test
  public void testDeriveKey_notEnoughKeyMaterial_throws() throws Exception {
    final int keySize = 32;
    final int derivedKeySize = 16;
    AesGcmHkdfStreamingKeyFormat format =
        createKeyFormat(keySize, derivedKeySize, HashType.SHA256, 1024);

    byte[] keyMaterial = Random.randBytes(31);
    assertThrows(
        GeneralSecurityException.class,
        () -> factory.deriveKey(format, new ByteArrayInputStream(keyMaterial)));
  }

  @Test
  public void testDeriveKey_justEnoughKeyMaterial_works() throws Exception {
    final int keySize = 32;
    final int derivedKeySize = 16;
    AesGcmHkdfStreamingKeyFormat format =
        createKeyFormat(keySize, derivedKeySize, HashType.SHA256, 1024);

    byte[] keyMaterial = Random.randBytes(32);
    AesGcmHkdfStreamingKey key = factory.deriveKey(format, new ByteArrayInputStream(keyMaterial));
    assertThat(key.getKeyValue()).hasSize(32);
    for (int i = 0; i < keySize; ++i) {
      assertThat(key.getKeyValue().byteAt(i)).isEqualTo(keyMaterial[i]);
    }
    assertThat(key.getParams()).isEqualTo(format.getParams());
  }

  @Test
  public void testDeriveKey_badVersion_throws() throws Exception {
    final int keySize = 32;
    final int derivedKeySize = 16;
    AesGcmHkdfStreamingKeyFormat format =
        AesGcmHkdfStreamingKeyFormat.newBuilder(
                createKeyFormat(keySize, derivedKeySize, HashType.SHA256, 1024))
            .setVersion(1)
            .build();

    byte[] keyMaterial = Random.randBytes(32);
    assertThrows(
        GeneralSecurityException.class,
        () -> factory.deriveKey(format, new ByteArrayInputStream(keyMaterial)));
  }

  @Test
  public void testSkip() throws Exception {
    AesGcmHkdfStreamingKey key = factory.createKey(createKeyFormat(32, 32, HashType.SHA256, 1024));
    StreamingAead streamingAead = manager.getPrimitive(key, StreamingAead.class);
    int offset = 0;
    int plaintextSize = 1 << 16;
    // Runs the test with different sizes for the chunks to skip.
    StreamingTestUtil.testSkipWithStream(streamingAead, offset, plaintextSize, 1);
    StreamingTestUtil.testSkipWithStream(streamingAead, offset, plaintextSize, 64);
    StreamingTestUtil.testSkipWithStream(streamingAead, offset, plaintextSize, 300);
  }

  @Test
  public void testNewKeyMultipleTimes() throws Exception {
    AesGcmHkdfStreamingKeyFormat keyFormat = createKeyFormat(32, 32, HashType.SHA256, 1024);
    Set<String> keys = new TreeSet<>();
    // Calls newKey multiple times and make sure that they generate different keys.
    int numTests = 100;
    for (int i = 0; i < numTests; i++) {
      keys.add(Hex.encode(factory.createKey(keyFormat).getKeyValue().toByteArray()));
    }
    assertThat(keys).hasSize(numTests);
  }

  @Test
  public void testAes128GcmHkdf4KBTemplate() throws Exception {
    KeyTemplate template = AesGcmHkdfStreamingKeyManager.aes128GcmHkdf4KBTemplate();
    assertThat(template.toParameters())
        .isEqualTo(
            AesGcmHkdfStreamingParameters.builder()
                .setKeySizeBytes(16)
                .setDerivedAesGcmKeySizeBytes(16)
                .setCiphertextSegmentSizeBytes(4 * 1024)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .build());
  }

  @Test
  public void testAes256GcmHkdf4KBTemplate() throws Exception {
    KeyTemplate template = AesGcmHkdfStreamingKeyManager.aes256GcmHkdf4KBTemplate();
    assertThat(template.toParameters())
        .isEqualTo(
            AesGcmHkdfStreamingParameters.builder()
                .setKeySizeBytes(32)
                .setDerivedAesGcmKeySizeBytes(32)
                .setCiphertextSegmentSizeBytes(4 * 1024)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .build());
  }

  @Test
  public void testAes128GcmHkdf1MBTemplate() throws Exception {
    KeyTemplate template = AesGcmHkdfStreamingKeyManager.aes128GcmHkdf1MBTemplate();
    assertThat(template.toParameters())
        .isEqualTo(
            AesGcmHkdfStreamingParameters.builder()
                .setKeySizeBytes(16)
                .setDerivedAesGcmKeySizeBytes(16)
                .setCiphertextSegmentSizeBytes(1024 * 1024)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .build());
  }

  @Test
  public void testAes256GcmHkdf1MBTemplate() throws Exception {
    KeyTemplate template = AesGcmHkdfStreamingKeyManager.aes256GcmHkdf1MBTemplate();
    assertThat(template.toParameters())
        .isEqualTo(
            AesGcmHkdfStreamingParameters.builder()
                .setKeySizeBytes(32)
                .setDerivedAesGcmKeySizeBytes(32)
                .setCiphertextSegmentSizeBytes(1024 * 1024)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .build());
  }

  @DataPoints("templateNames")
  public static final String[] KEY_TEMPLATES =
      new String[] {
        "AES128_GCM_HKDF_4KB", "AES128_GCM_HKDF_1MB", "AES256_GCM_HKDF_4KB", "AES256_GCM_HKDF_1MB",
      };

  @Theory
  public void testTemplates(@FromDataPoints("templateNames") String templateName) throws Exception {
    KeysetHandle h = KeysetHandle.generateNew(KeyTemplates.get(templateName));
    assertThat(h.size()).isEqualTo(1);
    assertThat(h.getAt(0).getKey().getParameters())
        .isEqualTo(KeyTemplates.get(templateName).toParameters());
  }

}
