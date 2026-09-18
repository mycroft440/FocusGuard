#!/usr/bin/env python3
"""Validate GitHub Actions artifact ZIPs against their recorded digest and source files."""

from __future__ import annotations

import argparse
import hashlib
import io
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


class VerificationError(RuntimeError):
    pass


def _sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sha256_stream(handle: io.BufferedReader) -> str:
    digest = hashlib.sha256()
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def _normalize_digest(value: str) -> str:
    digest = value.strip().lower()
    if digest.startswith("sha256:"):
        digest = digest.removeprefix("sha256:")
    if len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest):
        raise VerificationError(f"Invalid SHA-256 digest: {value!r}")
    return digest


def _parse_expected(spec: str) -> tuple[str, Path]:
    if "=" not in spec:
        raise VerificationError(
            f"Invalid --expect value {spec!r}; use ARCHIVE_NAME=SOURCE_PATH"
        )
    archive_name, source_path = spec.split("=", 1)
    archive_name = archive_name.strip()
    source_path = source_path.strip()
    if not archive_name or not source_path:
        raise VerificationError(
            f"Invalid --expect value {spec!r}; both sides must be non-empty"
        )
    return archive_name, Path(source_path)


def _safe_member_name(name: str) -> bool:
    path = PurePosixPath(name)
    return (
        bool(name)
        and "\\" not in name
        and not path.is_absolute()
        and ".." not in path.parts
    )


def verify_archive(
    archive: Path,
    expected_digest: str | None,
    expected_files: list[tuple[str, Path]],
) -> None:
    if not archive.is_file() or archive.stat().st_size == 0:
        raise VerificationError(f"Artifact ZIP is missing or empty: {archive}")

    actual_archive_digest = _sha256_path(archive)
    if expected_digest is not None:
        normalized_expected_digest = _normalize_digest(expected_digest)
        if actual_archive_digest != normalized_expected_digest:
            raise VerificationError(
                "Artifact ZIP digest mismatch: "
                f"expected {normalized_expected_digest}, got {actual_archive_digest}"
            )

    try:
        with zipfile.ZipFile(archive, "r") as zipped:
            corrupt_member = zipped.testzip()
            if corrupt_member is not None:
                raise VerificationError(f"CRC failure in ZIP member: {corrupt_member}")

            file_infos = [info for info in zipped.infolist() if not info.is_dir()]
            if not file_infos:
                raise VerificationError("Artifact ZIP contains no files")

            names = [info.filename for info in file_infos]
            if len(names) != len(set(names)):
                raise VerificationError("Artifact ZIP contains duplicate file names")

            unsafe_names = [name for name in names if not _safe_member_name(name)]
            if unsafe_names:
                raise VerificationError(
                    "Artifact ZIP contains unsafe paths: " + ", ".join(unsafe_names)
                )

            if expected_files:
                expected_names = [archive_name for archive_name, _ in expected_files]
                if set(names) != set(expected_names) or len(names) != len(expected_names):
                    raise VerificationError(
                        "Artifact ZIP file set mismatch: "
                        f"expected {sorted(expected_names)}, got {sorted(names)}"
                    )

                info_by_name = {info.filename: info for info in file_infos}
                for archive_name, source_path in expected_files:
                    if not source_path.is_file():
                        raise VerificationError(
                            f"Expected source file is missing: {source_path}"
                        )
                    source_size = source_path.stat().st_size
                    archived_size = info_by_name[archive_name].file_size
                    if source_size != archived_size:
                        raise VerificationError(
                            f"Size mismatch for {archive_name}: "
                            f"source {source_size}, ZIP {archived_size}"
                        )
                    source_digest = _sha256_path(source_path)
                    with zipped.open(archive_name, "r") as archived_file:
                        archived_digest = _sha256_stream(archived_file)
                    if source_digest != archived_digest:
                        raise VerificationError(
                            f"Content mismatch for {archive_name}: "
                            f"source {source_digest}, ZIP {archived_digest}"
                        )
    except zipfile.BadZipFile as exc:
        raise VerificationError(f"Invalid ZIP archive: {archive}") from exc

    print(
        f"Verified artifact ZIP: {archive} "
        f"(sha256={actual_archive_digest}, files={len(file_infos)})"
    )


def _self_test() -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        root = Path(temp_dir)
        source = root / "payload.apk"
        source.write_bytes(b"focusguard-artifact-integrity\n" * 128)
        archive = root / "artifact.zip"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as zipped:
            zipped.write(source, arcname="payload.apk")

        verify_archive(
            archive,
            f"sha256:{_sha256_path(archive)}",
            [("payload.apk", source)],
        )

        wrong_source = root / "wrong.apk"
        wrong_source.write_bytes(b"different")
        try:
            verify_archive(
                archive,
                _sha256_path(archive),
                [("payload.apk", wrong_source)],
            )
        except VerificationError:
            pass
        else:
            raise AssertionError("Self-test failed to detect content mismatch")

        truncated = root / "truncated.zip"
        truncated.write_bytes(archive.read_bytes()[:-8])
        try:
            verify_archive(truncated, None, [])
        except VerificationError:
            pass
        else:
            raise AssertionError("Self-test failed to detect truncated ZIP")

    print("Self-test passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--expected-digest")
    parser.add_argument(
        "--expect",
        action="append",
        default=[],
        metavar="ARCHIVE_NAME=SOURCE_PATH",
        help="Require an exact ZIP member and verify its bytes against a local source file.",
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    try:
        if args.self_test:
            _self_test()
            return 0
        if args.archive is None:
            parser.error("--archive is required unless --self-test is used")
        expected_files = [_parse_expected(spec) for spec in args.expect]
        verify_archive(args.archive, args.expected_digest, expected_files)
        return 0
    except VerificationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
