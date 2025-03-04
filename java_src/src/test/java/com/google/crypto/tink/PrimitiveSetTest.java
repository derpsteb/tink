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

package com.google.crypto.tink;

import static com.google.common.truth.Truth.assertThat;
import static com.google.crypto.tink.testing.TestUtil.assertExceptionContains;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.crypto.tink.internal.LegacyProtoKey;
import com.google.crypto.tink.mac.HmacKey;
import com.google.crypto.tink.mac.HmacKeyManager;
import com.google.crypto.tink.monitoring.MonitoringAnnotations;
import com.google.crypto.tink.proto.HashType;
import com.google.crypto.tink.proto.HmacParams;
import com.google.crypto.tink.proto.KeyData;
import com.google.crypto.tink.proto.KeyStatusType;
import com.google.crypto.tink.proto.Keyset.Key;
import com.google.crypto.tink.proto.OutputPrefixType;
import com.google.crypto.tink.subtle.Hex;
import com.google.crypto.tink.testing.TestUtil;
import com.google.protobuf.ByteString;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for PrimitiveSet. */
@RunWith(JUnit4.class)
public class PrimitiveSetTest {

  private static class DummyMac1 implements Mac {
    public DummyMac1() {}

    @Override
    public byte[] computeMac(byte[] data) throws GeneralSecurityException {
      return this.getClass().getSimpleName().getBytes(UTF_8);
    }

    @Override
    public void verifyMac(byte[] mac, byte[] data) throws GeneralSecurityException {
      return;
    }
  }

  private static class DummyMac2 implements Mac {
    public DummyMac2() {}

    @Override
    public byte[] computeMac(byte[] data) throws GeneralSecurityException {
      return this.getClass().getSimpleName().getBytes(UTF_8);
    }

    @Override
    public void verifyMac(byte[] mac, byte[] data) throws GeneralSecurityException {
      return;
    }
  }

  @BeforeClass
  public static void setUp() throws GeneralSecurityException {
    HmacKeyManager.register(true);
  }

  @Test
  public void testBasicFunctionalityWithDeprecatedMutableInterface() throws Exception {
    PrimitiveSet<Mac> pset = PrimitiveSet.newPrimitiveSet(Mac.class);
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    pset.addPrimitive(new DummyMac1(), key1);
    Key key2 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    pset.setPrimary(pset.addPrimitive(new DummyMac2(), key2));
    Key key3 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    pset.addPrimitive(new DummyMac1(), key3);

    assertThat(pset.getAll()).hasSize(3);

    List<PrimitiveSet.Entry<Mac>> entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1));
    assertThat(entries).hasSize(1);
    PrimitiveSet.Entry<Mac> entry = entries.get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(CryptoFormat.TINK_START_BYTE, entry.getIdentifier()[0]);
    assertArrayEquals(CryptoFormat.getOutputPrefix(key1), entry.getIdentifier());
    assertEquals(1, entry.getKeyId());

    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key2));
    assertThat(entries).hasSize(1);
    entry = entries.get(0);
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertThat(entry.getIdentifier()).isEmpty();
    assertArrayEquals(CryptoFormat.getOutputPrefix(key2), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());

    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key3));
    assertThat(entries).hasSize(1);
    entry = entries.get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(CryptoFormat.LEGACY_START_BYTE, entry.getIdentifier()[0]);
    assertArrayEquals(CryptoFormat.getOutputPrefix(key3), entry.getIdentifier());
    assertEquals(3, entry.getKeyId());

    entry = pset.getPrimary();
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key2), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());
  }

  @Test
  public void testBasicFunctionalityWithBuilder() throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key3 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class)
            .addPrimitive(new DummyMac1(), key1)
            .addPrimaryPrimitive(new DummyMac2(), key2)
            .addPrimitive(new DummyMac1(), key3)
            .build();

    // The builder always creates an immutable PrimitiveSet.
    // Check that setPrimary and addPrimitive throw an IllegalStateException.
    PrimitiveSet.Entry<Mac> rawEntry = pset.getRawPrimitives().get(0);
    assertThrows(IllegalStateException.class, () -> pset.setPrimary(rawEntry));
    assertThrows(
        IllegalStateException.class,
        () ->
            pset.addPrimitive(
                new DummyMac1(),
                Key.newBuilder()
                    .setKeyId(4)
                    .setStatus(KeyStatusType.ENABLED)
                    .setOutputPrefixType(OutputPrefixType.TINK)
                    .build()));

    assertThat(pset.getAll()).hasSize(3);

    List<PrimitiveSet.Entry<Mac>> entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1));
    assertThat(entries).hasSize(1);
    PrimitiveSet.Entry<Mac> entry = entries.get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(CryptoFormat.TINK_START_BYTE, entry.getIdentifier()[0]);
    assertArrayEquals(CryptoFormat.getOutputPrefix(key1), entry.getIdentifier());
    assertEquals(1, entry.getKeyId());

    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key2));
    assertThat(entries).hasSize(1);
    entry = entries.get(0);
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertThat(entry.getIdentifier()).isEmpty();
    assertArrayEquals(CryptoFormat.getOutputPrefix(key2), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());

    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key3));
    assertThat(entries).hasSize(1);
    entry = entries.get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(CryptoFormat.LEGACY_START_BYTE, entry.getIdentifier()[0]);
    assertArrayEquals(CryptoFormat.getOutputPrefix(key3), entry.getIdentifier());
    assertEquals(3, entry.getKeyId());

    entry = pset.getPrimary();
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key2), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());
  }

  @Test
  public void testAddTwoPrimaryPrimitivesWithBuilder_throws() throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    assertThrows(
        IllegalStateException.class,
        () ->
            PrimitiveSet.newBuilder(Mac.class)
                .addPrimaryPrimitive(new DummyMac1(), key1)
                .addPrimaryPrimitive(new DummyMac2(), key2)
                .build());
  }

  @Test
  public void testAddFullPrimitiveAndOptionalPrimitive_works() throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key3 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class)
            .addFullPrimitiveAndOptionalPrimitive(new DummyMac1(), null, key1)
            .addPrimaryFullPrimitiveAndOptionalPrimitive(new DummyMac2(), null, key2)
            .addFullPrimitiveAndOptionalPrimitive(new DummyMac1(), new DummyMac2(), key3)
            .build();

    assertThat(pset.getAll()).hasSize(3);

    List<PrimitiveSet.Entry<Mac>> entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1));
    assertThat(entries).hasSize(1);

    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key2));
    assertThat(entries).hasSize(1);

    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key3));
    assertThat(entries).hasSize(1);

    PrimitiveSet.Entry<Mac> entry = pset.getPrimary();
    assertThat(entry).isNotNull();
  }

  @Test
  public void testAddFullPrimitiveAndOptionalPrimitive_fullPrimitiveHandledCorrectly()
      throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key3 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    Key key4 =
        Key.newBuilder()
            .setKeyId(4)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class)
            .addFullPrimitiveAndOptionalPrimitive(new DummyMac1(), null, key1)
            .addPrimaryFullPrimitiveAndOptionalPrimitive(new DummyMac2(), null, key2)
            .addFullPrimitiveAndOptionalPrimitive(new DummyMac1(), new DummyMac2(), key3)
            .addFullPrimitiveAndOptionalPrimitive(null, new DummyMac2(), key4)
            .build();

    PrimitiveSet.Entry<Mac> entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1)).get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(),
        new String(entry.getFullPrimitive().computeMac(null), UTF_8));

    entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key2)).get(0);
    assertEquals(
        DummyMac2.class.getSimpleName(),
        new String(entry.getFullPrimitive().computeMac(null), UTF_8));

    entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key3)).get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(),
        new String(entry.getFullPrimitive().computeMac(null), UTF_8));

    entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key4)).get(0);
    assertThat(entry.getFullPrimitive()).isNull();

    entry = pset.getPrimary();
    assertEquals(
        DummyMac2.class.getSimpleName(),
        new String(entry.getFullPrimitive().computeMac(null), UTF_8));
  }

  @Test
  public void testAddFullPrimitiveAndOptionalPrimitive_keysHandledCorrectly() throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key3 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class)
            .addFullPrimitiveAndOptionalPrimitive(new DummyMac1(), null, key1)
            .addPrimaryFullPrimitiveAndOptionalPrimitive(new DummyMac2(), null, key2)
            .addFullPrimitiveAndOptionalPrimitive(new DummyMac1(), new DummyMac2(), key3)
            .build();

    PrimitiveSet.Entry<Mac> entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1)).get(0);
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key1), entry.getIdentifier());
    assertEquals(1, entry.getKeyId());

    entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key2)).get(0);
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key2), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());

    entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key3)).get(0);
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key3), entry.getIdentifier());
    assertEquals(3, entry.getKeyId());

    entry = pset.getPrimary();
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key2), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());
  }

  @Test
  public void testAddFullPrimitiveAndOptionalPrimitive_primitiveHandledCorrectly()
      throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key3 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class)
            .addFullPrimitiveAndOptionalPrimitive(new DummyMac1(), null, key1)
            .addPrimaryFullPrimitiveAndOptionalPrimitive(new DummyMac2(), null, key2)
            .addFullPrimitiveAndOptionalPrimitive(new DummyMac1(), new DummyMac2(), key3)
            .build();

    PrimitiveSet.Entry<Mac> entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1)).get(0);
    assertThat(entry.getPrimitive()).isNull();

    entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key2)).get(0);
    assertThat(entry.getPrimitive()).isNull();

    entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key3)).get(0);
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));

    entry = pset.getPrimary();
    assertThat(entry.getPrimitive()).isNull();
  }

  @Test
  public void testAddFullPrimitiveAndOptionalPrimitive_throwsOnDoublePrimaryAdd() throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    assertThrows(
        IllegalStateException.class,
        () ->
            PrimitiveSet.newBuilder(Mac.class)
                .addPrimaryFullPrimitiveAndOptionalPrimitive(new DummyMac1(), null, key1)
                .addPrimaryFullPrimitiveAndOptionalPrimitive(new DummyMac2(), null, key2)
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            PrimitiveSet.newBuilder(Mac.class)
                .addPrimaryFullPrimitiveAndOptionalPrimitive(new DummyMac1(), null, key1)
                .addPrimaryPrimitive(new DummyMac2(), key2)
                .build());
  }

  @Test
  public void testNoPrimary_getPrimaryReturnsNull() throws Exception {
    Key key =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class).addPrimitive(new DummyMac1(), key).build();
    assertThat(pset.getPrimary()).isNull();
  }

  @Test
  public void testEntryGetParametersToString() throws Exception {
    PrimitiveSet<Mac> pset = PrimitiveSet.newPrimitiveSet(Mac.class);
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .setKeyData(KeyData.newBuilder().setTypeUrl("typeUrl1").build())
            .build();
    pset.addPrimitive(new DummyMac1(), key1);

    PrimitiveSet.Entry<Mac> entry = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1)).get(0);
    assertThat(entry.getParameters().toString())
        .isEqualTo("(typeUrl=typeUrl1, outputPrefixType=TINK)");

    PrimitiveSet<Mac> pset2 =
        PrimitiveSet.newBuilder(Mac.class).addPrimaryPrimitive(new DummyMac1(), key1).build();
    assertThat(
            pset2
                .getPrimitive(CryptoFormat.getOutputPrefix(key1))
                .get(0)
                .getParameters()
                .toString())
        .isEqualTo("(typeUrl=typeUrl1, outputPrefixType=TINK)");
  }

  @Test
  public void getKeyWithoutParser_givesLegacyProtoKey() throws Exception {
    PrimitiveSet.Builder<Mac> builder = PrimitiveSet.newBuilder(Mac.class);
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .setKeyData(KeyData.newBuilder().setTypeUrl("typeUrl1").build())
            .build();
    builder.addPrimitive(new DummyMac1(), key1);
    PrimitiveSet<Mac> pset = builder.build();
    com.google.crypto.tink.Key key =
        pset.getPrimitive(CryptoFormat.getOutputPrefix(key1)).get(0).getKey();

    assertThat(key).isInstanceOf(LegacyProtoKey.class);
    LegacyProtoKey legacyProtoKey = (LegacyProtoKey) key;
    assertThat(legacyProtoKey.getSerialization(InsecureSecretKeyAccess.get()).getTypeUrl())
        .isEqualTo("typeUrl1");
  }

  @Test
  public void getKeyWithParser_works() throws Exception {
    // HmacKey's proto serialization HmacProtoSerialization is registed in HmacKeyManager.
    Key protoKey =
        TestUtil.createKey(
            TestUtil.createHmacKeyData("01234567890123456".getBytes(UTF_8), 16),
            /* keyId= */ 42,
            KeyStatusType.ENABLED,
            OutputPrefixType.TINK);
    byte[] prefix = CryptoFormat.getOutputPrefix(protoKey);
    PrimitiveSet.Builder<Mac> builder = PrimitiveSet.newBuilder(Mac.class);
    builder.addPrimitive(new DummyMac1(), protoKey);
    PrimitiveSet<Mac> pset = builder.build();

    com.google.crypto.tink.Key key = pset.getPrimitive(prefix).get(0).getKey();
    assertThat(key).isInstanceOf(HmacKey.class);
    HmacKey hmacKey = (HmacKey) key;
    assertThat(hmacKey.getIdRequirementOrNull()).isEqualTo(42);
  }

  @Test
  public void addPrimitiveWithInvalidKeyThatHasAParser_throws() throws Exception {
    // HmacKey's proto serialization HmacProtoSerialization is registed in HmacKeyManager.
    com.google.crypto.tink.proto.HmacKey invalidProtoHmacKey =
        com.google.crypto.tink.proto.HmacKey.newBuilder()
            .setVersion(999)
            .setKeyValue(ByteString.copyFromUtf8("01234567890123456"))
            .setParams(HmacParams.newBuilder().setHash(HashType.UNKNOWN_HASH).setTagSize(0))
            .build();
    Key protoKey =
        TestUtil.createKey(
            TestUtil.createKeyData(
                invalidProtoHmacKey,
                "type.googleapis.com/google.crypto.tink.HmacKey",
                KeyData.KeyMaterialType.SYMMETRIC),
            /* keyId= */ 42,
            KeyStatusType.ENABLED,
            OutputPrefixType.TINK);

    PrimitiveSet.Builder<Mac> builder = PrimitiveSet.newBuilder(Mac.class);
    assertThrows(
        GeneralSecurityException.class, () -> builder.addPrimitive(new DummyMac1(), protoKey));
  }

  @Test
  public void testWithAnnotations() throws Exception {
    MonitoringAnnotations annotations =
        MonitoringAnnotations.newBuilder().add("name", "value").build();
    PrimitiveSet<Mac> pset = PrimitiveSet.newBuilder(Mac.class).setAnnotations(annotations).build();

    HashMap<String, String> expected = new HashMap<>();
    expected.put("name", "value");
    assertThat(pset.getAnnotations().toMap()).containsExactlyEntriesIn(expected);
  }

  @Test
  public void testGetEmptyAnnotations() throws Exception {
    PrimitiveSet<Mac> pset = PrimitiveSet.newPrimitiveSet(Mac.class);
    assertThat(pset.getAnnotations()).isEqualTo(MonitoringAnnotations.EMPTY);

    PrimitiveSet<Mac> pset2 = PrimitiveSet.newBuilder(Mac.class).build();
    assertThat(pset2.getAnnotations()).isEqualTo(MonitoringAnnotations.EMPTY);
  }

  @Test
  public void testDuplicateKeys() throws Exception {
    PrimitiveSet<Mac> pset = PrimitiveSet.newPrimitiveSet(Mac.class);
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    pset.addPrimitive(new DummyMac1(), key1);

    Key key2 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    pset.setPrimary(pset.addPrimitive(new DummyMac2(), key2));

    Key key3 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    pset.addPrimitive(new DummyMac1(), key3);

    Key key4 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    pset.addPrimitive(new DummyMac2(), key4);

    Key key5 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    pset.addPrimitive(new DummyMac1(), key5);

    Key key6 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    pset.addPrimitive(new DummyMac1(), key6);

    assertThat(pset.getAll()).hasSize(3); // 3 instead of 6 because of duplicated key ids

    // tink keys
    List<PrimitiveSet.Entry<Mac>> entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1));
    assertThat(entries).hasSize(1);
    PrimitiveSet.Entry<Mac> entry = entries.get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(CryptoFormat.TINK_START_BYTE, entry.getIdentifier()[0]);
    assertArrayEquals(CryptoFormat.getOutputPrefix(key1), entry.getIdentifier());
    assertEquals(1, entry.getKeyId());

    // raw keys
    List<Integer> ids = new ArrayList<>(); // The order of the keys is an implementation detail.
    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key2));
    assertThat(entries).hasSize(3);
    entry = entries.get(0);
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(0, entry.getIdentifier().length);
    ids.add(entry.getKeyId());
    entry = entries.get(1);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(0, entry.getIdentifier().length);
    ids.add(entry.getKeyId());
    entry = entries.get(2);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(0, entry.getIdentifier().length);
    ids.add(entry.getKeyId());

    assertThat(ids).containsExactly(1, 3, 3);
    // legacy keys
    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key3));
    assertEquals(2, entries.size());
    entry = entries.get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key3), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());
    entry = entries.get(1);
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key4), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());

    entry = pset.getPrimary();
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(0, entry.getIdentifier().length);
    assertArrayEquals(CryptoFormat.getOutputPrefix(key2), entry.getIdentifier());
    assertEquals(1, entry.getKeyId());
  }

  @Test
  public void testDuplicateKeysWithBuilder() throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key3 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    Key key4 =
        Key.newBuilder()
            .setKeyId(2)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    Key key5 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key6 =
        Key.newBuilder()
            .setKeyId(3)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();

    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class)
            .addPrimitive(new DummyMac1(), key1)
            .addPrimaryPrimitive(new DummyMac2(), key2)
            .addPrimitive(new DummyMac1(), key3)
            .addPrimitive(new DummyMac2(), key4)
            .addPrimitive(new DummyMac1(), key5)
            .addPrimitive(new DummyMac1(), key6)
            .build();

    assertThat(pset.getAll()).hasSize(3); // 3 instead of 6 because of duplicated key ids

    // tink keys
    List<PrimitiveSet.Entry<Mac>> entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key1));
    assertThat(entries).hasSize(1);
    PrimitiveSet.Entry<Mac> entry = entries.get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertEquals(CryptoFormat.TINK_START_BYTE, entry.getIdentifier()[0]);
    assertArrayEquals(CryptoFormat.getOutputPrefix(key1), entry.getIdentifier());
    assertEquals(1, entry.getKeyId());

    // raw keys
    List<Integer> ids = new ArrayList<>(); // The order of the keys is an implementation detail.
    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key2));
    assertThat(entries).hasSize(3);
    entry = entries.get(0);
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertThat(entry.getIdentifier()).isEmpty();
    ids.add(entry.getKeyId());
    entry = entries.get(1);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertThat(entry.getIdentifier()).isEmpty();
    ids.add(entry.getKeyId());
    entry = entries.get(2);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertThat(entry.getIdentifier()).isEmpty();
    ids.add(entry.getKeyId());

    assertThat(ids).containsExactly(1, 3, 3);
    // legacy keys
    entries = pset.getPrimitive(CryptoFormat.getOutputPrefix(key3));
    assertThat(entries).hasSize(2);
    entry = entries.get(0);
    assertEquals(
        DummyMac1.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key3), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());
    entry = entries.get(1);
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertArrayEquals(CryptoFormat.getOutputPrefix(key4), entry.getIdentifier());
    assertEquals(2, entry.getKeyId());

    entry = pset.getPrimary();
    assertEquals(
        DummyMac2.class.getSimpleName(), new String(entry.getPrimitive().computeMac(null), UTF_8));
    assertEquals(KeyStatusType.ENABLED, entry.getStatus());
    assertThat(entry.getIdentifier()).isEmpty();
    assertArrayEquals(CryptoFormat.getOutputPrefix(key2), entry.getIdentifier());
    assertEquals(1, entry.getKeyId());
  }

  @Test
  public void testAddPrimive_withUnknownPrefixType_shouldFail() throws Exception {
    PrimitiveSet<Mac> pset = PrimitiveSet.newPrimitiveSet(Mac.class);
    Key key1 = Key.newBuilder().setKeyId(1).setStatus(KeyStatusType.ENABLED).build();
    GeneralSecurityException e =
        assertThrows(
            GeneralSecurityException.class, () -> pset.addPrimitive(new DummyMac1(), key1));
    assertExceptionContains(e, "unknown output prefix type");

    assertThrows(
        GeneralSecurityException.class,
        () -> PrimitiveSet.newBuilder(Mac.class).addPrimitive(new DummyMac1(), key1).build());
    assertThrows(
        GeneralSecurityException.class,
        () ->
            PrimitiveSet.newBuilder(Mac.class).addPrimaryPrimitive(new DummyMac1(), key1).build());
  }

  @Test
  public void testAddPrimive_withDisabledKey_shouldFail() throws Exception {
    PrimitiveSet<Mac> pset = PrimitiveSet.newPrimitiveSet(Mac.class);
    Key key1 =
        Key.newBuilder()
            .setKeyId(1)
            .setStatus(KeyStatusType.DISABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    GeneralSecurityException e =
        assertThrows(
            GeneralSecurityException.class, () -> pset.addPrimitive(new DummyMac1(), key1));
    assertExceptionContains(e, "only ENABLED key is allowed");

    assertThrows(
        GeneralSecurityException.class,
        () -> PrimitiveSet.newBuilder(Mac.class).addPrimitive(new DummyMac1(), key1).build());
    assertThrows(
        GeneralSecurityException.class,
        () ->
            PrimitiveSet.newBuilder(Mac.class).addPrimaryPrimitive(new DummyMac1(), key1).build());
  }

  @Test
  public void testPrefix_isUnique() throws Exception {
    PrimitiveSet<Mac> pset = PrimitiveSet.newPrimitiveSet(Mac.class);
    Key key1 =
        Key.newBuilder()
            .setKeyId(0xffffffff)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    pset.addPrimitive(new DummyMac1(), key1);
    Key key2 =
        Key.newBuilder()
            .setKeyId(0xffffffdf)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    pset.setPrimary(pset.addPrimitive(new DummyMac2(), key2));
    Key key3 =
        Key.newBuilder()
            .setKeyId(0xffffffef)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    pset.addPrimitive(new DummyMac1(), key3);

    assertThat(pset.getAll()).hasSize(3);

    assertThat(pset.getPrimitive(Hex.decode("01ffffffff"))).hasSize(1);
    assertThat(pset.getPrimitive(Hex.decode("01ffffffef"))).isEmpty();
    assertThat(pset.getPrimitive(Hex.decode("00ffffffff"))).isEmpty();
    assertThat(pset.getPrimitive(Hex.decode("00ffffffef"))).hasSize(1);
  }

  @Test
  public void testPrefixIsUniqueWithBuilder() throws Exception {
    Key key1 =
        Key.newBuilder()
            .setKeyId(0xffffffff)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(0xffffffdf)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key3 =
        Key.newBuilder()
            .setKeyId(0xffffffef)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();

    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class)
            .addPrimitive(new DummyMac1(), key1)
            .addPrimaryPrimitive(new DummyMac2(), key2)
            .addPrimitive(new DummyMac1(), key3)
            .build();

    assertThat(pset.getAll()).hasSize(3);
    assertThat(pset.getPrimitive(Hex.decode("01ffffffff"))).hasSize(1);
    assertThat(pset.getPrimitive(Hex.decode("01ffffffef"))).isEmpty();
    assertThat(pset.getPrimitive(Hex.decode("00ffffffff"))).isEmpty();
    assertThat(pset.getPrimitive(Hex.decode("00ffffffef"))).hasSize(1);
  }

  @Test
  public void getAllInKeysetOrder_works() throws Exception {
    Key key0 =
        Key.newBuilder()
            .setKeyId(0xffffffff)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build();
    Key key1 =
        Key.newBuilder()
            .setKeyId(0xffffffdf)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build();
    Key key2 =
        Key.newBuilder()
            .setKeyId(0xffffffef)
            .setStatus(KeyStatusType.ENABLED)
            .setOutputPrefixType(OutputPrefixType.LEGACY)
            .build();
    PrimitiveSet<Mac> pset =
        PrimitiveSet.newBuilder(Mac.class)
            .addPrimitive(new DummyMac1(), key0)
            .addPrimaryPrimitive(new DummyMac2(), key1)
            .addPrimitive(new DummyMac1(), key2)
            .build();

    List<PrimitiveSet.Entry<Mac>> entries = pset.getAllInKeysetOrder();
    assertThat(entries).hasSize(3);
    assertThat(entries.get(0).getOutputPrefixType()).isEqualTo(OutputPrefixType.TINK);
    assertThat(entries.get(1).getOutputPrefixType()).isEqualTo(OutputPrefixType.RAW);
    assertThat(entries.get(2).getOutputPrefixType()).isEqualTo(OutputPrefixType.LEGACY);
  }
}
