#! /bin/bash

set -e
set -x

function notifyIfPossible() {
    if which osascript > /dev/null; then
        osascript -e 'display notification "Background build finished." with title "Buck"'
    elif which notify-send > /dev/null; then
        notify-send "Buck" "Background build finished."
    fi
}

function getScript() {
    local bash_source="${BASH_SOURCE[0]}"
    local source_dir="$( dirname "${bash_source}" )"
    while [ -h "${source_dir}" ]
    do
      bash_source="$(readlink "$SOURCE")"
      [[ "${bash_source}" != /* ]] && bash_source="${source_dir}/${bash_source}"
      source_dir="$( cd -P "$( dirname "${bash_source}"  )" && pwd )"
    done
    echo "$( cd -P "$( dirname "${bash_source}" )" && pwd )/$( basename "${BASH_SOURCE[0]}" )"
}

function syncDirectory() {
    local sourceDirectory=$(printf %q "$1")
    local destinationDirectory=$(printf %q "$2")

    echo >&2 "=== Syncing master and slave directories ==="
    (
        # Save the state of source directory
        cd ${sourceDirectory}
        local currentSourceHash=$(git rev-parse HEAD)
        local currentSourceBranch=$(git rev-parse --abbrev-ref HEAD)
        local patchFile=$(mktemp -t patch.XXXXX)
        git diff > ${patchFile}

        if [ ! -d ${destinationDirectory} ]; then
            (
                echo >&2 "=== Creating slave directory ==="
                mkdir -p ${destinationDirectory}
                cd ${destinationDirectory}
                git init .
            )
        fi
        # Move to and clean the destination directory
        cd ${destinationDirectory}
        git clean -fd
        if ! git rev-parse --quiet --verify ${currentSourceHash}^{commit} > /dev/null; then
            echo >&2 "=== Fetching from master to slave ==="
            git fetch --depth=1 file://${sourceDirectory} ${currentSourceBranch}
        fi
        git reset --hard ${currentSourceHash}
        if [ -s ${patchFile} ]; then
            git apply ${patchFile}
        fi

        # Cleanup
        rm ${patchFile}
    )
}

function onChange() {
    local sourceDirectory=$(printf %q "$1")
    local destinationDirectory=$(printf %q "$2")
    local target=$(printf %q "$3")

    syncDirectory ${sourceDirectory} ${destinationDirectory}
    (
        cd ${destinationDirectory}
        cat << EOF > .buckconfig.local
[cache]
    dir = ${sourceDirectory}/buck-cache
    dir_mode = readwrite
EOF
        echo >&2 "=== Building ${target} ==="
        mkdir -p ${sourceDirectory}/.buckd
        BUCK_EXTRA_JAVA_ARGS=-Dbuck.autobuild=1 nice buck build ${target} &
        local buckd_pid=$!
        echo ${buckd_pid} > ${sourceDirectory}/.buckd/autobuild.pid
        wait ${buckd_pid}
        notifyIfPossible
        rm -rf ${sourceDirectory}/.buckd/autobuild.pid
    )
}

function createTrigger() {
    local sourceDirectory=$(printf %q "$1")
    local destinationDirectory=$(printf %q "$2")
    local target=$(printf %q "$3")
    echo >&2 "=== Creating Watchman Trigger ==="

    if [ ! -d ${sourceDirectory}/.git ]; then
        echo >&2 "Sorry, autobuilds only work in git repos for now!"
    fi

    onChange ${sourceDirectory} ${destinationDirectory} ${target}
    watchman trigger-del ${sourceDirectory} buck_speculate
    echo $(getScript)
    watchman -j <<-EOT
["trigger", "${sourceDirectory}", {
  "name": "buck_speculate",
  "expression": ["allof", ["type", "f"], ["not", ["pcre", "(^.git|pyc\$|swp\$|swo\$)", "wholename"]]],
  "command": ["$(getScript)", "change", "${sourceDirectory}", "${destinationDirectory}", "${target}"]
}]
EOT
}

function usage() {
    cat <<- EOF
usage: buck_autobuild.sh watch <Source Directory> <Workspace Directory> <Target to Build>

Script is used to build a buck target on save in a safe manner.

The workspace directory will be a mirror of the source directory.

EOF
}

function main() {
    if [ $# -lt 4 ]; then
        usage
        exit 1
    fi

    local command=$(printf %q "$1"); shift
    local sourceDirectory=$(printf %q "$1"); shift
    local destinationDirectory=$(printf %q "$1"); shift
    local target=$(printf %q "$1"); shift

    case ${command} in
    change)
    set -x
    onChange ${sourceDirectory} ${destinationDirectory} ${target}
    ;;
    watch)
    createTrigger ${sourceDirectory} ${destinationDirectory} ${target}
    ;;
    esac

    echo "Finished Speculative Build!"
}

main $@
