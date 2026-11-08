"""Install the verified RN50 files into DropZone's debug app on the selected AVD."""
import argparse
import hashlib
from pathlib import Path
import subprocess

FILES = {
    "image.onnx": "996b0927a517b0e3728a5878e5e29de556db5627a48bfb08d7942562f425a6ff",
    "text.onnx": "08be11b6e1de3b83b8db57a97f7d464adcbbab6c26a7d126356c4411c8bdbb11",
    "vocab.txt": "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
}
PACKAGE = "com.bitocean.dropzone"
FOLDER = "files/chinese-clip"
STAMP = "rn50-fp32-v1.ready"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=r"D:\Android\Sdk\platform-tools\adb.exe")
    parser.add_argument("--device", required=True)
    parser.add_argument("--models", type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, "-s", args.device]
    def shell(*command):
        return subprocess.check_output(adb + ["shell", *command], text=True).strip()
    for name, expected in FILES.items():
        digest = hashlib.sha256()
        with (args.models / name).open("rb") as source:
            for data in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(data)
        if digest.hexdigest() != expected:
            raise ValueError(f"Wrong RN50 artifact: {name}")
    shell("run-as", PACKAGE, "mkdir", "-p", FOLDER)
    shell("run-as", PACKAGE, "rm", "-f", f"{FOLDER}/{STAMP}")
    for name, expected in FILES.items():
        target = f"{FOLDER}/{name}"
        temporary = target + ".partial"
        staging = f"/data/local/tmp/dropzone-rn50-{name}"
        try:
            subprocess.run(
                adb + ["push", str(args.models / name), staging], check=True,
            )
            shell(f"cat {staging} | run-as {PACKAGE} sh -c 'cat > {temporary}'")
        finally:
            shell("rm", "-f", staging)
        result = shell("run-as", PACKAGE, "sha256sum", temporary)
        if result.split()[0] != expected:
            raise RuntimeError(f"Model transfer verification failed: {name}")
        shell("run-as", PACKAGE, "mv", temporary, target)
        print(f"Verified {name}", flush=True)
    shell("run-as", PACKAGE, "touch", f"{FOLDER}/{STAMP}")

if __name__ == "__main__":
    main()
