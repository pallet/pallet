# http://tldp.org/LDP/abs/html/contributed-scripts.html#HASHLIB
# Hash function library

# Author: Mariusz Gniazdowski <mariusz.gn-at-gmail.com>
# Date: 2005-04-07

# Author: Charles Duffy <charles@dyfis.net>
# Date: 2010-10-21

# Functions making emulating hashes in Bash a little less painful.
# (for bash >3.2; unnecessary with 4.0's associative arrays)

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
	local name="${Hash_config_varname_prefix}${1}_${2}"
	printf -v "$name" '%s' "$3"
}

function hash_clear_all {
	local v
	for v in $(compgen -v "${Hash_config_varname_prefix}"); do
		unset "$v"
	done
}

function test__hash_set {
	hash_clear_all
	hash_set foo bar $'foo\nbar'
	name="${Hash_config_varname_prefix}foo_bar"
	[[ ${!name} = $'foo\nbar' ]]
}

# Emulates:  value=hash[key]
#
# Params:
# 1 - hash
# 2 - key
# 3 - value (name of global variable to set)
function hash_get_into {
	local name="${Hash_config_varname_prefix}${1}_${2}"
	printf -v "$3" '%s' "${!name}"
}

function test__hash_get_into {
	hash_clear_all
	hash_set foo bar $'foo\nbar'
	hash_get_into foo bar baz
	[[ $baz = $'foo\nbar' ]]
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

function test__hash_echo {
	hash_clear_all
	hash_set foo bar $'foo\nbar'
	[[ "$(hash_echo foo bar)" = $'foo\nbar' ]]
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
	printf -v "${w_name}" '%s' "${!r_name}"
}

function test__hash_copy {
	hash_set src key1 $'foo\nbar'
	hash_copy dst key2 src key1
	hash_get_into dst key2 dst
	[[ "$dst" = $'foo\nbar' ]]
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

function test__hash_dup {
	unset dst
	hash_set src key1 $'foo\nbar'
	hash_dup src key1 key2 key3
	hash_get_into src key3 dst
	[[ "$dst" = $'foo\nbar' ]]
}

# Emulates:  unset hash[key]
#
# Params:
# 1 - hash
# 2 - key
function hash_unset {
	unset "${Hash_config_varname_prefix}${1}_${2}"
}

function test__hash_unset {
	hash_clear_all
	hash_set src key1 ""
	if ! hash_is_set src key1 ; then return 1; fi
	hash_unset src key1
	if hash_is_set src key1 ; then return 1; fi
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
	printf -v "$3" '%s' "${Hash_config_varname_prefix}${1}_${2}"
}

function test__hash_get_ref_into {
	hash_get_ref_into dict key dest
	[[ "$dest" = "${Hash_config_varname_prefix}dict_key" ]]
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

function test__hash_echo_ref {
	[[ "$(hash_echo_ref dict key)" = "${Hash_config_varname_prefix}dict_key" ]]
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

function testhelper__hash_call {
	printf '%s:%s' "$@"
	printf '\n'
}

function test__hash_call {
	hash_clear_all
	hash_set dict key1 printf
	[[ "$(hash_call dict key1 '%s:' foo bar)" = "foo:bar:" ]]
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

function test__hash_is_set {
	hash_clear_all
	hash_set dict normal_case "value here"
	hash_set dict empty_case ""
	hash_is_set dict normal_case && hash_is_set dict empty_case && ! hash_is_set dict unset_case
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

function testfunc__hash_foreach {
	printf "%s=%s\n" "$@"
}

function test__hash_foreach {
	hash_clear_all
	hash_set dict key1 "foo"
	hash_set dict key2 "bar"
	hash_set other key1 "bad"
	[[ "$(hash_foreach dict testfunc__hash_foreach | sort | tr '\n' ';')" = "key1=foo;key2=bar;" ]]
}

#  NOTE: In lines 103 and 116, ampersand changed.
#  But, it doesn't matter, because these are comment lines anyhow.

function hash_test_all {
	for testfunc in $(compgen -A function test__hash_); do
		if "${testfunc}" ; then
			echo "${testfunc}: ok"
		else
			echo "${testfunc}: FAIL"
		fi
	done
}

# vim: ai noet sts=2 sw=2 ts=2
