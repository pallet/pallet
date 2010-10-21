# http://tldp.org/LDP/abs/html/contributed-scripts.html#HASHLIB
# Hash function library
# Author: Mariusz Gniazdowski <mariusz.gn-at-gmail.com>
# Date: 2005-04-07

#  updated by Charles Duffy http://gist.github.com/636900

# Functions making emulating hashes in Bash a little less painful.


#    Limitations:
#  * Only global variables are supported.
#  * Each hash instance generates one global variable per value.
#  * Variable names collisions are possible
#+   if you define variable like __hash__hashname_key
#  * Keys must use chars that can be part of a Bash variable name
#+   (no dashes, periods, etc.).
#  * The hash is created as a variable:
#    ... hashname_keyname
#    So if somone will create hashes like:
#      myhash_ + mykey = myhash__mykey
#      myhash + _mykey = myhash__mykey
#    Then there will be a collision.
#    (This should not pose a major problem.)


Hash_config_varname_prefix=__hash__


# Emulates:  hash[key]=value
#
# Params:
# 1 - hash
# 2 - key
# 3 - value
function hash_set {
        read -r "${Hash_config_varname_prefix}${1}_${2}" <<<"$3"
}


# Emulates:  value=hash[key]
#
# Params:
# 1 - hash
# 2 - key
# 3 - value (name of global variable to set)
function hash_get_into {
        local name="${Hash_config_varname_prefix}${1}_${2}"
        read -r "$3" <<<"${!name}"
}


# Emulates:  echo hash[key]
#
# Params:
# 1 - hash
# 2 - key
# 3 - echo params (like -n, for example)
function hash_echo {
        local name="${Hash_config_varname_prefix}${1}_${2}"
        echo "${!name}"
}


# Emulates:  hash1[key1]=hash2[key2]
#
# Params:
# 1 - hash1
# 2 - key1
# 3 - hash2
# 4 - key2
function hash_copy {
        local w_name="${Hash_config_varname_prefix}${1}_${2}"
        local r_name="${Hash_config_varname_prefix}${3}_${4}"
        read -r "${w_name}" <<<"${!r_name}"
}


# Emulates:  hash[keyN-1]=hash[key2]=...hash[key1]
#
# Copies first key to rest of keys.
#
# Params:
# 1 - hash1
# 2 - key1
# 3 - key2
# . . .
# N - keyN
function hash_dup {
  local hash_name key_to_copy val_to_copy
  hash_name="$1"
  key_to_copy="$2"
  hash_get_into "$hash_name" "$key_to_copy" val_to_copy
  shift 2

  while (( $# > 0 )) ; do
    hash_set "$hash_name" "$1" "$val_to_copy"
    shift
  done
}


# Emulates:  unset hash[key]
#
# Params:
# 1 - hash
# 2 - key
function hash_unset {
	eval "unset ${Hash_config_varname_prefix}${1}_${2}"
}


# Emulates something similar to:  ref=&hash[key]
#
# The reference is name of the variable in which value is held.
#
# Params:
# 1 - hash
# 2 - key
# 3 - ref - Name of global variable to set.
function hash_get_ref_into {
        read -r "$3" <<<"${Hash_config_varname_prefix}${1}_${2}"
}


# Emulates something similar to:  echo &hash[key]
#
# That reference is name of variable in which value is held.
#
# Params:
# 1 - hash
# 2 - key
# 3 - echo params (like -n for example)
function hash_echo_ref {
        echo $3 "${Hash_config_varname_prefix}${1}_${2}"
}



# Emulates something similar to:  $$hash[key](param1, param2, ...)
#
# Params:
# 1 - hash
# 2 - key
# 3,4, ... - Function parameters
function hash_call {
  local varname
  varname="${Hash_config_varname_prefix}${1}_${2}"
  shift 2
  "${!varname}" "$@"
}


# Emulates something similar to:  isset(hash[key]) or hash[key]==NULL
#
# Params:
# 1 - hash
# 2 - key
# Returns:
# 0 - there is such key
# 1 - there is no such key
function hash_is_set {
  local varname="${Hash_config_varname_prefix}${1}_${2}"
  declare -p "${varname}" >/dev/null 2>&1
}


# Emulates something similar to:
#   foreach($hash as $key => $value) { fun($key,$value); }
#
# It is possible to write different variations of this function.
# Here we use a function call to make it as "generic" as possible.
#
# Params:
# 1 - hash
# 2 - function name
function hash_foreach {
  local keyname_prefix keyname_full keyname value oldIFS="$IFS"
  IFS=$'\n'
  keyname_prefix="${Hash_config_varname_prefix}${1}_"
  for keyname_full in $(compgen -A variable "${keyname_prefix}"); do
    keyname="${keyname_full:${#keyname_prefix}}"
    value="${!keyname_full}"
    "$2" "${keyname}" "${value}"
  done
  IFS="$oldIFS"
}

#  NOTE: In lines 103 and 116, ampersand changed.
#  But, it doesn't matter, because these are comment lines anyhow.
