#!/system/bin/sh

set -e
set -u

# --- Define Termux Prefix (Replace com.itsaky.androidide if your package name is different) ---
termux_prefix="/data/data/com.itsaky.androidide/files/usr"
# --- Check if prefix exists, create if not (basic sanity check) ---
if [ ! -d "$termux_prefix" ]; then
    echo "Warning: Termux prefix '$termux_prefix' not found. Attempting to create." >&2
    # Creating /data/data/... might fail due to permissions if not run by the app itself
    mkdir -p "$termux_prefix/bin" "$termux_prefix/etc" "$termux_prefix/opt" || {
        echo "Error: Failed to create Termux prefix directories." >&2
        exit 1
    }
fi


Color_Off='\033[0m'
Red='\033[0;31m'
Green='\033[0;32m'
Blue='\033[0;34m'
Orange="\e[38;5;208m"

# Default values
arch=$(uname -m)
install_dir=$HOME
sdkver_org=34.0.4
with_cmdline=true
assume_yes=false
manifest="https://raw.githubusercontent.com/AndroidIDEOfficial/androidide-tools/main/manifest.json"
pkgm="pkg"
pkg_curl="libcurl"
# Define pkgs initially, will append later
pkgs="jq tar"
jdk_version="17"

print_info() {
  # shellcheck disable=SC2059
  printf "${Blue}%s${Color_Off}\n" "$1"
}

print_err() {
  # shellcheck disable=SC2059
  printf "${Red}%s${Color_Off}\n" "$1"
}

print_warn() {
  # shellcheck disable=SC2059
  printf "${Orange}%s${Color_Off}\n" "$1"
}

print_success() {
  # shellcheck disable=SC2059
  printf "${Green}%s${Color_Off}\n" "$1"
}

is_yes() {
  msg=$1
  printf "%s ([y]es/[n]o): " "$msg"

  if [ "$assume_yes" = "true" ]; then # Use single '=' for POSIX sh string comparison
    ans="y"
    echo "$ans" # Quote the variable just in case
  else
    read -r ans
  fi

  # Use a POSIX-compliant case statement for matching
  case "$ans" in
    # Match variations of yes, case-insensitively
    y | Y | ye | YE | yes | YES)
      return 0 # Success (true)
      ;;
    *)
      return 1 # Failure (false)
      ;;
  esac
}

check_arg_value() {
  option_name="$1"
  arg_value="$2"
  if [ -z "$arg_value" ]; then # Use [ -z ... ] for POSIX compliance
    print_err "No value provided for $option_name!" >&2
    exit 1
  fi
}

check_command_exists() {
  if command -v "$1" >/dev/null 2>&1; then # More portable check
    return
  else
    print_err "Command '$1' not found!"
    exit 1
  fi
}

install_packages() {
  # $@ expands to separate arguments, which is needed by install
  # shellcheck disable=SC2086
  if [ "$assume_yes" = "true" ]; then # Use single '=' for POSIX sh string comparison
    $pkgm install $@ -y
  else
    $pkgm install $@
  fi
}

print_help() {
  echo "AndroidIDE build tools installer"
  echo "This script helps you easily install build tools in AndroidIDE."
  echo ""
  echo "Usage:"
  echo "${0} -s 34.0.4 -c -j 17"
  echo "This will install Android SDK 34.0.4 with command line tools and JDK 17."
  echo ""
  echo "Options :"
  echo "-i   Set the installation directory. Defaults to \$HOME."
  echo "-s   Android SDK version to download."
  echo "-c   Download Android SDK with command line tools."
  echo "-m   Manifest file URL. Defaults to 'manifest.json' in 'androidide-tools' GitHub repository."
  echo "-j   OpenJDK version to install. Values can be '17' or '21'"
  echo "-g   Install package: 'git'."
  echo "-o   Install package: 'openssh'." # Corrected help message
  echo "-y   Assume \"yes\" as answer to all prompts and run non-interactively."
  echo ""
  echo "For testing purposes:"
  echo "-a   CPU architecture. Extracted using 'uname -m' by default."
  echo "-p   Package manager. Defaults to 'pkg'."
  echo "-l   Name of curl package that will be installed before starting installation process. Defaults to 'libcurl'."
  echo ""
  echo "-h   Prints this message."
}

download_and_extract() {
  # Display name to use in print messages
  name=$1

  # URL to download from
  url=$2

  # Directory in which the downloaded archive will be extracted
  dir=$3

  # Destination path for downloading the file
  dest=$4

  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
  fi

  cd "$dir" || exit 1 # Exit if cd fails

  do_download=true
  if [ -f "$dest" ]; then
    file_base_name=$(basename "$dest") # Use different variable name
    print_info "File ${file_base_name} already exists."
    if is_yes "Do you want to skip the download process?"; then
      do_download=false
    fi
    echo ""
  fi

  if [ "$do_download" = "true" ]; then
    print_info "Downloading $name..."
    curl -L -o "$dest" "$url" --http1.1 # Consider adding --fail to exit on HTTP errors
    print_success "$name has been downloaded."
    echo ""
  fi

  if [ ! -f "$dest" ]; then
    print_err "The downloaded file $name does not exist. Cannot proceed..."
    exit 1
  fi

  # Extract the downloaded archive
  print_info "Extracting downloaded archive..."
  # Ensure tar exists before using it
  check_command_exists "tar"
  tar xvJf "$dest" && print_info "Extracted successfully"

  echo ""

  # Delete the downloaded file
  rm -vf "$dest"

  # cd into the previous working directory
  # Using cd - might not be fully POSIX, use PWD if needed, but often works
  cd - > /dev/null
}

download_comp() {
  nm=$1
  jq_query=$2
  mdir=$3
  dname=$4

  # Check if jq command exists
  check_command_exists "jq"

  # Check if manifest file exists
  if [ ! -f "$downloaded_manifest" ]; then
      print_err "Manifest file '$downloaded_manifest' not found!"
      exit 1
  fi

  # Extract the Android SDK URL
  print_info "Extracting URL for $nm from manifest..."
  url=$(jq -r "${jq_query}" "$downloaded_manifest")
  if [ -z "$url" ] || [ "$url" = "null" ]; then
      print_err "Failed to extract URL for '$nm' using query '$jq_query'"
      exit 1
  fi
  print_success "Found URL: $url"
  echo ""

  # Download and extract the Android SDK build tools
  download_and_extract "$nm" "$url" "$mdir" "$mdir/$dname.tar.xz"
}

## NOTE!
## When adding more installation configuration arguments,
# add them in com.itsaky.andridide.models.IdeSetupArgument as well
while [ $# -gt 0 ]; do
  case $1 in
  -c | --with-cmdline-tools)
    shift
    with_cmdline=false
    ;;
  -g | --with-git)
    shift
    # Use POSIX concatenation
    pkgs="$pkgs git"
    ;;
  -o | --with-openssh)
    shift
    # Use POSIX concatenation
    pkgs="$pkgs openssh"
    ;;
  -y | --assume-yes)
    shift
    assume_yes=true
    ;;
  -i | --install-dir)
    shift
    check_arg_value "--install-dir" "${1:-}"
    install_dir="$1"
    ;;
  -m | --manifest)
    shift
    check_arg_value "--manifest" "${1:-}"
    manifest="$1"
    ;;
  -s | --sdk)
    shift
    check_arg_value "--sdk" "${1:-}"
    sdkver_org="$1"
    ;;
  -j | --jdk)
    shift
    check_arg_value "--jdk" "${1:-}"
    jdk_version="$1"
    ;;
  -a | --arch)
    shift
    check_arg_value "--arch" "${1:-}"
    arch="$1"
    ;;
  -p | --package-manager)
    shift
    check_arg_value "--package-manager" "${1:-}"
    pkgm="$1"
    ;;
  -l | --curl)
    shift
    check_arg_value "--curl" "${1:-}"
    pkg_curl="$1"
    ;;
  -h | --help)
    print_help
    exit 0
    ;;
  -*)
    echo "Invalid option: $1" >&2
    exit 1
    ;;
  *) break ;;
  esac
  shift
done

if [ "$arch" = "armv7l" ]; then
  arch="arm"
fi

# 64-bit CPU in 32-bit mode
if [ "$arch" = "armv8l" ]; then
  arch="arm"
fi

check_command_exists "$pkgm"

if [ "$jdk_version" = "21" ]; then # Use = for POSIX
  print_warn "OpenJDK 21 support in AndroidIDE is experimental. It may or may not work properly."
  print_warn "Also, OpenJDK 21 is only supported in Gradle v8.4 and newer. Older versions of Gradle will NOT work!"
  if ! is_yes "Do you still want to install OpenJDK 21?"; then
    jdk_version="17"
    print_info "OpenJDK version has been reset to '17'"
  fi
fi

# Check using POSIX compliant AND logic (separate tests)
if [ "$jdk_version" != "17" ] && [ "$jdk_version" != "21" ]; then
  print_err "Invalid JDK version '$jdk_version'. Value can be '17' or '21'."
  exit 1
fi

# Use tr for POSIX compliance
sdk_version="_$(echo "$sdkver_org" | tr '.' '_')"

# Use POSIX concatenation (ensure space if pkgs is not empty)
if [ -n "$pkgs" ]; then
  pkgs="$pkgs $pkg_curl"
else
  pkgs="$pkg_curl"
fi


echo "------------------------------------------"
echo "Installation directory    : ${install_dir}"
echo "SDK version               : ${sdkver_org}"
echo "JDK version               : ${jdk_version}"
echo "With command line tools   : ${with_cmdline}"
echo "Extra packages            : ${pkgs}"
echo "CPU architecture          : ${arch}"
echo "Termux Prefix (SYSROOT)   : ${termux_prefix}" # Added for clarity
echo "------------------------------------------"

if ! is_yes "Confirm configuration"; then
  print_err "Aborting..."
  exit 1
fi

# Check if install_dir is a directory, not a file
if [ -e "$install_dir" ] && [ ! -d "$install_dir" ]; then
    print_err "Installation path '$install_dir' exists but is not a directory!"
    exit 1
elif [ ! -d "$install_dir" ]; then
    print_info "Installation directory does not exist. Creating directory..."
    mkdir -p "$install_dir"
fi


if ! command -v "$pkgm" >/dev/null 2>&1; then
  print_err "'$pkgm' command not found. Try installing 'termux-tools' and 'apt'."
  exit 1
fi


# Update repositories and packages
print_info "Update packages..."

# Fix for expired GPG key issues
print_info "Adding trusted flag to APT repositories..."
sources_list="$termux_prefix/etc/apt/sources.list" # Use termux_prefix
if [ -f "$sources_list" ]; then
  # Add [trusted=yes] flag to all repository lines
  # Using sed -i might work if termux provides GNU sed, otherwise use temp file method
  sed -i 's/^deb /deb [trusted=yes] /' "$sources_list" && \
  print_success "Added trusted flag to APT repositories" || \
  print_warn "Failed to automatically add trusted flag (sed -i might not be supported). Manual check might be needed."
else
  print_warn "Could not find sources.list file at '$sources_list'. Skipping trusted flag addition."
fi

$pkgm update || print_warn "pkg update failed"
if [ "$assume_yes" = "true" ]; then
  $pkgm upgrade -y || print_warn "pkg upgrade failed"
else
  $pkgm upgrade || print_warn "pkg upgrade failed"
fi

# Sometimes running twice helps resolve dependencies
$pkgm update || print_warn "Second pkg update failed"
if [ "$assume_yes" = "true" ]; then
  $pkgm upgrade -y || print_warn "Second pkg upgrade failed"
else
  $pkgm upgrade || print_warn "Second pkg upgrade failed"
fi

# Install required packages
print_info "Installing required packages: $pkgs"
install_packages $pkgs && print_success "Packages installed" || print_err "Failed to install required packages!"
echo ""

# Download the manifest.json file
print_info "Downloading manifest file..."
downloaded_manifest="$install_dir/manifest.json"
check_command_exists "curl"
curl -L -f -o "$downloaded_manifest" "$manifest" && print_success "Manifest file downloaded" || { print_err "Failed to download manifest file from $manifest"; exit 1; }
echo ""

# Install the Android SDK
download_comp "Android SDK" ".android_sdk" "$install_dir" "android-sdk"

# Install build tools
download_comp "Android SDK Build Tools" ".build_tools | .\"${arch}\" | .\"${sdk_version}\"" "$install_dir/android-sdk" "android-sdk-build-tools"

# Install platform tools
download_comp "Android SDK Platform Tools" ".platform_tools | .\"${arch}\" | .\"${sdk_version}\"" "$install_dir/android-sdk" "android-sdk-platform-tools"

# Use = for POSIX check
if [ "$with_cmdline" = true ]; then
  # Install the Command Line tools
  download_comp "Command-line tools" ".cmdline_tools" "$install_dir/android-sdk" "cmdline-tools"
fi

# Install JDK
print_info "Installing package: 'openjdk-$jdk_version'"
install_packages "openjdk-$jdk_version" && print_info "JDK $jdk_version has been installed." || print_err "Failed to install OpenJDK $jdk_version"

jdk_dir="$termux_prefix/opt/openjdk" # Use termux_prefix

print_info "Updating ide-environment.properties..."
print_info "JAVA_HOME=$jdk_dir"
echo ""
props_dir="$termux_prefix/etc" # Use termux_prefix
props="$props_dir/ide-environment.properties"

if [ ! -d "$props_dir" ]; then
  mkdir -p "$props_dir"
fi

if [ ! -e "$props" ]; then
  # Using printf is safer than echo with variable data
  printf "JAVA_HOME=%s\n" "$jdk_dir" >"$props" && print_success "Properties file created and updated successfully!" || print_err "Failed to create properties file '$props'"
else
  if is_yes "$props file already exists. Would you like to overwrite it?"; then
    printf "JAVA_HOME=%s\n" "$jdk_dir" >"$props" && print_success "Properties file overwritten successfully!" || print_err "Failed to overwrite properties file '$props'"
  else
    # Provide more specific path using termux_prefix
    print_warn "Manually edit $props_dir/ide-environment.properties file and set JAVA_HOME (e.g., JAVA_HOME=$jdk_dir)."
  fi
fi

# Clean up manifest
if [ -f "$downloaded_manifest" ]; then
    rm -vf "$downloaded_manifest"
fi

print_success "Downloads completed. You are ready to go!"

# Exit explicitly with success code
exit 0