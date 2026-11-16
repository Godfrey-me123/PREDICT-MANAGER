package com.example.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small, explicit macro language for Predict Manager (Screen Earner)'s reusable UI actions.
 *
 * StrategyEngine remains responsible for deciding WHEN to act. This class only executes a
 * requested ordered sequence and stops on the first failed action.
 */
class ScreenEarnerMacroRunner(
    private val executor: ScreenEarnerActionExecutor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    enum class State { IDLE, RUNNING, PAUSED, STOPPED, FAILED }

    data class StepResult(
        val lineNumber: Int,
        val command: String,
        val success: Boolean,
        val message: String,
        val observedValue: String? = null
    )

    private val lock = Mutex()
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastResult = MutableStateFlow<StepResult?>(null)
    val lastResult: StateFlow<StepResult?> = _lastResult.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    private var runningJob: Job? = null

    fun start(
        script: String,
        repeatCount: Int = 1,
        stepDelayMs: Long = 150L,
        onStep: (StepResult) -> Unit = {},
        onFinished: (State) -> Unit = {}
    ): Boolean {
        if (runningJob?.isActive == true) return false
        val lines = script.lines().mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) null else (index + 1) to line
        }
        if (lines.isEmpty()) {
            _state.value = State.FAILED
            _lastResult.value = StepResult(0, "", false, "Script is empty")
            onFinished(State.FAILED)
            return false
        }

        val loops = if (repeatCount <= 0) Int.MAX_VALUE else repeatCount.coerceAtMost(10_000)
        _isPaused.value = false
        _state.value = State.RUNNING
        runningJob = scope.launch {
            var finalState = State.STOPPED
            try {
                lock.withLock {
                    loop@ for (loopIndex in 0 until loops) {
                        for ((lineNumber, line) in lines) {
                            if (!awaitResume()) break@loop
                            val result = executeLine(lineNumber, line)
                            _lastResult.value = result
                            onStep(result)
                            if (!result.success) {
                                finalState = State.FAILED
                                break@loop
                            }
                            if (stepDelayMs > 0L) delay(stepDelayMs.coerceAtMost(10_000L))
                        }
                        if (repeatCount <= 0 && !runningJobActive()) break
                    }
                }
                if (_state.value != State.FAILED) finalState = State.STOPPED
            } catch (cancelled: CancellationException) {
                finalState = State.STOPPED
                throw cancelled
            } catch (t: Throwable) {
                finalState = State.FAILED
                _lastResult.value = StepResult(0, "", false, "Runner error: ${t.message}")
            } finally {
                _state.value = finalState
                _isPaused.value = false
                onFinished(finalState)
            }
        }
        return true
    }

    fun pause() {
        if (_state.value == State.RUNNING) {
            _isPaused.value = true
            _state.value = State.PAUSED
        }
    }

    fun resume() {
        if (_state.value == State.PAUSED) {
            _isPaused.value = false
            _state.value = State.RUNNING
        }
    }

    fun stop() {
        runningJob?.cancel()
        runningJob = null
        _isPaused.value = false
        if (_state.value != State.IDLE) _state.value = State.STOPPED
    }

    private suspend fun awaitResume(): Boolean {
        if (_state.value == State.STOPPED || _state.value == State.FAILED) return false
        _isPaused.first { paused -> !paused }
        return _state.value == State.RUNNING
    }

    private fun runningJobActive(): Boolean = runningJob?.isActive == true

    private suspend fun executeLine(lineNumber: Int, line: String): StepResult {
        val parsed = parse(line)
            ?: return StepResult(lineNumber, line, false, "Invalid command syntax")
        val result = try {
            when (parsed.name) {
                "tap" -> executor.tap(parsed.numbers[0], parsed.numbers[1])
                "doubletap" -> executor.doubleTap(parsed.numbers[0], parsed.numbers[1])
                "longpress" -> executor.longPress(
                    parsed.numbers[0], parsed.numbers[1],
                    parsed.numbers.getOrElse(2) { 800f }.toLong()
                )
                "swipe" -> executor.swipe(
                    parsed.numbers[0], parsed.numbers[1],
                    parsed.numbers[2], parsed.numbers[3],
                    parsed.numbers.getOrElse(4) { 300f }.toLong()
                )
                "click" -> executor.clickText(parsed.text)
                "focus" -> executor.focusField(parsed.text)
                "setfield" -> executor.replaceFieldText(parsed.label, parsed.value)
                "type" -> executor.typeIntoFocusedField(parsed.text)
                "append" -> executor.appendToFocusedField(parsed.text)
                "clear" -> executor.clearFocusedField()
                "typeslow" -> {
                    var last = ScreenEarnerActionExecutor.Result(true, "No characters")
                    val interval = parsed.numbers.firstOrNull()?.toLong()?.coerceIn(0L, 2_000L) ?: 100L
                    for (character in parsed.text) {
                        last = executor.appendToFocusedField(character.toString())
                        if (!last.success) break
                        if (interval > 0) delay(interval)
                    }
                    last
                }
                "wait", "sleep", "delay" -> {
                    delay(parsed.numbers[0].toLong().coerceIn(0L, 60_000L))
                    ScreenEarnerActionExecutor.Result(true, "Wait completed")
                }
                "back" -> executor.pressBack()
                "home" -> executor.pressHome()
                "recents" -> executor.openRecents()
                "notifications" -> executor.openNotifications()
                "enter" -> executor.pressEnter()
                "open" -> executor.launchApp(parsed.text)
                else -> ScreenEarnerActionExecutor.Result(false, "Unsupported command")
            }
        } catch (t: Throwable) {
            ScreenEarnerActionExecutor.Result(false, "Action exception: ${t.message}")
        }
        return StepResult(
            lineNumber, line, result.success, result.message, result.observedValue
        )
    }

    private data class Parsed(
        val name: String,
        val numbers: List<Float> = emptyList(),
        val text: String = "",
        val label: String = "",
        val value: String = ""
    )

    private fun parse(line: String): Parsed? {
        val name = line.substringBefore(' ').lowercase()
        val rest = line.substringAfter(' ', "").trim()
        fun floats(count: Int, optional: Boolean = false): List<Float>? {
            val parts = rest.split(Regex("\\s+"))
            if ((!optional && parts.size != count) || (optional && parts.size !in (count - 1)..count)) return null
            return parts.mapNotNull { it.toFloatOrNull() }.takeIf { it.size == parts.size }
        }
        fun textArg(): String? = unquote(rest).takeIf { it.isNotBlank() }

        return when (name) {
            "tap", "doubletap" -> floats(2)?.let { Parsed(name, numbers = it) }
            "longpress" -> floats(3, optional = true)?.let { Parsed(name, numbers = it) }
            "swipe" -> floats(5, optional = true)?.let { values ->
                if (values.size >= 4) Parsed(name, numbers = values) else null
            }
            "wait", "sleep", "delay" -> floats(1)?.let { Parsed(name, numbers = it) }
            "click", "focus", "type", "append", "open" ->
                textArg()?.let { Parsed(name, text = it) }
            "typeslow" -> {
                val match = Regex("""^(\d+)\s+(.+)$""").matchEntire(rest) ?: return null
                val text = unquote(match.groupValues[2])
                Parsed(name, numbers = listOf(match.groupValues[1].toFloat()), text = text)
            }
            "setfield" -> {
                val parts = quotedParts(rest) ?: return null
                if (parts.size != 2) null else Parsed(name, label = parts[0], value = parts[1])
            }
            "clear", "back", "home", "recents", "notifications", "enter" ->
                if (rest.isBlank()) Parsed(name) else null
            else -> null
        }
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length >= 2 &&
            ((trimmed.first() == '"' && trimmed.last() == '"') ||
                (trimmed.first() == '\'' && trimmed.last() == '\''))
        ) trimmed.substring(1, trimmed.length - 1) else trimmed
    }

    private fun quotedParts(value: String): List<String>? {
        val matches = Regex(""""([^"]*)"|'([^']*)'""").findAll(value).toList()
        if (matches.isEmpty()) return null
        return matches.map { it.groups[1]?.value ?: it.groups[2]?.value.orEmpty() }
    }
}
