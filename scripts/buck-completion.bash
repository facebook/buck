# Copyright 2004-present Facebook. All Rights Reserved.
#
# Usage:
#
# To enable tab-completion for buck commands in bash, run the following
# command:
#
#     source path/to/this/file/buck-completion.bash
#
#
# Debugging:
#
# This script will output a bunch of information about what it is doing and
# considering to $DEBUG_BUCK_COMPLETION_TTY.  This enables debugging via
# the following steps.  Also consider 'set -x', though this is a bit more
# painful.
#
# To enable debugging for this script, open a bash window and run:
#
#     tty; cat >/dev/null
#
# Note the device output by the tty command for the next command.
#
# In a second bash window, run:
#
#     export DEBUG_BUCK_COMPLETION_TTY=/dev/other-window-tty
#     source path/to/this/file/buck-completion.bash
#
# replacing '/dev/other-window-tty' with the tty output from the first command.
#
# At this point, you can cd to a buck project, type a partial buck command, hit
# tab, and you should see logs in the original window of what this script is
# doing.

function _buck_completion_run() {
  COMPREPLY=()

  local words=( "${COMP_WORDS[@]}" )
  local cword=$COMP_CWORD
  local word="${words[$cword]}"
  local prev="$3"
  local log=_buck_completion_log

  $log "==============================="
  $log "word=$word"
  $log "prev=$prev"
  $log "words:"
  for w in "${words[@]}"; do
    $log "  $w"
  done
  $log "COMP_TYPE=$COMP_TYPE"

  case "$cword" in
    0)
      _buck_completion_internal_error "cword=0 in _buck_completion_run"
      ;;

    1)
      local commands=$(_buck_completion_echo_buck_commands)
      COMPREPLY=( $(compgen -W "$commands" -- "$word") )
      ;;

    *)
      if _buck_completion_try_long_arg "--version --help"; then
        return 0
      fi

      case "${words[1]}" in
        audit)      _buck_completion_try_audit      "$@";;
        build)      _buck_completion_try_build      "$@";;
        cache)      _buck_completion_try_cache      "$@";;
        clean)      _buck_completion_try_clean      "$@";;
        install)    _buck_completion_try_install    "$@";;
        project)    _buck_completion_try_project    "$@";;
        quickstart) _buck_completion_try_quickstart "$@";;
        run)        _buck_completion_try_run        "$@";;
        targets)    _buck_completion_try_targets    "$@";;
        test)       _buck_completion_try_test       "$@";;
        uninstall)  _buck_completion_try_uninstall  "$@";;
      esac
      ;;
  esac

  if [[ ${#COMPREPLY[@]} != 0 ]]; then
    $log "=========="
    $log "COMPREPLY:"
    for r in "${COMPREPLY[@]}"; do
      $log "  $r"
    done
  fi

  # Set return status
  [[ ${#COMPREPLY[@]} > 0 ]]
}

function _buck_completion_try_build() {
  _buck_completion_try_long_arg "--build-dependencies --help --no-cache --num-threads --verbose" \
    || _buck_completion_try_build_dependencies "$@" \
    || _buck_completion_try_target "$@"
}

function _buck_completion_try_audit() {
  case "$cword" in
    0 | 1)
      _buck_completion_internal_error "cword=$cword in _buck_completion_try_audit"
      return 1
      ;;

    2)
      COMPREPLY=( $(compgen -W "input classpath owner rules" -- "$word") )
      ;;

    *)
      if [[ "$word" == --* ]]; then
        local extra
        case "${words[2]}" in
          input)     extra="--dot --json";;
          classpath) extra="--dot --json";;
          owner)     extra="--dot --full --guess-for-missing";;
          rules)     extra="--type";;
        esac
        _buck_completion_try_long_arg "--help --no-cache --verbose $extra"
      else
        _buck_completion_try_file "$@"
      fi
      ;;
  esac
}

function _buck_completion_try_cache() {
  _buck_completion_try_long_arg "--help --no-cache --verbose"
}

function _buck_completion_try_clean() {
  _buck_completion_try_long_arg "--help --no-cache --verbose --project"
}

function _buck_completion_try_install() {
  _buck_completion_try_long_arg "
      --activity
      --build-dependencies
      --help
      --no-cache
      --num-threads
      --run
      --uninstall
      --verbose
      --via-sd
      --keep
      --adb-threads
      --emulator
      --device
      --serial" \
    || _buck_completion_try_short_arg "-all" \
    || _buck_completion_try_build_dependencies "$@" \
    || _buck_completion_try_serial "$@" \
    || _buck_completion_try_target "$@"
}

function _buck_completion_try_project() {
  _buck_completion_try_long_arg "
      --combined-project
      --help
      --ide
      --no-cache
      --process-annotations
      --verbose
      --without-tests"
}

function _buck_completion_try_quickstart() {
  _buck_completion_try_long_arg "--android-sdk --dest-dir --help --no-cache --verbose" \
    || _buck_completion_try_dest_dir "$@"
}

function _buck_completion_try_run() {
  _buck_completion_try_long_arg "--help --no-cache --verbose" \
    || _buck_completion_try_target "$@"
}

function _buck_completion_try_targets() {
  _buck_completion_try_long_arg "
      --build-dependencies
      --help
      --json
      --no-cache
      --num-threads
      --referenced_file
      --resolvealias
      --show_output
      --show_rulekey
      --type
      --verbose" \
    || _buck_completion_try_build_dependencies "$@" \
    || _buck_completion_try_resolve_alias "$@"
  # TODO _buck_completion_try_referenced_file_set
}

function _buck_completion_try_test() {
  _buck_completion_try_long_arg "
      --all
      --build-dependencies
      --code-coverage
      --debug
      --dry-run
      --help
      --ignore-when-dependencies-fail
      --jacoco
      --no-cache
      --no-results-cache
      --num-threads
      --verbose
      --xml
      --emulator
      --device
      --serial
      --test-selectors
      --explain-test-selectors
      --exclude
      --always_exclude" \
    || _buck_completion_try_build_dependencies "$@" \
    || _buck_completion_try_serial "$@" \
    || _buck_completion_try_target "$@"
}

function _buck_completion_try_uninstall() {
  _buck_completion_try_long_arg "
      --help
      --no-cache
      --verbose
      --keep
      --adb-threads
      --emulator
      --device
      --serial" \
    || _buck_completion_try_short_arg "-all" \
    || _buck_completion_try_serial "$@" \
    || _buck_completion_try_target "$@"
}

function _buck_completion_try_long_arg() {
  if [[ "$word" == --* ]]; then
    COMPREPLY=( $(compgen -W "$@" -- "$word") )
  fi

  # Set return status
  [[ "${#COMPREPLY[@]}" > 0 ]]
}

function _buck_completion_try_short_arg() {
  if [[ "$word" == -* ]]; then
    COMPREPLY=( $(compgen -W "$@" -- "$word") )
  fi

  # Set return status
  [[ "${#COMPREPLY[@]}" > 0 ]]
}

function _buck_completion_try_build_dependencies() {
  case "$prev" in
    -b | --build-dependencies)
      COMPREPLY=( $(compgen -W "FIRST_ORDER_ONLY WARN_ON_TRANSITIVE TRANSITIVE" -- "$word") )
      ;;
  esac

  # Set return status
  [[ "${#COMPREPLY[@]}" > 0 ]]
}

function _buck_completion_try_dest_dir() {
  if [[ "$prev" == "--dest-dir" ]]; then
    COMPREPLY=( $(compgen -A directory -- "$word") )
  fi

  # Set return status
  [[ "${#COMPREPLY[@]}" > 0 ]]
}

function _buck_completion_try_file() {
  COMPREPLY=( $(compgen -A file -- "$word") )

  # Set return status
  [[ "${#COMPREPLY[@]}" > 0 ]]
}

function _buck_completion_try_serial() {
  case "$prev" in
    -s | --serial)
      for d in "$(adb devices | tail -n+2 | awk '/./ { print $1 }')"; do
        if [[ "$d" == "$word"* ]]; then
          _buck_completion_add_reply "$d"
        fi
      done
      ;;
  esac

  # Set return status
  [[ "${#COMPREPLY[@]}" > 0 ]]
}

function _buck_completion_try_resolve_alias() {
  # Make sure we are in a buck root
  local root=$(_buck_completion_get_root)
  $log "root=$root"
  if [[ -z "$root" ]]; then
    return 1
  fi

  case "$prev" in
    --resolvealias | --resolve-alias)
      _buck_completion_add_target_alias "$@"
      ;;
  esac

  # Set return status
  [[ "${#COMPREPLY[@]}" > 0 ]]
}

function _buck_completion_try_target() {
  # Make sure we are in a buck root
  local root=$(_buck_completion_get_root)
  $log "root=$root"
  if [[ -z "$root" ]]; then
    return 1
  fi

  if [[ "$word" == //*:* ]]; then
    _buck_completion_add_explicit_target_name_unsplit "$@"
  elif [[ "$prev" == ":" || "$word" == ":" ]]; then
    if [[ "$word" == ":" ]]; then
      # Patch up word arrays to pretend we are looking at '' after the ':'
      word=''
      words[${#words[@]}]=''
      cword=$(($cword + 1))
    fi
    _buck_completion_add_explicit_target_name "$@"
  elif [[ "$word" == *:* ]]; then
    _buck_completion_add_relative_target_name_unsplit "$@"
  elif [[ "$word" == //* ]]; then
    _buck_completion_add_explicit_target_path "$@"
  else
    _buck_completion_add_target_alias_or_relative_path "$@"
  fi

  # Set return status
  [[ ${#COMPREPLY[@]} > 0 ]]
}

function _buck_completion_add_explicit_target_path() {
  local dir="${root}/${word#//}"
  $log "dir=$dir"

  _buck_completion_add_relative_path_with_prefix "$dir" "//"
}

function _buck_completion_add_explicit_target_name() {
  # TODO: verify previous words
  local dir="${root}/${words[$(($cword - 2))]#//}"
  local buck_file="$dir/BUCK"
  local name_prefix="$word"

  _buck_completion_add_target_names "$buck_file" "$name_prefix"
}

function _buck_completion_add_explicit_target_name_unsplit() {
  local dir="${root}/$(echo $word | sed -E -e 's|//(.*):.*|\1|')"
  local buck_file="$dir/BUCK"
  local name_prefix="${word#//*:}"

  _buck_completion_add_target_names "$buck_file" "$name_prefix"
}

function _buck_completion_add_relative_target_name_unsplit() {
  local dir="${word%:*}"
  local buck_file="$dir/BUCK"
  local name_prefix="${word#*:}"

  _buck_completion_add_target_names "$buck_file" "$name_prefix"
}

function _buck_completion_add_target_alias_or_relative_path() {
  local dir="${word}"
  $log "dir=$dir"

  _buck_completion_add_target_alias "$@"
  _buck_completion_add_relative_path_with_prefix "$dir" ""
}

function _buck_completion_add_target_alias() {
  local prog='/^\[/ { p=0 } /^\[alias]/ { p=1 } /^ *[a-zA-Z_-]* *= *\/\// { if (p) print $1 }'
  local aliases=( $(awk "$prog" < "$root/.buckconfig") )

  for a in "${aliases[@]}"; do
    if [[ "$a" == "$word"* ]]; then
      _buck_completion_add_reply "$a"
    fi
  done
}

function _buck_completion_add_relative_path() {
  local dir="${root}/${word}"
  _buck_completion_add_relative_path_with_prefix "$dir" ""
}

function _buck_completion_add_relative_path_with_prefix() {
  local dir="$1"
  local prefix="$2"

  # Complete directory containing BUCK file
  local raw_dirs=( $(compgen -A directory -- "$dir") )

  for d in "${raw_dirs[@]}"; do
    local suffix
    if [[ -f "$d/BUCK" ]]; then
      suffix=':'
    else
      suffix='/'
    fi
    _buck_completion_add_reply "${prefix}${d#${root}/}${suffix}"
  done
}

function _buck_completion_add_target_names() {
  local buck_file="$1"
  $log "buck_file=$buck_file"

  local name_prefix="$2"
  $log "name_prefix=$name_prefix"

  if [[ ! -f "$buck_file" ]]; then
    $log "No such file: '$buck_file'"
    return 1
  fi

  local pattern="^ *name *= *[\'\"]\([^\'\"]*\)[\'\"] *, *$"
  local target_names
  if [[ -n "${BUCK_COMPLETION_HARDTARGETRESOLUTION-}" || -n "${BUCK_COMPLETION_USE_BUCK}" ]]; then
    target_names=( $(_buck_completion_buck_output audit rules "$buck_file" 2>/dev/null | sed -n -e "s/$pattern/\\1/ p") )
  else
    target_names=( $(grep "$pattern" "$buck_file" | sed -n -e "s/$pattern/\\1/ p") )
  fi

  $log "target_names='${target_names[@]}'"
  for name in "${target_names[@]}"; do
    $log "  considering target '$name'"
    if [[ "$name" == "$name_prefix"* ]]; then
      $log "    adding '$name' because it has prefix '$name_prefix'"
      _buck_completion_add_reply "${name}"
    else
      $log "    not adding '$name' because it does not have prefix '$name_prefix'"
    fi
  done
}

function _buck_completion_buck_output() {
    local nobuckcheck="$root/.nobuckcheck"
    local preexisting=$([[ -e "$nobuckcheck" ]] && echo "$nobuckcheck")
    touch "$nobuckcheck"

    $(type -P buck) "$@" 2>/dev/null

    if [[ -z "$preexisting" ]]; then
      $log "REMOVING '$nobuckcheck' because it did not exist before."
      rm "$nobuckcheck" &>/dev/null
    else
      $log "LEAVING '$nobuckcheck' because it existed before."
    fi
}

function _buck_completion_get_root() {
  local root=$PWD
  while [[ -n "$root" ]]; do
    if [[ -e "$root"/.buckconfig ]]; then
      echo "$root" && return 0
    fi
    root=${root%/*}
  done
}

function _buck_completion_add_reply() {
  local completion="$1"

  $log "Adding completion: '$completion'"
  COMPREPLY[${#COMPREPLY[@]}]="$completion"
}

function _buck_completion_log() {
  [[ -n "$DEBUG_BUCK_COMPLETION_TTY" ]] && echo "$@" >"$DEBUG_BUCK_COMPLETION_TTY"
}

function _buck_completion_echo_buck_commands() {
  echo "audit build cache clean install project quickstart run targets test uninstall --version --help -V"
}

function _buck_completion_internal_error() {
    $log ""
    $log "Internal error in _buck_completion_echo_target_names:"
    $log "  $@"
    $log "at:"
    for f in "${FUNCNAME[@]}"; do
      $log "  $f"
    done
}

complete -o nospace -F _buck_completion_run buck
