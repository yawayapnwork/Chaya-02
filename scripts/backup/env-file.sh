# shellcheck shell=bash
# Loads a Docker Compose env file (.env) into the environment WITHOUT executing it. Sourced by the deploy and backup
# scripts; the file is the same one `docker compose --env-file` reads, so it is parsed the way Compose parses it, not the
# way a shell would:
#   * blank lines and lines starting with # are skipped; an optional leading `export ` is ignored;
#   * NAME=value, NAME a shell identifier; anything else is refused, naming the line number (never its content);
#   * 'single' and "double" quoted values are taken literally (no expansion);
#   * an unquoted value ends at a ` #` comment and is trimmed;
#   * nothing is evaluated: spaces (KEYCLOAK_ADMIN_ALLOW="a b"), $, backticks, < and > are plain characters. Sourcing
#     the file with `.` ran `KEYCLOAK_ADMIN_ALLOW=10.0.0.0/8 192.168.0.0/16` as a command and read `ghcr.io/<owner>` as
#     a redirect, so a configuration Compose accepted stopped the deploy.
#   * like Compose, a variable already set in the environment wins over the file.
#
#   load_env_file FILE
load_env_file() {
  local file=$1 line name value n=0
  [ -f "$file" ] || { echo "error: $file not found" >&2; return 2; }
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    line=${line%$'\r'}
    # Leading whitespace, comments and blank lines.
    line=${line#"${line%%[![:space:]]*}"}
    case "$line" in ''|'#'*) continue ;; esac
    line=${line#export }
    name=${line%%=*}
    if [ "$name" = "$line" ] || ! [[ $name =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      echo "error: $file line $n is not NAME=value" >&2
      return 2
    fi
    value=${line#*=}
    case "$value" in
      \'*) value=${value#\'}; [[ $value == *\'* ]] || { echo "error: $file line $n: unterminated quote" >&2; return 2; }
           value=${value%%\'*} ;;
      \"*) value=${value#\"}; [[ $value == *\"* ]] || { echo "error: $file line $n: unterminated quote" >&2; return 2; }
           value=${value%%\"*} ;;
      *)   if [[ $value =~ ^(.*[^[:space:]])?[[:space:]]+#.*$ ]]; then value=${BASH_REMATCH[1]}; fi
           [[ $value == '#'* ]] && value=""
           value=${value%"${value##*[![:space:]]}"} ;;
    esac
    [ -n "${!name+x}" ] && continue
    printf -v "$name" '%s' "$value"
    export "${name?}"
  done < "$file"
}
