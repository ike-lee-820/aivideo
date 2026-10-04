package com.ikelee.aivideo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TaskRepo {
    private val _tasks = MutableStateFlow<List<VideoTask>>(emptyList())
    val tasks: StateFlow<List<VideoTask>> = _tasks.asStateFlow()

    fun add(task: VideoTask) {
        _tasks.update { listOf(task) + it }
    }

    fun update(id: String, transform: (VideoTask) -> VideoTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun remove(id: String) {
        _tasks.update { list -> list.filter { it.id != id } }
    }

    fun clear() {
        _tasks.value = emptyList()
    }
}
