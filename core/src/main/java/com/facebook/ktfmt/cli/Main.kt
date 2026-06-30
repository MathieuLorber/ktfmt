/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.ktfmt.cli

import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.ParseError
import com.google.googlejavaformat.FormattingError
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private const val EXIT_CODE_FAILURE = 1
private const val EXIT_CODE_SUCCESS = 0
private const val UTF8_BOM = "\uFEFF"

private val USAGE =
    """
    |Usage:
    |  ktfmt [OPTIONS] File1.kt File2.kt ...
    |  ktfmt @ARGFILE
    |
    |For more details see `ktfmt --help`
    |"""
        .trimMargin()

class Main(
    private val input: InputStream,
    private val out: PrintStream,
    private val err: PrintStream,
    private val inputArgs: Array<String>,
) {
  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      exitProcess(Main(System.`in`, System.out, System.err, args).run())
    }

    /**
     * expandArgsToFileNames expands 'args' to a list of .kt files to format.
     *
     * Most commonly, 'args' is either a list of .kt files, or a name of a directory whose contents
     * the user wants to format.
     *
     * When [removeIgnored] is true, directory arguments are expanded by asking git for their
     * non-ignored Kotlin files (see [gitListKotlinFiles]) instead of walking the filesystem. This is
     * both faster (git never descends into ignored directories such as `build/`) and honours the
     * full git ignore semantics. Files passed explicitly (i.e. non-directory arguments) are always
     * kept, regardless of git ignore rules.
     */
    fun expandArgsToFileNames(args: List<String>, removeIgnored: Boolean = false): List<File> {
      if (args.size == 1 && File(args[0]).isFile) {
        return listOf(File(args[0]))
      }
      val result = mutableListOf<File>()
      for (arg in args) {
        val argFile = File(arg)
        if (removeIgnored && argFile.isDirectory) {
          val listed = gitListKotlinFiles(argFile)
          if (listed != null) {
            result.addAll(listed)
            continue
          }
          // git unavailable or not a repository: fall back to walking the filesystem.
        }
        result.addAll(
            argFile.walkTopDown().filter {
              it.isFile && (it.extension == "kt" || it.extension == "kts")
            }
        )
      }
      return result
    }

    /**
     * Lists the Kotlin (`.kt`/`.kts`) files under [dir] that git does not ignore, by delegating to
     * `git ls-files`. This returns both tracked files and untracked-but-not-ignored files
     * (`--others --exclude-standard`), honouring the full git ignore semantics: nested `.gitignore`
     * files, `.git/info/exclude`, global excludes, negations and anchoring.
     *
     * Returns null when git is unavailable or [dir] is not inside a git repository, so the caller
     * can fall back to walking the filesystem.
     */
    private fun gitListKotlinFiles(dir: File): List<File>? {
      return try {
        val process =
            ProcessBuilder(
                    "git",
                    "ls-files",
                    "--cached",
                    "--others",
                    "--exclude-standard",
                    "-z",
                    "--",
                    "*.kt",
                    "*.kts",
                )
                .directory(dir)
                .redirectErrorStream(false)
                .start()
        // Read stdout fully (draining the pipe) before waiting, to avoid blocking on a full buffer.
        val stdout = process.inputStream.readBytes().toString(UTF_8)
        process.waitFor()
        if (process.exitValue() != 0) {
          null
        } else {
          // `-z` separates entries with NUL, so paths with spaces or newlines stay intact.
          stdout
              .split('\u0000')
              .filter { it.isNotEmpty() }
              .map { dir.resolve(it) }
              .filter { it.isFile }
        }
      } catch (_: IOException) {
        // git is not installed or could not be executed.
        null
      }
    }
  }

  fun run(): Int {
    val parsedArgs =
        when (val processArgs = ParsedArgs.processArgs(inputArgs)) {
          is ParseResult.Ok -> processArgs.parsedValue
          is ParseResult.ShowMessage -> {
            out.println(processArgs.message)
            return EXIT_CODE_SUCCESS
          }
          is ParseResult.Error -> {
            err.println(processArgs.errorMessage)
            return EXIT_CODE_FAILURE
          }
        }
    if (parsedArgs.fileNames.isEmpty()) {
      err.println(USAGE)
      return EXIT_CODE_FAILURE
    }

    if (parsedArgs.fileNames.size == 1 && parsedArgs.fileNames[0] == "-") {
      // Format code read from stdin
      return try {
        val alreadyFormatted = format(null, parsedArgs)
        if (!alreadyFormatted && parsedArgs.setExitIfChanged) EXIT_CODE_FAILURE
        else EXIT_CODE_SUCCESS
      } catch (_: Exception) {
        EXIT_CODE_FAILURE
      }
    }

    val files: List<File> = expandArgsToFileNames(parsedArgs.fileNames, parsedArgs.gitignore)

    if (files.isEmpty()) {
      err.println("Error: no .kt files found")
      return EXIT_CODE_FAILURE
    }

    val returnCode = AtomicInteger(EXIT_CODE_SUCCESS)
    files.parallelStream().forEach {
      try {
        if (!format(it, parsedArgs) && parsedArgs.setExitIfChanged) {
          returnCode.set(EXIT_CODE_FAILURE)
        }
      } catch (_: Exception) {
        returnCode.set(EXIT_CODE_FAILURE)
      }
    }
    return returnCode.get()
  }

  /**
   * Handles the logic for formatting and flags.
   *
   * If dry run mode is active, this simply prints the name of the source (file path or `<stdin>`)
   * to [out]. Otherwise, this will run the appropriate formatting as normal.
   *
   * @param file The file to format. If null, the code is read from <stdin>.
   * @return true iff input is valid and already formatted.
   */
  private fun format(file: File?, args: ParsedArgs): Boolean {
    val fileName = file?.toString() ?: args.stdinName ?: "<stdin>"
    try {
      val formattingOptions =
          if (file == null || !args.editorConfig) args.formattingOptions
          else EditorConfigResolver.resolveFormattingOptions(file, args.formattingOptions)
      val bytes = if (file == null) input else FileInputStream(file)
      val code = BufferedReader(InputStreamReader(bytes, UTF_8)).readText().removePrefix(UTF8_BOM)
      val formattedCode = Formatter.format(formattingOptions, code)
      val alreadyFormatted = code == formattedCode

      // stdin
      if (file == null) {
        if (args.dryRun) {
          if (!alreadyFormatted) {
            out.println(fileName)
          }
        } else {
          BufferedWriter(OutputStreamWriter(out, UTF_8)).use { it.write(formattedCode) }
        }
        return alreadyFormatted
      }

      if (args.dryRun) {
        if (!alreadyFormatted) {
          out.println(fileName)
        }
      } else {
        // TODO(T111284144): Add tests
        if (!alreadyFormatted) {
          file.writeText(formattedCode, UTF_8)
        }
        if (!args.quiet) {
          err.println("Done formatting $fileName")
        }
      }

      return alreadyFormatted
    } catch (e: IOException) {
      err.println("Error formatting $fileName: ${e.message}; skipping.")
      throw e
    } catch (e: ParseError) {
      err.println("$fileName:${e.message}")
      throw e
    } catch (e: FormattingError) {
      for (diagnostic in e.diagnostics()) {
        err.println("$fileName:$diagnostic")
      }
      e.printStackTrace(err)
      throw e
    }
  }
}
