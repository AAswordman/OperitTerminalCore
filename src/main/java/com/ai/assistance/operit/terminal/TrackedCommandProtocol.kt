package com.ai.assistance.operit.terminal

/** Shell completion records are separate from visible prompt text and command output. */
object TrackedCommandProtocol {
    const val PREFIX = "\u001b]777;operit-command;"

    fun wrap(command: String, commandId: String): String {
        require(Regex("[a-zA-Z0-9-]+").matches(commandId))
        // PROMPT_COMMAND captures $? even after Ctrl+C skips the remainder of an eval.
        // Prepending our hook preserves user hooks; eval keeps cwd and variables in this shell.
        val bashScript = "__operit_report_command_status() { local __operit_status=\$?; " +
            "if [ -n \"\${__operit_tracked_command-}\" ]; then " +
            "builtin printf '\\033]777;operit-command;%s;%s\\007' \"\$__operit_tracked_command\" \"\$__operit_status\"; " +
            "unset __operit_tracked_command; fi; return \"\$__operit_status\"; }; " +
            "__operit_prompt_hooks=''; " +
            "for __operit_prompt_hook in \"\${PROMPT_COMMAND[@]}\"; do " +
            "if [ \"\$__operit_prompt_hook\" != __operit_report_command_status ]; then " +
            "case \"\$__operit_prompt_hook\" in __operit_report_command_status\$'\\n'*) " +
            "__operit_prompt_hook=\${__operit_prompt_hook#__operit_report_command_status\$'\\n'};; esac; " +
            "__operit_prompt_hooks+=\$'\\n'\"\$__operit_prompt_hook\"; fi; done; " +
            "unset PROMPT_COMMAND; PROMPT_COMMAND=\"__operit_report_command_status\$__operit_prompt_hooks\"; " +
            "unset __operit_prompt_hooks __operit_prompt_hook; " +
            "__operit_tracked_command='$commandId'; " +
            "builtin printf '\\033]777;operit-command;$commandId;start\\007'; " +
            "__operit_restore_status() { return \"\$__operit_previous_status\"; }; " +
            "__operit_restore_status; eval -- ${quote(command)}"
        return "__operit_previous_status=\$?; if [ -n \"\${BASH_VERSION-}\" ]; then eval ${quote(bashScript)}; " +
            "else printf '\\033]777;operit-command;$commandId;start\\007'; " +
            "printf '%s\\n' 'Tracked terminal commands require Bash'; " +
            "printf '\\033]777;operit-command;$commandId;126\\007'; fi"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    sealed interface Frame {
        data class Text(val value: String) : Frame
        data class Started(val commandId: String) : Frame
        data class Completed(val commandId: String, val exitCode: Int?) : Frame
    }

    /** Retains only a partial control record across arbitrary PTY read boundaries. */
    class Decoder {
        private val pending = StringBuilder()

        fun append(chunk: String): List<Frame> {
            pending.append(chunk)
            val frames = mutableListOf<Frame>()
            while (pending.isNotEmpty()) {
                val start = pending.indexOf(PREFIX)
                if (start < 0) {
                    val retained = (1 until PREFIX.length).lastOrNull { length ->
                        pending.length >= length && pending.endsWith(PREFIX.take(length))
                    } ?: 0
                    val count = pending.length - retained
                    if (count > 0) {
                        frames.add(Frame.Text(pending.substring(0, count)))
                        pending.delete(0, count)
                    }
                    break
                }
                if (start > 0) {
                    frames.add(Frame.Text(pending.substring(0, start)))
                    pending.delete(0, start)
                }
                val end = pending.indexOf("\u0007", PREFIX.length)
                if (end < 0) {
                    if (pending.length > 128) {
                        // Arbitrary command output may contain OSC-like bytes; only bounded
                        // records in our grammar are control messages.
                        frames.add(Frame.Text(pending.toString()))
                        pending.clear()
                    }
                    break
                }
                val fields = pending.substring(PREFIX.length, end).split(';')
                if (fields.size != 2 || !Regex("[a-zA-Z0-9-]+").matches(fields[0])) {
                    frames.add(Frame.Text(pending.substring(0, end + 1)))
                    pending.delete(0, end + 1)
                    continue
                }
                if (fields[1] == "start") {
                    frames.add(Frame.Started(fields[0]))
                } else {
                    val exitCode = fields[1].toIntOrNull()?.takeIf { it in 0..255 }
                    frames.add(Frame.Completed(fields[0], exitCode))
                }
                pending.delete(0, end + 1)
            }
            return frames
        }
    }
}
