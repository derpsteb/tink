#!/bin/bash
# Copyright 2023 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
################################################################################

set -euo pipefail

BAZEL_CMD="bazel"
# Prefer using Bazelisk if available.
if command -v "bazelisk" &> /dev/null; then
  BAZEL_CMD="bazelisk"
fi
readonly BAZEL_CMD

OUT_FILE="BUILD.bazel"

usage() {
  cat <<EOF
Usage: $0 [-o <destination file>]
 -o: Output file (default=BUILD.bazel).
 -h: Show this help message.
EOF
  exit 1
}

process_params() {
  while getopts "ho:" opt; do
    case "${opt}" in
      o) OUT_FILE="${OPTARG}" ;;
      *) usage ;;
    esac
  done
  shift $((OPTIND - 1))
  readonly OUT_FILE
}

create_build_file() {
  local -r deps="$1"
  cat <<EOF > "${OUT_FILE}"
# Build file for generating the tink-awskms Maven artifact.

load("//tools:gen_maven_jar_rules.bzl", "gen_maven_jar_rules")

package(default_visibility = ["//visibility:public"])

licenses(["notice"])

exports_files(["BUILD"])

# WARNING: This is autogenerated using tools/create_maven_build_file.sh.
gen_maven_jar_rules(
    name = "tink-awskms",
    doctitle = "Tink Cryptography API with AWS KMS",
    manifest_lines = [
        "Automatic-Module-Name: com.google.crypto.tink.integration.awskms",
    ],
    root_packages = [
        "com.google.crypto.tink.integration.awskms",
    ],
    deps = [
$(cat "${deps}" | sed 's/^/        "/' | sed 's/$/",/')
    ],
)
EOF
}

readonly TINK_JAVA_PREFIX="//src/main/java/com/google/crypto/tink"
readonly TINK_JAVA_INTEGRATION_AWSKMS_PREFIX="${TINK_JAVA_PREFIX}/integration/awskms"

main() {
  process_params "$@"

  # Targets in TINK_JAVA_INTEGRATION_AWSKMS_PREFIX of type java_library,
  # excluding testonly targets.
  local -r deps="$(mktemp)"
  "${BAZEL_CMD}" query "\
kind(java_library,${TINK_JAVA_INTEGRATION_AWSKMS_PREFIX}/...) \
except attr(testonly,1,${TINK_JAVA_INTEGRATION_AWSKMS_PREFIX}/...)" > "${deps}"

  create_build_file "${deps}"
}

main "$@"
