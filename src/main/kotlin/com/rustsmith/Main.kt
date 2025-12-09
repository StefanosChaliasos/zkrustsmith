package com.rustsmith

import com.andreapivetta.kolor.Color
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.rustsmith.ast.BoxDereferenceExpression
import com.rustsmith.ast.I128Type
import com.rustsmith.ast.StringType
import com.rustsmith.ast.U128Type
import com.rustsmith.ast.generateProgram
import com.rustsmith.exceptions.NoAvailableStatementException
import com.rustsmith.generation.IdentGenerator
import com.rustsmith.generation.selection.*
import com.rustsmith.logging.Logger
import com.rustsmith.recondition.Reconditioner
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.random.Random

lateinit var CustomRandom: Random
lateinit var selectionManager: SelectionManager
var USizeWidthBits: Int = 64

class RustSmith : CliktCommand(name = "rustsmith") {
    private val count: Int by option(help = "No. of files", names = arrayOf("-n", "-count")).int().default(100)
    private val print: Boolean by option("-p", "-print", help = "Print out program only").flag(default = false)
    private val outputStats: Boolean by option("--stats", help = "Output stats (as JSON files)").flag(default = false)
    private val threads: Int by option(help = "No. of threads", names = arrayOf("-t", "--threads")).int().default(8)
    private val chosenSelectionManagers: List<SelectionManagerOptions> by argument(
        "selection-manager",
        help = "Choose selection manager(s) for generation"
    ).enum<SelectionManagerOptions>().multiple()
    private val failFast: Boolean by option("-f", "--fail-fast", help = "Use fail fast approach").flag(default = false)
    private val seed: Long? by option(help = "Optional Seed", names = arrayOf("-s", "--seed")).long()
    private val directory: String by option(help = "Directory to save files").default("outRust")
    private val emitZkvmLayout: Boolean by option(
        help = "Emit zkVM-style output under <dir>/{native,input}",
        names = arrayOf("--zkvm")
    ).flag(default = false)
    private val usizeWidth: Int by option(
        help = "usize width in bits (32 or 64)",
        names = arrayOf("--usize-width")
    ).int().default(64)

    enum class SelectionManagerOptions {
        BASE_SELECTION,
        SWARM_SELECTION,
        OPTIMAL_SELECTION,
        AGGRESSIVE_SELECTION
    }

    private fun getSelectionManager(): List<SelectionManager> {
        return chosenSelectionManagers.toSet().ifEmpty { setOf(SelectionManagerOptions.OPTIMAL_SELECTION) }.map {
            when (it) {
                SelectionManagerOptions.BASE_SELECTION -> BaseSelectionManager()
                SelectionManagerOptions.SWARM_SELECTION -> SwarmBasedSelectionManager(getRandomConfiguration())
                SelectionManagerOptions.OPTIMAL_SELECTION -> OptimalSelectionManager()
                SelectionManagerOptions.AGGRESSIVE_SELECTION -> AggressiveSelectionManager(BoxDereferenceExpression::class)
            }
        }
    }

    override fun run() {
        if (!print) {
            File(directory).deleteRecursively()
            File(directory).mkdirs()
        }
        // Don't make progress bar if printing out the program in console
        val progressBar = if (!print) ProgressBarBuilder().setTaskName("Generating").setInitialMax(count.toLong())
            .setStyle(ProgressBarStyle.ASCII).setUpdateIntervalMillis(10).build() else null
        val executor = Executors.newFixedThreadPool(count.coerceAtMost(threads))
        (0 until count).map {
            Runnable {
                while (true) {
                    val randomSeed = seed ?: Random.nextLong()
                    val identGenerator = IdentGenerator()
                    CustomRandom = Random(randomSeed)
                    USizeWidthBits = if (usizeWidth == 32) 32 else 64
                    selectionManager = getSelectionManager().random(CustomRandom)
                    Logger.logText("Chosen selection manager ${selectionManager::class}", null, Color.YELLOW)
                    val reconditioner = Reconditioner()
                    try {
                        val (generatedProgram, cliArguments, cliTypes) = generateProgram(randomSeed, identGenerator, failFast)
                        if (generatedProgram.toRust().count { char -> char == '\n' } > 20000) continue
                        val program = reconditioner.recondition(generatedProgram)
                        if (print) {
                            println(program.toRust())
                            print(cliArguments.joinToString(" "))
                            break
                        }
                        val stats: MutableMap<String, Any> =
                            reconditioner.nodeCounters.mapKeys { it.key.simpleName!! }.toMutableMap()
                        stats["averageVarUse"] = reconditioner.variableUsageCounter.map { it.value }.sum()
                            .toDouble() / reconditioner.variableUsageCounter.size.toDouble()
                        val currentCount = it
                        val path = Path(directory, "file$currentCount")
                        path.toFile().mkdir()
                        path.resolve("file$currentCount.rs").toFile().writeText(program.toRust())
                        path.resolve("file$currentCount.txt").toFile().writeText(cliArguments.joinToString(" "))

                        if (emitZkvmLayout) {
                            val progName = "file$currentCount"
                            val nativeRoot = Path(directory, "native").toFile().apply { mkdirs() }
                            val inputRoot = Path(directory, "input").toFile().apply { mkdirs() }

                            // Create crate structure: <dir>/native/<progName>/{Cargo.toml,config.json,src/{lib.rs,main.rs}}
                            val crateDir = Path(nativeRoot.path, progName).toFile().apply { mkdirs() }
                            val srcDir = Path(crateDir.path, "src").toFile().apply { mkdirs() }

                            // Transform generated program into a library exposing `pub fn smith(...) -> u64`
                            val original = program.toRust()
                            val params = cliTypes.mapIndexed { idx, t -> "a$idx: ${t.toRust()}" }.joinToString(", ")
                            val fnName = progName
                            var libSource = original
                                // remove std::env import
                                .replace(Regex("(?m)^use\\s+std::env;\\n"), "")
                                // rename main to function name first (robust to spacing)
                                .replace(Regex("(?m)^fn\\s+main\\s*\\("), "fn $fnName(")
                                // replace empty signature with params and return type
                                .replace(
                                    Regex("(?m)^fn\\s+" + Regex.escape(fnName) + "\\(\\s*\\)\\s*->\\s*\\(\\)\\s*\\{"),
                                    "pub fn $fnName($params) -> u64 {"
                                )
                                // drop env args collection but keep hasher setup
                                .replace(
                                    "let cli_args: Vec<String> = env::args().collect();\nlet mut s = DefaultHasher::new();\nlet hasher = &mut s;",
                                    "let mut s = DefaultHasher::new();\nlet hasher = &mut s;"
                                )
                                // remove program seed print(s), if any
                                .replace(
                                    Regex("(?m)^\\s*println!\\(\\\"Program Seed:.*\\);\\s*"),
                                    ""
                                )
                                // return hasher result instead of printing it
                                .replace("println!(\"{:?}\", hasher.finish());", "return hasher.finish();")

                            // Replace CLI access expressions with typed params a0, a1, ...
                            for (idx in cliTypes.indices) {
                                val pattern = Regex("cli_args\\s*\\[\\s*${idx + 1}\\s*]\\s*\\.clone\\(\\)\\.parse::\\<[^>]+>\\(\\)\\.unwrap\\(\\)")
                                val replacement = if (cliTypes[idx].toRust() == "String") "a$idx.clone()" else "a$idx"
                                libSource = libSource.replace(pattern, replacement)
                            }

                            Path(srcDir.path, "lib.rs").toFile().writeText(libSource)

                            // Create a small binary wrapper that parses inputs and prints result
                            val usage = "Usage: $progName <args...>"
                            val callArgs = (0 until cliTypes.size).joinToString(", ") { "a$it" }
                            val mainSource = buildString {
                                appendLine("use std::env;")
                                appendLine("use std::process;")
                                appendLine()
                                appendLine("use $progName::$fnName;")
                                appendLine()
                                appendLine("fn main() {")
                                appendLine("    let mut args = env::args().skip(1);")
                                for ((i, t) in cliTypes.withIndex()) {
                                    appendLine("    let raw_$i = args.next().unwrap_or_else(|| { eprintln!(\"$usage\"); process::exit(1); });")
                                    val ty = t.toRust()
                                    if (ty == "String") {
                                        appendLine("    let a$i: String = raw_$i;")
                                    } else {
                                        appendLine("    let a$i: $ty = raw_$i.parse().unwrap_or_else(|_| { eprintln!(\"Invalid arg #${i + 1} for type $ty: \\\"{}\\\"\", raw_$i); process::exit(1); });")
                                    }
                                }
                                appendLine("    if args.next().is_some() { eprintln!(\"$usage\"); process::exit(1); }")
                                appendLine()
                                appendLine("    println!(\"{}\", $fnName($callArgs));")
                                appendLine("}")
                            }
                            Path(srcDir.path, "main.rs").toFile().writeText(mainSource)

                            // Minimal Cargo.toml matching the fib example layout
                            Path(crateDir.path, "Cargo.toml").toFile().writeText(
                                """
                                [package]
                                name = "$progName"
                                version = "0.1.0"
                                edition = "2024"

                                [dependencies]
                                """.trimIndent()
                            )

                            // Build config.json describing input/output signature
                            val inputTypes = cliTypes.map { it.toRust() }
                            val outputTypes = listOf("u64") // smith returns u64
                            val configJson = buildString {
                                appendLine("{")
                                appendLine("  \"root\": \".\",")
                                appendLine("  \"program_file\": \"src/lib.rs\",")
                                appendLine("  \"program_name\": \"$progName\",")
                                appendLine("  \"signature\": {")
                                appendLine("    \"input\": [${inputTypes.joinToString(",") { "\"$it\"" }}],")
                                appendLine("    \"output\": [${outputTypes.joinToString(",") { "\"$it\"" }}]")
                                appendLine("  }")
                                appendLine("}")
                            }
                            Path(crateDir.path, "config.json").toFile().writeText(configJson)

                            // Emit input JSON alongside, e.g., <dir>/input/fileN.json
                            val argsJsonArray = cliArguments.zip(cliTypes).joinToString(
                                ","
                            ) { (value, type) ->
                                if (type == StringType) {
                                    val escaped = value
                                        .replace("\\", "\\\\")
                                        .replace("\"", "\\\"")
                                    "\"$escaped\""
                                } else if (type == I128Type || type == U128Type) {
                                    "\"$value\""
                                } else {
                                    value
                                }
                            }
                            val inputJson = "{\n  \"args\": [$argsJsonArray]\n}"
                            Path(inputRoot.path, "$progName.json").toFile().writeText(inputJson)
                        }
                        if (outputStats) {
                            path.resolve("file$currentCount.json").toFile()
                                .writeText(
                                    jacksonObjectMapper().writeValueAsString(
                                        stats
                                    )
                                )
                        }
                        progressBar?.step()
                        break
                    } catch (e: NoAvailableStatementException) {
                        continue
                    }
                }
            }
        }.forEach { executor.execute(it) }
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.HOURS)
        progressBar?.close()
    }
}

fun main(args: Array<String>) = RustSmith().main(args)
