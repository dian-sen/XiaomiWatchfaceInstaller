package org.zxor.oronbox

/** Fixed read-only source policy; adapted from XiaomiWatchfaceInstaller/RootCacheSource.kt. */
internal object WatchfaceCacheRootPolicy {
    val roots = listOf(
        "/storage/emulated/0/Android/data/com.mi.health/files/WatchFace",
        "/storage/emulated/0/Android/data/com.xiaomi.hm.health/files/watch_skin_local",
    )
    const val MAX_BIN_BYTES = 32L * 1024 * 1024
    const val MAX_SCAN_BYTES = 1024L * 1024
    const val MAX_FILES = 200
    const val MAX_DIAGNOSTICS = 100
    const val MAX_VISITED = 10_000
    const val MAX_DEPTH = 8
    const val MAX_PREVIEW_CANDIDATES = 32
    const val MAX_PREVIEW_VISITED = 256

    fun quote(value: String): String {
        require('\u0000' !in value)
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    fun rootFor(path: String): String {
        require(path.length <= 4096 && '\u0000' !in path && "//" !in path)
        require(path.split('/').none { it == "." || it == ".." })
        return roots.firstOrNull { path.startsWith("$it/") }
            ?: throw IllegalArgumentException("Cache path is outside the allowlist")
    }

    fun validateBin(path: String): String {
        val root = rootFor(path)
        require(path.endsWith(".bin", ignoreCase = true))
        return root
    }

    fun candidate(bin: String): Boolean {
        validateBin(bin)
        return bin.substringAfterLast('/').equals("resource.bin", ignoreCase = true) &&
            bin.substringBeforeLast('/').split('/').none {
                it.equals("resources", true) || it.equals("_lua", true)
            }
    }

    fun metadataCommand(bin: String, relative: String): String {
        require(candidate(bin) && relative in WatchfaceCacheMetadata.XML_PATHS)
        return fixedReadCommand(bin.substringBeforeLast('/') + "/" + relative, WatchfaceCacheMetadata.MAX_XML_BYTES.toLong())
    }

    fun previewCommand(bin: String, relative: String): String {
        require(candidate(bin) && WatchfaceCacheMetadata.previewRelative(relative) == relative)
        return fixedReadCommand(bin.substringBeforeLast('/') + "/" + relative, WatchfaceCacheMetadata.MAX_PREVIEW_BYTES.toLong())
    }

    fun readBinCommand(bin: String): String {
        validateBin(bin)
        return fixedReadCommand(bin, MAX_BIN_BYTES)
    }

    // Android's /storage/emulated/0 alias is allowed; every component BELOW it is checked.
    private val prelude = """
        export LC_ALL=C
        uid=${'$'}(id -u 2>/dev/null) || exit 77
        [ "${'$'}uid" = 0 ] || exit 77
        absent() {
          local detail
          detail=${'$'}(stat -c %s "${'$'}1" 2>&1) && return 47
          case "${'$'}detail" in
            *'Permission denied'*|*'Operation not permitted'*) return 42;;
            *'No such file or directory'*|*'Not a directory'*) return 41;;
            *) return 48;;
          esac
        }
        enumerate() {
          local detail code
          exec 3>&1
          detail=${'$'}(
            { find "${'$'}@"; code=${'$'}?; [ "${'$'}code" = 0 ] || printf '!ENUM_FAILED!' >&2; } 2>&1 1>&3 | head -c 2048
          )
          exec 3>&-
          case "${'$'}detail" in
            '') return 0;;
            *'Permission denied'*|*'Operation not permitted'*) return 42;;
            *'No such file or directory'*|*'Not a directory'*) return 41;;
            *) return 48;;
          esac
        }
        checkdirs() {
          local current=/storage/emulated/0 rest part parent
          [ -d "${'$'}current" ] || return 41
          [ -r "${'$'}current" ] && [ -x "${'$'}current" ] || return 42
          rest=${'$'}{1#/storage/emulated/0/}
          while [ -n "${'$'}rest" ]; do
            part=${'$'}{rest%%/*}; parent=${'$'}current; current="${'$'}current/${'$'}part"
            [ ! -L "${'$'}current" ] || return 43
            if [ ! -e "${'$'}current" ]; then
              [ -r "${'$'}parent" ] && [ -x "${'$'}parent" ] || return 42
              absent "${'$'}current"; return ${'$'}?
            fi
            [ -d "${'$'}current" ] || return 45
            [ -r "${'$'}current" ] && [ -x "${'$'}current" ] || return 42
            [ "${'$'}rest" != "${'$'}part" ] || break
            rest=${'$'}{rest#*/}
          done
        }
        canonical() {
          local storage resolved suffix
          storage=${'$'}(readlink -f /storage/emulated/0 2>/dev/null) || return 48
          resolved=${'$'}(readlink -f "${'$'}1" 2>/dev/null) || return 48
          suffix=${'$'}{1#/storage/emulated/0/}
          [ "${'$'}resolved" = "${'$'}storage/${'$'}suffix" ] || return 44
          printf '%s' "${'$'}resolved"
        }
        checkfile() {
          checkdirs "${'$'}{1%/*}" || return ${'$'}?
          [ ! -L "${'$'}1" ] || return 43
          [ -e "${'$'}1" ] || { absent "${'$'}1"; return ${'$'}?; }
          [ -f "${'$'}1" ] || return 45
          [ -r "${'$'}1" ] || return 42
        }
    """.trimIndent()

    private fun fixedReadCommand(path: String, limit: Long): String {
        val root = rootFor(path)
        return prelude + "\n" + """
            r=${quote(root)}
            p=${quote(path)}
            checkdirs "${'$'}r" || exit ${'$'}?
            base=${'$'}(canonical "${'$'}r") || exit ${'$'}?
            checkfile "${'$'}p" || exit ${'$'}?
            resolved=${'$'}(canonical "${'$'}p") || exit ${'$'}?
            case "${'$'}resolved" in "${'$'}base"/*) ;; *) exit 44;; esac
            # Keep one opened descriptor: a pathname swap can never change bytes being copied.
            exec 3< "${'$'}p" || exit 42
            opened=${'$'}(readlink -f /proc/${'$'}${'$'}/fd/3 2>/dev/null) || exit 48
            [ "${'$'}opened" = "${'$'}resolved" ] || exit 47
            case "${'$'}opened" in "${'$'}base"/*) ;; *) exit 44;; esac
            [ -f /proc/${'$'}${'$'}/fd/3 ] || exit 45
            checkfile "${'$'}p" || exit ${'$'}?
            [ "${'$'}(canonical "${'$'}p")" = "${'$'}opened" ] || exit 47
            size=${'$'}(stat -L -c %s /proc/${'$'}${'$'}/fd/3 2>/dev/null) || exit 48
            case "${'$'}size" in ''|*[!0-9]*) exit 48;; esac
            [ "${'$'}size" -le $limit ] || exit 46
            # limit+1 detects a file that grows after fstat; RootShell rejects the extra byte.
            exec head -c ${limit + 1} <&3
        """.trimIndent()
    }

    fun scanCommand(): String = prelude + "\n" + """
        # find streams NUL-delimited names. No shell glob or complete directory list is allocated.
        # A closed reader pipe stops the producer when the global entry/file/output budget is hit.
        {
          for r in ${roots.joinToString(" ", transform = ::quote)}; do
            checkdirs "${'$'}r"; code=${'$'}?
            if [ "${'$'}code" = 0 ]; then
              canonical "${'$'}r" >/dev/null; code=${'$'}?
            fi
            if [ "${'$'}code" = 0 ]; then
              printf '\000ROOT\000%s\0000\000' "${'$'}r"
              enumerate "${'$'}r" -maxdepth ${MAX_DEPTH + 1} -print0
              code=${'$'}?
              printf '\000FIND\000%s\000%s\000' "${'$'}r" "${'$'}code"
            else
              printf '\000SKIP\000%s\000%s\000' "${'$'}r" "${'$'}code"
            fi
          done
        } | (
          visited=0; count=0; diagnostics=0; output=0; stopped=0; r=''
          record() {
            local length
            length=${'$'}(( ${'$'}{#1} + ${'$'}{#2} + ${'$'}{#3} + ${'$'}{#4} + 4 ))
            if [ ${'$'}((output+length)) -gt ${MAX_SCAN_BYTES - 256} ]; then
              printf 'LIMIT\000%s\000output\0000\000' ${quote(roots.first())}
              stopped=1; return
            fi
            printf '%s\000%s\000%s\000%s\000' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4"
            output=${'$'}((output+length))
          }
          diagnostic() {
            if [ "${'$'}diagnostics" -ge $MAX_DIAGNOSTICS ]; then
              record LIMIT "${'$'}r" diagnostics 0; stopped=1; return
            fi
            diagnostics=${'$'}((diagnostics+1)); record "${'$'}1" "${'$'}2" "${'$'}3" 0
          }
          status() {
            case "${'$'}1" in
              41) diagnostic MISSING "${'$'}2" "${'$'}3";;
              42) diagnostic DENIED "${'$'}2" "${'$'}3";;
              43) diagnostic LINK "${'$'}2" "${'$'}3";;
              44) diagnostic ESCAPE "${'$'}2" "${'$'}3";;
              45) diagnostic NOT_REGULAR "${'$'}2" "${'$'}3";;
              *) diagnostic IO "${'$'}2" "${'$'}3";;
            esac
          }
          while IFS= read -r -d '' p; do
            [ "${'$'}stopped" = 0 ] || break
            if [ -z "${'$'}p" ]; then
              IFS= read -r -d '' tag && IFS= read -r -d '' r && IFS= read -r -d '' code || exit 48
              case "${'$'}tag" in
                ROOT) ;;
                SKIP) status "${'$'}code" "${'$'}r" root;;
                FIND) [ "${'$'}code" = 0 ] || status "${'$'}code" "${'$'}r" enumeration;;
                *) exit 48;;
              esac
              [ "${'$'}stopped" = 0 ] || break
              continue
            fi
            if [ "${'$'}visited" -ge $MAX_VISITED ]; then diagnostic LIMIT "${'$'}r" entries; break; fi
            if [ "${'$'}count" -ge $MAX_FILES ]; then diagnostic LIMIT "${'$'}r" files; break; fi
            visited=${'$'}((visited+1))
            [ "${'$'}{#p}" -le 4096 ] || { diagnostic LIMIT "${'$'}r" path; continue; }
            [ ! -L "${'$'}p" ] || { diagnostic LINK "${'$'}p" skipped; continue; }
            if [ -d "${'$'}p" ]; then
              checkdirs "${'$'}p"; code=${'$'}?
              if [ "${'$'}code" != 0 ]; then status "${'$'}code" "${'$'}p" directory; continue; fi
              relative=${'$'}{p#"${'$'}r"}; rest=${'$'}relative; depth=0
              while [ -n "${'$'}rest" ]; do rest=${'$'}{rest#*/}; depth=${'$'}((depth+1)); case "${'$'}rest" in */*) ;; *) break;; esac; done
              if [ "${'$'}depth" -ge $MAX_DEPTH ]; then diagnostic LIMIT "${'$'}p" depth; fi
            elif [ -f "${'$'}p" ]; then
              case "${'$'}{p##*/}" in resource.bin) ;; *) continue;; esac
              # Exclude find's extra depth layer (used only to discover the depth boundary).
              relative=${'$'}{p#"${'$'}r"/}; rest=${'$'}relative; depth=1
              while [ "${'$'}{rest#*/}" != "${'$'}rest" ]; do rest=${'$'}{rest#*/}; depth=${'$'}((depth+1)); done
              [ "${'$'}depth" -le $MAX_DEPTH ] || continue
              checkfile "${'$'}p"; code=${'$'}?
              if [ "${'$'}code" != 0 ]; then status "${'$'}code" "${'$'}p" file; continue; fi
              canonical "${'$'}p" >/dev/null; code=${'$'}?
              if [ "${'$'}code" != 0 ]; then status "${'$'}code" "${'$'}p" canonical; continue; fi
              size=${'$'}(stat -c %s "${'$'}p" 2>/dev/null) && stamp=${'$'}(stat -c %Y "${'$'}p" 2>/dev/null) || { diagnostic IO "${'$'}p" stat; continue; }
              case "${'$'}size:${'$'}stamp" in *[!0-9:]*|'') diagnostic IO "${'$'}p" stat; continue;; esac
              [ "${'$'}size" -le $MAX_BIN_BYTES ] || { diagnostic LIMIT "${'$'}p" size; continue; }
              record FILE "${'$'}p" "${'$'}size" "${'$'}stamp"; count=${'$'}((count+1))
            elif [ ! -e "${'$'}p" ]; then
              checkdirs "${'$'}{p%/*}"; code=${'$'}?
              if [ "${'$'}code" = 0 ]; then diagnostic MISSING "${'$'}p" vanished; else status "${'$'}code" "${'$'}p" parent; fi
            fi
            [ "${'$'}stopped" = 0 ] || break
          done
          printf 'DONE\000\000\000\000'
        )
    """.trimIndent()

    fun previewListCommand(bin: String): String {
        require(candidate(bin))
        val directory = bin.substringBeforeLast('/') + "/resources/_preview"
        return prelude + "\n" + """
            d=${quote(directory)}
            checkdirs "${'$'}d" || exit ${'$'}?
            canonical "${'$'}d" >/dev/null || exit ${'$'}?
            {
              enumerate "${'$'}d" -mindepth 1 -maxdepth 1 -print0
              code=${'$'}?; printf '\000%s\000' "${'$'}code"
            } | (
              visited=0; count=0
              while IFS= read -r -d '' p; do
                if [ -z "${'$'}p" ]; then
                  IFS= read -r -d '' code || exit 48
                  case "${'$'}code" in 0) ;; 42) printf '!DENIED\000';; 41) printf '!MISSING\000';; *) printf '!IO\000';; esac
                  break
                fi
                if [ "${'$'}visited" -ge $MAX_PREVIEW_VISITED ]; then printf '!LIMIT\000'; break; fi
                visited=${'$'}((visited+1))
                if [ -L "${'$'}p" ]; then printf '!LINK\000'; continue; fi
                [ -f "${'$'}p" ] || continue
                case "${'$'}p" in *.[pP][nN][gG]|*.[jJ][pP][gG]|*.[jJ][pP][eE][gG]) ;; *) continue;; esac
                [ "${'$'}count" -lt $MAX_PREVIEW_CANDIDATES ] || { printf '!LIMIT\000'; break; }
                name=${'$'}{p##*/}
                [ "${'$'}{#name}" -le 240 ] || { printf '!LIMIT\000'; continue; }
                printf 'resources/_preview/%s\000' "${'$'}name"; count=${'$'}((count+1))
              done
              printf '!DONE\000'
            )
        """.trimIndent()
    }
}
