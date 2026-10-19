#!/usr/bin/env bash
# Linux x86_64 cloud build prerequisites, kept outside the application checkout.
# Run: bash scripts/bootstrap-android.sh
# Then: source /workspace/.tools/env.sh && gradle --no-daemon assembleDebug
set -euo pipefail
export COSMO_TOOLS_ROOT="${COSMO_TOOLS_ROOT:-/workspace/.tools}"
mkdir -p "$COSMO_TOOLS_ROOT/downloads"
python3 - <<'PY'
import concurrent.futures, hashlib, os, pathlib, shutil, tarfile, urllib.request, zipfile
root = pathlib.Path(os.environ['COSMO_TOOLS_ROOT'])
# Checksums published by the official Temurin release, Gradle distribution metadata,
# and Google's repository2-1.xml (cmdline-tools;12.0). TLS remains enabled throughout.
artifacts = [
 ('jdk.tar.gz', 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz', 'sha256', 'ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94'),
 ('gradle-8.11.1-bin.zip', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip', 'sha256', 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'),
 ('commandlinetools-linux-11076708_latest.zip', 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip', 'sha1', 'd313adb7aedccf6cf0cfca51ec180f0059f5f8f8'),
]
def fetch(item):
    name, url, algorithm, expected = item
    destination = root / 'downloads' / name
    if not destination.exists():
        partial = destination.with_suffix(destination.suffix + '.part')
        with urllib.request.urlopen(url, timeout=120) as response, partial.open('wb') as out:
            shutil.copyfileobj(response, out)
        partial.rename(destination)
    digest = hashlib.new(algorithm)
    with destination.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise SystemExit('Checksum mismatch; remove and redownload: ' + str(destination))
    print('Verified ' + name, flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
    list(executor.map(fetch, artifacts))
if not (root / 'jdk' / 'bin' / 'javac').exists():
    with tarfile.open(root / 'downloads' / 'jdk.tar.gz') as archive:
        directory = archive.getnames()[0].split('/')[0]
        archive.extractall(root, filter='data')
    (root / directory).rename(root / 'jdk')
if not (root / 'gradle-8.11.1' / 'bin' / 'gradle').exists():
    with zipfile.ZipFile(root / 'downloads' / 'gradle-8.11.1-bin.zip') as archive:
        archive.extractall(root)
    (root / 'gradle-8.11.1' / 'bin' / 'gradle').chmod(0o755)
target = root / 'android-sdk' / 'cmdline-tools' / '12.0'
if not (target / 'bin' / 'sdkmanager').exists():
    stage = root / 'cmdline-tools-stage'
    stage.mkdir(exist_ok=True)
    with zipfile.ZipFile(root / 'downloads' / 'commandlinetools-linux-11076708_latest.zip') as archive:
        archive.extractall(stage)
    target.parent.mkdir(parents=True, exist_ok=True)
    (stage / 'cmdline-tools').rename(target)
    stage.rmdir()
    for executable in (target / 'bin').iterdir():
        executable.chmod(0o755)
PY
export JAVA_HOME="$COSMO_TOOLS_ROOT/jdk"
export ANDROID_HOME="$COSMO_TOOLS_ROOT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$COSMO_TOOLS_ROOT/gradle-user-home"
export ANDROID_USER_HOME="$COSMO_TOOLS_ROOT/android-user-home"
export PATH="$JAVA_HOME/bin:$COSMO_TOOLS_ROOT/gradle-8.11.1/bin:$ANDROID_HOME/cmdline-tools/12.0/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME/cache" "$COSMO_TOOLS_ROOT/user-home/.android/cache"
# Add the cloud proxy CA to a copy of the JDK's standard trust store. Never disable
# certificate verification, and never persist proxy credentials or secret values.
python3 - <<'PY'
import hashlib, os, pathlib, shlex, shutil, subprocess, urllib.parse
root = pathlib.Path(os.environ['COSMO_TOOLS_ROOT'])
truststore = root / 'java-cacerts'
shutil.copyfile(root / 'jdk' / 'lib' / 'security' / 'cacerts', truststore)
certificate = os.environ.get('CODEX_PROXY_CERT', '')
if certificate and pathlib.Path(certificate).is_file():
    subprocess.run([str(root / 'jdk' / 'bin' / 'keytool'), '-importcert', '-noprompt', '-alias', 'cosmo-cloud-proxy', '-file', certificate, '-keystore', str(truststore), '-storepass', 'changeit'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
flags = ['-Djavax.net.ssl.trustStore=' + str(truststore), '-Duser.home=' + str(root / 'user-home')]
for scheme in ('http', 'https'):
    configured = os.environ.get(scheme.upper() + '_PROXY') or os.environ.get(scheme + '_proxy')
    if configured:
        parsed = urllib.parse.urlsplit(configured)
        if parsed.hostname:
            flags += ['-D' + scheme + '.proxyHost=' + parsed.hostname,
                      '-D' + scheme + '.proxyPort=' + str(parsed.port or (443 if parsed.scheme == 'https' else 80))]
lines = ['# Generated local toolchain configuration; contains no credentials.',
         'export COSMO_TOOLS_ROOT=' + shlex.quote(str(root)),
         'export JAVA_HOME=' + shlex.quote(str(root / 'jdk')),
         'export ANDROID_HOME=' + shlex.quote(str(root / 'android-sdk')),
         'export ANDROID_SDK_ROOT="$ANDROID_HOME"',
         'export ANDROID_USER_HOME=' + shlex.quote(str(root / 'android-user-home')),
         'export GRADLE_USER_HOME=' + shlex.quote(str(root / 'gradle-user-home')),
         'export PATH="$JAVA_HOME/bin:$COSMO_TOOLS_ROOT/gradle-8.11.1/bin:$ANDROID_HOME/cmdline-tools/12.0/bin:$ANDROID_HOME/platform-tools:$PATH"',
         'export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} "' + shlex.quote(' '.join(flags))]
(root / 'env.sh').write_text('\n'.join(lines) + '\n')
PY
# shellcheck disable=SC1090
source "$COSMO_TOOLS_ROOT/env.sh"
# Accept the SDK licenses required for the selected development packages.
# Avoid a pipefail caused by yes receiving SIGPIPE after sdkmanager exits.
set +o pipefail
yes 2>/dev/null | sdkmanager --licenses > "$COSMO_TOOLS_ROOT/android-licenses.log" 2>&1
license_status="${PIPESTATUS[1]}"
set -o pipefail
if [ "$license_status" -ne 0 ]; then
  cat "$COSMO_TOOLS_ROOT/android-licenses.log"
  exit "$license_status"
fi
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
printf 'Toolchain ready. Run: source %s/env.sh\n' "$COSMO_TOOLS_ROOT"
