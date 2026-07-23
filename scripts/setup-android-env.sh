#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Inkleaf Android Development Environment Setup Script
# ============================================================
# Usage:
#   ./scripts/setup-android-env.sh
#
# What it does:
#   1. Installs Android CLI tool
#   2. Installs required Android SDK packages (platform-tools,
#      build-tools, platform) based on the project's build.gradle.kts
#   3. Configures shell environment variables (ANDROID_HOME, PATH)
#   4. Verifies the installation
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- Configurable defaults ----
COMPILE_SDK="${COMPILE_SDK:-37}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-37.0.0}"
PLATFORM_PACKAGE="${PLATFORM_PACKAGE:-platforms/android-37.0}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_BIN_DIR="$HOME/.local/bin"

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

# ---- Check prerequisites ----
check_prerequisites() {
  info "Checking prerequisites..."

  if ! command -v curl >/dev/null 2>&1; then
    error "curl is required but not installed."
    exit 1
  fi
  success "curl is available"

  if ! command -v java >/dev/null 2>&1; then
    warn "Java not found in PATH. Android CLI and Gradle require Java."
    warn "Please install JDK 11+ before proceeding."
    read -rp "Continue anyway? [y/N] " answer
    case "$answer" in
      [Yy]|[Yy][Ee][Ss]) ;;
      *) exit 1 ;;
    esac
  else
    local java_version
    java_version="$(java -version 2>&1 | head -1)"
    success "Java available: $java_version"
  fi
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

# ---- Configure shell environment ----
configure_shell_env() {
  info "Configuring shell environment..."

  local env_block
  env_block=$(cat <<EOF

# Android SDK (added by setup-android-env.sh)
export ANDROID_HOME="\$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="\$HOME/Android/Sdk"
export PATH="\$HOME/.local/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION:\$PATH"
EOF
)

  local updated=0

  # Update .bashrc
  if [ -f "$HOME/.bashrc" ]; then
    if grep -q "setup-android-env.sh" "$HOME/.bashrc" 2>/dev/null; then
      info ".bashrc already configured"
    else
      echo "$env_block" >> "$HOME/.bashrc"
      success "Updated ~/.bashrc"
      updated=1
    fi
  fi

  # Update .zshrc
  if [ -f "$HOME/.zshrc" ]; then
    if grep -q "setup-android-env.sh" "$HOME/.zshrc" 2>/dev/null; then
      info ".zshrc already configured"
    else
      echo "$env_block" >> "$HOME/.zshrc"
      success "Updated ~/.zshrc"
      updated=1
    fi
  fi

  # Apply to current session
  export ANDROID_HOME
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$ANDROID_BIN_DIR:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION:$PATH"

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
  check_prerequisites
  read_project_config
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
