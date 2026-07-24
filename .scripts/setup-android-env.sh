#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Inkleaf Android Development Environment Setup Script
# ============================================================
# Usage:
#   ./scripts/setup-android-env.sh
#
# What it does:
#   1. Detects system info (OS, architecture, CPU, memory, disk space)
#   2. Installs a JDK (Temurin) into the user home if missing/too old
#   3. Installs Android CLI tool
#   4. Installs required Android SDK packages (platform-tools,
#      build-tools, platform) based on the project's build.gradle.kts
#   5. Configures China-friendly Maven and Gradle distribution mirrors
#   6. Configures shell environment variables (JAVA_HOME, ANDROID_HOME, PATH)
#   7. Verifies the installation
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- Configurable defaults ----
COMPILE_SDK="${COMPILE_SDK:-37}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-37.0.0}"
PLATFORM_PACKAGE="${PLATFORM_PACKAGE:-platforms/android-37.0}"
# Prefer Temurin 25 (matches gradle/gradle-daemon-jvm.properties toolchainVersion).
REQUIRED_JDK_MAJOR="${INKLEAF_REQUIRED_JDK_MAJOR:-25}"
JDK_MAJOR="${INKLEAF_JDK_MAJOR:-25}"
JAVA_HOME="${JAVA_HOME:-}"
JDK_INSTALL_ROOT="${INKLEAF_JDK_INSTALL_ROOT:-$HOME/.local/jdk}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_BIN_DIR="$HOME/.local/bin"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
CONFIGURE_CHINA_MIRRORS="${INKLEAF_CONFIGURE_CHINA_MIRRORS:-true}"
ALIYUN_MAVEN_MIRROR_URL="${INKLEAF_ALIYUN_MAVEN_MIRROR_URL:-https://maven.aliyun.com/repository/public}"
ALIYUN_GOOGLE_MIRROR_URL="${INKLEAF_ALIYUN_GOOGLE_MIRROR_URL:-https://maven.aliyun.com/repository/google}"
ALIYUN_GRADLE_PLUGIN_MIRROR_URL="${INKLEAF_ALIYUN_GRADLE_PLUGIN_MIRROR_URL:-https://maven.aliyun.com/repository/gradle-plugin}"
TENCENT_GRADLE_MIRROR_URL="${INKLEAF_TENCENT_GRADLE_MIRROR_URL:-https://mirrors.cloud.tencent.com/gradle/}"
# Optional override for the Adoptium binary API (must still return a JDK archive).
ADOPTIUM_JDK_API_BASE="${INKLEAF_ADOPTIUM_JDK_API_BASE:-https://api.adoptium.net/v3/binary/latest}"

# Hard minimums — below these the machine cannot usefully install/run the toolchain.
# Soft recommendations still warn but do not abort.
MIN_CPU_CORES="${INKLEAF_MIN_CPU_CORES:-2}"
MIN_TOTAL_MEM_MB="${INKLEAF_MIN_TOTAL_MEM_MB:-4096}"
MIN_DISK_MB="${INKLEAF_MIN_DISK_MB:-8192}"
RECOMMENDED_TOTAL_MEM_MB="${INKLEAF_RECOMMENDED_TOTAL_MEM_MB:-8192}"
RECOMMENDED_DISK_MB="${INKLEAF_RECOMMENDED_DISK_MB:-10240}"

# ---- Colors ----
if [ -t 1 ]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  BLUE='\033[0;34m'
  NC='\033[0m'
else
  RED=''
  GREEN=''
  YELLOW=''
  BLUE=''
  NC=''
fi

info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
success() { echo -e "${GREEN}[OK]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ---- Detect OS and architecture ----
detect_os() {
  case "$(uname -s)" in
    Linux*)  OS="linux" ;;
    Darwin*) OS="darwin" ;;
    MINGW*|MSYS*|CYGWIN*)
      error "Windows is not supported by this script. Use WSL or run manually."
      exit 1
      ;;
    *)
      error "Unsupported OS: $(uname -s)"
      exit 1
      ;;
  esac

  case "$(uname -m)" in
    x86_64|amd64) ARCH="x86_64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    *)
      error "Unsupported architecture: $(uname -m)"
      exit 1
      ;;
  esac

  info "Detected: OS=$OS, ARCH=$ARCH"
}

# ---- Helper for formatting sizes ----
format_size_mb() {
  local mb="${1:-0}"
  if [ "$mb" -ge 1024 ]; then
    awk -v mb="$mb" 'BEGIN {printf "%.1f GB", mb/1024}'
  else
    echo "${mb} MB"
  fi
}

# ---- Check system resources (CPU, Memory, Disk Space) ----
get_cpu_info() {
  local cores=""
  local model=""

  if command -v nproc >/dev/null 2>&1; then
    cores="$(nproc 2>/dev/null || true)"
  elif command -v sysctl >/dev/null 2>&1; then
    cores="$(sysctl -n hw.ncpu 2>/dev/null || true)"
  fi

  if [ -f /proc/cpuinfo ]; then
    model="$( (grep -m1 'model name' /proc/cpuinfo 2>/dev/null || true) | awk -F: '{print $2}' | sed 's/^[ \t]*//' )"
  elif command -v sysctl >/dev/null 2>&1; then
    model="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)"
  fi

  CPU_CORES=0
  if [[ "${cores:-}" =~ ^[0-9]+$ ]]; then
    CPU_CORES="$cores"
  fi

  if [ -n "$cores" ] && [ -n "$model" ]; then
    CPU_INFO="$cores cores ($model)"
  elif [ -n "$cores" ]; then
    CPU_INFO="$cores cores"
  elif [ -n "$model" ]; then
    CPU_INFO="$model"
  else
    CPU_INFO="Unknown"
  fi
}

get_mem_info() {
  TOTAL_MEM_MB=0
  AVAIL_MEM_MB=0

  if [ "$OS" = "linux" ] && [ -f /proc/meminfo ]; then
    local total_kb avail_kb
    total_kb="$(awk '/MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
    avail_kb="$(awk '/MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
    if [[ "${total_kb:-0}" =~ ^[0-9]+$ ]]; then
      TOTAL_MEM_MB=$((total_kb / 1024))
    fi
    if [[ "${avail_kb:-0}" =~ ^[0-9]+$ ]]; then
      AVAIL_MEM_MB=$((avail_kb / 1024))
    fi
  elif [ "$OS" = "darwin" ] && command -v sysctl >/dev/null 2>&1; then
    local mem_bytes
    mem_bytes="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
    if [[ "${mem_bytes:-0}" =~ ^[0-9]+$ ]]; then
      TOTAL_MEM_MB=$((mem_bytes / 1024 / 1024))
    fi

    if command -v vm_stat >/dev/null 2>&1; then
      local page_size free_pages inactive_pages
      page_size="$(vm_stat | awk '/page size of/ {print $8}' | tr -d '.' 2>/dev/null || echo 4096)"
      free_pages="$(vm_stat | awk '/Pages free:/ {print $3}' | tr -d '.' 2>/dev/null || echo 0)"
      inactive_pages="$(vm_stat | awk '/Pages inactive:/ {print $3}' | tr -d '.' 2>/dev/null || echo 0)"
      if [[ "${page_size:-0}" =~ ^[0-9]+$ ]] && [[ "${free_pages:-0}" =~ ^[0-9]+$ ]] && [[ "${inactive_pages:-0}" =~ ^[0-9]+$ ]]; then
        AVAIL_MEM_MB=$(((free_pages + inactive_pages) * page_size / 1024 / 1024))
      fi
    fi
  fi
}

get_disk_info() {
  local path="$1"
  local avail_kb
  avail_kb="$( (df -P -k "$path" 2>/dev/null || true) | awk 'NR==2 {print $4}' )"
  avail_kb="${avail_kb:-0}"
  if [[ "$avail_kb" =~ ^[0-9]+$ ]]; then
    echo $((avail_kb / 1024))
  else
    echo 0
  fi
}

fail_resource() {
  error "$*"
  RESOURCE_CHECK_FAILED=1
}

check_system_resources() {
  info "Checking system hardware resources..."
  RESOURCE_CHECK_FAILED=0

  # CPU
  get_cpu_info
  info "  CPU: $CPU_INFO"
  if [ "$CPU_CORES" -le 0 ]; then
    fail_resource "Unable to determine CPU core count. Refusing to continue."
  elif [ "$CPU_CORES" -lt "$MIN_CPU_CORES" ]; then
    fail_resource "CPU core count too low ($CPU_CORES). Need at least $MIN_CPU_CORES cores."
  fi

  # Memory
  get_mem_info
  if [ "$TOTAL_MEM_MB" -gt 0 ]; then
    local total_formatted avail_formatted
    total_formatted="$(format_size_mb "$TOTAL_MEM_MB")"
    if [ "$AVAIL_MEM_MB" -gt 0 ]; then
      avail_formatted="$(format_size_mb "$AVAIL_MEM_MB")"
      info "  Memory: $total_formatted total ($avail_formatted available)"
    else
      info "  Memory: $total_formatted total"
    fi

    if [ "$TOTAL_MEM_MB" -lt "$MIN_TOTAL_MEM_MB" ]; then
      fail_resource "RAM too low ($total_formatted). Need at least $(format_size_mb "$MIN_TOTAL_MEM_MB") total."
    elif [ "$TOTAL_MEM_MB" -lt "$RECOMMENDED_TOTAL_MEM_MB" ]; then
      warn "  Low RAM ($total_formatted). $(format_size_mb "$RECOMMENDED_TOTAL_MEM_MB") is recommended; builds may be slow or OOM."
    fi
  else
    fail_resource "Unable to determine total system memory. Refusing to continue."
  fi

  # Disk Space
  local check_path="$HOME"
  if [ -d "$ANDROID_HOME" ]; then
    check_path="$ANDROID_HOME"
  elif [ -d "$(dirname "$ANDROID_HOME")" ]; then
    check_path="$(dirname "$ANDROID_HOME")"
  fi

  local disk_avail_mb
  disk_avail_mb="$(get_disk_info "$check_path")"
  if [ "$disk_avail_mb" -gt 0 ]; then
    local disk_formatted
    disk_formatted="$(format_size_mb "$disk_avail_mb")"
    info "  Disk Space: $disk_formatted available ($check_path)"
    if [ "$disk_avail_mb" -lt "$MIN_DISK_MB" ]; then
      fail_resource "Disk space too low ($disk_formatted on $check_path). Need at least $(format_size_mb "$MIN_DISK_MB") free."
    elif [ "$disk_avail_mb" -lt "$RECOMMENDED_DISK_MB" ]; then
      warn "  Tight disk space ($disk_formatted). $(format_size_mb "$RECOMMENDED_DISK_MB") free is recommended for SDK & Gradle caches."
    fi
  else
    fail_resource "Unable to determine free disk space on $check_path. Refusing to continue."
  fi

  if [ "$RESOURCE_CHECK_FAILED" -ne 0 ]; then
    error "System resources are below the minimum required to install/use this environment."
    error "Override thresholds only if you know what you are doing:"
    error "  INKLEAF_MIN_CPU_CORES (default $MIN_CPU_CORES)"
    error "  INKLEAF_MIN_TOTAL_MEM_MB (default $MIN_TOTAL_MEM_MB)"
    error "  INKLEAF_MIN_DISK_MB (default $MIN_DISK_MB)"
    exit 1
  fi

  success "System resources meet minimum requirements"
}

# ---- Check prerequisites ----
check_prerequisites() {
  info "Checking prerequisites..."

  if ! command -v curl >/dev/null 2>&1; then
    error "curl is required but not installed."
    exit 1
  fi
  success "curl is available"

  if ! command -v tar >/dev/null 2>&1; then
    error "tar is required but not installed."
    exit 1
  fi
  success "tar is available"

  ensure_java
}

# ---- Java / JDK helpers ----
java_major_version() {
  local java_bin="${1:-java}"
  local version_line major

  version_line="$("$java_bin" -version 2>&1 | head -1 || true)"
  # Examples: openjdk version "17.0.13"  /  openjdk version "1.8.0_422"
  major="$(printf '%s\n' "$version_line" | sed -n 's/.*version "\([0-9][0-9]*\)\..*/\1/p')"
  if [ "$major" = "1" ]; then
    major="$(printf '%s\n' "$version_line" | sed -n 's/.*version "1\.\([0-9][0-9]*\)\..*/\1/p')"
  fi
  if [[ "${major:-}" =~ ^[0-9]+$ ]]; then
    echo "$major"
  else
    echo 0
  fi
}

resolve_existing_java_home() {
  local java_bin candidate major

  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    major="$(java_major_version "$JAVA_HOME/bin/java")"
    if [ "$major" -ge "$REQUIRED_JDK_MAJOR" ]; then
      echo "$JAVA_HOME"
      return 0
    fi
  fi

  if command -v java >/dev/null 2>&1; then
    java_bin="$(command -v java)"
    major="$(java_major_version "$java_bin")"
    if [ "$major" -ge "$REQUIRED_JDK_MAJOR" ]; then
      if command -v readlink >/dev/null 2>&1; then
        candidate="$(readlink -f "$java_bin" 2>/dev/null || true)"
      else
        candidate=""
      fi
      if [ -z "$candidate" ]; then
        candidate="$java_bin"
      fi
      # .../bin/java -> JAVA_HOME
      echo "$(cd "$(dirname "$candidate")/.." && pwd)"
      return 0
    fi
  fi

  # Reuse a previous user-local install if present.
  if [ -x "$JDK_INSTALL_ROOT/current/bin/java" ]; then
    major="$(java_major_version "$JDK_INSTALL_ROOT/current/bin/java")"
    if [ "$major" -ge "$REQUIRED_JDK_MAJOR" ]; then
      echo "$JDK_INSTALL_ROOT/current"
      return 0
    fi
  fi

  return 1
}

adoptium_os_name() {
  case "$OS" in
    linux) echo "linux" ;;
    darwin) echo "mac" ;;
    *)
      error "Unsupported OS for JDK install: $OS"
      exit 1
      ;;
  esac
}

adoptium_arch_name() {
  case "$ARCH" in
    x86_64) echo "x64" ;;
    arm64) echo "aarch64" ;;
    *)
      error "Unsupported architecture for JDK install: $ARCH"
      exit 1
      ;;
  esac
}

install_temurin_jdk() {
  local os_name arch_name download_url archive_path extract_dir
  local marker_dir extracted_home

  os_name="$(adoptium_os_name)"
  arch_name="$(adoptium_arch_name)"
  download_url="${ADOPTIUM_JDK_API_BASE%/}/${JDK_MAJOR}/ga/${os_name}/${arch_name}/jdk/hotspot/normal/eclipse?project=jdk"
  marker_dir="$JDK_INSTALL_ROOT/temurin-${JDK_MAJOR}"
  archive_path="$(mktemp "${TMPDIR:-/tmp}/temurin-${JDK_MAJOR}.XXXXXX.tar.gz")"
  extract_dir="$(mktemp -d "${TMPDIR:-/tmp}/temurin-${JDK_MAJOR}-extract.XXXXXX")"

  info "Installing Temurin JDK ${JDK_MAJOR} into $marker_dir (user-local, no root)..."
  info "  Download: $download_url"

  # shellcheck disable=SC2064
  trap "rm -rf '$archive_path' '$extract_dir'" RETURN

  if ! curl -fL --retry 3 --retry-delay 2 -o "$archive_path" "$download_url"; then
    error "Failed to download Temurin JDK ${JDK_MAJOR}."
    error "Override API base with INKLEAF_ADOPTIUM_JDK_API_BASE if needed."
    exit 1
  fi

  if ! tar -xzf "$archive_path" -C "$extract_dir"; then
    error "Failed to extract JDK archive."
    exit 1
  fi

  extracted_home="$(find "$extract_dir" -mindepth 1 -maxdepth 3 -type f -path '*/bin/java' 2>/dev/null | head -1 || true)"
  if [ -z "$extracted_home" ]; then
    error "Downloaded archive does not contain bin/java."
    exit 1
  fi
  extracted_home="$(cd "$(dirname "$extracted_home")/.." && pwd)"

  if [ ! -x "$extracted_home/bin/java" ]; then
    error "Extracted JDK is not executable: $extracted_home/bin/java"
    exit 1
  fi

  mkdir -p "$JDK_INSTALL_ROOT"
  rm -rf "$marker_dir"
  mkdir -p "$marker_dir"
  # Copy resolved JAVA_HOME tree into a stable path (macOS archives already
  # resolve to Contents/Home via the bin/java lookup above).
  cp -a "$extracted_home"/. "$marker_dir"/

  ln -sfn "$marker_dir" "$JDK_INSTALL_ROOT/current"

  JAVA_HOME="$marker_dir"
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"

  local major
  major="$(java_major_version "$JAVA_HOME/bin/java")"
  if [ "$major" -lt "$REQUIRED_JDK_MAJOR" ]; then
    error "Installed JDK major version $major is below required $REQUIRED_JDK_MAJOR."
    exit 1
  fi

  success "Installed Temurin JDK ${major}: $JAVA_HOME"
  success "  $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
}

ensure_java() {
  local existing_home major

  if existing_home="$(resolve_existing_java_home)"; then
    JAVA_HOME="$existing_home"
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
    major="$(java_major_version "$JAVA_HOME/bin/java")"
    success "Java available: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
    info "  JAVA_HOME=$JAVA_HOME (major=$major)"
    return 0
  fi

  if command -v java >/dev/null 2>&1; then
    major="$(java_major_version "$(command -v java)")"
    warn "Found Java major=$major, but JDK ${REQUIRED_JDK_MAJOR}+ is required. Installing latest Temurin ${JDK_MAJOR}..."
  else
    info "Java not found. Installing latest Temurin JDK ${JDK_MAJOR}..."
  fi

  install_temurin_jdk
}

# ---- Install Android CLI ----
install_android_cli() {
  if command -v android >/dev/null 2>&1; then
    success "Android CLI already installed: $(android --version 2>&1 | head -1)"
    return 0
  fi

  info "Installing Android CLI..."
  mkdir -p "$ANDROID_BIN_DIR"

  local install_url
  case "$OS" in
    linux)
      install_url="https://dl.google.com/android/cli/latest/linux_${ARCH}/install.sh"
      ;;
    darwin)
      install_url="https://dl.google.com/android/cli/latest/darwin_${ARCH}/install.sh"
      ;;
  esac

  info "Downloading from: $install_url"
  if ! curl -fsSL "$install_url" | bash; then
    error "Failed to install Android CLI."
    exit 1
  fi

  # Ensure the binary is in PATH for the current session
  export PATH="$ANDROID_BIN_DIR:$PATH"

  if command -v android >/dev/null 2>&1; then
    success "Android CLI installed: $(android --version 2>&1 | head -1)"
  else
    error "Android CLI installation completed but 'android' not found in PATH."
    exit 1
  fi
}

# ---- Install SDK packages ----
install_sdk_packages() {
  info "Installing Android SDK packages..."
  info "  SDK location: $ANDROID_HOME"
  info "  Compile SDK: $COMPILE_SDK"
  info "  Build tools: $BUILD_TOOLS_VERSION"
  info "  Platform: $PLATFORM_PACKAGE"

  # Ensure android command is available in this session
  export PATH="$ANDROID_BIN_DIR:$PATH"

  # Check what's already installed
  local installed
  installed="$(android sdk list 2>/dev/null | grep -E "^\s+" || true)"

  local packages=()

  # Platform tools
  if echo "$installed" | grep -q "platform-tools"; then
    success "platform-tools already installed"
  else
    packages+=("platform-tools")
  fi

  # Build tools
  if echo "$installed" | grep -q "build-tools/$BUILD_TOOLS_VERSION"; then
    success "build-tools/$BUILD_TOOLS_VERSION already installed"
  else
    packages+=("build-tools/$BUILD_TOOLS_VERSION")
  fi

  # Platform
  local platform_name
  platform_name="$(echo "$PLATFORM_PACKAGE" | tr '/' '-')"
  if echo "$installed" | grep -q "$platform_name"; then
    success "$PLATFORM_PACKAGE already installed"
  else
    packages+=("$PLATFORM_PACKAGE")
  fi

  if [ ${#packages[@]} -eq 0 ]; then
    success "All SDK packages already installed"
    return 0
  fi

  info "Installing: ${packages[*]}"
  android sdk install "${packages[@]}"

  success "SDK packages installed"
}

# ---- Configure China-friendly Gradle mirrors ----
china_mirrors_enabled() {
  case "$CONFIGURE_CHINA_MIRRORS" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    0|false|FALSE|no|NO|off|OFF) return 1 ;;
    *)
      error "Invalid INKLEAF_CONFIGURE_CHINA_MIRRORS value: $CONFIGURE_CHINA_MIRRORS"
      error "Use true/false, yes/no, on/off, or 1/0."
      exit 1
      ;;
  esac
}

validate_mirror_url() {
  local label="$1"
  local url="$2"

  case "$url" in
    https://*) ;;
    *)
      error "$label must use HTTPS: $url"
      exit 1
      ;;
  esac

  # Keep generated Gradle and properties files safe from shell/code injection.
  case "$url" in
    *[!A-Za-z0-9._~:/?#@%+=,-]*)
      error "$label contains unsupported characters: $url"
      exit 1
      ;;
  esac
}

configure_maven_mirror() {
  local init_dir="$GRADLE_HOME_DIR/init.d"
  local init_script="$init_dir/inkleaf-cn-mirrors.gradle"
  local temp_file

  mkdir -p "$init_dir"
  temp_file="$(mktemp "$init_dir/.inkleaf-cn-mirrors.gradle.XXXXXX")"

  cat > "$temp_file" <<EOF
// Managed by Inkleaf's setup-android-env.sh.
// Mirrors are inserted first; repositories declared by the build remain fallbacks.
def aliyunGoogleMirrorUrl = '$ALIYUN_GOOGLE_MIRROR_URL'
def aliyunPublicMirrorUrl = '$ALIYUN_MAVEN_MIRROR_URL'
def aliyunGradlePluginMirrorUrl = '$ALIYUN_GRADLE_PLUGIN_MIRROR_URL'

beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven {
            name = 'AliyunGoogleMirror'
            url = settings.uri(aliyunGoogleMirrorUrl)
        }
        maven {
            name = 'AliyunGradlePluginMirror'
            url = settings.uri(aliyunGradlePluginMirrorUrl)
        }
        maven {
            name = 'AliyunPublicMirror'
            url = settings.uri(aliyunPublicMirrorUrl)
        }
    }

    settings.dependencyResolutionManagement.repositories {
        maven {
            name = 'AliyunGoogleMirror'
            url = settings.uri(aliyunGoogleMirrorUrl)
        }
        maven {
            name = 'AliyunPublicMirror'
            url = settings.uri(aliyunPublicMirrorUrl)
        }
        maven {
            name = 'AliyunGradlePluginMirror'
            url = settings.uri(aliyunGradlePluginMirrorUrl)
        }
    }
}
EOF

  mv "$temp_file" "$init_script"
  success "Maven mirrors configured (Google, Public, Gradle-Plugin)"
  info "  Aliyun Google: $ALIYUN_GOOGLE_MIRROR_URL"
  info "  Aliyun Public: $ALIYUN_MAVEN_MIRROR_URL"
  info "  Aliyun Gradle Plugin: $ALIYUN_GRADLE_PLUGIN_MIRROR_URL"
  info "  Gradle init script: $init_script"
}

configure_gradle_distribution_mirror() {
  local wrapper_properties="$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties"
  local distribution_value
  local distribution_url
  local distribution_file
  local mirror_url
  local temp_file

  if [ ! -f "$wrapper_properties" ]; then
    warn "Gradle wrapper properties not found: $wrapper_properties"
    return 0
  fi

  distribution_value="$(sed -n 's/^distributionUrl=//p' "$wrapper_properties" | head -1)"
  if [ -z "$distribution_value" ]; then
    warn "distributionUrl not found in $wrapper_properties"
    return 0
  fi

  # Java properties commonly escape the URL colon; normalize it before parsing.
  distribution_url="$(printf '%s\n' "$distribution_value" | sed 's/\\:/:/g')"
  distribution_file="${distribution_url##*/}"
  distribution_file="${distribution_file%%\?*}"

  case "$distribution_file" in
    gradle-*-bin.zip|gradle-*-all.zip) ;;
    *)
      warn "Unsupported Gradle distribution URL: $distribution_url"
      warn "Keeping the existing distributionUrl."
      return 0
      ;;
  esac

  mirror_url="${TENCENT_GRADLE_MIRROR_URL%/}/$distribution_file"
  if [ "$distribution_url" = "$mirror_url" ]; then
    success "Gradle distribution mirror already configured: $mirror_url"
    return 0
  fi

  temp_file="$(mktemp "$wrapper_properties.XXXXXX")"
  if ! awk -v replacement="distributionUrl=$mirror_url" '
    BEGIN { updated = 0 }
    /^distributionUrl=/ {
      print replacement
      updated = 1
      next
    }
    { print }
    END { if (!updated) exit 1 }
  ' "$wrapper_properties" > "$temp_file"; then
    rm -f "$temp_file"
    error "Failed to update Gradle wrapper distribution URL."
    exit 1
  fi

  mv "$temp_file" "$wrapper_properties"
  success "Gradle distribution mirror configured: $mirror_url"
  info "  Updated local wrapper file: $wrapper_properties"
}

configure_gradle_mirrors() {
  if ! china_mirrors_enabled; then
    info "China mirror configuration skipped (INKLEAF_CONFIGURE_CHINA_MIRRORS=$CONFIGURE_CHINA_MIRRORS)"
    return 0
  fi

  validate_mirror_url "Aliyun Maven mirror URL" "$ALIYUN_MAVEN_MIRROR_URL"
  validate_mirror_url "Aliyun Google mirror URL" "$ALIYUN_GOOGLE_MIRROR_URL"
  validate_mirror_url "Aliyun Gradle Plugin mirror URL" "$ALIYUN_GRADLE_PLUGIN_MIRROR_URL"
  validate_mirror_url "Tencent Gradle mirror URL" "$TENCENT_GRADLE_MIRROR_URL"

  info "Configuring China-friendly Gradle mirrors..."
  configure_maven_mirror
  configure_gradle_distribution_mirror
}

# ---- Configure shell environment ----
append_env_block_if_needed() {
  local rc_file="$1"
  local env_block="$2"
  local label="$3"

  if [ ! -f "$rc_file" ]; then
    return 0
  fi

  if grep -q "setup-android-env.sh" "$rc_file" 2>/dev/null; then
    # Refresh managed block so JAVA_HOME / build-tools path stay current.
    local temp_file
    temp_file="$(mktemp "$rc_file.XXXXXX")"
    awk '
      BEGIN { skip = 0 }
      /^# Android SDK \(added by setup-android-env\.sh\)$/ { skip = 1; next }
      skip == 1 && /^export PATH=/ { skip = 0; next }
      skip == 1 { next }
      { print }
    ' "$rc_file" > "$temp_file"
    printf '%s\n' "$env_block" >> "$temp_file"
    mv "$temp_file" "$rc_file"
    success "Updated $label"
    return 1
  fi

  printf '%s\n' "$env_block" >> "$rc_file"
  success "Updated $label"
  return 1
}

configure_shell_env() {
  info "Configuring shell environment..."

  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    error "JAVA_HOME is not set to a usable JDK before shell configuration."
    exit 1
  fi

  local env_block
  env_block=$(cat <<EOF

# Android SDK (added by setup-android-env.sh)
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="\$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="\$HOME/Android/Sdk"
export PATH="\$JAVA_HOME/bin:\$HOME/.local/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION:\$PATH"
EOF
)

  local updated=0

  if append_env_block_if_needed "$HOME/.bashrc" "$env_block" "~/.bashrc"; then
    :
  else
    updated=1
  fi

  if append_env_block_if_needed "$HOME/.zshrc" "$env_block" "~/.zshrc"; then
    :
  else
    updated=1
  fi

  # Apply to current session
  export JAVA_HOME
  export ANDROID_HOME
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$JAVA_HOME/bin:$ANDROID_BIN_DIR:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION:$PATH"

  if [ "$updated" -eq 1 ]; then
    warn "Remember to restart your shell or run:"
    warn "  source ~/.bashrc   # for bash"
    warn "  source ~/.zshrc    # for zsh"
  fi
}

# ---- Verify installation ----
verify_installation() {
  info "Verifying installation..."

  local all_ok=1

  # Java
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    local major
    major="$(java_major_version "$JAVA_HOME/bin/java")"
    if [ "$major" -ge "$REQUIRED_JDK_MAJOR" ]; then
      success "java: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
      success "JAVA_HOME=$JAVA_HOME"
    else
      error "java major $major is below required $REQUIRED_JDK_MAJOR"
      all_ok=0
    fi
  else
    error "java: not found"
    all_ok=0
  fi

  # Android CLI
  if command -v android >/dev/null 2>&1; then
    success "android: $(android --version 2>&1 | head -1)"
  else
    error "android: not found"
    all_ok=0
  fi

  # ADB
  if command -v adb >/dev/null 2>&1; then
    success "adb: $(adb --version 2>&1 | head -1)"
  else
    error "adb: not found"
    all_ok=0
  fi

  # SDK directory
  if [ -d "$ANDROID_HOME" ]; then
    success "ANDROID_HOME=$ANDROID_HOME"
  else
    error "ANDROID_HOME directory not found: $ANDROID_HOME"
    all_ok=0
  fi

  # Platform
  local android_jar
  android_jar="$(find "$ANDROID_HOME/platforms" -name "android.jar" -maxdepth 3 2>/dev/null | head -1)"
  if [ -n "$android_jar" ]; then
    success "android.jar found: $android_jar"
  else
    error "android.jar not found in $ANDROID_HOME/platforms"
    all_ok=0
  fi

  # Build tools
  local aapt2
  aapt2="$(find "$ANDROID_HOME/build-tools" -name "aapt2" -maxdepth 3 2>/dev/null | head -1)"
  if [ -n "$aapt2" ]; then
    success "build-tools found: $aapt2"
  else
    error "build-tools not found in $ANDROID_HOME/build-tools"
    all_ok=0
  fi

  echo ""
  if [ "$all_ok" -eq 1 ]; then
    success "================================================"
    success "  Android development environment is ready!"
    success "================================================"
  else
    error "================================================"
    error "  Some components failed to install."
    error "================================================"
    exit 1
  fi
}

# ---- Read project config from build.gradle.kts ----
read_project_config() {
  local build_file="$PROJECT_ROOT/app/build.gradle.kts"
  if [ ! -f "$build_file" ]; then
    warn "Project build file not found: $build_file"
    warn "Using default SDK versions."
    return 0
  fi

  info "Reading project configuration from app/build.gradle.kts..."

  # Extract compileSdk
  local compile_sdk
  compile_sdk="$(grep -E '^\s*compileSdk\s*=' "$build_file" | head -1 | sed 's/.*=\s*//' | tr -d ' ')"
  if [ -n "$compile_sdk" ]; then
    COMPILE_SDK="$compile_sdk"
    info "  compileSdk = $COMPILE_SDK (from project)"
  fi

  # Set platform package based on compileSdk
  PLATFORM_PACKAGE="platforms/android-${COMPILE_SDK}.0"
  BUILD_TOOLS_VERSION="${COMPILE_SDK}.0.0"
}

# ---- Main ----
main() {
  echo ""
  echo "================================================"
  echo "  Inkleaf Android Environment Setup"
  echo "================================================"
  echo ""

  detect_os
  check_system_resources
  check_prerequisites
  read_project_config
  configure_gradle_mirrors
  install_android_cli
  install_sdk_packages
  configure_shell_env
  verify_installation

  echo ""
  info "Project root: $PROJECT_ROOT"
  info "To build the project, run:"
  info "  cd $PROJECT_ROOT"
  info "  ./gradlew assembleDebug"
  echo ""
}

main "$@"
