#!/usr/bin/env python3
"""Package a Gradle staging publication for Sonatype Central Portal."""
import argparse
import hashlib
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile


def package(repository, group, version, output, unsigned=False):
    if not re.fullmatch(r"[a-zA-Z0-9_-]+(?:\.[a-zA-Z0-9_-]+)+", group):
        raise ValueError("Invalid Maven group")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){2}", version):
        raise ValueError("Invalid release version")
    base = f"pdn-engine-{version}"
    relative = Path(*group.split(".")) / "pdn-engine" / version
    directory = repository / relative
    names = [base + suffix for suffix in (".pom", ".aar", "-sources.jar", "-javadoc.jar")]
    if (directory / (base + ".module")).is_file():
        names.append(base + ".module")
    contents = {name: (directory / name).read_bytes() for name in names}
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.fromstring(contents[base + ".pom"])
    for key, expected in [("groupId", group), ("artifactId", "pdn-engine"), ("version", version), ("packaging", "aar")]:
        if pom.findtext("m:" + key, namespaces=ns) != expected:
            raise ValueError(f"POM {key} does not match publication")
    for field in ("name", "description", "url", "licenses/m:license/m:name", "developers/m:developer/m:id", "scm/m:connection"):
        if not pom.findtext("m:" + field, namespaces=ns):
            raise ValueError(f"Missing POM metadata: {field}")
    with zipfile.ZipFile(directory / (base + ".aar")) as aar:
        libs = {n for n in aar.namelist() if n.startswith("jni/") and not n.endswith("/")}
        expected = {"jni/arm64-v8a/" + n for n in ("libpdn.so", "libproot-loader.so", "libptyjni.so")}
        if libs != expected or aar.testzip() is not None:
            raise ValueError("AAR native membership or ZIP integrity mismatch")
    with zipfile.ZipFile(directory / (base + "-sources.jar")) as sources:
        if "META-INF/pdn/source.tar.gz" not in sources.namelist() or not any(n.endswith("PdnTask.java") for n in sources.namelist()):
            raise ValueError("Missing Android or corresponding native sources")
    with zipfile.ZipFile(directory / (base + "-javadoc.jar")) as docs:
        if "index.html" not in docs.namelist() or "guides/pdn-aar-api.md" not in docs.namelist():
            raise ValueError("Missing API reference or usage guide")
    if not unsigned:
        for name in names:
            signature = (directory / (name + ".asc")).read_bytes()
            if not signature.startswith(b"-----BEGIN PGP SIGNATURE-----"):
                raise ValueError(f"Missing ASCII-armored PGP signature: {name}")
            contents[name + ".asc"] = signature
    for name in names:
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            contents[name + "." + algorithm] = hashlib.new(algorithm, contents[name]).hexdigest().encode("ascii")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, data in contents.items():
            bundle.writestr((relative / name).as_posix(), data)
    print(f"{'UNSIGNED preview (not uploadable)' if unsigned else 'Signed Central bundle'}: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path("build/maven/repository"))
    parser.add_argument("--group", default="io.github.emerladd")
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--unsigned", action="store_true", help="Create a local preview; cannot be uploaded to Central")
    args = parser.parse_args()
    try:
        package(args.repository, args.group, args.version, args.output, args.unsigned)
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"Cannot package Maven publication: {error}", file=sys.stderr)
        sys.exit(1)
