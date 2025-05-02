#!/system/bin/sh

set -x # <-- ADDED: Prints every command before execution
echo "DEBUG: idesetup.sh starting with args: $*" >&2 # <-- ADDED
echo "DEBUG: Current directory: $(pwd)" >&2 # <-- ADDED
# echo "DEBUG: Who am I: $(whoami)" >&2 # <-- Optional: Might fail
echo "DEBUG: Checking termux_prefix variable" >&2 # <-- ADDED

set -e # <-- Keep these
set -u # <-- Keep these


# --- Define Termux Prefix (Replace com.itsaky.androidide if your package name is different) ---
termux_prefix="/data/data/com.itsaky.androidide/files/usr"
echo "DEBUG: termux_prefix set to: $termux_prefix" >&2 # <-- ADDED

# --- Check if prefix exists, create if not (basic sanity check) ---
echo "DEBUG: Checking if prefix directory exists..." >&2 # <-- ADDED
if [ ! -d "$termux_prefix" ]; then
    echo "DEBUG: Prefix directory DOES NOT exist." >&2 # <-- ADDED
    echo "Warning: Termux prefix '$termux_prefix' not found. Attempting to create." >&2
    # Creating /data/data/... might fail due to permissions if not run by the app itself
    mkdir -p "$termux_prefix/bin" "$termux_prefix/etc" "$termux_prefix/opt" || {
        echo "Error: Failed to create Termux prefix directories." >&2
        exit 1
    }
else
   echo "DEBUG: Prefix directory exists." >&2 # <-- ADDED
fi


Color_Off='\033[0m'
Red='\033[0;31m'
Green='\033[0;32m'
Blue='\033[0;34m'
Orange="\e[38;5;208m"

# Default values
arch=$(uname -m)
echo "DEBUG: uname -m result: $arch" >&2 # <-- ADDED
install_dir=$HOME
echo "DEBUG: HOME is: $HOME, install_dir set to: $install_dir" >&2 # <-- ADDED
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
  printf "${Red}%s${Color_Off}\n" "$1" >&2 # Send errors to stderr
}

print_warn() {
  # shellcheck disable=SC2059
  printf "${Orange}%s${Color_Off}\n" "$1" >&2 # Send warnings to stderr
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
    # Reading input might not work correctly if stdin isn't connected properly
    echo "DEBUG: Attempting to read user input for is_yes prompt..." >&2 # <-- ADDED
    read -r ans
    echo "DEBUG: User input read: $ans" >&2 # <-- ADDED
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
  echo "DEBUG: check_arg_value: option='$option_name', value='$arg_value'" >&2 # <-- ADDED
  if [ -z "$arg_value" ]; then # Use [ -z ... ] for POSIX compliance
    print_err "No value provided for $option_name!"
    exit 1
  fi
}

check_command_exists() {
  echo "DEBUG: check_command_exists: checking for command '$1'" >&2 # <-- ADDED
  # Check PATH
  echo "DEBUG: Current PATH=$PATH" >&2 # <-- ADDED
  if command -v "$1" >/dev/null 2>&1; then # More portable check
    echo "DEBUG: Command '$1' found." >&2 # <-- ADDED
    return
  else
    print_err "Command '$1' not found!"
    exit 1
  fi
}

install_packages() {
  # $@ expands to separate arguments, which is needed by install
  # shellcheck disable=SC2086
  echo "DEBUG: install_packages: packages='$@', assume_yes='$assume_yes'" >&2 # <-- ADDED
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

  echo "DEBUG: download_and_extract: name='$name', url='$url', dir='$dir', dest='$dest'" >&2 # <-- ADDED

  if [ ! -d "$dir" ]; then
    echo "DEBUG: download_and_extract: Creating directory '$dir'" >&2 # <-- ADDED
    mkdir -p "$dir"
  fi

  echo "DEBUG: download_and_extract: Changing directory to '$dir'" >&2 # <-- ADDED
  cd "$dir" || exit 1 # Exit if cd fails

  do_download=true
  if [ -f "$dest" ]; then
    file_base_name=$(basename "$dest") # Use different variable name
    print_info "File ${file_base_name} already exists."
    # Skip asking if assume_yes is true
    if [ "$assume_yes" != "true" ]; then
        if is_yes "Do you want to skip the download process?"; then
          do_download=false
        fi
    else
        echo "DEBUG: assume_yes is true, skipping 'skip download' prompt." >&2 # <-- ADDED
    fi
    echo ""
  fi

  if [ "$do_download" = "true" ]; then
    echo "DEBUG: download_and_extract: Attempting download '$name' from '$url' to '$dest'" >&2 # <-- ADDED
    print_info "Downloading $name..."
    curl -L -o "$dest" "$url" --http1.1 # Consider adding --fail to exit on HTTP errors
    print_success "$name has been downloaded."
    echo ""
  else
    echo "DEBUG: download_and_extract: Skipping download for '$name'" >&2 # <-- ADDED
  fi

  if [ ! -f "$dest" ]; then
    print_err "The downloaded file $name does not exist. Cannot proceed..."
    exit 1
  fi

  # Extract the downloaded archive
  echo "DEBUG: download_and_extract: Attempting extraction of '$dest'" >&2 # <-- ADDED
  print_info "Extracting downloaded archive..."
  # Ensure tar exists before using it
  check_command_exists "tar"
  tar xvJf "$dest" && print_info "Extracted successfully"

  echo ""

  # Delete the downloaded file
  echo "DEBUG: download_and_extract: Removing '$dest'" >&2 # <-- ADDED
  rm -vf "$dest"

  # cd into the previous working directory
  # Using cd - might not be fully POSIX, use PWD if needed, but often works
  echo "DEBUG: download_and_extract: Changing back to previous directory" >&2 # <-- ADDED
  cd - > /dev/null
}

download_comp() {
  nm=$1
  jq_query=$2
  mdir=$3
  dname=$4

  echo "DEBUG: download_comp: name='$nm', query='$jq_query', dir='$mdir', dname='$dname'" >&2 # <-- ADDED

  # Check if jq command exists
  echo "DEBUG: download_comp: Checking for jq command" >&2 # <-- ADDED
  check_command_exists "jq"

  # Check if manifest file exists
  if [ ! -f "$downloaded_manifest" ]; then
      print_err "Manifest file '$downloaded_manifest' not found!"
      exit 1
  fi

  # Extract the Android SDK URL
  print_info "Extracting URL for $nm from manifest..."
  echo "DEBUG: download_comp: Running jq query: '$jq_query' on '$downloaded_manifest'" >&2 # <-- ADDED
  url=$(jq -r "${jq_query}" "$downloaded_manifest")
  echo "DEBUG: download_comp: jq result URL='$url'" >&2 # <-- ADDED
  if [ -z "$url" ] || [ "$url" = "null" ]; then
      print_err "Failed to extract URL for '$nm' using query '$jq_query'"
      exit 1
  fi
  print_success "Found URL: $url"
  echo ""

  # Download and extract the Android SDK build tools
  download_and_extract "$nm" "$url" "$mdir" "$mdir/$dname.tar.xz"
}

echo "DEBUG: Starting argument parsing loop." >&2 # <-- ADDED
## NOTE!
## When adding more installation configuration arguments,
# add them in com.itsaky.andridide.models.IdeSetupArgument as well
while [ $# -gt 0 ]; do
  echo "DEBUG: Parsing argument: '$1'" >&2 # <-- ADDED
  case $1 in
  -c | --with-cmdline-tools)
    shift
    with_cmdline=false # Actually, -c means WITH tools, should be true
    with_cmdline=true  # Corrected logic
    echo "DEBUG: Option -c found, with_cmdline=true" >&2 # <-- ADDED
    ;;
  -g | --with-git)
    shift
    # Use POSIX concatenation
    pkgs="$pkgs git"
    echo "DEBUG: Option -g found, pkgs='$pkgs'" >&2 # <-- ADDED
    ;;
  -o | --with-openssh)
    shift
    # Use POSIX concatenation
    pkgs="$pkgs openssh"
    echo "DEBUG: Option -o found, pkgs='$pkgs'" >&2 # <-- ADDED
    ;;
  -y | --assume-yes)
    shift
    assume_yes=true
    echo "DEBUG: Option -y found, assume_yes=true" >&2 # <-- ADDED
    ;;
  -i | --install-dir)
    shift
    check_arg_value "--install-dir" "${1:-}"
    install_dir="$1"
    echo "DEBUG: Option -i found, install_dir='$install_dir'" >&2 # <-- ADDED
    ;;
  -m | --manifest)
    shift
    check_arg_value "--manifest" "${1:-}"
    manifest="$1"
    echo "DEBUG: Option -m found, manifest='$manifest'" >&2 # <-- ADDED
    ;;
  -s | --sdk)
    shift
    check_arg_value "--sdk" "${1:-}"
    sdkver_org="$1"
    echo "DEBUG: Option -s found, sdkver_org='$sdkver_org'" >&2 # <-- ADDED
    ;;
  -j | --jdk)
    shift
    check_arg_value "--jdk" "${1:-}"
    jdk_version="$1"
    echo "DEBUG: Option -j found, jdk_version='$jdk_version'" >&2 # <-- ADDED
    ;;
  -a | --arch)
    shift
    check_arg_value "--arch" "${1:-}"
    arch="$1"
    echo "DEBUG: Option -a found, arch='$arch'" >&2 # <-- ADDED
    ;;
  -p | --package-manager)
    shift
    check_arg_value "--package-manager" "${1:-}"
    pkgm="$1"
    echo "DEBUG: Option -p found, pkgm='$pkgm'" >&2 # <-- ADDED
    ;;
  -l | --curl)
    shift
    check_arg_value "--curl" "${1:-}"
    pkg_curl="$1"
    echo "DEBUG: Option -l found, pkg_curl='$pkg_curl'" >&2 # <-- ADDED
    ;;
  -h | --help)
    echo "DEBUG: Option -h found, printing help and exiting." >&2 # <-- ADDED
    print_help
    exit 0
    ;;
  -*)
    echo "Invalid option: $1" >&2
    exit 1
    ;;
  *) echo "DEBUG: No more options found, breaking loop." >&2; break ;; # <-- ADDED debug message
  esac
  shift
done
echo "DEBUG: Argument parsing finished." >&2 # <-- ADDED

if [ "$arch" = "armv7l" ]; then
  arch="arm"
fi

# 64-bit CPU in 32-bit mode
if [ "$arch" = "armv8l" ]; then
  arch="arm"
fi

echo "DEBUG: Checking command $pkgm" >&2 # <-- ADDED
check_command_exists "$pkgm"

if [ "$jdk_version" = "21" ]; then # Use = for POSIX
  print_warn "OpenJDK 21 support in AndroidIDE is experimental. It may or may not work properly."
  print_warn "Also, OpenJDK 21 is only supported in Gradle v8.4 and newer. Older versions of Gradle will NOT work!"
  if [ "$assume_yes" != "true" ] && ! is_yes "Do you still want to install OpenJDK 21?"; then # <-- Added check for assume_yes
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
echo "DEBUG: Converting SDK version $sdkver_org" >&2 # <-- ADDED
sdk_version="_$(echo "$sdkver_org" | tr '.' '_')"
echo "DEBUG: Converted SDK version: $sdk_version" >&2 # <-- ADDED

# Use POSIX concatenation (ensure space if pkgs is not empty)
echo "DEBUG: Adding pkg_curl '$pkg_curl' to pkgs '$pkgs'" >&2 # <-- ADDED
if [ -n "$pkgs" ]; then
  pkgs="$pkgs $pkg_curl"
else
  pkgs="$pkg_curl"
fi
echo "DEBUG: Final pkgs list: '$pkgs'" >&2 # <-- ADDED


echo "------------------------------------------"
echo "Installation directory    : ${install_dir}"
echo "SDK version               : ${sdkver_org}"
echo "JDK version               : ${jdk_version}"
echo "With command line tools   : ${with_cmdline}"
echo "Extra packages            : ${pkgs}"
echo "CPU architecture          : ${arch}"
echo "Termux Prefix (SYSROOT)   : ${termux_prefix}" # Added for clarity
echo "------------------------------------------"

# Skip confirmation if assume_yes is true
if [ "$assume_yes" != "true" ]; then
    if ! is_yes "Confirm configuration"; then
      print_err "Aborting..."
      exit 1
    fi
else
    echo "DEBUG: assume_yes is true, skipping confirmation prompt." >&2 # <-- ADDED
fi


# Check if install_dir is a directory, not a file
echo "DEBUG: Checking installation directory '$install_dir'" >&2 # <-- ADDED
if [ -e "$install_dir" ] && [ ! -d "$install_dir" ]; then
    print_err "Installation path '$install_dir' exists but is not a directory!"
    exit 1
elif [ ! -d "$install_dir" ]; then
    print_info "Installation directory does not exist. Creating directory..."
    echo "DEBUG: Running mkdir -p '$install_dir'" >&2 # <-- ADDED
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
echo "DEBUG: sources_list path: $sources_list" >&2 # <-- ADDED
if [ -f "$sources_list" ]; then
  # Add [trusted=yes] flag to all repository lines
  # Using sed -i might work if termux provides GNU sed, otherwise use temp file method
  echo "DEBUG: Attempting to run sed on sources.list" >&2 # <-- ADDED
  sed -i 's/^deb /deb [trusted=yes] /' "$sources_list" && \
  print_success "Added trusted flag to APT repositories" || \
  print_warn "Failed to automatically add trusted flag (sed -i might not be supported). Manual check might be needed."
else
  print_warn "Could not find sources.list file at '$sources_list'. Skipping trusted flag addition."
fi

echo "DEBUG: Running $pkgm update" >&2 # <-- ADDED
$pkgm update || print_warn "pkg update failed"
echo "DEBUG: Running $pkgm upgrade" >&2 # <-- ADDED
if [ "$assume_yes" = "true" ]; then
  $pkgm upgrade -y || print_warn "pkg upgrade failed"
else
  $pkgm upgrade || print_warn "pkg upgrade failed"
fi

# Sometimes running twice helps resolve dependencies
echo "DEBUG: Running second $pkgm update" >&2 # <-- ADDED
$pkgm update || print_warn "Second pkg update failed"
echo "DEBUG: Running second $pkgm upgrade" >&2 # <-- ADDED
if [ "$assume_yes" = "true" ]; then
  $pkgm upgrade -y || print_warn "Second pkg upgrade failed"
else
  $pkgm upgrade || print_warn "Second pkg upgrade failed"
fi

# Install required packages
print_info "Installing required packages: $pkgs"
echo "DEBUG: Calling install_packages $pkgs" >&2 # <-- ADDED
install_packages $pkgs && print_success "Packages installed" || print_err "Failed to install required packages!"
echo ""

# Download the manifest.json file
print_info "Downloading manifest file..."
downloaded_manifest="$install_dir/manifest.json"
echo "DEBUG: downloaded_manifest path: $downloaded_manifest" >&2 # <-- ADDED
echo "DEBUG: Checking for curl command" >&2 # <-- ADDED
check_command_exists "curl"
echo "DEBUG: Running curl -L -f -o '$downloaded_manifest' '$manifest'" >&2 # <-- ADDED
curl -L -f -o "$downloaded_manifest" "$manifest" && print_success "Manifest file downloaded" || { print_err "Failed to download manifest file from $manifest"; exit 1; }
echo ""

# Install the Android SDK
echo "DEBUG: Calling download_comp for Android SDK" >&2 # <-- ADDED
download_comp "Android SDK" ".android_sdk" "$install_dir" "android-sdk"

# Install build tools
echo "DEBUG: Calling download_comp for Build Tools" >&2 # <-- ADDED
download_comp "Android SDK Build Tools" ".build_tools | .\"${arch}\" | .\"${sdk_version}\"" "$install_dir/android-sdk" "android-sdk-build-tools"

# Install platform tools
echo "DEBUG: Calling download_comp for Platform Tools" >&2 # <-- ADDED
download_comp "Android SDK Platform Tools" ".platform_tools | .\"${arch}\" | .\"${sdk_version}\"" "$install_dir/android-sdk" "android-sdk-platform-tools"

# Use = for POSIX check
echo "DEBUG: Checking if with_cmdline is true ('$with_cmdline')" >&2 # <-- ADDED
if [ "$with_cmdline" = true ]; then
  # Install the Command Line tools
  echo "DEBUG: Calling download_comp for Command-line tools" >&2 # <-- ADDED
  download_comp "Command-line tools" ".cmdline_tools" "$install_dir/android-sdk" "cmdline-tools"
fi

# Install JDK
print_info "Installing package: 'openjdk-$jdk_version'"
echo "DEBUG: Calling install_packages openjdk-$jdk_version" >&2 # <-- ADDED
install_packages "openjdk-$jdk_version" && print_info "JDK $jdk_version has been installed." || print_err "Failed to install OpenJDK $jdk_version"

jdk_dir="$termux_prefix/opt/openjdk" # Use termux_prefix
echo "DEBUG: jdk_dir set to: $jdk_dir" >&2 # <-- ADDED

print_info "Updating ide-environment.properties..."
print_info "JAVA_HOME=$jdk_dir"
echo ""
props_dir="$termux_prefix/etc" # Use termux_prefix
props="$props_dir/ide-environment.properties"
echo "DEBUG: props_dir: $props_dir, props file path: $props" >&2 # <-- ADDED

if [ ! -d "$props_dir" ]; then
  echo "DEBUG: Properties directory '$props_dir' does not exist. Creating..." >&2 # <-- ADDED
  mkdir -p "$props_dir"
fi

if [ ! -e "$props" ]; then
  # Using printf is safer than echo with variable data
  echo "DEBUG: Properties file '$props' does not exist. Creating and writing JAVA_HOME." >&2 # <-- ADDED
  printf "JAVA_HOME=%s\n" "$jdk_dir" >"$props" && print_success "Properties file created and updated successfully!" || print_err "Failed to create properties file '$props'"
else
  # Skip asking if assume_yes is true
  if [ "$assume_yes" != "true" ]; then
      if is_yes "$props file already exists. Would you like to overwrite it?"; then
        echo "DEBUG: Properties file '$props' exists. Overwriting JAVA_HOME." >&2 # <-- ADDED
        printf "JAVA_HOME=%s\n" "$jdk_dir" >"$props" && print_success "Properties file overwritten successfully!" || print_err "Failed to overwrite properties file '$props'"
      else
        # Provide more specific path using termux_prefix
        print_warn "Manually edit $props_dir/ide-environment.properties file and set JAVA_HOME (e.g., JAVA_HOME=$jdk_dir)."
      fi
  else
      echo "DEBUG: assume_yes is true. Overwriting properties file '$props' without asking." >&2 # <-- ADDED
      printf "JAVA_HOME=%s\n" "$jdk_dir" >"$props" && print_success "Properties file overwritten successfully!" || print_err "Failed to overwrite properties file '$props'"
  fi
fi

# Clean up manifest
echo "DEBUG: Checking if manifest file '$downloaded_manifest' exists for removal." >&2 # <-- ADDED
if [ -f "$downloaded_manifest" ]; then
    echo "DEBUG: Removing manifest file '$downloaded_manifest'." >&2 # <-- ADDED
    rm -vf "$downloaded_manifest"
fi

print_success "Downloads completed. You are ready to go!"

echo "DEBUG: idesetup.sh finished execution." >&2 # <-- ADDED
# Exit explicitly with success code
exit 0