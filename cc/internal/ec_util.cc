// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
///////////////////////////////////////////////////////////////////////////////
#include "tink/internal/ec_util.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <utility>

#include "absl/memory/memory.h"
#include "absl/status/status.h"
#include "absl/strings/escaping.h"
#include "absl/strings/string_view.h"
#include "absl/types/span.h"
#include "openssl/ec.h"
#include "openssl/evp.h"
#include "tink/internal/bn_util.h"
#include "tink/internal/err_util.h"
#include "tink/internal/ssl_unique_ptr.h"
#include "tink/subtle/common_enums.h"
#include "tink/subtle/subtle_util.h"
#include "tink/util/status.h"
#include "tink/util/statusor.h"

namespace crypto {
namespace tink {
namespace internal {
namespace {

using ::crypto::tink::subtle::EcPointFormat;
using ::crypto::tink::subtle::EllipticCurveType;

// Encodes the given `point` to string, according to a `conversion_form`.
util::StatusOr<std::string> SslEcPointEncode(
    EC_GROUP *group, const EC_POINT *point,
    point_conversion_form_t conversion_form) {
  // Get the buffer size first passing a NULL buffer.
  size_t buffer_size =
      EC_POINT_point2oct(group, point, conversion_form,
                         /*buf=*/nullptr, /*len=*/0, /*ctx=*/nullptr);
  if (buffer_size == 0) {
    return util::Status(absl::StatusCode::kInternal,
                        "EC_POINT_point2oct failed");
  }

  std::string encoded_point;
  subtle::ResizeStringUninitialized(&encoded_point, buffer_size);
  size_t size =
      EC_POINT_point2oct(group, point, conversion_form,
                         reinterpret_cast<uint8_t *>(&encoded_point[0]),
                         buffer_size, /*ctx=*/nullptr);
  if (size == 0) {
    return util::Status(absl::StatusCode::kInternal,
                        "EC_POINT_point2oct failed");
  }
  return encoded_point;
}

// Returns an EC_POINT from `group`, and encoded (bigendian string
// representation of BIGNUMs) point coordinates `pubx`, `puby`.
util::StatusOr<SslUniquePtr<EC_POINT>> SslGetEcPointFromCoordinates(
    const EC_GROUP *group, absl::string_view pubx, absl::string_view puby) {
  util::StatusOr<SslUniquePtr<BIGNUM>> bn_x = StringToBignum(pubx);
  if (!bn_x.ok()) {
    return bn_x.status();
  }
  util::StatusOr<SslUniquePtr<BIGNUM>> bn_y = StringToBignum(puby);
  if (!bn_y.ok()) {
    return bn_y.status();
  }
  SslUniquePtr<EC_POINT> pub_key(EC_POINT_new(group));
  // In BoringSSL and OpenSSL > 1.1.0 EC_POINT_set_affine_coordinates_GFp
  // already checkes if the point is on the curve.
  if (EC_POINT_set_affine_coordinates_GFp(group, pub_key.get(), bn_x->get(),
                                          bn_y->get(), nullptr) != 1) {
    return util::Status(absl::StatusCode::kInternal,
                        "EC_POINT_set_affine_coordinates_GFp failed");
  }
  return std::move(pub_key);
}

// Returns the encoding size for the given `curve` and point `format`.
util::StatusOr<int32_t> EncodingSizeInBytes(EllipticCurveType curve,
                                            EcPointFormat format) {
  util::StatusOr<SslUniquePtr<EC_GROUP>> group = EcGroupFromCurveType(curve);
  if (!group.ok()) {
    return group.status();
  }
  const int kCurveSizeInBytes = (EC_GROUP_get_degree(group->get()) + 7) / 8;
  switch (format) {
    case EcPointFormat::UNCOMPRESSED:
      return 2 * kCurveSizeInBytes + 1;
    case EcPointFormat::DO_NOT_USE_CRUNCHY_UNCOMPRESSED:
      return 2 * kCurveSizeInBytes;
    case EcPointFormat::COMPRESSED:
      return kCurveSizeInBytes + 1;
    default:
      return util::Status(
          absl::StatusCode::kInvalidArgument,
          absl::StrCat("Unsupported elliptic curve point format: %s",
                       subtle::EnumToString(format)));
  }
}

// Returns an EC_POINT from an `encoded` point with format `format` and curve
// type `curve`. `format` is either COMPRESSED or UNCOMPRESSED.
util::StatusOr<SslUniquePtr<EC_POINT>> SslGetEcPointFromEncoded(
    EllipticCurveType curve, EcPointFormat format, absl::string_view encoded) {
  if (format != EcPointFormat::UNCOMPRESSED &&
      format != EcPointFormat::COMPRESSED) {
    return util::Status(
        absl::StatusCode::kInvalidArgument,
        absl::StrCat("Invalid format ", subtle::EnumToString(format)));
  }
  util::StatusOr<SslUniquePtr<EC_GROUP>> group = EcGroupFromCurveType(curve);
  if (!group.ok()) {
    return group.status();
  }

  util::StatusOr<int32_t> encoding_size = EncodingSizeInBytes(curve, format);
  if (!encoding_size.ok()) {
    return encoding_size.status();
  }
  if (encoded.size() != *encoding_size) {
    return util::Status(absl::StatusCode::kInternal,
                        absl::StrCat("Encoded point's size is ", encoded.size(),
                                     " bytes; expected ", *encoding_size));
  }

  // Check starting byte.
  if (format == EcPointFormat::UNCOMPRESSED &&
      static_cast<int>(encoded[0]) != 0x04) {
    return util::Status(
        absl::StatusCode::kInternal,
        "Uncompressed point should start with 0x04, but input doesn't");
  } else if (format == EcPointFormat::COMPRESSED &&
             static_cast<int>(encoded[0]) != 0x03 &&
             static_cast<int>(encoded[0]) != 0x02) {
    return util::Status(absl::StatusCode::kInternal,
                        "Compressed point should start with either 0x02 or "
                        "0x03, but input doesn't");
  }

  SslUniquePtr<EC_POINT> point(EC_POINT_new(group->get()));
  if (EC_POINT_oct2point(group->get(), point.get(),
                         reinterpret_cast<const uint8_t *>(encoded.data()),
                         encoded.size(), nullptr) != 1) {
    return util::Status(absl::StatusCode::kInternal,
                        "EC_POINT_toc2point failed");
  }
  // Check that point is on curve.
  if (EC_POINT_is_on_curve(group->get(), point.get(), nullptr) != 1) {
    return util::Status(absl::StatusCode::kInternal, "Point is not on curve");
  }

  return std::move(point);
}

}  // namespace

util::StatusOr<std::unique_ptr<X25519Key>> NewX25519Key() {
  auto key = absl::make_unique<X25519Key>();
  EVP_PKEY *private_key = nullptr;
  SslUniquePtr<EVP_PKEY_CTX> pctx(
      EVP_PKEY_CTX_new_id(EVP_PKEY_X25519, /*e=*/nullptr));
  if (EVP_PKEY_keygen_init(pctx.get()) != 1) {
    return util::Status(absl::StatusCode::kInternal,
                        "EVP_PKEY_keygen_init failed");
  }
  if (EVP_PKEY_keygen(pctx.get(), &private_key) != 1) {
    return util::Status(absl::StatusCode::kInternal, "EVP_PKEY_keygen failed");
  }
  SslUniquePtr<EVP_PKEY> private_key_ptr(private_key);

  size_t len = X25519KeyPrivKeySize();
  if (EVP_PKEY_get_raw_private_key(private_key_ptr.get(), key->private_key,
                                   &len) != 1) {
    return util::Status(absl::StatusCode::kInternal,
                        "EVP_PKEY_get_raw_private_key failed");
  }
  len = X25519KeyPubKeySize();
  if (EVP_PKEY_get_raw_public_key(private_key_ptr.get(), key->public_value,
                                  &len) != 1) {
    return util::Status(absl::StatusCode::kInternal,
                        "EVP_PKEY_get_raw_public_key failed");
  }
  return key;
}

EcKey EcKeyFromX25519Key(const X25519Key *x25519_key) {
  EcKey ec_key;
  ec_key.curve = subtle::EllipticCurveType::CURVE25519;
  // Curve25519 public key is x, not (x,y).
  ec_key.pub_x =
      std::string(reinterpret_cast<const char *>(x25519_key->public_value),
                  X25519KeyPubKeySize());
  ec_key.priv = util::SecretData(std::begin(x25519_key->private_key),
                                 std::end(x25519_key->private_key));
  return ec_key;
}

util::StatusOr<std::unique_ptr<X25519Key>> X25519KeyFromEcKey(
    const EcKey &ec_key) {
  auto x25519_key = absl::make_unique<X25519Key>();
  if (ec_key.curve != subtle::EllipticCurveType::CURVE25519) {
    return util::Status(absl::StatusCode::kInvalidArgument,
                        "This key is not on curve 25519");
  }
  if (!ec_key.pub_y.empty()) {
    return util::Status(absl::StatusCode::kInvalidArgument,
                        "Invalid X25519 key. pub_y is unexpectedly set.");
  }
  // Curve25519 public key is x, not (x,y).
  std::copy_n(ec_key.pub_x.begin(), X25519KeyPubKeySize(),
              std::begin(x25519_key->public_value));
  std::copy_n(ec_key.priv.begin(), X25519KeyPrivKeySize(),
              std::begin(x25519_key->private_key));
  return std::move(x25519_key);
}

util::StatusOr<std::string> EcPointEncode(EllipticCurveType curve,
                                          EcPointFormat format,
                                          const EC_POINT *point) {
  util::StatusOr<SslUniquePtr<EC_GROUP>> group = EcGroupFromCurveType(curve);
  if (!group.ok()) {
    return group.status();
  }
  if (EC_POINT_is_on_curve(group->get(), point, nullptr) != 1) {
    return util::Status(absl::StatusCode::kInternal, "Point is not on curve");
  }

  const unsigned int kCurveSizeInBytes =
      (EC_GROUP_get_degree(group->get()) + 7) / 8;

  switch (format) {
    case EcPointFormat::UNCOMPRESSED: {
      return SslEcPointEncode(group->get(), point,
                              POINT_CONVERSION_UNCOMPRESSED);
    }
    case EcPointFormat::COMPRESSED: {
      return SslEcPointEncode(group->get(), point, POINT_CONVERSION_COMPRESSED);
    }
    case EcPointFormat::DO_NOT_USE_CRUNCHY_UNCOMPRESSED: {
      std::string encoded_point;
      SslUniquePtr<BIGNUM> x(BN_new());
      SslUniquePtr<BIGNUM> y(BN_new());
      if (x == nullptr || y == nullptr) {
        return util::Status(absl::StatusCode::kInternal,
                            "Unable to allocate memory for coordinates");
      }
      subtle::ResizeStringUninitialized(&encoded_point, 2 * kCurveSizeInBytes);
      if (EC_POINT_get_affine_coordinates(group->get(), point, x.get(), y.get(),
                                          /*ctx=*/nullptr) != 1) {
        return util::Status(absl::StatusCode::kInternal,
                            "EC_POINT_get_affine_coordinates failed");
      }

      util::Status res = BignumToBinaryPadded(
          absl::MakeSpan(&encoded_point[0], kCurveSizeInBytes), x.get());
      if (!res.ok()) {
        return util::Status(
            absl::StatusCode::kInternal,
            absl::StrCat(res.message(), " serializing the x coordinate"));
      }

      res = BignumToBinaryPadded(
          absl::MakeSpan(&encoded_point[kCurveSizeInBytes], kCurveSizeInBytes),
          y.get());
      if (!res.ok()) {
        return util::Status(
            absl::StatusCode::kInternal,
            absl::StrCat(res.message(), " serializing the y coordinate"));
      }
      return encoded_point;
    }
    default:
      return util::Status(absl::StatusCode::kInternal,
                          "Unsupported point format");
  }
}

util::StatusOr<SslUniquePtr<EC_POINT>> EcPointDecode(
    EllipticCurveType curve, EcPointFormat format, absl::string_view encoded) {
  switch (format) {
    case EcPointFormat::UNCOMPRESSED:
    case EcPointFormat::COMPRESSED:
      return SslGetEcPointFromEncoded(curve, format, encoded);
    case EcPointFormat::DO_NOT_USE_CRUNCHY_UNCOMPRESSED: {
      util::StatusOr<SslUniquePtr<EC_GROUP>> group =
          EcGroupFromCurveType(curve);
      if (!group.ok()) {
        return group.status();
      }
      const int kCurveSizeInBytes = (EC_GROUP_get_degree(group->get()) + 7) / 8;
      if (encoded.size() != 2 * kCurveSizeInBytes) {
        return util::Status(
            absl::StatusCode::kInternal,
            absl::StrCat("Encoded point's size is ", encoded.size(),
                         " bytes; expected ", 2 * kCurveSizeInBytes));
      }
      // SslGetEcPoint already checks if the point is on curve so we can return
      // directly.
      return SslGetEcPointFromCoordinates(group->get(),
                                          encoded.substr(0, kCurveSizeInBytes),
                                          encoded.substr(kCurveSizeInBytes));
    }
    default:
      return util::Status(absl::StatusCode::kInternal, "Unsupported format");
  }
}

util::StatusOr<SslUniquePtr<EC_GROUP>> EcGroupFromCurveType(
    EllipticCurveType curve_type) {
  EC_GROUP *ec_group = nullptr;
  switch (curve_type) {
    case EllipticCurveType::NIST_P256: {
      ec_group = EC_GROUP_new_by_curve_name(NID_X9_62_prime256v1);
      break;
    }
    case EllipticCurveType::NIST_P384: {
      ec_group = EC_GROUP_new_by_curve_name(NID_secp384r1);
      break;
    }
    case EllipticCurveType::NIST_P521: {
      ec_group = EC_GROUP_new_by_curve_name(NID_secp521r1);
      break;
    }
    default:
      return util::Status(absl::StatusCode::kUnimplemented,
                          "Unsupported elliptic curve");
  }
  if (ec_group == nullptr) {
    return util::Status(absl::StatusCode::kInternal,
                        "EC_GROUP_new_by_curve_name failed");
  }
  return {SslUniquePtr<EC_GROUP>(ec_group)};
}

util::StatusOr<EllipticCurveType> CurveTypeFromEcGroup(const EC_GROUP *group) {
  if (group == nullptr) {
    return util::Status(absl::StatusCode::kInvalidArgument,
                        "Null group provided");
  }
  switch (EC_GROUP_get_curve_name(group)) {
    case NID_X9_62_prime256v1:
      return EllipticCurveType::NIST_P256;
    case NID_secp384r1:
      return EllipticCurveType::NIST_P384;
    case NID_secp521r1:
      return EllipticCurveType::NIST_P521;
    default:
      return util::Status(absl::StatusCode::kUnimplemented,
                          "Unsupported elliptic curve");
  }
}

util::StatusOr<SslUniquePtr<EC_POINT>> GetEcPoint(EllipticCurveType curve,
                                                  absl::string_view pubx,
                                                  absl::string_view puby) {
  util::StatusOr<SslUniquePtr<EC_GROUP>> group = EcGroupFromCurveType(curve);
  if (!group.ok()) {
    return group.status();
  }
  return SslGetEcPointFromCoordinates(group->get(), pubx, puby);
}

}  // namespace internal
}  // namespace tink
}  // namespace crypto